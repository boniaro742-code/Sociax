package com.sociax.app.data;

/**
 * Step 76 (plan v3.10.4 sections 1-2, Stage A #38): the PURE maths of the photo editor. No Android class is used here on purpose:
 * this file is compiled and tested on a normal computer (tools/PhotoMathTest.java, run by CI), so the filter list, the filter
 * strength, the Adjust sliders, the crop window and the EXIF turn are proved by running the REAL code, not a copy of it.
 *
 * A colour matrix is 20 floats (4 rows x 5 columns, row by row) in the same layout as android.graphics.ColorMatrix:
 *   R' = a*R + b*G + c*B + d*A + e   (the same for G', B', A'), channel values 0..255, the 5th column is an offset in 0..255.
 */
public final class PhotoMath {
    private PhotoMath() { }

    // ------------------------------------------------------------------------------------------------------------
    // Filter presets (v3.10.4 section 2: at least 20 distinct, working filters; Original restores the source).
    // Columns: saturation, contrast, brightness offset, R offset, G offset, B offset, gain R, gain G, gain B, fade (0-1), sepia (0-1)
    // ------------------------------------------------------------------------------------------------------------
    public static final String[] FILTER_IDS = {
        "original", "warm", "cool", "vivid", "soft", "bright", "contrast", "mono", "noir", "sepia", "vintage",
        "fade", "matte", "golden", "portrait", "clean", "dramatic", "film", "pastel", "night", "sunset", "forest"
    };

    private static final float[][] FILTER_SPECS = {
        /* original */ {1.00f, 1.00f,   0f,   0f,  0f,   0f, 1.00f, 1.00f, 1.00f, 0.00f, 0.00f},
        /* warm     */ {1.08f, 1.04f,   0f,  18f,  4f, -16f, 1.00f, 1.00f, 1.00f, 0.00f, 0.00f},
        /* cool     */ {1.02f, 1.03f,   0f, -14f,  2f,  18f, 1.00f, 1.00f, 1.00f, 0.00f, 0.00f},
        /* vivid    */ {1.55f, 1.12f,   4f,   0f,  0f,   0f, 1.00f, 1.00f, 1.00f, 0.00f, 0.00f},
        /* soft     */ {0.92f, 0.82f,  14f,   0f,  0f,   0f, 1.00f, 1.00f, 1.00f, 0.06f, 0.00f},
        /* bright   */ {1.06f, 1.02f,  30f,   0f,  0f,   0f, 1.00f, 1.00f, 1.00f, 0.00f, 0.00f},
        /* contrast */ {1.05f, 1.38f,  -6f,   0f,  0f,   0f, 1.00f, 1.00f, 1.00f, 0.00f, 0.00f},
        /* mono     */ {0.00f, 1.08f,   0f,   0f,  0f,   0f, 1.00f, 1.00f, 1.00f, 0.00f, 0.00f},
        /* noir     */ {0.00f, 1.55f, -14f,   0f,  0f,   0f, 1.00f, 1.00f, 1.00f, 0.00f, 0.00f},
        /* sepia    */ {1.00f, 1.05f,   0f,   0f,  0f,   0f, 1.00f, 1.00f, 1.00f, 0.00f, 1.00f},
        /* vintage  */ {0.78f, 0.94f,   0f,  16f,  6f, -14f, 1.00f, 1.00f, 1.00f, 0.12f, 0.25f},
        /* fade     */ {0.82f, 0.90f,   0f,   0f,  0f,   0f, 1.00f, 1.00f, 1.00f, 0.18f, 0.00f},
        /* matte    */ {0.90f, 0.92f,  -4f,   0f,  0f,   6f, 1.00f, 1.00f, 1.00f, 0.26f, 0.00f},
        /* golden   */ {1.18f, 1.06f,   8f,  26f, 12f, -22f, 1.00f, 1.00f, 1.00f, 0.00f, 0.00f},
        /* portrait */ {0.96f, 0.94f,  10f,  10f,  2f,   2f, 1.00f, 1.00f, 1.00f, 0.00f, 0.00f},
        /* clean    */ {1.04f, 1.10f,  12f,   0f,  0f,   0f, 1.00f, 1.00f, 1.03f, 0.00f, 0.00f},
        /* dramatic */ {1.25f, 1.50f, -18f,   0f,  0f,   0f, 1.02f, 0.98f, 1.00f, 0.00f, 0.00f},
        /* film     */ {0.88f, 1.08f,   0f,   0f,  4f,   0f, 1.05f, 1.00f, 0.94f, 0.10f, 0.00f},
        /* pastel   */ {0.70f, 0.78f,  22f,   6f,  0f,  10f, 1.00f, 1.00f, 1.00f, 0.08f, 0.00f},
        /* night    */ {0.90f, 1.20f, -26f,   0f,  0f,  14f, 0.88f, 0.95f, 1.18f, 0.00f, 0.00f},
        /* sunset   */ {1.30f, 1.10f,   0f,  30f, -6f, -10f, 1.08f, 0.96f, 0.92f, 0.00f, 0.00f},
        /* forest   */ {1.15f, 1.08f,  -4f, -10f, 16f,  -6f, 0.96f, 1.08f, 0.96f, 0.00f, 0.00f},
    };

