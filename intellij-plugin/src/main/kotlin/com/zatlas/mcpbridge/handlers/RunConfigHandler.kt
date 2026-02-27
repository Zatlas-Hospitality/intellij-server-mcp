package com.zatlas.mcpbridge.handlers

import com.intellij.execution.*
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.*
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Key
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class RunInfo(
    val id: String,
    val configName: String,
    val projectName: String,
    val startTime: Long,
    val output: StringBuilder = StringBuilder(),
    var exitCode: Int? = null,
    var isRunning: Boolean = true,
    var processHandler: ProcessHandler? = null
)

data class RunStartResult(
    val success: Boolean,
    val runId: String? = null,
    val error: String? = null,
    val configName: String? = null,
    val projectName: String? = null
)

data class RunOutputResult(
    val success: Boolean,
    val runId: String,
    val output: String,
    val isRunning: Boolean,
    val exitCode: Int? = null,
    val error: String? = null
)

data class RunStopResult(
    val success: Boolean,
    val runId: String,
    val message: String? = null,
    val error: String? = null
)

data class ActiveRun(
    val runId: String,
    val configName: String,
    val projectName: String,
    val startTime: Long,
    val isRunning: Boolean,
    val exitCode: Int?
)

data class RunListResult(
    val runs: List<ActiveRun>
)

data class OpenProjectInfo(
    val name: String,
    val basePath: String?
)

data class ProjectListResult(
    val projects: List<OpenProjectInfo>
)

class RunConfigHandler {

    private val log = Logger.getInstance(RunConfigHandler::class.java)
    private val activeRuns = ConcurrentHashMap<String, RunInfo>()
    private val runIdCounter = AtomicInteger(0)

    companion object {
        private const val MAX_OUTPUT_SIZE = 1_000_000  // 1MB max output per run
    }

    /**
     * Reset handler state - clears all tracked runs and frees memory
     */
    fun reset() {
        log.info("Resetting RunConfigHandler state - clearing ${activeRuns.size} tracked runs")
        // Stop any running processes
        activeRuns.values.forEach { runInfo ->
            try {
                runInfo.processHandler?.let { handler ->
                    if (!handler.isProcessTerminated) {
                        handler.destroyProcess()
                    }
                }
            } catch (e: Exception) {
                log.warn("Failed to stop process for run ${runInfo.id}", e)
            }
        }
        activeRuns.clear()
        log.info("RunConfigHandler reset complete")
    }

    private fun findProject(projectPath: String?): Project? {
        val openProjects = ProjectManager.getInstance().openProjects

        if (projectPath.isNullOrBlank()) {
            return openProjects.firstOrNull()
        }

        // Try to match by base path or name
        return openProjects.find { project ->
            project.basePath == projectPath ||
            project.basePath?.endsWith(projectPath) == true ||
            project.name == projectPath ||
            project.name.equals(projectPath, ignoreCase = true)
        } ?: openProjects.firstOrNull()
    }

    fun listProjects(): ProjectListResult {
        val projects = ProjectManager.getInstance().openProjects.map { project ->
            OpenProjectInfo(
                name = project.name,
                basePath = project.basePath
            )
        }
        return ProjectListResult(projects)
    }

