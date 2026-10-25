package com.sociax.app.data

import android.content.Context

/** Small on-phone settings (plan section 9: Short Video autoplay, Data Saver, upload quality). */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("sociax_prefs", Context.MODE_PRIVATE)

    var autoplay: Boolean
        get() = sp.getBoolean("autoplay", true)
        set(v) = sp.edit().putBoolean("autoplay", v).apply()

    /** Data Saver: play the smaller 480p copy of videos. */
    var dataSaver: Boolean
        get() = sp.getBoolean("data_saver", false)
        set(v) = sp.edit().putBoolean("data_saver", v).apply()

    /** "low" (480p) | "standard" (720p) | "high" (1080p): quality of clips this phone uploads. */
    var uploadQuality: String
        get() = sp.getString("upload_quality", "standard") ?: "standard"
        set(v) = sp.edit().putString("upload_quality", v).apply()

    /** Appearance (plan section 9): "DARK" | "LIGHT" | "SYSTEM". Saved so it survives closing the app. */
    var themeMode: String
        get() = sp.getString("theme_mode", "DARK") ?: "DARK"
        set(v) = sp.edit().putString("theme_mode", v).apply()

    /** Language code: "en" | "fr" | "zh" | "ko" | "ru". English is the default (plan v3.5). Choosing a language (opening screen or
     *  Settings) saves it here, on the phone, so it works offline and survives closing the app. */
    var language: String
        get() = sp.getString("language", null) ?: "en"
        set(v) = sp.edit().putString("language", v).putBoolean("language_chosen", true).apply()

    /** True once the person picked a language on THIS phone. Only then does the phone's choice win over the one saved on the account. */
    val languageChosen: Boolean get() = sp.getBoolean("language_chosen", false)

    /** Uses the language saved on the account (new phone, nothing chosen here yet) without marking it as chosen here. */
    fun adoptLanguage(code: String) = sp.edit().putString("language", code).apply()

    /** Step 46: push notifications On for THIS phone (opt-in; set Off on sign-out so a signed-out phone shows nothing). */
    var pushEnabled: Boolean
        get() = sp.getBoolean("push_enabled", false)
        set(v) = sp.edit().putBoolean("push_enabled", v).apply()

    /** Accent colour as ARGB (default = Sociax teal). */
    var accent: Long
        get() = sp.getLong("accent", 0xFF2FE0B2)
        set(v) = sp.edit().putLong("accent", v).apply()

    /** "SMALL" | "NORMAL" | "LARGE". */
    var textSize: String
        get() = sp.getString("text_size", "NORMAL") ?: "NORMAL"
        set(v) = sp.edit().putString("text_size", v).apply()

    var reduceMotion: Boolean
        get() = sp.getBoolean("reduce_motion", false)
        set(v) = sp.edit().putBoolean("reduce_motion", v).apply()

    // ---- Daily alarms (plan v3.3 section 1), all kept on the phone ----
    /** Alarms at 3:00 AM / 3:00 PM are On. Off by default; only turned On by the user (and only after the OS permission is granted). */
    var alarmsEnabled: Boolean
        get() = sp.getBoolean("alarms_enabled", false)
        set(v) = sp.edit().putBoolean("alarms_enabled", v).apply()

    /** Index (0..4) of the chosen built-in alarm sound. */
    var alarmSound: Int
        get() = sp.getInt("alarm_sound", 0)
        set(v) = sp.edit().putInt("alarm_sound", v).apply()

    /** The Home prompt was answered (Yes / No) or closed with X: it is not shown again. Alarms can still be turned On in Settings. */
    var alarmPromptDone: Boolean
        get() = sp.getBoolean("alarm_prompt_done", false)
        set(v) = sp.edit().putBoolean("alarm_prompt_done", v).apply()

    /** Reference of a Sociax Pro payment that was started but not yet confirmed by the server (so it survives closing the app). */
    var pendingPayment: String?
        get() = sp.getString("pending_payment", null)
        set(v) = sp.edit().apply { if (v == null) remove("pending_payment") else putString("pending_payment", v) }.apply()
}
