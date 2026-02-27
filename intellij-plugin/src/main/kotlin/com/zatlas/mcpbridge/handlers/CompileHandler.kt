package com.zatlas.mcpbridge.handlers

import com.intellij.compiler.CompilerMessageImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.compiler.CompileContext
import com.intellij.openapi.compiler.CompileStatusNotification
import com.intellij.openapi.compiler.CompilerManager
import com.intellij.openapi.compiler.CompilerMessageCategory
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.problems.WolfTheProblemSolver
import com.zatlas.mcpbridge.models.CompileError
import com.zatlas.mcpbridge.models.CompileResult
import com.zatlas.mcpbridge.models.DiagnosticsResult
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock

class CompileHandler {

    private val log = Logger.getInstance(CompileHandler::class.java)

    @Volatile
    private var lastResult: CompileResult? = null

    companion object {
        private val compileLock = ReentrantLock()
        private const val MAX_WAIT_FOR_LOCK_MS = 30_000L  // 30 seconds instead of 5 minutes
        private const val MAX_WAIT_FOR_DAEMON_MS = 60_000L  // 1 minute for active compilation
        private const val POLL_INTERVAL_MS = 500L
    }

    /**
     * Reset handler state - use for recovery after errors
     */
    fun reset() {
        log.info("Resetting CompileHandler state")
        lastResult = null
        // Force unlock if stuck (only works if current thread holds it)
        if (compileLock.isHeldByCurrentThread) {
            compileLock.unlock()
            log.info("Released compile lock held by current thread")
        }
        // Try to acquire and release to clear any stale state
        if (compileLock.tryLock()) {
            compileLock.unlock()
            log.info("Compile lock is available")
        } else {
            log.warn("Compile lock is held by another thread - may need IDE restart")
        }
    }

    /**
     * Check if compilation is currently locked
     */
    fun isLocked(): Boolean = compileLock.isLocked

