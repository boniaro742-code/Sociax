package com.sociax.app.data

import android.content.Context
import com.sociax.app.ui.theme.OldSociaxPurple
import com.sociax.app.ui.theme.SociaxPurple

/** Step 54 (plan v3.9.2 section 3): an account that signed in on this phone, shown on the login screen. Only a name and an @username
 *  (no e-mail, no token, no password). It is NOT a sign-in: tapping it opens the normal Google account chooser. */
data class RememberedAccount(val uid: String, val displayName: String, val username: String)

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

    /** Appearance (plan section 9): "DARK" | "LIGHT" | "SYSTEM". Saved so it survives closing the app.
     *  Step 59: new design = light by default. A new key is used so a choice made in the old (blue) design does not carry over. */
    var themeMode: String
        get() = sp.getString("theme_mode_v2", "LIGHT") ?: "LIGHT"
        set(v) = sp.edit().putString("theme_mode_v2", v).apply()

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

    /** Accent colour as ARGB (default = Sociax purple of the new design; a new key, so the old teal choice does not carry over). */
    var accent: Long
        get() = sp.getLong("accent_v2", SociaxPurple).let { if (it == OldSociaxPurple) SociaxPurple else it }
        set(v) = sp.edit().putLong("accent_v2", v).apply()

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

    // ---- Step 54: accounts remembered on this phone (login screen). At most 5, newest first. ----
    var rememberedAccounts: List<RememberedAccount>
        get() = runCatching {
            val arr = org.json.JSONArray(sp.getString("remembered_accounts", "[]"))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RememberedAccount(o.getString("uid"), o.optString("name"), o.optString("username"))
            }
        }.getOrDefault(emptyList())
        private set(v) {
            val arr = org.json.JSONArray()
            v.take(5).forEach { arr.put(org.json.JSONObject().put("uid", it.uid).put("name", it.displayName).put("username", it.username)) }
            sp.edit().putString("remembered_accounts", arr.toString()).apply()
        }

    fun rememberAccount(uid: String, displayName: String, username: String) {
        rememberedAccounts = listOf(RememberedAccount(uid, displayName, username)) + rememberedAccounts.filter { it.uid != uid }
    }

    // ---- Step 57 (plan v3.10.0): unfinished first-time onboarding of ONE account, kept on the phone only until it is saved or the person logs out ----
    fun saveOnboardingDraft(uid: String, name: String, username: String, age: String) {
        if (name.isEmpty() && username.isEmpty() && age.isEmpty()) { clearOnboardingDraft(); return }
        sp.edit().putString("onboarding_draft", org.json.JSONObject().put("uid", uid).put("name", name).put("username", username).put("age", age).toString()).apply()
    }

    /** (name, username, age) typed before, only if it belongs to THIS account; otherwise null. */
    fun onboardingDraft(uid: String): Triple<String, String, String>? = runCatching {
        val o = org.json.JSONObject(sp.getString("onboarding_draft", null) ?: return null)
        if (o.getString("uid") != uid) null else Triple(o.optString("name"), o.optString("username"), o.optString("age"))
    }.getOrNull()

    fun clearOnboardingDraft() = sp.edit().remove("onboarding_draft").apply()

    fun forgetAccount(uid: String) { rememberedAccounts = rememberedAccounts.filter { it.uid != uid }; forgetTour(uid) }

    /** Step 85 (plan v5.0.0 section 7): the on-phone copy of "this account finished / skipped the first-time app tour" (the account itself keeps the main copy). */
    fun tourDone(uid: String?): Boolean = uid != null && (sp.getStringSet("tour_done_uids", emptySet())?.contains(uid) == true)
    fun markTourDone(uid: String?) {
        if (uid == null) return
        sp.edit().putStringSet("tour_done_uids", (sp.getStringSet("tour_done_uids", emptySet()) ?: emptySet()) + uid).apply()
    }
    private fun forgetTour(uid: String) {
        sp.edit().putStringSet("tour_done_uids", (sp.getStringSet("tour_done_uids", emptySet()) ?: emptySet()) - uid).apply()
    }
}
