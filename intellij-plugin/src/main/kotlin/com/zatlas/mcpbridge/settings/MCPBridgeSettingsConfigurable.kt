package com.zatlas.mcpbridge.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.zatlas.mcpbridge.MCPBridgeService
import javax.swing.JComponent
import javax.swing.JPanel

class MCPBridgeSettingsConfigurable : Configurable {

    private var portField: JBTextField? = null
    private var autoStartCheckbox: JBCheckBox? = null
    private var mainPanel: JPanel? = null

    override fun getDisplayName(): String = "MCP Bridge"

    override fun createComponent(): JComponent {
        portField = JBTextField().apply {
            text = MCPBridgeSettings.getInstance().port.toString()
        }

        autoStartCheckbox = JBCheckBox("Auto-start server on IDE startup").apply {
            isSelected = MCPBridgeSettings.getInstance().autoStart
        }

        mainPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("HTTP Port:"), portField!!, 1, false)
            .addComponent(autoStartCheckbox!!, 1)
            .addComponentFillVertically(JPanel(), 0)
            .addComponent(JBLabel("<html><small>The MCP Bridge server exposes HTTP endpoints for compilation and test execution.<br/>Default port: ${MCPBridgeSettings.DEFAULT_PORT}</small></html>"))
            .panel

        return mainPanel!!
    }

    override fun isModified(): Boolean {
        val settings = MCPBridgeSettings.getInstance()
        return portField?.text?.toIntOrNull() != settings.port ||
                autoStartCheckbox?.isSelected != settings.autoStart
    }

    override fun apply() {
        val newPort = portField?.text?.toIntOrNull()
        if (newPort == null || newPort < 1 || newPort > 65535) {
            Messages.showErrorDialog(
                "Port must be a number between 1 and 65535",
                "Invalid Port"
            )
            return
        }

        val settings = MCPBridgeSettings.getInstance()
        val portChanged = newPort != settings.port

        settings.port = newPort
        settings.autoStart = autoStartCheckbox?.isSelected ?: true

        if (portChanged) {
            MCPBridgeService.getInstance().restart()
        }
    }

    override fun reset() {
        val settings = MCPBridgeSettings.getInstance()
        portField?.text = settings.port.toString()
        autoStartCheckbox?.isSelected = settings.autoStart
    }

    override fun disposeUIResources() {
        portField = null
        autoStartCheckbox = null
        mainPanel = null
    }
}
