package com.sayit.translator

import android.content.Context

class AppSettings(context: Context) {
    private val preferences = context.getSharedPreferences("say_it_settings", Context.MODE_PRIVATE)

    init {
        preferences.edit()
            .remove(KEY_GEMINI_MODEL)
            .remove(KEY_TRANSLATION_ENGINE)
            .remove(KEY_TTS_ENGINE)
            .remove(KEY_LAYOUT_MODE)
            .remove(KEY_STT)
            .remove(KEY_SILENCE_AUTO_STOP_SECONDS)
            .remove(KEY_SERBIAN_SCRIPT)
            .apply()
    }

    var languageA: AppLanguage
        get() = enumValue(preferences.getString(KEY_LANGUAGE_A, null), AppLanguage.RUSSIAN)
        set(value) = preferences.edit().putString(KEY_LANGUAGE_A, value.name).apply()

    var languageB: AppLanguage
        get() = enumValue(preferences.getString(KEY_LANGUAGE_B, null), AppLanguage.ENGLISH)
        set(value) = preferences.edit().putString(KEY_LANGUAGE_B, value.name).apply()

    private inline fun <reified T : Enum<T>> enumValue(raw: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == raw } ?: fallback

    private companion object {
        const val KEY_GEMINI_MODEL = "gemini_translation_model"
        const val KEY_TRANSLATION_ENGINE = "translation_engine"
        const val KEY_TTS_ENGINE = "tts_engine"
        const val KEY_STT = "stt_engine"
        const val KEY_LAYOUT_MODE = "layout_mode"
        const val KEY_SERBIAN_SCRIPT = "serbian_script"
        const val KEY_LANGUAGE_A = "language_a"
        const val KEY_LANGUAGE_B = "language_b"
        const val KEY_SILENCE_AUTO_STOP_SECONDS = "silence_auto_stop_seconds"
    }
}
