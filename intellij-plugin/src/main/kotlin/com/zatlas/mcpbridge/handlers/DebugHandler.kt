package com.zatlas.mcpbridge.handlers

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.breakpoints.XBreakpointType
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.XFullValueEvaluator
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink
import com.intellij.xdebugger.impl.breakpoints.XExpressionImpl
import com.intellij.ui.SimpleTextAttributes
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.swing.Icon

data class DebugSessionInfo(
    val sessionId: String,
    val sessionName: String,
    val isSuspended: Boolean,
    val currentFile: String?,
    val currentLine: Int?,
    val projectName: String
)

data class DebugSessionsResult(
    val success: Boolean,
    val sessions: List<DebugSessionInfo>,
    val error: String? = null
)

data class StackFrameInfo(
    val index: Int,
    val functionName: String?,
    val file: String?,
    val line: Int?,
    val isTopFrame: Boolean
)

data class DebugStackResult(
    val success: Boolean,
    val sessionName: String?,
    val isSuspended: Boolean,
    val frames: List<StackFrameInfo>,
    val error: String? = null
)

data class VariableInfo(
    val name: String,
    val value: String?,
    val type: String?,
    val hasChildren: Boolean
)

data class DebugVariablesResult(
    val success: Boolean,
    val sessionName: String?,
    val frameIndex: Int,
    val variables: List<VariableInfo>,
    val error: String? = null
)

data class DebugEvaluateResult(
    val success: Boolean,
    val expression: String,
    val result: String?,
    val type: String?,
    val hasChildren: Boolean,
    val error: String? = null
)

data class DebugStepResult(
    val success: Boolean,
    val action: String,
    val message: String? = null,
    val error: String? = null
)

data class BreakpointInfo(
    val id: String,
    val file: String,
    val line: Int,
    val enabled: Boolean,
    val condition: String?,
    val logExpression: String?
)

data class BreakpointListResult(
    val success: Boolean,
    val breakpoints: List<BreakpointInfo>,
    val error: String? = null
)

data class BreakpointSetResult(
    val success: Boolean,
    val breakpointId: String?,
    val file: String?,
    val line: Int?,
    val message: String? = null,
    val error: String? = null
)

data class BreakpointRemoveResult(
    val success: Boolean,
    val message: String? = null,
    val error: String? = null
)

class DebugHandler {

    private val log = Logger.getInstance(DebugHandler::class.java)


    private fun getOpenProject(): Project? {
        return ProjectManager.getInstance().openProjects.firstOrNull()
    }

    private fun getCurrentSession(): XDebugSession? {
        val project = getOpenProject() ?: return null
        return XDebuggerManager.getInstance(project).currentSession
    }

    fun listSessions(): DebugSessionsResult {
        val project = getOpenProject()
            ?: return DebugSessionsResult(
                success = false,
                sessions = emptyList(),
                error = "No project open in IntelliJ"
            )

        return try {
            val debuggerManager = XDebuggerManager.getInstance(project)
            val sessions = debuggerManager.debugSessions.map { session ->
                val position = session.currentPosition
                DebugSessionInfo(
                    sessionId = session.hashCode().toString(),
                    sessionName = session.sessionName,
                    isSuspended = session.isSuspended,
                    currentFile = position?.file?.path,
                    currentLine = position?.line?.plus(1),
                    projectName = project.name
                )
            }
            DebugSessionsResult(success = true, sessions = sessions)
        } catch (e: Exception) {
            log.error("Failed to list debug sessions", e)
            DebugSessionsResult(success = false, sessions = emptyList(), error = e.message)
        }
    }

