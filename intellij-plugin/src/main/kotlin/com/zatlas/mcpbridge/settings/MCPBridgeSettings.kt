package com.zatlas.mcpbridge.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(
    name = "MCPBridgeSettings",
    storages = [Storage("mcpbridge.xml")]
)
class MCPBridgeSettings : PersistentStateComponent<MCPBridgeSettings.State> {

    data class State(
        var port: Int = DEFAULT_PORT,
        var autoStart: Boolean = true
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    var port: Int
        get() = myState.port
        set(value) {
            myState.port = value
        }

    var autoStart: Boolean
        get() = myState.autoStart
        set(value) {
            myState.autoStart = value
        }

    companion object {
        const val DEFAULT_PORT = 10082

        fun getInstance(): MCPBridgeSettings {
            return ApplicationManager.getApplication().getService(MCPBridgeSettings::class.java)
        }
    }
}
