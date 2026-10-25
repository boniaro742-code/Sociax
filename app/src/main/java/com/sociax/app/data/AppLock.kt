package com.sociax.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

enum class LockType { PIN, PATTERN }

/** Answer of [AppLock.check]. */
sealed interface LockCheck {
    data object Ok : LockCheck
    /** Wrong PIN / pattern. [triesLeft] = tries before the next short wait starts. */
    data class Wrong(val triesLeft: Int) : LockCheck
    /** Too many wrong tries: nothing is checked until [seconds] have passed. */
    data class Wait(val seconds: Int) : LockCheck
}

/**
 * Plan v3.3 section 2: mandatory four-digit PIN or pattern lock, kept ON THIS PHONE only.
 *  - Only a salted verifier is stored: PBKDF2(secret, random salt), then sealed with an HMAC key that lives in the
 *    Android Keystore (the key cannot be copied off the phone). The PIN / pattern itself is never stored, never logged,
 *    never sent to the server and can never be shown again.
 *  - Wrong tries are throttled: after every 5 wrong tries the app waits 30 s, then 60 s, 120 s ... (max 15 minutes).
 *    The counter survives closing the app.
 *  - The lock is separate from Google sign-in. Forgetting it is handled by a fresh Google verification (see LockScreens.kt).
 * [secret] is the PIN ("1234") or the pattern as dot numbers 0..8 joined by "-" ("0-1-2-5").
 */
class AppLock(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("sociax_lock", Context.MODE_PRIVATE)

    val isSet: Boolean get() = sp.contains("verifier")

    val type: LockType? get() = runCatching { LockType.valueOf(sp.getString("type", "") ?: "") }.getOrNull()

    /** Seconds in the background before the lock asks again. Default 1 minute. */
    var timeoutSec: Int
        get() = sp.getInt("timeout_sec", 60)
        set(v) = sp.edit().putInt("timeout_sec", v).apply()

    /** Saves a NEW lock (replaces the old one). The caller already made the user type it twice. */
    fun set(type: LockType, secret: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val algo = pickAlgorithm()
        val raw = derive(secret, salt, algo)
        val wrapped = seal(raw)
        sp.edit()
            .putString("type", type.name)
            .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("algo", algo)
            .putBoolean("sealed", wrapped != null)
            .putString("verifier", Base64.encodeToString(wrapped ?: raw, Base64.NO_WRAP))
            .putInt("fails", 0).putLong("wait_until", 0L)
            .apply()
    }

    /** Seconds left of a "too many tries" wait (0 = none). */
    fun waitSeconds(): Int {
        val left = sp.getLong("wait_until", 0L) - System.currentTimeMillis()
        return if (left > 0) ((left + 999) / 1000).toInt() else 0
    }

    fun check(secret: String): LockCheck {
        val w = waitSeconds()
        if (w > 0) return LockCheck.Wait(w)
        val salt = Base64.decode(sp.getString("salt", "") ?: "", Base64.NO_WRAP)
        val stored = Base64.decode(sp.getString("verifier", "") ?: "", Base64.NO_WRAP)
        val algo = sp.getString("algo", "PBKDF2WithHmacSHA1") ?: "PBKDF2WithHmacSHA1"
        val raw = derive(secret, salt, algo)
        val candidate = if (sp.getBoolean("sealed", false)) seal(raw) else raw
        // constant-time compare; a lost Keystore key (candidate == null) never matches: use "Forgot PIN / pattern"
        if (candidate != null && MessageDigest.isEqual(candidate, stored)) {
            sp.edit().putInt("fails", 0).putLong("wait_until", 0L).apply()
            return LockCheck.Ok
        }
        val fails = sp.getInt("fails", 0) + 1
        val e = sp.edit().putInt("fails", fails)
        if (fails % TRIES_PER_ROUND == 0) {
            val round = fails / TRIES_PER_ROUND                       // 1, 2, 3 ...
            val seconds = minOf(30L * (1L shl minOf(round - 1, 5)), 900L)
            e.putLong("wait_until", System.currentTimeMillis() + seconds * 1000)
            e.apply()
            return LockCheck.Wait(seconds.toInt())
        }
        e.apply()
        return LockCheck.Wrong(TRIES_PER_ROUND - fails % TRIES_PER_ROUND)
    }

    /** Removes the lock (log out / delete account). */
    fun clear() {
        sp.edit().clear().apply()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS) }
    }

    // ---- private helpers ----
    private fun pickAlgorithm(): String =
        try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256"); "PBKDF2WithHmacSHA256" }
        catch (e: Exception) { "PBKDF2WithHmacSHA1" }        // Android 7.x has no SHA-256 variant

    private fun derive(secret: String, salt: ByteArray, algo: String): ByteArray =
        SecretKeyFactory.getInstance(algo).generateSecret(PBEKeySpec(secret.toCharArray(), salt, ITERATIONS, 256)).encoded

    /** HMAC with a non-exportable Keystore key. null = this phone's Keystore is not usable (the plain salted hash is used). */
    private fun seal(data: ByteArray): ByteArray? = try {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(KEY_ALIAS)) {
            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
            kg.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN).build())
            kg.generateKey()
        }
        val key = ks.getKey(KEY_ALIAS, null) as SecretKey
        Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal(data)
    } catch (e: Exception) { null }

    companion object {
        const val TRIES_PER_ROUND = 5
        private const val ITERATIONS = 120_000
        private const val KEY_ALIAS = "sociax_app_lock_key"
        /** Timeout choices (seconds) shown in Settings > App Lock. */
        val TIMEOUTS = listOf(30, 60, 300, 900)
    }
}
