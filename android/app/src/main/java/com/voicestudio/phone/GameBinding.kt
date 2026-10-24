package com.voicestudio.phone

import android.content.SharedPreferences
import org.json.JSONObject

/**
 * 每个游戏记住自己用哪套「音色 + 方案」。开始变声前在「状态」页选好，之后一直沿用，
 * 直到再次修改。
 *
 * 存储：`game_binding:<包名>` = {"model":"Firefly","profile":"低沉"}
 * 0.5.3 的 `game_voice:<包名>` 只有音色名，迁移时补上默认方案。
 */
object GameBinding {
    private const val PREFIX = "game_binding:"
    private const val LEGACY = "game_voice:"

    data class Bound(val model: String, val profile: String)

    fun get(prefs: SharedPreferences, packageName: String): Bound? {
        val raw = prefs.getString(PREFIX + packageName, null) ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val model = json.optString("model", "")
        if (model.isBlank()) return null
        return Bound(model, json.optString("profile", VoiceProfiles.DEFAULT).ifBlank { VoiceProfiles.DEFAULT })
    }

    fun bind(prefs: SharedPreferences, packageName: String, model: String, profile: String) {
        val json = JSONObject().put("model", model).put("profile", profile)
        prefs.edit().putString(PREFIX + packageName, json.toString()).apply()
    }

    fun clear(prefs: SharedPreferences, packageName: String) {
        prefs.edit().remove(PREFIX + packageName).remove(LEGACY + packageName).apply()
    }

    /** 已添加的游戏（有绑定的包名），供「配置」页列出。 */
    fun games(prefs: SharedPreferences): List<String> =
        prefs.all.keys
            .filter { it.startsWith(PREFIX) && it.length > PREFIX.length }
            .map { it.removePrefix(PREFIX) }
            .sorted()

    /** 把 0.5.3 的 game_voice:<包名> 迁到新绑定（补默认方案），只补缺失的。 */
    fun migrateLegacy(prefs: SharedPreferences): Int {
        var migrated = 0
        for ((key, value) in prefs.all) {
            if (!key.startsWith(LEGACY)) continue
            val pkg = key.removePrefix(LEGACY)
            val model = value as? String ?: continue
            if (model.isBlank() || get(prefs, pkg) != null) continue
            bind(prefs, pkg, model, VoiceProfiles.DEFAULT)
            migrated++
        }
        return migrated
    }
}
