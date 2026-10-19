package com.voicestudio.phone

import android.content.SharedPreferences

/** Remembers which voice model belongs to each destination package. */
object GameVoiceMap {
    private const val PREFIX = "game_voice:"

    fun model(prefs: SharedPreferences, packageName: String): String? =
        prefs.getString(PREFIX + packageName, null)

    fun bind(prefs: SharedPreferences, packageName: String, model: String) {
        prefs.edit().putString(PREFIX + packageName, model).apply()
    }

    fun clear(prefs: SharedPreferences, packageName: String) {
        prefs.edit().remove(PREFIX + packageName).apply()
    }
}
