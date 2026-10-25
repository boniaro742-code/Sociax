package com.sociax.app.data

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.VideoOptions
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.sociax.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Step 41 (plan v3.8 section 28). What the SERVER says about ads for this account. The phone never decides this by itself. */
data class AdsStatus(
    val eligible: Boolean,          // Free account AND today's cap not reached
    val pro: Boolean,
    val dailyCap: Int,
    val used: Int,                  // COUNTED impressions today (counted by the server)
    val remaining: Int,
    val resetsAt: Long,
    val everyMin: Int,              // an ad after this many ordinary videos ...
    val everyMax: Int,              // ... up to this many (varied, never back-to-back)
    val minor: Boolean              // under 18: AdMob is told to treat the request as child-safe / non-personalised
)

/** One loaded AdMob native ad. [key] is the idempotency key of its impression (a retry counts once on the server). */
class LoadedAd(val ad: NativeAd, val key: String) {
    var impressed by mutableStateOf(false)      // the official SDK impression callback fired for this ad
}

/** The Activity behind a Compose context (needed by the consent form). */
fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * Keeps ONE preloaded native ad ready for the Short Video feed. Rules (plan v3.8 section 28):
 *  - ads only when the server said "eligible" (Free, cap not reached). Unknown / error = NO ads. Pro = the AdMob SDK is not even started.
 *  - an impression is COUNTED only when the official SDK callback onAdImpression fires; the server then counts it (idempotent key).
 *  - no fill, load error, offline, consent refused = no ad is shown and the feed simply continues.
 */
class AdsController(private val api: ApiClient, private val appContext: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** null = not known (offline, error, signed out) -> no ads. */
    var status by mutableStateOf<AdsStatus?>(null)
        private set

    /** A loaded ad waiting for its place in the feed. The feed takes it with [take]. */
    var ready by mutableStateOf<LoadedAd?>(null)
        private set

    val allowed: Boolean get() = status?.eligible == true

    private var consentDone = false
    private var sdkReady = false
    private var starting = false
    private var loading = false
    private var lastFailAt = 0L
    private var lastStatusAt = 0L
    private val inFlightKeys = HashSet<String>()

    /** Ask the server who may see ads. Called when the Short Video tab opens and when the app comes back. */
    suspend fun refresh(activity: Activity?) {
        try {
            val s = api.adsStatus()
            status = s
            lastStatusAt = System.currentTimeMillis()
            if (!s.eligible) {                      // Pro or cap reached: nothing is requested, a waiting ad is thrown away
                ready?.ad?.destroy(); ready = null
            } else {
                start(activity)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Cannot verify (offline / server error): safe choice = no ads
            status = null
            ready?.ad?.destroy(); ready = null
        }
    }

    fun statusIsStale(): Boolean = System.currentTimeMillis() - lastStatusAt > 60_000

    /** Consent (EEA / UK form when Google requires it) -> start the SDK -> load the first ad. Only for eligible accounts. */
    private fun start(activity: Activity?) {
        if (sdkReady) { ensureLoaded(); return }
        if (starting || activity == null) return
        starting = true
        val minor = status?.minor == true
        val info = UserMessagingPlatform.getConsentInformation(activity)
        val params = ConsentRequestParameters.Builder().setTagForUnderAgeOfConsent(minor).build()
        fun afterConsent() {
            consentDone = info.canRequestAds()
            if (!consentDone) { starting = false; return }          // the person did not allow ads: none are requested
            scope.launch {
                withContext(Dispatchers.IO) {
                    MobileAds.setRequestConfiguration(
                        MobileAds.getRequestConfiguration().toBuilder()
                            .setTagForUnderAgeOfConsent(
                                if (minor) RequestConfiguration.TAG_FOR_UNDER_AGE_OF_CONSENT_TRUE
                                else RequestConfiguration.TAG_FOR_UNDER_AGE_OF_CONSENT_FALSE)
                            .setMaxAdContentRating(
                                if (minor) RequestConfiguration.MAX_AD_CONTENT_RATING_PG
                                else RequestConfiguration.MAX_AD_CONTENT_RATING_MA)
                            .build())
                    MobileAds.initialize(appContext) { }
                }
                sdkReady = true; starting = false
                ensureLoaded()
            }
        }
        info.requestConsentInfoUpdate(activity, params,
            { UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { _ -> afterConsent() } },
            { _ -> afterConsent() })
    }

    /** Make sure one ad is being loaded / ready. Safe to call often. */
    fun ensureLoaded() {
        if (!allowed || !sdkReady || ready != null || loading) return
        if (System.currentTimeMillis() - lastFailAt < 30_000) return      // after a failure wait 30 s (no-fill must not hammer AdMob)
        loading = true
        val key = "ad:" + UUID.randomUUID().toString().replace("-", "")
        val loader = AdLoader.Builder(appContext, BuildConfig.ADMOB_NATIVE_UNIT_ID)
            .forNativeAd { native ->
                loading = false
                if (!allowed) { native.destroy() } else { ready = LoadedAd(native, key) }
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) { loading = false; lastFailAt = System.currentTimeMillis() }
                // OFFICIAL impression callback: only now does this ad count (the server counts it, once per key)
                override fun onAdImpression() { reportImpression(key) }
            })
            .withNativeAdOptions(
                NativeAdOptions.Builder()
                    .setMediaAspectRatio(NativeAdOptions.NATIVE_MEDIA_ASPECT_RATIO_PORTRAIT)
                    .setVideoOptions(VideoOptions.Builder().setStartMuted(true).build())
                    .build())
            .build()
        loader.loadAd(AdRequest.Builder().build())
    }

    /** The feed takes the waiting ad (it is now the feed's; a new one is requested). */
    fun take(): LoadedAd? {
        val a = ready ?: return null
        ready = null
        shown[a.key] = a
        return a
    }

    private val shown = HashMap<String, LoadedAd>()

    private fun reportImpression(key: String) {
        shown[key]?.impressed = true
        if (!inFlightKeys.add(key)) return
        scope.launch {
            // the same key on every retry: the server counts it once
            for (wait in longArrayOf(0, 2_000, 8_000, 30_000)) {
                if (wait > 0) delay(wait)
                try {
                    val s = api.adImpression(key)
                    status = s
                    if (!s.eligible) { ready?.ad?.destroy(); ready = null }
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
        ready?.ad?.destroy(); ready = null
        shown.clear(); status = null; loading = false; lastFailAt = 0L; lastStatusAt = 0L
    }

    fun close() { scope.cancel() }
}
