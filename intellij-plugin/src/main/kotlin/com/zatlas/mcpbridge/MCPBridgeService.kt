package com.zatlas.mcpbridge

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.zatlas.mcpbridge.handlers.CompileHandler
import com.zatlas.mcpbridge.handlers.RunConfigHandler
import com.zatlas.mcpbridge.handlers.TestHandler
import com.zatlas.mcpbridge.settings.MCPBridgeSettings

@Service
class MCPBridgeService : Disposable {

    private val log = Logger.getInstance(MCPBridgeService::class.java)

    private var httpServer: HttpServer? = null
    private val compileHandler = CompileHandler()
    private val testHandler = TestHandler()
    private val runConfigHandler = RunConfigHandler()

    @Volatile
    private var isRunning = false

    /**
     * Start the HTTP server
     */
    @Synchronized
    fun start() {
        if (isRunning) {
            log.info("MCP Bridge server is already running")
            return
        }

        val settings = MCPBridgeSettings.getInstance()
        val port = settings.port

        try {
            log.info("Starting MCP Bridge server on port $port")
            httpServer = HttpServer.create(port, compileHandler, testHandler, runConfigHandler)
            isRunning = true
            log.info("MCP Bridge server started successfully on port $port")
        } catch (e: Exception) {
            log.error("Failed to start MCP Bridge server on port $port", e)
            isRunning = false
        }
    }

    /**
     * Stop the HTTP server
     */
    @Synchronized
    fun stop() {
        if (!isRunning) {
            log.info("MCP Bridge server is not running")
            return
        }

        try {
            log.info("Stopping MCP Bridge server")
            httpServer?.stop()
            httpServer = null
            isRunning = false
            log.info("MCP Bridge server stopped")
        } catch (e: Exception) {
            log.error("Failed to stop MCP Bridge server", e)
        }
    }

    /**
     * Restart the HTTP server
     */
    fun restart() {
        stop()
        start()
    }

    /**
     * Check if the server is running
     */
    fun isRunning(): Boolean = isRunning

    /**
     * Get the current port
     */
    fun getPort(): Int = MCPBridgeSettings.getInstance().port

    override fun dispose() {
        stop()
    }

    companion object {
        fun getInstance(): MCPBridgeService {
            return ApplicationManager.getApplication().getService(MCPBridgeService::class.java)
        }
    }
}
