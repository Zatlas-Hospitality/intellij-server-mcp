package com.zatlas.mcpbridge

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.zatlas.mcpbridge.handlers.CompileHandler
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
    private val pluginHandler: PluginHandler
) : NanoHTTPD(port) {

    private val gson = Gson()

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        return try {
            when {
                uri == "/health" && method == Method.GET -> handleHealth(session)
                uri == "/reset" && method == Method.POST -> handleReset()
                uri == "/compile" && method == Method.POST -> handleCompile(session)
                uri == "/compile/status" && method == Method.GET -> handleCompileStatus()
                uri == "/test" && method == Method.POST -> handleTest(session)
                uri == "/test/results" && method == Method.GET -> handleTestResults()
                uri == "/diagnostics" && method == Method.GET -> handleDiagnostics()
                // Run config endpoints
                uri == "/run/start" && method == Method.POST -> handleRunStart(session)
                uri == "/run/list" && method == Method.GET -> handleRunList()
                uri == "/run/projects" && method == Method.GET -> handleListProjects()
                uri.startsWith("/run/") && uri.endsWith("/output") && method == Method.GET -> handleRunOutput(session, uri)
                uri.startsWith("/run/") && uri.endsWith("/stop") && method == Method.POST -> handleRunStop(uri)
                // Plugin management endpoints
                uri == "/plugin/reinstall" && method == Method.POST -> handlePluginReinstall(session)
                uri == "/plugin/restart" && method == Method.POST -> handlePluginRestart()
                uri == "/plugin/info" && method == Method.GET -> handlePluginInfo()
                else -> jsonResponse(Response.Status.NOT_FOUND, mapOf(
                    "error" to "Not found",
                    "path" to uri,
                    "availableEndpoints" to listOf(
                        "GET /health",
                        "POST /reset",
                        "POST /compile",
                        "GET /compile/status",
                        "POST /test",
                        "GET /test/results",
                        "GET /diagnostics",
                        "POST /run/start",
                        "GET /run/list",
                        "GET /run/projects",
                        "GET /run/{runId}/output",
                        "POST /run/{runId}/stop",
                        "POST /plugin/reinstall",
                        "POST /plugin/restart",
                        "GET /plugin/info"
                    )
                ))
            }
        } catch (e: Exception) {
            jsonResponse(Response.Status.INTERNAL_ERROR, mapOf(
                "error" to (e.message ?: "Unknown error"),
                "type" to e.javaClass.simpleName
            ))
        }
    }

    private fun handleHealth(session: IHTTPSession): Response {
        val projectPath = session.parms["projectPath"]
        val openProjects = com.intellij.openapi.project.ProjectManager.getInstance().openProjects

        val targetProject = if (projectPath.isNullOrBlank()) {
            openProjects.firstOrNull()
        } else {
            openProjects.find { project ->
                project.basePath == projectPath ||
                project.basePath?.endsWith(projectPath) == true ||
                project.name == projectPath ||
                project.name.equals(projectPath, ignoreCase = true)
            }
        }

        return jsonResponse(Response.Status.OK, mapOf(
            "status" to "ok",
            "version" to "1.0.0",
            "plugin" to "mcp-bridge",
            "openProjects" to openProjects.map { mapOf("name" to it.name, "basePath" to it.basePath) },
            "targetProject" to targetProject?.let { mapOf("name" to it.name, "basePath" to it.basePath) },
            "projectFound" to (targetProject != null),
            "requestedProjectPath" to projectPath
        ))
    }

    private fun handleReset(): Response {
        return try {
            compileHandler.reset()
            testHandler.reset()
            runConfigHandler.reset()
            jsonResponse(Response.Status.OK, mapOf(
                "status" to "reset",
                "message" to "All handlers reset successfully",
                "compileLocked" to compileHandler.isLocked(),
                "testLocked" to testHandler.isLocked()
            ))
        } catch (e: Exception) {
            jsonResponse(Response.Status.INTERNAL_ERROR, mapOf(
                "error" to "Reset failed: ${e.message}",
                "type" to e.javaClass.simpleName
            ))
        }
    }

    private fun handleCompile(session: IHTTPSession): Response {
        val body = parseBody(session)
        val incremental = body?.get("incremental")?.asBoolean ?: true
        val projectPath = body?.get("projectPath")?.asString

        val result = compileHandler.compile(incremental, projectPath)
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
        val projectPath = body.get("projectPath")?.asString

        val result = testHandler.runTest(pattern, timeout, projectPath)
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

        val result = runConfigHandler.startRunConfig(configName, projectPath)
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
            pluginHandler: PluginHandler
        ): HttpServer {
            val server = HttpServer(port, compileHandler, testHandler, runConfigHandler, pluginHandler)
            server.start(SOCKET_READ_TIMEOUT, false)
            return server
        }
    }
}