    fun getStack(): DebugStackResult {
        val session = getCurrentSession()
            ?: return DebugStackResult(
                success = false,
                sessionName = null,
                isSuspended = false,
                frames = emptyList(),
                error = "No active debug session"
            )

        if (!session.isSuspended) {
            return DebugStackResult(
                success = false,
                sessionName = session.sessionName,
                isSuspended = false,
                frames = emptyList(),
                error = "Debug session is not suspended. The program must be paused at a breakpoint."
            )
        }

        return try {
            val suspendContext = session.suspendContext
            val executionStack = suspendContext?.activeExecutionStack

            val frames = mutableListOf<StackFrameInfo>()
            val currentFrame = session.currentStackFrame

            if (executionStack != null) {
                val future = CompletableFuture<Unit>()
                val frameList = mutableListOf<XStackFrame>()

                ApplicationManager.getApplication().invokeLater {
                    executionStack.computeStackFrames(0, object : XExecutionStack.XStackFrameContainer {
                        override fun addStackFrames(stackFrames: MutableList<out XStackFrame>, last: Boolean) {
                            frameList.addAll(stackFrames)
                            if (last) future.complete(Unit)
                        }
                        override fun errorOccurred(errorMessage: String) {
                            future.complete(Unit)
                        }
                    })
                }

                try {
                    future.get(5, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    log.warn("Timeout getting stack frames", e)
                }

                frameList.forEachIndexed { index, frame ->
                    val position = frame.sourcePosition
                    frames.add(StackFrameInfo(
                        index = index,
                        functionName = frame.toString(),
                        file = position?.file?.path,
                        line = position?.line?.plus(1),
                        isTopFrame = frame == currentFrame
                    ))
                }
            }

            DebugStackResult(
                success = true,
                sessionName = session.sessionName,
                isSuspended = true,
                frames = frames
            )
        } catch (e: Exception) {
            log.error("Failed to get stack frames", e)
            DebugStackResult(
                success = false,
                sessionName = session.sessionName,
                isSuspended = session.isSuspended,
                frames = emptyList(),
                error = e.message
            )
        }
    }

    fun getVariables(frameIndex: Int = 0): DebugVariablesResult {
        val session = getCurrentSession()
            ?: return DebugVariablesResult(
                success = false,
                sessionName = null,
                frameIndex = frameIndex,
                variables = emptyList(),
                error = "No active debug session"
            )

        if (!session.isSuspended) {
            return DebugVariablesResult(
                success = false,
                sessionName = session.sessionName,
                frameIndex = frameIndex,
                variables = emptyList(),
                error = "Debug session is not suspended"
            )
        }

        return try {
            val currentFrame = session.currentStackFrame
                ?: return DebugVariablesResult(
                    success = false,
                    sessionName = session.sessionName,
                    frameIndex = frameIndex,
                    variables = emptyList(),
                    error = "No current stack frame"
                )

            val variables = mutableListOf<VariableInfo>()
            val future = CompletableFuture<Unit>()

            ApplicationManager.getApplication().invokeLater {
                currentFrame.computeChildren(object : XCompositeNode {
                    override fun addChildren(children: XValueChildrenList, last: Boolean) {
                        for (i in 0 until children.size()) {
                            val name = children.getName(i)
                            val value = children.getValue(i)

                            val valueFuture = CompletableFuture<VariableInfo>()
                            value.computePresentation(object : XValueNode {
                                override fun setPresentation(icon: Icon?, presentation: XValuePresentation, hasChildren: Boolean) {
                                    valueFuture.complete(VariableInfo(
                                        name = name,
                                        value = "{${presentation.type ?: "object"}}",
                                        type = presentation.type,
                                        hasChildren = hasChildren
                                    ))
                                }
                                override fun setFullValueEvaluator(fullValueEvaluator: XFullValueEvaluator) {}
                                @Deprecated("Deprecated in Java")
                                override fun setPresentation(icon: Icon?, type: String?, value: String, hasChildren: Boolean) {
                                    valueFuture.complete(VariableInfo(
                                        name = name,
                                        value = value,
                                        type = type,
                                        hasChildren = hasChildren
                                    ))
                                }
                            }, XValuePlace.TREE)

                            try {
                                val varInfo = valueFuture.get(2, TimeUnit.SECONDS)
                                variables.add(varInfo)
                            } catch (e: Exception) {
                                variables.add(VariableInfo(name = name, value = "(timeout)", type = null, hasChildren = false))
                            }
                        }
                        if (last) future.complete(Unit)
                    }

                    override fun tooManyChildren(remaining: Int) {
                        future.complete(Unit)
                    }

                    override fun setAlreadySorted(alreadySorted: Boolean) {}

                    override fun setErrorMessage(errorMessage: String) {
                        future.complete(Unit)
                    }

                    override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) {
                        future.complete(Unit)
                    }

                    override fun setMessage(message: String, icon: Icon?, attributes: SimpleTextAttributes, link: XDebuggerTreeNodeHyperlink?) {}

                    override fun isObsolete(): Boolean = false
                })
            }

            try {
                future.get(10, TimeUnit.SECONDS)
            } catch (e: Exception) {
                log.warn("Timeout getting variables", e)
            }

            DebugVariablesResult(
                success = true,
                sessionName = session.sessionName,
                frameIndex = frameIndex,
                variables = variables
            )
        } catch (e: Exception) {
            log.error("Failed to get variables", e)
            DebugVariablesResult(
                success = false,
                sessionName = session.sessionName,
                frameIndex = frameIndex,
                variables = emptyList(),
                error = e.message
            )
        }
    }