    /**
     * Check if any project has an active compilation
     */
    private fun isAnyCompilationActive(): Boolean {
        return ProjectManager.getInstance().openProjects.any { project ->
            try {
                CompilerManager.getInstance(project).isCompilationActive
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * Wait for any active compilation to finish
     */
    private fun waitForCompilationToFinish(): Boolean {
        val startWait = System.currentTimeMillis()
        while (isAnyCompilationActive()) {
            if (System.currentTimeMillis() - startWait > MAX_WAIT_FOR_DAEMON_MS) {
                log.warn("Timed out waiting for active compilation to finish")
                return false
            }
            log.info("Waiting for active compilation to finish...")
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return true
    }

    /**
     * Trigger compilation (incremental or full rebuild)
     */
    fun compile(incremental: Boolean, projectPath: String? = null): CompileResult {
        val project = findProject(projectPath)
            ?: return CompileResult(
                success = false,
                errors = listOf(CompileError("No project open in IntelliJ${projectPath?.let { " matching: $it" } ?: ""}")),
                warnings = emptyList(),
                timeMs = 0
            )

        if (!compileLock.tryLock(MAX_WAIT_FOR_LOCK_MS, TimeUnit.MILLISECONDS)) {
            return CompileResult(
                success = false,
                errors = listOf(CompileError("Timed out waiting for compile lock (${MAX_WAIT_FOR_LOCK_MS}ms) - another compilation may be stuck. Try POST /reset to recover.")),
                warnings = emptyList(),
                timeMs = 0
            )
        }

        // Clear previous result to free memory
        lastResult = null

        try {
            if (!waitForCompilationToFinish()) {
                return CompileResult(
                    success = false,
                    errors = listOf(CompileError("Timed out waiting for active compilation to finish")),
                    warnings = emptyList(),
                    timeMs = 0
                )
            }

            val future = CompletableFuture<CompileResult>()
            val startTime = System.currentTimeMillis()

            ApplicationManager.getApplication().invokeLater {
            try {
                val compilerManager = CompilerManager.getInstance(project)

                val callback = CompileStatusNotification { aborted, errors, warnings, context ->
                    val elapsed = System.currentTimeMillis() - startTime
                    val result = buildResult(context, aborted, errors, warnings, elapsed)
                    lastResult = result
                    future.complete(result)
                }

                if (incremental) {
                    log.info("Starting incremental compilation for project: ${project.name}")
                    compilerManager.make(callback)
                } else {
                    log.info("Starting full rebuild for project: ${project.name}")
                    compilerManager.rebuild(callback)
                }
            } catch (e: Exception) {
                log.error("Compilation failed", e)
                future.complete(CompileResult(
                    success = false,
                    errors = listOf(CompileError(e.message ?: "Unknown compilation error")),
                    warnings = emptyList(),
                    timeMs = System.currentTimeMillis() - startTime
                ))
            }
        }

            return try {
                future.get(5, TimeUnit.MINUTES)
            } catch (e: TimeoutException) {
                CompileResult(
                    success = false,
                    errors = listOf(CompileError("Compilation timed out after 5 minutes")),
                    warnings = emptyList(),
                    timeMs = 5 * 60 * 1000,
                    aborted = true
                )
            } catch (e: Exception) {
                CompileResult(
                    success = false,
                    errors = listOf(CompileError(e.message ?: "Compilation failed")),
                    warnings = emptyList(),
                    timeMs = System.currentTimeMillis() - startTime
                )
            }
        } finally {
            compileLock.unlock()
        }
    }

    /**
     * Get the last compilation result
     */
    fun getLastResult(): CompileResult? = lastResult

    /**
     * Get current diagnostics (errors/warnings from the problem solver)
     */
    fun getDiagnostics(projectPath: String? = null): DiagnosticsResult {
        val project = findProject(projectPath)
            ?: return DiagnosticsResult(
                errors = listOf(CompileError("No project open in IntelliJ${projectPath?.let { " matching: $it" } ?: ""}")),
                warnings = emptyList(),
                projectName = null
            )

        val errors = mutableListOf<CompileError>()
        val warnings = mutableListOf<CompileError>()

        try {
            val problemSolver = WolfTheProblemSolver.getInstance(project)

            // Get problems from the problem solver
            // Note: This provides a snapshot of current IDE problems
            val hasProblemFiles = problemSolver.hasProblemFilesBeneath { true }

            if (hasProblemFiles) {
                errors.add(CompileError(
                    message = "Project has files with problems. Run compilation to get detailed diagnostics.",
                    severity = "INFO"
                ))
            }
        } catch (e: Exception) {
            log.warn("Failed to get diagnostics", e)
        }

        // If we have last compile result, include those errors
        lastResult?.let { result ->
            errors.addAll(result.errors.filter { it.severity == "ERROR" })
            warnings.addAll(result.warnings)
        }

        return DiagnosticsResult(
            errors = errors,
            warnings = warnings,
            projectName = project.name
        )
    }

    private fun buildResult(
        context: CompileContext?,
        aborted: Boolean,
        errorCount: Int,
        warningCount: Int,
        timeMs: Long
    ): CompileResult {
        val errors = mutableListOf<CompileError>()
        val warnings = mutableListOf<CompileError>()

        context?.let { ctx ->
            // Extract error messages
            ctx.getMessages(CompilerMessageCategory.ERROR).forEach { msg ->
                errors.add(CompileError(
                    message = msg.message,
                    file = msg.virtualFile?.path,
                    line = if (msg is CompilerMessageImpl) msg.line else null,
                    column = if (msg is CompilerMessageImpl) msg.column else null,
                    severity = "ERROR"
                ))
            }

            // Extract warning messages
            ctx.getMessages(CompilerMessageCategory.WARNING).forEach { msg ->
                warnings.add(CompileError(
                    message = msg.message,
                    file = msg.virtualFile?.path,
                    line = if (msg is CompilerMessageImpl) msg.line else null,
                    column = if (msg is CompilerMessageImpl) msg.column else null,
                    severity = "WARNING"
                ))
            }
        }

        // If no messages were extracted but we have counts, add placeholder messages
        if (errors.isEmpty() && errorCount > 0) {
            errors.add(CompileError("$errorCount error(s) occurred during compilation"))
        }
        if (warnings.isEmpty() && warningCount > 0) {
            warnings.add(CompileError("$warningCount warning(s) occurred during compilation", severity = "WARNING"))
        }

        return CompileResult(
            success = errorCount == 0 && !aborted,
            errors = errors,
            warnings = warnings,
            timeMs = timeMs,
            aborted = aborted
        )
    }

    private fun findProject(projectPath: String?): Project? {
        val openProjects = ProjectManager.getInstance().openProjects
        if (projectPath.isNullOrBlank()) {
            return openProjects.firstOrNull()
        }
        return openProjects.find { project ->
            project.basePath == projectPath ||
            project.basePath?.endsWith(projectPath) == true ||
            project.name == projectPath ||
            project.name.equals(projectPath, ignoreCase = true)
        } ?: openProjects.firstOrNull()
    }
}
