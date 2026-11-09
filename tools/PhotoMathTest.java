import com.sociax.app.data.PhotoMath;

import java.util.Random;

/**
 * Step 76: runs the REAL PhotoMath code (the same file the Android app compiles) on a normal computer.
 *   javac -d /tmp/pm app/src/main/java/com/sociax/app/data/PhotoMath.java tools/PhotoMathTest.java && java -cp /tmp/pm PhotoMathTest
 * Exit code 1 when a check fails. Needs no Android SDK and no internet.
 */
public class PhotoMathTest {
    static int checks = 0, failed = 0;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) { failed++; System.out.println("FAIL: " + what); }
    }

    /** 24 sample colours: greys, skin, sky, grass, red / green / blue, warm and cool tones, a dark and a light colour. */
    static final float[][] SAMPLES = {
        {0, 0, 0}, {32, 32, 32}, {64, 64, 64}, {96, 96, 96}, {128, 128, 128}, {160, 160, 160}, {192, 192, 192}, {224, 224, 224}, {255, 255, 255},
        {224, 172, 105}, {198, 134, 66}, {141, 85, 36},          // skin tones
        {135, 206, 235}, {70, 130, 180},                          // sky
        {86, 160, 60}, {34, 90, 40},                              // grass / leaves
        {200, 40, 40}, {40, 180, 60}, {50, 70, 210},              // red, green, blue
        {250, 220, 140}, {255, 140, 90}, {120, 90, 160}, {20, 30, 60}, {240, 240, 250}
    };

    /** Mean absolute difference (0..255 scale) between what two matrices do to the sample colours. */
    static double distance(float[] a, float[] b) {
        double sum = 0; int n = 0;
        for (float[] c : SAMPLES) {
            float[] x = PhotoMath.applyTo(a, c[0], c[1], c[2]), y = PhotoMath.applyTo(b, c[0], c[1], c[2]);
            for (int i = 0; i < 3; i++) { sum += Math.abs(x[i] - y[i]); n++; }
        }
        return sum / n;
    }

    static boolean finite(float[] m) {
        for (float v : m) if (Float.isNaN(v) || Float.isInfinite(v)) return false;
        return m.length == 20;
    }

    static char[][] rotateCW(char[][] g) {
        int h = g.length, w = g[0].length;
        char[][] r = new char[w][h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) r[x][h - 1 - y] = g[y][x];
        return r;
    }

    static char[][] mirrorLR(char[][] g) {
        int h = g.length, w = g[0].length;
        char[][] r = new char[h][w];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) r[y][w - 1 - x] = g[y][x];
        return r;
    }

    static String show(char[][] g) {
        StringBuilder sb = new StringBuilder();
        for (char[] row : g) { sb.append(new String(row)).append('/'); }
        return sb.toString();
    }

    public static void main(String[] args) {
        // ---- 1. the filter list ----
        String[] ids = PhotoMath.FILTER_IDS;
        check(ids.length >= 20, "at least 20 filters (found " + ids.length + ")");
        check(ids[0].equals("original"), "Original is first");
        for (int i = 0; i < ids.length; i++)
            for (int j = i + 1; j < ids.length; j++) check(!ids[i].equals(ids[j]), "unique filter id " + ids[i]);
        check(PhotoMath.isIdentity(PhotoMath.filter("original"), 1e-6f), "Original is the identity (restores the source)");
        check(PhotoMath.isIdentity(PhotoMath.filter("does-not-exist"), 1e-6f), "an unknown / old saved id behaves as Original");
        for (String id : ids) check(finite(PhotoMath.filter(id)), "filter " + id + " has 20 finite numbers");

        // ---- 2. every filter visibly differs from Original and from every other filter ----
        float[] orig = PhotoMath.filter("original");
        double minVsOriginal = 1e9, minPair = 1e9; String minVsName = "", minPairName = "";
        for (int i = 1; i < ids.length; i++) {
            double d = distance(orig, PhotoMath.filter(ids[i]));
            if (d < minVsOriginal) { minVsOriginal = d; minVsName = ids[i]; }
        }
        for (int i = 0; i < ids.length; i++)
            for (int j = i + 1; j < ids.length; j++) {
                double d = distance(PhotoMath.filter(ids[i]), PhotoMath.filter(ids[j]));
                if (d < minPair) { minPair = d; minPairName = ids[i] + " / " + ids[j]; }
            }
        check(minVsOriginal >= 4.0, "every filter differs from Original by at least 4/255 (weakest: " + minVsName + " = " + String.format("%.2f", minVsOriginal) + ")");
        check(minPair >= 3.0, "every two filters differ by at least 3/255 (closest pair: " + minPairName + " = " + String.format("%.2f", minPair) + ")");

        // ---- 3. strength 0..100 % ----
        for (String id : ids) {
            float[] f = PhotoMath.filter(id);
            check(PhotoMath.isIdentity(PhotoMath.blend(f, 0f), 1e-6f), id + " at 0 % changes nothing");
            check(distance(PhotoMath.blend(f, 1f), f) < 1e-4, id + " at 100 % is the full filter");
            check(distance(PhotoMath.blend(f, 2f), f) < 1e-4 && distance(PhotoMath.blend(f, -1f), orig) < 1e-4, id + " strength is clamped to 0..1");
            double prev = -1; boolean mono = true;
            for (int p = 0; p <= 10; p++) {
                double d = distance(PhotoMath.blend(f, p / 10f), orig);
                if (d + 1e-6 < prev) mono = false;
                prev = d;
            }
            check(mono, id + ": more strength never means less change");
        }

        // ---- 4. no repeated degrading: the result depends only on the CURRENT choice, applied to the original ----
        float[] a = PhotoMath.compose("noir", 0.7f, 0f, 0f, 0f, 0f);
        PhotoMath.compose("vivid", 1f, 0.3f, 0.2f, 0.1f, 0.4f);           // an earlier choice in between
        float[] b = PhotoMath.compose("noir", 0.7f, 0f, 0f, 0f, 0f);
        check(distance(a, b) == 0.0, "choosing the same filter again gives the SAME matrix (nothing accumulates)");
        check(PhotoMath.isIdentity(PhotoMath.compose("original", 1f, 0f, 0f, 0f, 0f), 1e-6f), "Original + neutral Adjust = no change");

        // ---- 5. Adjust sliders ----
        check(PhotoMath.isIdentity(PhotoMath.adjust(0, 0, 0, 0), 1e-6f), "Adjust at zero = identity");
        float[] white = {200, 200, 200};
        check(PhotoMath.applyTo(PhotoMath.adjust(0.5f, 0, 0, 0), 100, 100, 100)[0] > 120, "brightness + makes it brighter");
        check(PhotoMath.applyTo(PhotoMath.adjust(-0.5f, 0, 0, 0), 100, 100, 100)[0] < 80, "brightness - makes it darker");
        check(PhotoMath.applyTo(PhotoMath.adjust(0, 0.8f, 0, 0), 200, 200, 200)[0] > 200 && PhotoMath.applyTo(PhotoMath.adjust(0, 0.8f, 0, 0), 60, 60, 60)[0] < 60, "contrast + pushes light up and dark down");
        float[] sat0 = PhotoMath.applyTo(PhotoMath.adjust(0, 0, -1f, 0), 200, 40, 40);
        check(Math.abs(sat0[0] - sat0[1]) < 1.0 && Math.abs(sat0[1] - sat0[2]) < 1.0, "saturation -100 % gives grey");
        float[] warm = PhotoMath.applyTo(PhotoMath.adjust(0, 0, 0, 1f), 128, 128, 128);
        check(warm[0] > warm[2] + 30, "warmth + moves red up and blue down");
        float[] cool = PhotoMath.applyTo(PhotoMath.adjust(0, 0, 0, -1f), 128, 128, 128);
        check(cool[2] > cool[0] + 30, "warmth - moves blue up and red down");
        check(distance(PhotoMath.adjust(5f, 5f, 5f, 5f), PhotoMath.adjust(1f, 1f, 1f, 1f)) < 1e-4, "Adjust values are clamped to -1..1");
        check(finite(PhotoMath.compose("vivid", 1f, 1f, 1f, 1f, 1f)) && finite(PhotoMath.compose("noir", 1f, -1f, -1f, -1f, -1f)), "extreme settings stay finite");

        // ---- 6. crop window: property test ----
        Random rnd = new Random(76);
        int cropChecks = 0;
        for (int n = 0; n < 20000; n++) {
            int w = 1 + rnd.nextInt(6000), h = 1 + rnd.nextInt(6000);
            String cid = PhotoMath.CROP_IDS[rnd.nextInt(PhotoMath.CROP_IDS.length)];
            int[] asp = PhotoMath.aspectOf(cid);
            float px = rnd.nextFloat() * 4f - 2f, py = rnd.nextFloat() * 4f - 2f;
            int[] r = PhotoMath.cropRect(w, h, asp[0], asp[1], px, py);
            boolean inside = r[0] >= 0 && r[1] >= 0 && r[2] >= 1 && r[3] >= 1 && r[0] + r[2] <= w && r[1] + r[3] <= h;
            boolean ratioOk = asp[0] == 0 || Math.abs((long) r[2] * asp[1] - (long) r[3] * asp[0]) <= Math.max(asp[0], asp[1]) * 2L || r[2] == 1 || r[3] == 1;
            boolean full = asp[0] == 0 ? (r[0] == 0 && r[1] == 0 && r[2] == w && r[3] == h) : (r[2] == w || r[3] == h);
            if (!inside || !ratioOk || !full) { check(false, "cropRect " + w + "x" + h + " " + cid + " pan " + px + "," + py + " -> " + java.util.Arrays.toString(r)); break; }
            cropChecks++;
        }
        check(cropChecks == 20000, "cropRect: 20000 random images / ratios / pans stay inside the image, keep the ratio and use the full width or height");
        int[] centre = PhotoMath.cropRect(1000, 1000, 16, 9, 0f, 0f);
        check(centre[2] == 1000 && centre[3] == 563 && centre[0] == 0 && centre[1] == 219, "1000x1000 -> 16:9 centred: " + java.util.Arrays.toString(centre));
        check(PhotoMath.cropRect(1000, 1000, 16, 9, 0f, -1f)[1] == 0 && PhotoMath.cropRect(1000, 1000, 16, 9, 0f, 1f)[1] == 437, "pan -1 / +1 reach the edges");
        check(PhotoMath.cropRect(4000, 3000, 1, 1, -1f, 0f)[0] == 0 && PhotoMath.cropRect(4000, 3000, 1, 1, 1f, 0f)[0] == 1000, "4000x3000 -> 1:1 can move 1000 px");
        check(PhotoMath.aspectOf("original")[0] == 0 && PhotoMath.aspectOf("nonsense")[0] == 0 && PhotoMath.aspectOf(null)[0] == 0, "original / unknown ratio = no crop");

        // ---- 7. drag to move ----
        check(PhotoMath.panBy(0f, 50f, 500, 1000, 1000) < 0f, "dragging the picture to the right moves the window left");
        check(PhotoMath.panBy(0f, -50f, 500, 1000, 1000) > 0f, "dragging the picture to the left moves the window right");
        check(PhotoMath.panBy(0f, 99999f, 500, 1000, 1000) == -1f && PhotoMath.panBy(0f, -99999f, 500, 1000, 1000) == 1f, "pan is clamped to -1..1");
        check(PhotoMath.panBy(0.3f, 80f, 500, 1000, 0) == 0f, "nothing to move -> 0");
        // the picture follows the finger exactly: a drag of d view pixels moves the picture d view pixels (cropPx/viewPx source px per view px)
        int viewW = 400, cropW = 1000, slack = 600;
        float pan2 = PhotoMath.panBy(0f, -100f, viewW, cropW, slack);
        int left0 = PhotoMath.cropRect(cropW + slack, 1000, 1, 1, 0f, 0f)[0], left1 = PhotoMath.cropRect(cropW + slack, 1000, 1, 1, pan2, 0f)[0];
        check(Math.abs((left1 - left0) - 250) <= 1, "a 100 px drag on a 400 px view moves the window 250 source px (got " + (left1 - left0) + ")");

        // ---- 8. EXIF turns ----
        char[][] stored = { "ABC".toCharArray(), "DEF".toCharArray() };
        String[] expected = { null, "ABC/DEF/", "CBA/FED/", "FED/CBA/", "DEF/ABC/", "AD/BE/CF/", "DA/EB/FC/", "FC/EB/DA/", "CF/BE/AD/" };
        for (int o = 1; o <= 8; o++) {
            int[] t = PhotoMath.exifTransform(o);
            char[][] g = stored;
            for (int q = 0; q < t[0] / 90; q++) g = rotateCW(g);
            if (t[1] == 1) g = mirrorLR(g);
            check(show(g).equals(expected[o]), "EXIF orientation " + o + " -> " + show(g) + " (expected " + expected[o] + ")");
        }
        check(PhotoMath.exifTransform(0)[0] == 0 && PhotoMath.exifTransform(99)[1] == 0, "unknown EXIF value = no change");

        // ---- 9. decode step ----
        for (int n = 0; n < 5000; n++) {
            int w = 1 + rnd.nextInt(12000), h = 1 + rnd.nextInt(12000), max = 256 + rnd.nextInt(2000);
            int s = PhotoMath.sampleSize(w, h, max);
            boolean pow2 = (s & (s - 1)) == 0;
            int longest = Math.max(w, h);
            if (!pow2 || longest / s > max * 2 || (s > 1 && longest / (s / 2) <= max * 2)) { check(false, "sampleSize " + w + "x" + h + " max " + max + " -> " + s); break; }
        }
        check(PhotoMath.sampleSize(2000, 1500, 1440) == 1 && PhotoMath.sampleSize(4000, 3000, 1440) == 2 && PhotoMath.sampleSize(8000, 6000, 1440) == 4 && PhotoMath.sampleSize(12000, 9000, 1080) == 8, "sampleSize examples (the longer side ends at most 2 x maxSide)");

        System.out.println(String.format("weakest filter vs Original: %.2f/255, closest two filters: %.2f/255 (%s)", minVsOriginal, minPair, minPairName));
        System.out.println("PhotoMathTest: " + checks + " checks, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
