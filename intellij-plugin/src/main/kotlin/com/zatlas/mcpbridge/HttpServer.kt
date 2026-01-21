package com.zatlas.mcpbridge

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.zatlas.mcpbridge.handlers.CompileHandler
import com.zatlas.mcpbridge.handlers.DebugHandler
import com.zatlas.mcpbridge.handlers.PluginHandler
import com.zatlas.mcpbridge.handlers.RunConfigHandler
import com.zatlas.mcpbridge.handlers.TestHandler
import fi.iki.elonen.NanoHTTPD
import java.io.IOException

class HttpServer(
    port: Int,
    private val compileHandler: CompileHandler,
    private val testHandler: TestHandler,
    private val runConfigHandler: RunConfigHandler,
    private val pluginHandler: PluginHandler,
    private val debugHandler: DebugHandler
) : NanoHTTPD(port) {

    private val gson = Gson()

    private data class Route(
        val method: Method,
        val path: String,
        val handler: (IHTTPSession) -> Response,
        val pathPattern: Regex? = null
    ) {
        fun matches(reqMethod: Method, uri: String): Boolean {
            if (reqMethod != method) return false
            return pathPattern?.matches(uri) ?: (path == uri)
        }

        override fun toString(): String = "${method.name} $path"
    }

    private val routes: List<Route> by lazy {
        listOf(
            Route(Method.GET, "/health", { handleHealth() }),
            Route(Method.POST, "/compile", { handleCompile(it) }),
            Route(Method.GET, "/compile/status", { handleCompileStatus() }),
            Route(Method.POST, "/test", { handleTest(it) }),
            Route(Method.GET, "/test/results", { handleTestResults() }),
            Route(Method.GET, "/diagnostics", { handleDiagnostics() }),
            Route(Method.POST, "/run/start", { handleRunStart(it) }),
            Route(Method.GET, "/run/list", { handleRunList() }),
            Route(Method.GET, "/run/projects", { handleListProjects() }),
            Route(Method.GET, "/run/{runId}/output", { s -> handleRunOutput(s, s.uri) }, Regex("/run/[^/]+/output")),
            Route(Method.POST, "/run/{runId}/stop", { s -> handleRunStop(s.uri) }, Regex("/run/[^/]+/stop")),
            Route(Method.POST, "/plugin/reinstall", { handlePluginReinstall(it) }),
            Route(Method.POST, "/plugin/restart", { handlePluginRestart() }),
            Route(Method.GET, "/plugin/info", { handlePluginInfo() }),
            Route(Method.GET, "/debug/sessions", { handleDebugSessions() }),
            Route(Method.GET, "/debug/stack", { handleDebugStack() }),
            Route(Method.GET, "/debug/variables", { handleDebugVariables(it) }),
            Route(Method.POST, "/debug/evaluate", { handleDebugEvaluate(it) }),
            Route(Method.POST, "/debug/pause", { handleDebugPause() }),
            Route(Method.POST, "/debug/resume", { handleDebugResume() }),
            Route(Method.POST, "/debug/step/over", { handleDebugStepOver() }),
            Route(Method.POST, "/debug/step/into", { handleDebugStepInto() }),
            Route(Method.POST, "/debug/step/out", { handleDebugStepOut() }),
            Route(Method.GET, "/breakpoint/list", { handleBreakpointList() }),
            Route(Method.POST, "/breakpoint/set", { handleBreakpointSet(it) }),
            Route(Method.POST, "/breakpoint/remove", { handleBreakpointRemove(it) })
        )
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        return try {
            routes.firstOrNull { it.matches(method, uri) }?.handler?.invoke(session)
                ?: jsonResponse(Response.Status.NOT_FOUND, mapOf(
                    "error" to "Not found",
                    "path" to uri,
                    "availableEndpoints" to routes.map { it.toString() }
                ))
        } catch (e: Exception) {
            jsonResponse(Response.Status.INTERNAL_ERROR, mapOf(
                "error" to (e.message ?: "Unknown error"),
                "type" to e.javaClass.simpleName
            ))
        }
    }

    private fun handleHealth(): Response {
        return jsonResponse(Response.Status.OK, mapOf(
            "status" to "ok",
            "version" to "1.0.0",
            "plugin" to "mcp-bridge",
            "endpoints" to routes.map { it.toString() }
        ))
    }

    private fun handleCompile(session: IHTTPSession): Response {
        val body = parseBody(session)
        val incremental = body?.get("incremental")?.asBoolean ?: true

        val result = compileHandler.compile(incremental)
        return jsonResponse(
            if (result.success) Response.Status.OK else Response.Status.INTERNAL_ERROR,
            result
        )
    }

    private fun handleCompileStatus(): Response {
        val status = compileHandler.getLastResult()
        return if (status != null) {
            jsonResponse(Response.Status.OK, status)
        } else {
            jsonResponse(Response.Status.OK, mapOf(
                "status" to "no_compilation_yet"
            ))
        }
    }

    private fun handleTest(session: IHTTPSession): Response {
        val body = parseBody(session)
        val pattern = body?.get("pattern")?.asString
            ?: return jsonResponse(Response.Status.BAD_REQUEST, mapOf(
                "error" to "Missing required field: pattern"
            ))

        val timeout = body.get("timeout")?.asInt ?: 300
        val debug = body.get("debug")?.asBoolean ?: false

        val result = testHandler.runTest(pattern, timeout, debug)
        return jsonResponse(
            if (result.success) Response.Status.OK else Response.Status.INTERNAL_ERROR,
            result
        )
    }

    private fun handleTestResults(): Response {
        val results = testHandler.getLastResults()
        return if (results != null) {
            jsonResponse(Response.Status.OK, results)
        } else {
            jsonResponse(Response.Status.OK, mapOf(
                "status" to "no_tests_run_yet"
            ))
        }
    }

    private fun handleDiagnostics(): Response {
        val diagnostics = compileHandler.getDiagnostics()
        return jsonResponse(Response.Status.OK, diagnostics)
    }

    // Run config handlers
    private fun handleRunStart(session: IHTTPSession): Response {
        val body = parseBody(session)
        val configName = body?.get("configName")?.asString
            ?: return jsonResponse(Response.Status.BAD_REQUEST, mapOf(
                "error" to "Missing required field: configName"
            ))
        val projectPath = body.get("projectPath")?.asString
        val debug = body.get("debug")?.asBoolean ?: false

        val result = runConfigHandler.startRunConfig(configName, projectPath, debug)
        return jsonResponse(
            if (result.success) Response.Status.OK else Response.Status.INTERNAL_ERROR,
            result
        )
    }

    private fun handleRunList(): Response {
        val result = runConfigHandler.listRuns()
        return jsonResponse(Response.Status.OK, result)
    }

    private fun handleListProjects(): Response {
        val result = runConfigHandler.listProjects()
        return jsonResponse(Response.Status.OK, result)
    }

    private fun handleRunOutput(session: IHTTPSession, uri: String): Response {
        // Extract runId from /run/{runId}/output
        val runId = uri.removePrefix("/run/").removeSuffix("/output")
        val clear = session.parameters["clear"]?.firstOrNull()?.toBoolean() ?: false

        val result = runConfigHandler.getRunOutput(runId, clear)
        return jsonResponse(
            if (result.success) Response.Status.OK else Response.Status.NOT_FOUND,
            result
        )
    }

    private fun handleRunStop(uri: String): Response {
        // Extract runId from /run/{runId}/stop
        val runId = uri.removePrefix("/run/").removeSuffix("/stop")

        val result = runConfigHandler.stopRun(runId)
        return jsonResponse(
            if (result.success) Response.Status.OK else Response.Status.NOT_FOUND,
            result
        )
    }

    // Plugin management handlers
    private fun handlePluginReinstall(session: IHTTPSession): Response {
        val body = parseBody(session)
        val pluginPath = body?.get("pluginPath")?.asString

        val result = pluginHandler.reinstallPlugin(pluginPath)
        return jsonResponse(
            if (result.success) Response.Status.OK else Response.Status.INTERNAL_ERROR,
            result
        )
    }

    private fun handlePluginRestart(): Response {
        val result = pluginHandler.restartIde()
        return jsonResponse(
            if (result.success) Response.Status.OK else Response.Status.INTERNAL_ERROR,
            result
        )
    }

    private fun handlePluginInfo(): Response {
        val info = pluginHandler.getPluginInfo()
        return jsonResponse(Response.Status.OK, info)
    }

    // Debug handlers
    private fun handleDebugSessions(): Response {
        val result = debugHandler.listSessions()
        return jsonResponse(
            if (result.success) Response.Status.OK else Response.Status.INTERNAL_ERROR,
            result
        )
    }

    private fun handleDebugStack(): Response {
        val result = debugHandler.getStack()
        return jsonResponse(
            if (result.success) Response.Status.OK else Response.Status.INTERNAL_ERROR,
            result
        )
    }

    private fun handleDebugVariables(session: IHTTPSession): Response {
        val frameIndex = session.parameters["frameIndex"]?.firstOrNull()?.toIntOrNull() ?: 0
        val result = debugHandler.getVariables(frameIndex)
        return jsonResponse(
            if (result.success) Response.Status.OK else Response.Status.INTERNAL_ERROR,
            result
        )
    }

    private fun handleDebugEvaluate(session: IHTTPSession): Response {
        val body = parseBody(session)
        val expression = body?.get("expression")?.asString
            ?: return jsonResponse(Response.Status.BAD_REQUEST, mapOf(
                "error" to "Missing required field: expression"
            ))

        val result = debugHandler.evaluate(expression)
        return jsonResponse(
            if (result.success) Response.Status.OK else Response.Status.INTERNAL_ERROR,
            result
        )
    }

    private fun handleDebugPause(): Response {
        val result = debugHandler.pause()
        return jsonResponse(
            if (result.success) Response.Status.OK else Response.Status.INTERNAL_ERROR,
            result
        )
    }

    private fun handleDebugResume(): Response {
        val result = debugHandler.resume()
        return jsonResponse(
            if (result.success) Response.Status.OK else Response.Status.INTERNAL_ERROR,
            result
        )
    }

    private fun handleDebugStepOver(): Response {
        val result = debugHandler.stepOver()
        return jsonResponse(
            if (result.success) Response.Status.OK else Response.Status.INTERNAL_ERROR,
            result
        )
    }

    private fun handleDebugStepInto(): Response {
        val result = debugHandler.stepInto()
        return jsonResponse(
            if (result.success) Response.Status.OK else Response.Status.INTERNAL_ERROR,
            result
        )
    }

    private fun handleDebugStepOut(): Response {
        val result = debugHandler.stepOut()
        return jsonResponse(
            if (result.success) Response.Status.OK else Response.Status.INTERNAL_ERROR,
            result
        )
    }

    private fun handleBreakpointList(): Response {
        val result = debugHandler.listBreakpoints()
        return jsonResponse(
            if (result.success) Response.Status.OK else Response.Status.INTERNAL_ERROR,
            result
        )
    }

    private fun handleBreakpointSet(session: IHTTPSession): Response {
        val body = parseBody(session)
        val file = body?.get("file")?.asString
            ?: return jsonResponse(Response.Status.BAD_REQUEST, mapOf(
                "error" to "Missing required field: file"
            ))
        val line = body.get("line")?.asInt
            ?: return jsonResponse(Response.Status.BAD_REQUEST, mapOf(
                "error" to "Missing required field: line"
            ))
        val condition = body.get("condition")?.asString

        val result = debugHandler.setBreakpoint(file, line, condition)
        return jsonResponse(
            if (result.success) Response.Status.OK else Response.Status.INTERNAL_ERROR,
            result
        )
    }

    private fun handleBreakpointRemove(session: IHTTPSession): Response {
        val body = parseBody(session)
        val file = body?.get("file")?.asString
            ?: return jsonResponse(Response.Status.BAD_REQUEST, mapOf(
                "error" to "Missing required field: file"
            ))
        val line = body.get("line")?.asInt
            ?: return jsonResponse(Response.Status.BAD_REQUEST, mapOf(
                "error" to "Missing required field: line"
            ))

        val result = debugHandler.removeBreakpoint(file, line)
        return jsonResponse(
            if (result.success) Response.Status.OK else Response.Status.INTERNAL_ERROR,
            result
        )
    }

    private fun parseBody(session: IHTTPSession): JsonObject? {
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val postData = files["postData"] ?: return null
            JsonParser.parseString(postData).asJsonObject
        } catch (e: Exception) {
            null
        }
    }

    private fun jsonResponse(status: Response.Status, data: Any): Response {
        val json = gson.toJson(data)
        return newFixedLengthResponse(status, "application/json", json)
    }

    companion object {
        @Throws(IOException::class)
        fun create(
            port: Int,
            compileHandler: CompileHandler,
            testHandler: TestHandler,
            runConfigHandler: RunConfigHandler,
            pluginHandler: PluginHandler,
            debugHandler: DebugHandler
        ): HttpServer {
            val server = HttpServer(port, compileHandler, testHandler, runConfigHandler, pluginHandler, debugHandler)
            server.start(SOCKET_READ_TIMEOUT, false)
            return server
        }
    }
}
