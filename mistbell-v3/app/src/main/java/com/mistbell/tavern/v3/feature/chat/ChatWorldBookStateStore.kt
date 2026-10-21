package com.mistbell.tavern.v3.feature.chat

import android.content.SharedPreferences
import com.mistbell.tavern.v3.core.diagnostics.DiagnosticLogStore
import com.mistbell.tavern.v3.feature.worldbook.WorldBookActivationState

/**
 * Owns restoration of the existing persisted worldbook activation counters.
 */
internal class ChatWorldBookStateStore(
    private val settings: SharedPreferences,
    private val diagnosticStore: DiagnosticLogStore,
) {
    fun restore(stateKey: String): WorldBookActivationState =
        WorldBookActivationState().also { state ->
            runCatching {
                val saved = settings.getString(stateKey, null) ?: return@runCatching
                val json = org.json.JSONObject(saved)
                state.turn = json.optInt("turn", 0)
                fun restoreMap(name: String, target: MutableMap<String, Int>) {
                    val values = json.optJSONObject(name) ?: return
                    val keys = values.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (values.has(key) && !values.isNull(key)) target[key] = values.optInt(key)
                    }
                }
                restoreMap("lastActivatedTurn", state.lastActivatedTurn)
                restoreMap("stickyUntilTurn", state.stickyUntilTurn)
                restoreMap("delayedUntilTurn", state.delayedUntilTurn)
            }.onFailure { error ->
                diagnosticStore.warn(
                    "worldbook.state_restore_failed",
                    "reason=${error::class.java.simpleName}",
                )
            }
        }
}
