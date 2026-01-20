package com.zatlas.mcpbridge.handlers

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.ex.ApplicationEx
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class PluginHandler {

    private val log = Logger.getInstance(PluginHandler::class.java)

    companion object {
        const val PLUGIN_ID = "com.zatlas.mcpbridge"
        val DEFAULT_PLUGIN_PATH = System.getProperty("user.home") +
            "/zatlas_projects/mcp-intellij-server/intellij-plugin/build/distributions/intellij-plugin-1.0.0.zip"
    }

    data class ReinstallResult(
        val success: Boolean,
        val message: String,
        val requiresRestart: Boolean = false
    )

    fun reinstallPlugin(pluginPath: String? = null): ReinstallResult {
        val sourcePath = pluginPath ?: DEFAULT_PLUGIN_PATH
        val sourceFile = File(sourcePath)

        if (!sourceFile.exists()) {
            return ReinstallResult(
                success = false,
                message = "Plugin file not found at: $sourcePath. Build the plugin first with './gradlew build'"
            )
        }

        return try {
            val pluginsDir = File(PathManager.getPluginsPath())
            val targetFile = File(pluginsDir, sourceFile.name)

            log.info("Copying plugin from $sourcePath to $targetFile")

            // Copy the plugin zip to the plugins directory
            Files.copy(
                sourceFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )

            log.info("Plugin copied successfully")

            ReinstallResult(
                success = true,
                message = "Plugin copied to ${targetFile.absolutePath}. Restart IntelliJ IDEA to apply changes.",
                requiresRestart = true
            )
        } catch (e: Exception) {
            log.error("Failed to copy plugin", e)
            ReinstallResult(
                success = false,
                message = "Failed to copy plugin: ${e.message}"
            )
        }
    }

    fun restartIde(): ReinstallResult {
        return try {
            log.info("Requesting IDE restart")
            ApplicationManager.getApplication().invokeLater {
                (ApplicationManager.getApplication() as ApplicationEx).restart(true)
            }
            ReinstallResult(
                success = true,
                message = "IDE restart initiated"
            )
        } catch (e: Exception) {
            log.error("Failed to restart IDE", e)
            ReinstallResult(
                success = false,
                message = "Failed to restart IDE: ${e.message}"
            )
        }
    }

    fun getPluginInfo(): Map<String, Any?> {
        val pluginId = PluginId.getId(PLUGIN_ID)
        val plugin = PluginManagerCore.getPlugin(pluginId)

        return mapOf(
            "pluginId" to PLUGIN_ID,
            "installed" to (plugin != null),
            "version" to plugin?.version,
            "name" to plugin?.name,
            "enabled" to (plugin?.isEnabled ?: false),
            "pluginsPath" to PathManager.getPluginsPath(),
            "defaultSourcePath" to DEFAULT_PLUGIN_PATH
        )
    }
}