    /** Classic sepia matrix (declared BEFORE the static block below, which uses it). */
    private static final float[] SEPIA = {
        0.393f, 0.769f, 0.189f, 0f, 0f,
        0.349f, 0.686f, 0.168f, 0f, 0f,
        0.272f, 0.534f, 0.131f, 0f, 0f,
        0f,     0f,     0f,     1f, 0f };

    private static final float[][] FILTER_MATRICES = new float[FILTER_SPECS.length][];

    static {
        for (int i = 0; i < FILTER_SPECS.length; i++) FILTER_MATRICES[i] = build(FILTER_SPECS[i]);
    }

    private static float[] build(float[] s) {
        float[] m = identity();
        m = then(m, saturation(s[0]));
        m = then(m, gain(s[6], s[7], s[8]));
        m = then(m, contrast(s[1]));
        if (s[10] > 0f) m = then(m, blend(SEPIA, s[10]));
        if (s[9] > 0f) m = then(m, fade(s[9]));
        m = then(m, offset(s[2] + s[3], s[2] + s[4], s[2] + s[5]));
        return m;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Matrix building blocks
    // ------------------------------------------------------------------------------------------------------------
    public static float[] identity() {
        return new float[] { 1, 0, 0, 0, 0,  0, 1, 0, 0, 0,  0, 0, 1, 0, 0,  0, 0, 0, 1, 0 };
    }

    /** The matrix that does [first] and then [second]. */
    public static float[] then(float[] first, float[] second) {
        float[] r = new float[20];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 5; j++) {
                float sum = 0f;
                for (int k = 0; k < 4; k++) sum += second[i * 5 + k] * first[k * 5 + j];
                if (j == 4) sum += second[i * 5 + 4];
                r[i * 5 + j] = sum;
            }
        }
        return r;
    }

    /** Filter strength: 0 = no change (identity), 1 = the full filter. Values outside 0..1 are clamped. */
    public static float[] blend(float[] m, float t) {
        float k = clamp(t, 0f, 1f);
        float[] id = identity();
        float[] r = new float[20];
        for (int i = 0; i < 20; i++) r[i] = id[i] + (m[i] - id[i]) * k;
        return r;
    }

    public static float[] saturation(float s) {
        float lr = 0.213f, lg = 0.715f, lb = 0.072f, inv = 1f - s;
        return new float[] {
            lr * inv + s, lg * inv,     lb * inv,     0, 0,
            lr * inv,     lg * inv + s, lb * inv,     0, 0,
            lr * inv,     lg * inv,     lb * inv + s, 0, 0,
            0,            0,            0,            1, 0 };
    }

    public static float[] contrast(float c) {
        float o = 128f * (1f - c);
        return new float[] { c, 0, 0, 0, o,  0, c, 0, 0, o,  0, 0, c, 0, o,  0, 0, 0, 1, 0 };
    }

    public static float[] gain(float r, float g, float b) {
        return new float[] { r, 0, 0, 0, 0,  0, g, 0, 0, 0,  0, 0, b, 0, 0,  0, 0, 0, 1, 0 };
    }

    public static float[] offset(float r, float g, float b) {
        return new float[] { 1, 0, 0, 0, r,  0, 1, 0, 0, g,  0, 0, 1, 0, b,  0, 0, 0, 1, 0 };
    }

    /** Lifts the blacks (the "faded / matte film" look). f = 0..1. */
    public static float[] fade(float f) {
        float s = 1f - f, o = 255f * f * 0.18f;
        return new float[] { s, 0, 0, 0, o,  0, s, 0, 0, o,  0, 0, s, 0, o,  0, 0, 0, 1, 0 };
    }

    // ------------------------------------------------------------------------------------------------------------
    // Filters and Adjust
    // ------------------------------------------------------------------------------------------------------------
    public static int filterIndex(String id) {
        for (int i = 0; i < FILTER_IDS.length; i++) if (FILTER_IDS[i].equals(id)) return i;
        return 0;   // an unknown / old saved id behaves as Original
    }

    /** The full-strength matrix of a filter (a copy). An unknown id gives Original (identity). */
    public static float[] filter(String id) {
        return FILTER_MATRICES[filterIndex(id)].clone();
    }

    /**
     * The Adjust sliders, each -1..1 (0 = no change): brightness (+-80), contrast (x0.5 .. x1.5), saturation (x0 .. x2),
     * warmth (red up / blue down, +-24). All zero gives the identity matrix.
     */
    public static float[] adjust(float brightness, float contrast, float saturation, float warmth) {
        float b = clamp(brightness, -1f, 1f), c = clamp(contrast, -1f, 1f), s = clamp(saturation, -1f, 1f), w = clamp(warmth, -1f, 1f);
        float[] m = identity();
        if (s != 0f) m = then(m, saturation(1f + s));
        if (c != 0f) m = then(m, contrast(1f + 0.5f * c));
        if (b != 0f || w != 0f) m = then(m, offset(b * 80f + w * 24f, b * 80f + w * 4f, b * 80f - w * 24f));
        return m;
    }

    /**
     * What is drawn: the chosen filter at [intensity] (0..1), then the Adjust sliders. Always starts from the ORIGINAL pixels
     * (one matrix, applied once), so switching filters never degrades the photo (v3.10.4: "do not repeatedly degrade the image").
     */
    public static float[] compose(String filterId, float intensity, float brightness, float contrast, float saturation, float warmth) {
        return then(blend(filter(filterId), intensity), adjust(brightness, contrast, saturation, warmth));
    }

    public static boolean isIdentity(float[] m, float eps) {
        float[] id = identity();
        for (int i = 0; i < 20; i++) if (Math.abs(m[i] - id[i]) > eps) return false;
        return true;
    }

    /** Applies a matrix to one colour (0..255 each) and clamps, exactly as the phone does. Used by the tests. */
    public static float[] applyTo(float[] m, float r, float g, float b) {
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) out[i] = clamp(m[i * 5] * r + m[i * 5 + 1] * g + m[i * 5 + 2] * b + m[i * 5 + 4], 0f, 255f);
        return out;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Crop (aspect presets) and drag-to-move
    // ------------------------------------------------------------------------------------------------------------
    public static final String[] CROP_IDS = { "original", "1:1", "4:5", "3:4", "16:9", "9:16" };

    /** {width, height} of the ratio, {0, 0} for "original" (no crop) or an unknown id. */
    public static int[] aspectOf(String id) {
        switch (id == null ? "" : id) {
            case "1:1":  return new int[] { 1, 1 };
            case "4:5":  return new int[] { 4, 5 };
            case "3:4":  return new int[] { 3, 4 };
            case "16:9": return new int[] { 16, 9 };
            case "9:16": return new int[] { 9, 16 };
            default:     return new int[] { 0, 0 };
        }
    }

    /**
     * The crop window {left, top, width, height} inside a w x h image: the LARGEST window of ratio aw:ah, moved by [panX] / [panY]
     * (-1 = hard to the left / top edge, 0 = centred, +1 = hard to the right / bottom edge). aw = 0 (original) = the whole image.
     * Nothing is ever stretched: the window is a plain cut of the pixels.
     */
    public static int[] cropRect(int w, int h, int aw, int ah, float panX, float panY) {
        if (w <= 0 || h <= 0) return new int[] { 0, 0, Math.max(w, 1), Math.max(h, 1) };
        if (aw <= 0 || ah <= 0) return new int[] { 0, 0, w, h };
        int cw, ch;
        if ((long) w * ah >= (long) h * aw) { ch = h; cw = Math.min(w, Math.round((float) h * aw / ah)); }
        else { cw = w; ch = Math.min(h, Math.round((float) w * ah / aw)); }
        cw = Math.max(cw, 1); ch = Math.max(ch, 1);
        int slackX = w - cw, slackY = h - ch;
        int left = Math.round(slackX / 2f * (1f + clamp(panX, -1f, 1f)));
        int top = Math.round(slackY / 2f * (1f + clamp(panY, -1f, 1f)));
        left = Math.max(0, Math.min(slackX, left));
        top = Math.max(0, Math.min(slackY, top));
        return new int[] { left, top, cw, ch };
    }

    /**
     * Drag-to-move: the finger moved [dragPx] on a view that shows the crop window [viewPx] pixels wide, the window is [cropPx] source
     * pixels wide and the image has [slackPx] spare source pixels. The picture follows the finger, so the window moves the OTHER way.
     * Returns the new pan, clamped to -1..1 (0 when there is nothing to move).
     */
    public static float panBy(float pan, float dragPx, int viewPx, int cropPx, int slackPx) {
        if (slackPx <= 0 || viewPx <= 0) return 0f;
        float sourceShift = -dragPx * cropPx / viewPx;
        return clamp(pan + sourceShift / (slackPx / 2f), -1f, 1f);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Reading the photo
    // ------------------------------------------------------------------------------------------------------------
    /** Power-of-two decode step so that the longer side is at most 2 x maxSide (the same rule the app always used). */
    public static int sampleSize(int w, int h, int maxSide) {
        int sample = 1, longest = Math.max(w, h), limit = Math.max(1, maxSide) * 2;
        while (longest / sample > limit) sample *= 2;
        return sample;
    }

    /**
     * EXIF orientation (1..8) -> {clockwise degrees, mirror 0/1}; the turn is done first, then the mirror (left-right).
     * Without this a portrait photo from the camera is saved sideways (BitmapFactory ignores EXIF and a re-saved JPEG loses it).
     */
    public static int[] exifTransform(int orientation) {
        switch (orientation) {
            case 2:  return new int[] { 0, 1 };
            case 3:  return new int[] { 180, 0 };
            case 4:  return new int[] { 180, 1 };
            case 5:  return new int[] { 90, 1 };
            case 6:  return new int[] { 90, 0 };
            case 7:  return new int[] { 270, 1 };
            case 8:  return new int[] { 270, 0 };
            default: return new int[] { 0, 0 };
        }
    }

    static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
