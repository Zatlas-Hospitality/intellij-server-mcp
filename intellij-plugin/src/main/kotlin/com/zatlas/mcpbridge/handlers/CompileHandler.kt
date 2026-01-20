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

class CompileHandler {

    private val log = Logger.getInstance(CompileHandler::class.java)

    @Volatile
    private var lastResult: CompileResult? = null

    /**
     * Trigger compilation (incremental or full rebuild)
     */
    fun compile(incremental: Boolean): CompileResult {
        val project = getOpenProject()
            ?: return CompileResult(
                success = false,
                errors = listOf(CompileError("No project open in IntelliJ")),
                warnings = emptyList(),
                timeMs = 0
            )

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
    }

    /**
     * Get the last compilation result
     */
    fun getLastResult(): CompileResult? = lastResult

    /**
     * Get current diagnostics (errors/warnings from the problem solver)
     */
    fun getDiagnostics(): DiagnosticsResult {
        val project = getOpenProject()
            ?: return DiagnosticsResult(
                errors = listOf(CompileError("No project open in IntelliJ")),
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

    private fun getOpenProject(): Project? {
        return ProjectManager.getInstance().openProjects.firstOrNull()
    }
}
