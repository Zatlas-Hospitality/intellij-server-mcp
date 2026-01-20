package com.zatlas.mcpbridge

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.zatlas.mcpbridge.settings.MCPBridgeSettings

class MCPBridgeStartupActivity : ProjectActivity {

    private val log = Logger.getInstance(MCPBridgeStartupActivity::class.java)

    override suspend fun execute(project: Project) {
        val settings = MCPBridgeSettings.getInstance()

        if (settings.autoStart) {
            log.info("Auto-starting MCP Bridge server for project: ${project.name}")
            MCPBridgeService.getInstance().start()
        } else {
            log.info("MCP Bridge auto-start is disabled")
        }
    }
}
