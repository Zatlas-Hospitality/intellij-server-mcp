package com.zatlas.mcpbridge.handlers

import com.intellij.execution.ExecutionManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.junit.JUnitConfiguration
import com.intellij.execution.junit.JUnitConfigurationType
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.testframework.AbstractTestProxy
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.zatlas.mcpbridge.models.TestCaseResult
import com.zatlas.mcpbridge.models.TestResult
import com.zatlas.mcpbridge.models.TestStatus
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class TestHandler {

    private val log = Logger.getInstance(TestHandler::class.java)

    @Volatile
    private var lastResults: TestResult? = null

    /**
     * Run tests matching the given pattern
     *
     * Pattern formats supported:
     * - "MyTest" - run all tests in class MyTest
     * - "MyTest#testMethod" - run specific method
     * - "com.example.*" - run all tests in package
     * - "com.example.MyTest" - run tests in fully qualified class
     */
    fun runTest(pattern: String, timeoutSec: Int, debug: Boolean = false): TestResult {
        val project = getOpenProject()
            ?: return TestResult(
                success = false,
                passed = 0,
                failed = 0,
                skipped = 0,
                timeMs = 0,
                tests = emptyList(),
                error = "No project open in IntelliJ"
            )

        val future = CompletableFuture<TestResult>()
        val startTime = System.currentTimeMillis()

        ApplicationManager.getApplication().invokeLater {
            try {
                val config = createJUnitConfig(project, pattern)
                    ?: run {
                        future.complete(TestResult(
                            success = false,
                            passed = 0,
                            failed = 0,
                            skipped = 0,
                            timeMs = System.currentTimeMillis() - startTime,
                            tests = emptyList(),
                            error = "Failed to create JUnit configuration for pattern: $pattern"
                        ))
                        return@invokeLater
                    }

                executeTestConfig(project, config, future, startTime, debug)
            } catch (e: Exception) {
                log.error("Test execution failed", e)
                future.complete(TestResult(
                    success = false,
                    passed = 0,
                    failed = 0,
                    skipped = 0,
                    timeMs = System.currentTimeMillis() - startTime,
                    tests = emptyList(),
                    error = e.message ?: "Unknown test execution error"
                ))
            }
        }

        return try {
            val result = future.get(timeoutSec.toLong(), TimeUnit.SECONDS)
            lastResults = result
            result
        } catch (e: TimeoutException) {
            TestResult(
                success = false,
                passed = 0,
                failed = 0,
                skipped = 0,
                timeMs = timeoutSec * 1000L,
                tests = emptyList(),
                error = "Test execution timed out after $timeoutSec seconds"
            )
        } catch (e: Exception) {
            TestResult(
                success = false,
                passed = 0,
                failed = 0,
                skipped = 0,
                timeMs = System.currentTimeMillis() - startTime,
                tests = emptyList(),
                error = e.message ?: "Test execution failed"
            )
        }
    }

    /**
     * Get the last test results
     */
    fun getLastResults(): TestResult? = lastResults

    private fun createJUnitConfig(project: Project, pattern: String): JUnitConfiguration? {
        return try {
            val runManager = RunManager.getInstance(project)
            val configurationType = JUnitConfigurationType.getInstance()
            val factory = configurationType.configurationFactories.firstOrNull()
                ?: return null

            val settings = runManager.createConfiguration("MCP Test: $pattern", factory)
            val config = settings.configuration as JUnitConfiguration

            // Parse the pattern and configure appropriately
            when {
                pattern.contains("#") -> {
                    // Method test formats:
                    // - "MyTest#testMethod" - direct method
                    // - "MyTest$NestedClass#testMethod" - nested class method
                    // - "com.example.MyTest$Nested Class#test method" - with spaces (Kotlin)
                    val (classPath, methodName) = pattern.split("#", limit = 2)

                    // Check if it's a nested class pattern (contains $ separator)
                    val hasNestedClass = classPath.contains("$")
                    val (mainClass, nestedClass) = if (hasNestedClass) {
                        val parts = classPath.split("$", limit = 2)
                        Pair(parts[0], parts[1])
                    } else {
                        Pair(classPath, null)
                    }

                    val resolvedClass = resolveClassName(project, mainClass)

                    // Use TEST_UNIQUE_ID for Kotlin tests (display names with spaces or nested classes)
                    if (methodName.contains(" ") || hasNestedClass) {
                        config.persistentData.TEST_OBJECT = JUnitConfiguration.TEST_UNIQUE_ID
                        val uniqueId = if (nestedClass != null) {
                            // Nested class: [engine:junit-jupiter]/[class:fqcn]/[nested-class:Name]/[method:name()]
                            "[engine:junit-jupiter]/[class:$resolvedClass]/[nested-class:$nestedClass]/[method:$methodName()]"
                        } else {
                            // Direct method: [engine:junit-jupiter]/[class:fqcn]/[method:name()]
                            "[engine:junit-jupiter]/[class:$resolvedClass]/[method:$methodName()]"
                        }
                        config.persistentData.setUniqueIds(uniqueId)
                        log.info("Using unique ID for test: $uniqueId")
                    } else {
                        config.persistentData.TEST_OBJECT = JUnitConfiguration.TEST_METHOD
                        config.persistentData.MAIN_CLASS_NAME = resolvedClass
                        config.persistentData.METHOD_NAME = methodName
                    }
                }
                pattern.endsWith("*") -> {
                    // Package test: "com.example.*"
                    val packageName = pattern.removeSuffix("*").removeSuffix(".")
                    config.persistentData.TEST_OBJECT = JUnitConfiguration.TEST_PACKAGE
                    config.persistentData.PACKAGE_NAME = packageName
                }
                pattern.contains(".") && !pattern.endsWith("Test") && !pattern.contains("Test.") -> {
                    // Likely a package: "com.example.subpackage"
                    // But not something like "com.example.MyTest.NestedClass"
                    config.persistentData.TEST_OBJECT = JUnitConfiguration.TEST_PACKAGE
                    config.persistentData.PACKAGE_NAME = pattern
                }
                else -> {
                    // Class test: "MyTest" or "com.example.MyTest"
                    config.persistentData.TEST_OBJECT = JUnitConfiguration.TEST_CLASS
                    config.persistentData.MAIN_CLASS_NAME = resolveClassName(project, pattern)
                }
            }

            // Set the module if we can determine it
            val module = findModuleForTest(project, pattern)
            if (module != null) {
                config.setModule(module)
            }

            config
        } catch (e: Exception) {
            log.error("Failed to create JUnit configuration for pattern: $pattern", e)
            null
        }
    }

    private fun resolveClassName(project: Project, className: String): String {
        // If it's already fully qualified, return as-is
        if (className.contains(".")) {
            return className
        }

        // Try to find the class in the project using PSI (must run in read action)
        try {
            return ApplicationManager.getApplication().runReadAction<String> {
                val psiFacade = JavaPsiFacade.getInstance(project)
                val scope = GlobalSearchScope.allScope(project)

                // Search for classes with this short name
                val classes = psiFacade.findClasses(className, scope)
                if (classes.isNotEmpty()) {
                    // Prefer test classes in test source roots
                    val testClass = classes.find { it.qualifiedName?.contains("test", ignoreCase = true) == true }
                        ?: classes.first()
                    val qualifiedName = testClass.qualifiedName
                    if (qualifiedName != null) {
                        log.info("Resolved '$className' to '$qualifiedName'")
                        return@runReadAction qualifiedName
                    }
                }

                // Try with common test suffixes
                val searchNames = listOf(className, "${className}Test", "${className}Tests")
                for (searchName in searchNames) {
                    val foundClasses = psiFacade.findClasses(searchName, scope)
                    for (foundClass in foundClasses) {
                        val qualifiedName = foundClass.qualifiedName
                        if (qualifiedName != null) {
                            log.info("Resolved '$className' to '$qualifiedName'")
                            return@runReadAction qualifiedName
                        }
                    }
                }

                // Fallback: return as-is
                log.warn("Could not resolve class name '$className', using as-is")
                className
            }
        } catch (e: Exception) {
            log.warn("Failed to resolve class name '$className': ${e.message}")
            return className
        }
    }

    private fun findModuleForTest(project: Project, pattern: String): com.intellij.openapi.module.Module? {
        // Try to find the appropriate module
        val moduleManager = com.intellij.openapi.module.ModuleManager.getInstance(project)
        val modules = moduleManager.modules

        // Prefer modules with "test" in the name
        return modules.firstOrNull { it.name.contains("test", ignoreCase = true) }
            ?: modules.firstOrNull()
    }

    private fun executeTestConfig(
        project: Project,
        config: JUnitConfiguration,
        future: CompletableFuture<TestResult>,
        startTime: Long,
        debug: Boolean = false
    ) {
        try {
            val executor = if (debug)
                DefaultDebugExecutor.getDebugExecutorInstance()
            else
                DefaultRunExecutor.getRunExecutorInstance()
            val environmentBuilder = ExecutionEnvironmentBuilder.create(executor, config)
            val environment = environmentBuilder.build()

            // Add a process termination listener to capture results
            environment.runner?.let { runner ->
                ProgramRunnerUtil.executeConfigurationAsync(
                    environment,
                    true,
                    true
                ) { descriptor ->
                    // In debug mode, return immediately after starting - don't wait for completion
                    if (debug) {
                        future.complete(TestResult(
                            success = true,
                            passed = 0,
                            failed = 0,
                            skipped = 0,
                            timeMs = System.currentTimeMillis() - startTime,
                            tests = emptyList(),
                            error = null,
                            debugMessage = "Test started in debug mode. Use debug tools to interact with the debugger."
                        ))
                        return@executeConfigurationAsync
                    }

                    // Wait for the process to complete and capture results
                    val handler = descriptor.processHandler
                    handler?.addProcessListener(object : com.intellij.execution.process.ProcessAdapter() {
                        override fun processTerminated(event: com.intellij.execution.process.ProcessEvent) {
                            // Give the test framework time to update results
                            ApplicationManager.getApplication().invokeLater {
                                try {
                                    val result = extractTestResults(descriptor, startTime)
                                    future.complete(result)
                                } catch (e: Exception) {
                                    log.error("Failed to extract test results", e)
                                    future.complete(TestResult(
                                        success = false,
                                        passed = 0,
                                        failed = 0,
                                        skipped = 0,
                                        timeMs = System.currentTimeMillis() - startTime,
                                        tests = emptyList(),
                                        error = "Failed to extract test results: ${e.message}"
                                    ))
                                }
                            }
                        }
                    })
                }
            } ?: run {
                future.complete(TestResult(
                    success = false,
                    passed = 0,
                    failed = 0,
                    skipped = 0,
                    timeMs = System.currentTimeMillis() - startTime,
                    tests = emptyList(),
                    error = "No runner available for test execution"
                ))
            }
        } catch (e: Exception) {
            log.error("Failed to execute test configuration", e)
            future.complete(TestResult(
                success = false,
                passed = 0,
                failed = 0,
                skipped = 0,
                timeMs = System.currentTimeMillis() - startTime,
                tests = emptyList(),
                error = "Failed to execute tests: ${e.message}"
            ))
        }
    }

    private fun extractTestResults(
        descriptor: com.intellij.execution.ui.RunContentDescriptor,
        startTime: Long
    ): TestResult {
        val timeMs = System.currentTimeMillis() - startTime
        val tests = mutableListOf<TestCaseResult>()
        var passed = 0
        var failed = 0
        var skipped = 0

        try {
            val console = descriptor.executionConsole
            if (console is SMTRunnerConsoleView) {
                val resultsViewer = console.resultsViewer
                val root = resultsViewer.root as? SMTestProxy.SMRootTestProxy

                root?.let { rootProxy ->
                    collectTestResults(rootProxy, tests)
                    passed = tests.count { it.status == TestStatus.PASSED }
                    failed = tests.count { it.status == TestStatus.FAILED || it.status == TestStatus.ERROR }
                    skipped = tests.count { it.status == TestStatus.SKIPPED }
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to extract detailed test results", e)
        }

        // If we couldn't extract detailed results, report appropriately
        if (tests.isEmpty()) {
            val processHandler = descriptor.processHandler
            val exitCode = processHandler?.exitCode ?: -1

            if (exitCode != 0) {
                return TestResult(
                    success = false,
                    passed = 0,
                    failed = 1,
                    skipped = 0,
                    timeMs = timeMs,
                    tests = listOf(TestCaseResult(
                        name = "Unknown",
                        className = "Unknown",
                        methodName = "unknown",
                        status = TestStatus.ERROR,
                        timeMs = timeMs,
                        message = "Test process exited with code $exitCode"
                    )),
                    error = "Test process exited with code $exitCode"
                )
            }

            // No tests found - this is an error condition
            return TestResult(
                success = false,
                passed = 0,
                failed = 0,
                skipped = 0,
                timeMs = timeMs,
                tests = emptyList(),
                error = "No tests found matching the pattern. Check that the class/method name is correct and the test exists."
            )
        }

        return TestResult(
            success = failed == 0,
            passed = passed,
            failed = failed,
            skipped = skipped,
            timeMs = timeMs,
            tests = tests
        )
    }

    private fun collectTestResults(proxy: AbstractTestProxy, results: MutableList<TestCaseResult>) {
        if (proxy.isLeaf) {
            // This is an actual test case
            val smProxy = proxy as? SMTestProxy
            val status = when {
                smProxy?.isPassed == true -> TestStatus.PASSED
                smProxy?.isIgnored == true -> TestStatus.SKIPPED
                smProxy?.isDefect == true -> {
                    // Check if it's an error (exception) vs assertion failure
                    val stacktrace = smProxy.stacktrace ?: ""
                    if (stacktrace.contains("Error") || stacktrace.contains("Exception")) {
                        TestStatus.ERROR
                    } else {
                        TestStatus.FAILED
                    }
                }
                else -> TestStatus.PASSED
            }

            val fullName = proxy.name ?: "Unknown"
            val (className, methodName) = parseTestName(fullName, proxy.parent?.name)

            results.add(TestCaseResult(
                name = fullName,
                className = className,
                methodName = methodName,
                status = status,
                timeMs = proxy.duration ?: 0,
                message = smProxy?.errorMessage,
                stackTrace = smProxy?.stacktrace
            ))
        } else {
            // This is a test suite, recurse into children
            proxy.children.forEach { child ->
                collectTestResults(child, results)
            }
        }
    }

    private fun parseTestName(testName: String, parentName: String?): Pair<String, String> {
        // Try to parse "methodName(ClassName)" format
        val match = Regex("(.+)\\((.+)\\)").find(testName)
        if (match != null) {
            val methodName = match.groupValues[1]
            val className = match.groupValues[2]
            return Pair(className, methodName)
        }

        // Fallback: use parent as class name and test name as method
        return Pair(parentName ?: "Unknown", testName)
    }

    private fun getOpenProject(): Project? {
        return ProjectManager.getInstance().openProjects.firstOrNull()
    }
}
