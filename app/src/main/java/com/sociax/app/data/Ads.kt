package com.sociax.app.data

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.telephony.TelephonyManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sociax.app.BuildConfig
import com.startapp.sdk.ads.nativead.NativeAdDetails
import com.startapp.sdk.ads.nativead.NativeAdPreferences
import com.startapp.sdk.ads.nativead.StartAppNativeAd
import com.startapp.sdk.adsbase.Ad
import com.startapp.sdk.adsbase.StartAppSDK
import com.startapp.sdk.adsbase.adlisteners.AdEventListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

/** Step 41 (plan v3.8 section 28, Start.io since v0.82.0). What the SERVER says about ads for this account. The phone never decides this by itself. */
data class AdsStatus(
    val eligible: Boolean,          // Free account AND today's cap not reached
    val pro: Boolean,
    val dailyCap: Int,
    val used: Int,                  // COUNTED impressions today (counted by the server)
    val remaining: Int,
    val resetsAt: Long,
    val everyMin: Int,              // an ad after this many ordinary videos ...
    val everyMax: Int,              // ... up to this many (varied, never back-to-back)
    val minor: Boolean              // under 18: NO ads are requested at all
)

/** One loaded Start.io native ad. [key] is the idempotency key of its impression (a retry counts once on the server). */
class LoadedAd(val ad: NativeAdDetails, val key: String) {
    var impressed by mutableStateOf(false)      // this ad page was really on screen long enough to be counted
}

/** The Activity behind a Compose context. */
fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

// Countries where the law asks for the person's consent before personalised ads (EU / EEA, United Kingdom, Switzerland).
// Sociax has no consent screen for Start.io yet, so in these countries NO ad is requested (safe choice).
private val CONSENT_REGIONS = setOf(
    "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL",
    "PL", "PT", "RO", "SK", "SI", "ES", "SE", "IS", "LI", "NO", "GB", "CH"
)

private fun regionNeedsConsent(ctx: Context): Boolean {
    val codes = ArrayList<String>()
    try {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        tm?.networkCountryIso?.let { if (it.isNotBlank()) codes += it }
        tm?.simCountryIso?.let { if (it.isNotBlank()) codes += it }
    } catch (e: Exception) {
    }
    Locale.getDefault().country.let { if (it.isNotBlank()) codes += it }
    return codes.any { it.uppercase(Locale.ROOT) in CONSENT_REGIONS }
}

/**
 * Keeps ONE preloaded Start.io native ad ready for the Short Video feed. Rules (plan v3.8 section 28):
 *  - ads only when the server said "eligible" (Free, cap not reached). Unknown / error = NO ads. Pro = the Start.io SDK is not even started.
 *  - under 18, or a consent-law country (EU / EEA / UK / CH) = NO ads.
 *  - an impression is COUNTED when the ad page has really been on screen for a moment ([markSeen]); the server then counts it (idempotent key).
 *  - no fill, load error, offline = no ad is shown and the feed simply continues.
 */
class AdsController(private val api: ApiClient, private val appContext: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** null = not known (offline, error, signed out) -> no ads. */
    var status by mutableStateOf<AdsStatus?>(null)
        private set

    /** A loaded ad waiting for its place in the feed. The feed takes it with [take]. */
    var ready by mutableStateOf<LoadedAd?>(null)
        private set

    private var blockedHere by mutableStateOf(false)      // minor or consent-law country: decided on this phone, only ever to show FEWER ads

    val allowed: Boolean get() = status?.eligible == true && !blockedHere

    private var sdkReady = false
    private var starting = false
    private var loading = false
    private var lastFailAt = 0L
    private var lastStatusAt = 0L
    private val inFlightKeys = HashSet<String>()
    private val countedKeys = HashSet<String>()           // keys already reported (an ad page seen twice counts once)

    /** Ask the server who may see ads. Called when the Short Video tab opens and when the app comes back. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun refresh(activity: Activity?) {
        try {
            val s = api.adsStatus()
            status = s
            blockedHere = s.minor || regionNeedsConsent(appContext)
            lastStatusAt = System.currentTimeMillis()
            if (!allowed) {                         // Pro, cap reached, minor or consent country: nothing is requested, a waiting ad is thrown away
                ready = null
            } else {
                start()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Cannot verify (offline / server error): safe choice = no ads
            status = null
            ready = null
        }
    }

    fun statusIsStale(): Boolean = System.currentTimeMillis() - lastStatusAt > 60_000

    /** Start the Start.io SDK (only for eligible accounts) -> load the first ad. */
    private fun start() {
        if (sdkReady) { ensureLoaded(); return }
        if (starting) return
        starting = true
        // Real ads by default. Test ads only when the build asks for them (SOCIAX_STARTIO_TEST_ADS=1).
        StartAppSDK.setTestAdsEnabled(BuildConfig.STARTIO_TEST_ADS)
        StartAppSDK.initParams(appContext, BuildConfig.STARTIO_APP_ID)
            .setReturnAdsEnabled(false)         // no "return ads": ads only where Sociax places them
            .setCallback {
                scope.launch {
                    sdkReady = true; starting = false
                    ensureLoaded()
                }
            }
            .init()
    }

    /** Make sure one ad is being loaded / ready. Safe to call often. */
    fun ensureLoaded() {
        if (!allowed || !sdkReady || ready != null || loading) return
        if (System.currentTimeMillis() - lastFailAt < 30_000) return      // after a failure wait 30 s (no-fill must not hammer Start.io)
        loading = true
        val key = "ad:" + UUID.randomUUID().toString().replace("-", "")
        val prefs = NativeAdPreferences()
        prefs.setAutoBitmapDownload(true)
        val nativeAd = StartAppNativeAd(appContext)
        nativeAd.setPreferences(prefs)
        nativeAd.loadAd(object : AdEventListener {
            override fun onReceiveAd(ad: Ad) {
                scope.launch {
                    loading = false
                    val details = nativeAd.nativeAds?.firstOrNull()
                    if (details == null || !allowed) { lastFailAt = System.currentTimeMillis(); return@launch }
                    ready = LoadedAd(details, key)
                }
            }

            override fun onFailedToReceiveAd(ad: Ad?) {
                scope.launch { loading = false; lastFailAt = System.currentTimeMillis() }
            }
        })
    }

    /** The feed takes the waiting ad (it is now the feed's; a new one is requested). */
    fun take(): LoadedAd? {
        val a = ready ?: return null
        ready = null
        shown[a.key] = a
        return a
    }

    private val shown = HashMap<String, LoadedAd>()

    /** The ad page was on screen long enough: the server counts ONE impression for this key. */
    fun markSeen(key: String) {
        shown[key]?.impressed = true
        if (key in countedKeys || !inFlightKeys.add(key)) return
        scope.launch {
            // the same key on every retry: the server counts it once
            for (wait in longArrayOf(0, 2_000, 8_000, 30_000)) {
                if (wait > 0) delay(wait)
                try {
                    val s = api.adImpression(key)
                    countedKeys.add(key)
                    status = s
                    if (!allowed) ready = null
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // try again; a lost report is never invented on the phone
                }
            }
            inFlightKeys.remove(key)
        }
    }

    /** Signed out / another account: forget everything. */
    fun reset() {
        ready = null
        shown.clear(); countedKeys.clear(); status = null; blockedHere = false; loading = false; lastFailAt = 0L; lastStatusAt = 0L
    }

    fun close() { scope.cancel() }
}
