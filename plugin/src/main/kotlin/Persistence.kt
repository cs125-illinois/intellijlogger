package edu.illinois.cs.cs125.intellijlogger

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.time.Instant

@Suppress("unused")
@State(name = "Component", storages = [(Storage(file = "edu.illinois.cs.cs125.intellijlogger.2020.1.5.191.xml"))])
class Persistence : PersistentStateComponent<Persistence.State> {
    data class State(
        var activeCounters: MutableList<Counter> = mutableListOf(),
        var savedCounters: MutableList<Counter> = mutableListOf(),
        var counterIndex: Long = 0L,
        @Suppress("ConstructorParameterNaming")
        var UUID: String = "",
        var lastSave: Long = -1,
        val pluginVersion: String = version
    )

    var persistentState = State()
    override fun getState(): State {
        log.trace("Saving state")
        persistentState.lastSave = Instant.now().toEpochMilli()
        return persistentState
    }

    override fun loadState(state: State) {
        persistentState = state
    }

    companion object {
        fun getInstance(): Persistence {
            return ServiceManager.getService(Persistence::class.java)
        }
    }
}
