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

    /** Language code: "en" | "fr" | "zh" | "ko" | "ru". First start follows the phone language if it is one of the five, else English. */
    var language: String
        get() = sp.getString("language", null) ?: java.util.Locale.getDefault().language.takeIf { it in setOf("fr", "zh", "ko", "ru") } ?: "en"
        set(v) = sp.edit().putString("language", v).apply()

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

    /** Reference of an AI Pro payment that was started but not yet confirmed by the server (so it survives closing the app). */
    var pendingPayment: String?
        get() = sp.getString("pending_payment", null)
        set(v) = sp.edit().apply { if (v == null) remove("pending_payment") else putString("pending_payment", v) }.apply()

    /** Same for a Sociax App Pro payment (separate product, separate pending reference). */
    var pendingAppPayment: String?
        get() = sp.getString("pending_app_payment", null)
        set(v) = sp.edit().apply { if (v == null) remove("pending_app_payment") else putString("pending_app_payment", v) }.apply()
}
