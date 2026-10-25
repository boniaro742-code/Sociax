package com.sociax.app.ui.screens

import android.graphics.Color as AColor
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAdView
import com.sociax.app.data.LoadedAd
import com.sociax.app.ui.theme.CinematicBackground
import com.sociax.app.ui.theme.bottomBarClearance
import com.sociax.app.ui.theme.tr

/**
 * Step 41 (plan v3.8 section 28.2): one full-screen AdMob NATIVE ad page between ordinary Short Video items.
 * Rules kept here: the ad is ALWAYS labelled ("Ad", in the chosen language) so it can never pass as a user's video;
 * it has no Sociax like / comment / follow buttons; the person leaves it by swiping, like any other page;
 * nothing forces a click. The official SDK views (NativeAdView + MediaView) are used so impressions and clicks are valid.
 */
@Composable
fun AdPage(loaded: LoadedAd) {
    val label = tr("ad_label")
    Box(Modifier.fillMaxSize().background(CinematicBackground)) {
        AndroidView(
            modifier = Modifier.fillMaxSize().statusBarsPadding().padding(top = 56.dp, bottom = bottomBarClearance()),
            factory = { ctx -> buildAdView(ctx) },
            update = { view -> bindAd(view, loaded, label) }
        )
    }
}

private class AdViews(
    val root: NativeAdView, val badge: TextView, val advertiser: TextView, val media: MediaView,
    val icon: ImageView, val headline: TextView, val body: TextView, val cta: TextView
) { var boundKey: String? = null }

private fun buildAdView(ctx: android.content.Context): View {
    val d = ctx.resources.displayMetrics.density
    fun px(v: Int) = (v * d).toInt()
    fun pill(color: Int, radiusDp: Int) = GradientDrawable().apply { setColor(color); cornerRadius = radiusDp * d }

    val root = NativeAdView(ctx)
    val column = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(16), px(8), px(16), px(12))
    }

    // top row: the required "Ad" label + advertiser name
    val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    val badge = TextView(ctx).apply {
        setTextColor(AColor.WHITE); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
        setPadding(px(8), px(2), px(8), px(2)); background = pill(AColor.parseColor("#E0B14A"), 6)
        setTextColor(AColor.parseColor("#0B1220"))
    }
    val advertiser = TextView(ctx).apply {
        setTextColor(AColor.parseColor("#B8C4D9")); textSize = 13f; maxLines = 1
        setPadding(px(10), 0, 0, 0)
    }
    top.addView(badge); top.addView(advertiser)
    column.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    // the media (picture or video) takes the free height
    val media = MediaView(ctx).apply { setImageScaleType(ImageView.ScaleType.FIT_CENTER) }
    column.addView(media, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
        topMargin = px(10); bottomMargin = px(10)
    })

    // icon + headline + body
    val textRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    val icon = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; background = pill(AColor.parseColor("#121C2E"), 10) }
    textRow.addView(icon, LinearLayout.LayoutParams(px(44), px(44)).apply { marginEnd = px(10) })
    val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    val headline = TextView(ctx).apply { setTextColor(AColor.WHITE); textSize = 17f; typeface = Typeface.DEFAULT_BOLD; maxLines = 2 }
    val body = TextView(ctx).apply { setTextColor(AColor.parseColor("#D5DDEB")); textSize = 13f; maxLines = 3 }
    texts.addView(headline); texts.addView(body)
    textRow.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    column.addView(textRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    // call to action (comes from the ad; the person may ignore it, nothing forces a click)
    val cta = TextView(ctx).apply {
        setTextColor(AColor.parseColor("#0B1220")); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER; background = pill(AColor.parseColor("#2FE0B2"), 24)
        setPadding(px(16), px(12), px(16), px(12))
    }
    column.addView(cta, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(12) })

    root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    root.tag = AdViews(root, badge, advertiser, media, icon, headline, body, cta)
    return root
}

private fun bindAd(view: View, loaded: LoadedAd, label: String) {
    val v = view.tag as? AdViews ?: return
    val ad = loaded.ad
    if (v.boundKey == loaded.key) { v.badge.text = label; return }       // already bound: only the label (language) may change
    v.boundKey = loaded.key
    v.badge.text = label                                         // always visible, never removable by the ad
    v.headline.text = ad.headline ?: ""
    v.body.text = ad.body ?: ""; v.body.visibility = if (ad.body.isNullOrBlank()) View.GONE else View.VISIBLE
    v.advertiser.text = ad.advertiser ?: ""; v.advertiser.visibility = if (ad.advertiser.isNullOrBlank()) View.GONE else View.VISIBLE
    v.cta.text = ad.callToAction ?: ""; v.cta.visibility = if (ad.callToAction.isNullOrBlank()) View.GONE else View.VISIBLE
    val icon = ad.icon?.drawable
    if (icon != null) { v.icon.setImageDrawable(icon); v.icon.visibility = View.VISIBLE } else v.icon.visibility = View.GONE

    v.root.headlineView = v.headline
    v.root.bodyView = v.body
    v.root.advertiserView = v.advertiser
    v.root.callToActionView = v.cta
    v.root.iconView = v.icon
    v.root.mediaView = v.media
    v.root.setNativeAd(ad)                                       // registers the views: this is what makes impressions and clicks valid
}
