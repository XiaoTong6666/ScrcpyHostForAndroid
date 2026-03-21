package io.github.xiaotong6666.scrcpy

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class DeviceProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val adbPort: Int,
    val backendUrl: String = "local://bridge",
    val audioEnabled: Boolean = true,
    val sessionConfig: ScrcpySessionConfig = ScrcpySessionConfig(),
)

data class FloatingWindowState(
    val windowX: Int,
    val windowY: Int,
    val windowWidth: Int,
    val contentHeight: Int,
    val miniMode: Boolean,
)

object DeviceProfileStore {
    private const val PREFS_NAME = "scrcpy_device_profiles"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_FLOATING_WINDOW_STATES = "floating_window_states"

    fun load(context: Context): List<DeviceProfile> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PROFILES, "[]").orEmpty()
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                parseProfile(item)?.let(::add)
            }
        }
    }

    fun upsert(context: Context, profile: DeviceProfile) {
        val current = load(context).toMutableList()
        val existingIndex = current.indexOfFirst { it.id == profile.id }
        if (existingIndex >= 0) {
            current[existingIndex] = profile
        } else {
            current.add(0, profile)
        }
        persist(context, current)
    }

    fun delete(context: Context, profileId: String) {
        val next = load(context).filterNot { it.id == profileId }
        persist(context, next)
    }

    fun toJsonString(profile: DeviceProfile): String = serializeProfile(profile).toString()

    fun fromJsonString(raw: String?): DeviceProfile? {
        val json = raw?.takeIf { it.isNotBlank() }?.let { text ->
            runCatching { JSONObject(text) }.getOrNull()
        } ?: return null
        return parseProfile(json)
    }

    fun loadFloatingWindowState(
        context: Context,
        host: String,
        port: Int,
        backendUrl: String,
    ): FloatingWindowState? {
        if (host.isBlank() || port !in 1..65535) {
            return null
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_FLOATING_WINDOW_STATES, "{}").orEmpty()
        val root = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        val item = root.optJSONObject(floatingWindowStateKey(host, port, backendUrl)) ?: return null
        return FloatingWindowState(
            windowX = item.optInt("windowX", 0),
            windowY = item.optInt("windowY", 0),
            windowWidth = item.optInt("windowWidth", 0).coerceAtLeast(0),
            contentHeight = item.optInt("contentHeight", 0).coerceAtLeast(0),
            miniMode = item.optBoolean("miniMode", false),
        )
    }

    fun saveFloatingWindowState(
        context: Context,
        host: String,
        port: Int,
        backendUrl: String,
        state: FloatingWindowState,
    ) {
        if (host.isBlank() || port !in 1..65535) {
            return
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_FLOATING_WINDOW_STATES, "{}").orEmpty()
        val root = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        root.put(
            floatingWindowStateKey(host, port, backendUrl),
            JSONObject()
                .put("windowX", state.windowX)
                .put("windowY", state.windowY)
                .put("windowWidth", state.windowWidth)
                .put("contentHeight", state.contentHeight)
                .put("miniMode", state.miniMode),
        )
        prefs.edit().putString(KEY_FLOATING_WINDOW_STATES, root.toString()).apply()
    }

    private fun persist(context: Context, profiles: List<DeviceProfile>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(serializeProfile(profile))
        }
        prefs.edit().putString(KEY_PROFILES, array.toString()).apply()
    }

    private fun serializeProfile(profile: DeviceProfile): JSONObject = JSONObject()
        .put("id", profile.id)
        .put("name", profile.name)
        .put("host", profile.host)
        .put("adbPort", profile.adbPort)
        .put("backendUrl", profile.backendUrl)
        .put("audioEnabled", profile.audioEnabled)
        .put("sessionConfig", profile.sessionConfig.toJsonObject())

    private fun parseProfile(item: JSONObject): DeviceProfile? {
        val host = item.optString("host").trim()
        val port = item.optInt("adbPort", 5555)
        if (host.isBlank() || port !in 1..65535) {
            return null
        }
        return DeviceProfile(
            id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = item.optString("name").ifBlank { "$host:$port" },
            host = host,
            adbPort = port,
            backendUrl = item.optString("backendUrl").ifBlank { "local://bridge" },
            audioEnabled = item.optBoolean("audioEnabled", true),
            sessionConfig = ScrcpySessionConfig.fromJsonObject(item.optJSONObject("sessionConfig")),
        )
    }

    private fun floatingWindowStateKey(
        host: String,
        port: Int,
        backendUrl: String,
    ): String = "${host.trim().lowercase()}:$port@${backendUrl.trim()}"
}