    fun evaluate(expression: String): DebugEvaluateResult {
        val session = getCurrentSession()
            ?: return DebugEvaluateResult(
                success = false,
                expression = expression,
                result = null,
                type = null,
                hasChildren = false,
                error = "No active debug session"
            )

        if (!session.isSuspended) {
            return DebugEvaluateResult(
                success = false,
                expression = expression,
                result = null,
                type = null,
                hasChildren = false,
                error = "Debug session is not suspended"
            )
        }

        val currentFrame = session.currentStackFrame
            ?: return DebugEvaluateResult(
                success = false,
                expression = expression,
                result = null,
                type = null,
                hasChildren = false,
                error = "No current stack frame"
            )

        val evaluator = currentFrame.evaluator
            ?: return DebugEvaluateResult(
                success = false,
                expression = expression,
                result = null,
                type = null,
                hasChildren = false,
                error = "Evaluator not available for current frame"
            )

        val future = CompletableFuture<DebugEvaluateResult>()

        ApplicationManager.getApplication().invokeLater {
            evaluator.evaluate(expression, object : XDebuggerEvaluator.XEvaluationCallback {
                override fun evaluated(result: XValue) {
                    result.computePresentation(object : XValueNode {
                        override fun setPresentation(icon: Icon?, presentation: XValuePresentation, hasChildren: Boolean) {
                            future.complete(DebugEvaluateResult(
                                success = true,
                                expression = expression,
                                result = "{${presentation.type ?: "object"}}",
                                type = presentation.type,
                                hasChildren = hasChildren
                            ))
                        }
                        override fun setFullValueEvaluator(fullValueEvaluator: XFullValueEvaluator) {}
                        @Deprecated("Deprecated in Java")
                        override fun setPresentation(icon: Icon?, type: String?, value: String, hasChildren: Boolean) {
                            future.complete(DebugEvaluateResult(
                                success = true,
                                expression = expression,
                                result = value,
                                type = type,
                                hasChildren = hasChildren
                            ))
                        }
                    }, XValuePlace.TREE)
                }

                override fun errorOccurred(errorMessage: String) {
                    future.complete(DebugEvaluateResult(
                        success = false,
                        expression = expression,
                        result = null,
                        type = null,
                        hasChildren = false,
                        error = errorMessage
                    ))
                }
            }, null)
        }

        return try {
            future.get(10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            DebugEvaluateResult(
                success = false,
                expression = expression,
                result = null,
                type = null,
                hasChildren = false,
                error = "Evaluation timed out: ${e.message}"
            )
        }
    }

    fun pause(): DebugStepResult {
        val session = getCurrentSession()
            ?: return DebugStepResult(success = false, action = "pause", error = "No active debug session")

        val future = CompletableFuture<DebugStepResult>()
        ApplicationManager.getApplication().invokeLater {
            try {
                session.pause()
                future.complete(DebugStepResult(success = true, action = "pause", message = "Pause requested"))
            } catch (e: Exception) {
                future.complete(DebugStepResult(success = false, action = "pause", error = e.message))
            }
        }
        return future.get(5, TimeUnit.SECONDS)
    }

    fun resume(): DebugStepResult {
        val session = getCurrentSession()
            ?: return DebugStepResult(success = false, action = "resume", error = "No active debug session")

        if (!session.isSuspended) {
            return DebugStepResult(success = false, action = "resume", error = "Session is not suspended")
        }

        val future = CompletableFuture<DebugStepResult>()
        ApplicationManager.getApplication().invokeLater {
            try {
                session.resume()
                future.complete(DebugStepResult(success = true, action = "resume", message = "Execution resumed"))
            } catch (e: Exception) {
                future.complete(DebugStepResult(success = false, action = "resume", error = e.message))
            }
        }
        return future.get(5, TimeUnit.SECONDS)
    }

    fun stepOver(): DebugStepResult {
        val session = getCurrentSession()
            ?: return DebugStepResult(success = false, action = "step_over", error = "No active debug session")

        if (!session.isSuspended) {
            return DebugStepResult(success = false, action = "step_over", error = "Session is not suspended")
        }

        val future = CompletableFuture<DebugStepResult>()
        ApplicationManager.getApplication().invokeLater {
            try {
                session.stepOver(false)
                future.complete(DebugStepResult(success = true, action = "step_over", message = "Stepped over"))
            } catch (e: Exception) {
                future.complete(DebugStepResult(success = false, action = "step_over", error = e.message))
            }
        }
        return future.get(5, TimeUnit.SECONDS)
    }

    fun stepInto(): DebugStepResult {
        val session = getCurrentSession()
            ?: return DebugStepResult(success = false, action = "step_into", error = "No active debug session")

        if (!session.isSuspended) {
            return DebugStepResult(success = false, action = "step_into", error = "Session is not suspended")
        }

        val future = CompletableFuture<DebugStepResult>()
        ApplicationManager.getApplication().invokeLater {
            try {
                session.stepInto()
                future.complete(DebugStepResult(success = true, action = "step_into", message = "Stepped into"))
            } catch (e: Exception) {
                future.complete(DebugStepResult(success = false, action = "step_into", error = e.message))
            }
        }
        return future.get(5, TimeUnit.SECONDS)
    }

    fun stepOut(): DebugStepResult {
        val session = getCurrentSession()
            ?: return DebugStepResult(success = false, action = "step_out", error = "No active debug session")

        if (!session.isSuspended) {
            return DebugStepResult(success = false, action = "step_out", error = "Session is not suspended")
        }

        val future = CompletableFuture<DebugStepResult>()
        ApplicationManager.getApplication().invokeLater {
            try {
                session.stepOut()
                future.complete(DebugStepResult(success = true, action = "step_out", message = "Stepped out"))
            } catch (e: Exception) {
                future.complete(DebugStepResult(success = false, action = "step_out", error = e.message))
            }
        }
        return future.get(5, TimeUnit.SECONDS)
    }

    fun listBreakpoints(): BreakpointListResult {
        val project = getOpenProject()
            ?: return BreakpointListResult(success = false, breakpoints = emptyList(), error = "No project open")

        return try {
            val breakpointManager = XDebuggerManager.getInstance(project).breakpointManager
            val breakpoints = breakpointManager.allBreakpoints
                .filterIsInstance<com.intellij.xdebugger.breakpoints.XLineBreakpoint<*>>()
                .map { bp ->
                    BreakpointInfo(
                        id = bp.hashCode().toString(),
                        file = bp.fileUrl.removePrefix("file://"),
                        line = bp.line + 1,
                        enabled = bp.isEnabled,
                        condition = bp.conditionExpression?.expression,
                        logExpression = bp.logExpressionObject?.expression
                    )
                }
            BreakpointListResult(success = true, breakpoints = breakpoints)
        } catch (e: Exception) {
            log.error("Failed to list breakpoints", e)
            BreakpointListResult(success = false, breakpoints = emptyList(), error = e.message)
        }
    }

    fun setBreakpoint(filePath: String, line: Int, condition: String? = null): BreakpointSetResult {
        val project = getOpenProject()
            ?: return BreakpointSetResult(success = false, breakpointId = null, file = filePath, line = line, error = "No project open")

        val future = CompletableFuture<BreakpointSetResult>()

        ApplicationManager.getApplication().invokeLater {
            try {
                val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath)
                    ?: run {
                        future.complete(BreakpointSetResult(
                            success = false,
                            breakpointId = null,
                            file = filePath,
                            line = line,
                            error = "File not found: $filePath"
                        ))
                        return@invokeLater
                    }

                val breakpointManager = XDebuggerManager.getInstance(project).breakpointManager

                @Suppress("UNCHECKED_CAST")
                val lineBreakpointType = XBreakpointType.EXTENSION_POINT_NAME.extensions
                    .filterIsInstance<XLineBreakpointType<*>>()
                    .firstOrNull { it.canPutAt(virtualFile, line - 1, project) }
                    ?: run {
                        future.complete(BreakpointSetResult(
                            success = false,
                            breakpointId = null,
                            file = filePath,
                            line = line,
                            error = "Cannot set breakpoint at this location"
                        ))
                        return@invokeLater
                    }

                ApplicationManager.getApplication().runWriteAction {
                    val breakpoint = breakpointManager.addLineBreakpoint(
                        lineBreakpointType as XLineBreakpointType<Nothing>,
                        virtualFile.url,
                        line - 1,
                        null
                    )

                    if (condition != null && breakpoint != null) {
                        breakpoint.conditionExpression = XExpressionImpl.fromText(condition)
                    }

                    future.complete(BreakpointSetResult(
                        success = true,
                        breakpointId = breakpoint?.hashCode()?.toString(),
                        file = filePath,
                        line = line,
                        message = "Breakpoint set at $filePath:$line"
                    ))
                }
            } catch (e: Exception) {
                log.error("Failed to set breakpoint", e)
                future.complete(BreakpointSetResult(
                    success = false,
                    breakpointId = null,
                    file = filePath,
                    line = line,
                    error = e.message
                ))
            }
        }

        return try {
            future.get(10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            BreakpointSetResult(
                success = false,
                breakpointId = null,
                file = filePath,
                line = line,
                error = "Timeout setting breakpoint: ${e.message}"
            )
        }
    }

    fun removeBreakpoint(filePath: String, line: Int): BreakpointRemoveResult {
        val project = getOpenProject()
            ?: return BreakpointRemoveResult(success = false, error = "No project open")

        val future = CompletableFuture<BreakpointRemoveResult>()

        ApplicationManager.getApplication().invokeLater {
            try {
                val breakpointManager = XDebuggerManager.getInstance(project).breakpointManager
                val breakpoint = breakpointManager.allBreakpoints
                    .filterIsInstance<com.intellij.xdebugger.breakpoints.XLineBreakpoint<*>>()
                    .find { bp ->
                        bp.fileUrl.removePrefix("file://") == filePath && bp.line == line - 1
                    }

                if (breakpoint != null) {
                    ApplicationManager.getApplication().runWriteAction {
                        breakpointManager.removeBreakpoint(breakpoint)
                    }
                    future.complete(BreakpointRemoveResult(
                        success = true,
                        message = "Breakpoint removed at $filePath:$line"
                    ))
                } else {
                    future.complete(BreakpointRemoveResult(
                        success = false,
                        error = "No breakpoint found at $filePath:$line"
                    ))
                }
            } catch (e: Exception) {
                log.error("Failed to remove breakpoint", e)
                future.complete(BreakpointRemoveResult(success = false, error = e.message))
            }
        }

        return try {
            future.get(10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            BreakpointRemoveResult(success = false, error = "Timeout removing breakpoint: ${e.message}")
        }
    }
}
