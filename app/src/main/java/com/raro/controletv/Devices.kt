package com.raro.controletv

import android.content.SharedPreferences

/** Um aparelho salvo (Box, Projetor ou LG) com nome dado pelo usuário. */
data class SavedDevice(
    val id: String,
    val name: String,
    val type: String,   // "box" | "lg"
    val mode: String,   // box: "receiver" | "adb" ; lg: "lg"
    val ip: String,
    val mac: String = ""
)

/** Guarda a lista de aparelhos e qual está ativo, no SharedPreferences. */
object DeviceStore {
    private const val KEY = "devices_v1"
    private const val ACTIVE = "active_device_id"

    fun load(prefs: SharedPreferences): List<SavedDevice> =
        (prefs.getString(KEY, "") ?: "").split("\n")
            .filter { it.contains("\t") }
            .mapNotNull {
                val p = it.split("\t")
                if (p.size >= 5) SavedDevice(p[0], p[1], p[2], p[3], p[4], p.getOrElse(5) { "" }) else null
            }

    fun save(prefs: SharedPreferences, list: List<SavedDevice>) {
        prefs.edit().putString(KEY, list.joinToString("\n") {
            "${it.id}\t${it.name}\t${it.type}\t${it.mode}\t${it.ip}\t${it.mac}"
        }).apply()
    }

    fun activeId(prefs: SharedPreferences): String? = prefs.getString(ACTIVE, null)
    fun setActive(prefs: SharedPreferences, id: String) { prefs.edit().putString(ACTIVE, id).apply() }

    fun newId(): String = System.currentTimeMillis().toString(36)
}