    fun startRunConfig(configName: String, projectPath: String? = null): RunStartResult {
        val project = findProject(projectPath)
            ?: return RunStartResult(
                success = false,
                error = "No project open in IntelliJ"
            )

        val latch = CountDownLatch(1)
        var result: RunStartResult? = null

        ApplicationManager.getApplication().invokeLater {
            try {
                val runManager = RunManager.getInstance(project)
                val settings = runManager.allSettings.find { it.name == configName }

                if (settings == null) {
                    result = RunStartResult(
                        success = false,
                        error = "Run configuration '$configName' not found. Available: ${runManager.allSettings.map { it.name }}"
                    )
                    latch.countDown()
                    return@invokeLater
                }

                val executor = DefaultRunExecutor.getRunExecutorInstance()
                val environment = ExecutionEnvironmentBuilder.create(executor, settings).build()

                val runId = "run-${runIdCounter.incrementAndGet()}"
                val runInfo = RunInfo(
                    id = runId,
                    configName = configName,
                    projectName = project.name,
                    startTime = System.currentTimeMillis()
                )

                environment.callback = object : ProgramRunner.Callback {
                    override fun processStarted(descriptor: com.intellij.execution.ui.RunContentDescriptor) {
                        val processHandler = descriptor.processHandler
                        runInfo.processHandler = processHandler

                        processHandler?.addProcessListener(object : ProcessListener {
                            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                                synchronized(runInfo.output) {
                                    // Limit output size to prevent OOM
                                    if (runInfo.output.length < MAX_OUTPUT_SIZE) {
                                        val remainingCapacity = MAX_OUTPUT_SIZE - runInfo.output.length
                                        val textToAppend = if (event.text.length > remainingCapacity) {
                                            event.text.substring(0, remainingCapacity) + "\n... [output truncated]"
                                        } else {
                                            event.text
                                        }
                                        runInfo.output.append(textToAppend)
                                    }
                                }
                            }

                            override fun processTerminated(event: ProcessEvent) {
                                runInfo.exitCode = event.exitCode
                                runInfo.isRunning = false
                            }

                            override fun processWillTerminate(event: ProcessEvent, willBeDestroyed: Boolean) {}
                            override fun startNotified(event: ProcessEvent) {}
                        })
                    }
                }

                activeRuns[runId] = runInfo

                ProgramRunnerUtil.executeConfiguration(environment, false, false)

                result = RunStartResult(
                    success = true,
                    runId = runId,
                    configName = configName,
                    projectName = project.name
                )
            } catch (e: Exception) {
                log.error("Failed to start run configuration", e)
                result = RunStartResult(
                    success = false,
                    error = e.message ?: "Failed to start run configuration"
                )
            }
            latch.countDown()
        }

        latch.await(30, TimeUnit.SECONDS)
        return result ?: RunStartResult(success = false, error = "Timeout starting run configuration")
    }

    fun getRunOutput(runId: String, clear: Boolean = false): RunOutputResult {
        val runInfo = activeRuns[runId]
            ?: return RunOutputResult(
                success = false,
                runId = runId,
                output = "",
                isRunning = false,
                error = "Run '$runId' not found"
            )

        val output = synchronized(runInfo.output) {
            if (clear) {
                val currentOutput = runInfo.output.toString()
                runInfo.output.clear()
                currentOutput
            } else {
                runInfo.output.toString()
            }
        }

        return RunOutputResult(
            success = true,
            runId = runId,
            output = output,
            isRunning = runInfo.isRunning,
            exitCode = runInfo.exitCode
        )
    }

    fun stopRun(runId: String): RunStopResult {
        val runInfo = activeRuns[runId]
            ?: return RunStopResult(
                success = false,
                runId = runId,
                error = "Run '$runId' not found"
            )

        val processHandler = runInfo.processHandler
        if (processHandler == null || processHandler.isProcessTerminated) {
            return RunStopResult(
                success = true,
                runId = runId,
                message = "Process already terminated"
            )
        }

        processHandler.destroyProcess()

        return RunStopResult(
            success = true,
            runId = runId,
            message = "Stop signal sent"
        )
    }

    fun listRuns(): RunListResult {
        val runs = activeRuns.values.map { info ->
            ActiveRun(
                runId = info.id,
                configName = info.configName,
                projectName = info.projectName,
                startTime = info.startTime,
                isRunning = info.isRunning,
                exitCode = info.exitCode
            )
        }
        return RunListResult(runs)
    }

    fun cleanupTerminatedRuns(maxAgeMs: Long = 3600000) {
        activeRuns.entries.removeIf { (_, info) ->
            !info.isRunning && System.currentTimeMillis() - info.startTime > maxAgeMs
        }
    }
}
