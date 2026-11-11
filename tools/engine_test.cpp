// Step 77 (plan v4.0.3): runs the REAL native engine core (app/src/main/cpp/sociax_engine/engine.cpp) on a computer.
// Build + run (one line):
//   g++ -std=c++17 -O1 -g -fsanitize=address,undefined -Iapp/src/main/cpp/sociax_engine app/src/main/cpp/sociax_engine/engine.cpp tools/engine_test.cpp -o /tmp/engine_test && /tmp/engine_test
// Exit code 1 = at least one check failed.
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <random>
#include <string>
#include <vector>

#include "engine.h"

using namespace sociax;

static int g_checks = 0, g_failed = 0;
#define CHECK(cond, msg)                                                          \
  do {                                                                            \
    ++g_checks;                                                                   \
    if (!(cond)) { ++g_failed; std::printf("FAIL line %d: %s\n", __LINE__, msg); } \
  } while (0)

static ClipSpec clip(int64_t w = 1920, int64_t h = 1080) {
  ClipSpec c;
  c.srcW = w; c.srcH = h; c.srcDurationUs = 10000000; c.trimStartUs = 0; c.trimEndUs = 10000000; c.hasAudio = 1;
  return c;
}

// ---------------------------------------------------------------- reference grid maths (a DIFFERENT method from the engine)
using Grid = std::vector<std::vector<uint32_t>>;  // [y][x]
static Grid rot90cw(const Grid& g) {
  const size_t H = g.size(), W = g[0].size();
  Grid r(W, std::vector<uint32_t>(H));
  for (size_t y = 0; y < H; ++y) for (size_t x = 0; x < W; ++x) r[x][H - 1 - y] = g[y][x];  // forward mapping
  return r;
}
static Grid flipLR(const Grid& g) {
  Grid r = g;
  for (auto& row : r) std::reverse(row.begin(), row.end());
  return r;
}
static Grid cropGrid(const Grid& g, size_t x0, size_t y0, size_t w, size_t h) {
  Grid r(h, std::vector<uint32_t>(w));
  for (size_t y = 0; y < h; ++y) for (size_t x = 0; x < w; ++x) r[y][x] = g[y0 + y][x0 + x];
  return r;
}
static uint32_t pack(uint32_t id) { return (id & 255u) | (((id >> 8) & 255u) << 8) | (0x5Au << 16) | (0xFFu << 24); }
static std::vector<uint8_t> toBytes(const Grid& g) {
  std::vector<uint8_t> b;
  for (auto& row : g) for (uint32_t v : row) { b.push_back(v & 255); b.push_back((v >> 8) & 255); b.push_back((v >> 16) & 255); b.push_back((v >> 24) & 255); }
  return b;
}
static Grid makeGrid(size_t w, size_t h) {
  Grid g(h, std::vector<uint32_t>(w));
  uint32_t id = 1;
  for (size_t y = 0; y < h; ++y) for (size_t x = 0; x < w; ++x) g[y][x] = pack(id++);
  return g;
}
static bool sameBytes(const std::vector<uint8_t>& a, const std::vector<uint8_t>& b) { return a == b; }

// ---------------------------------------------------------------- colour helpers
static std::vector<uint8_t> palette() {
  static const uint8_t c[][3] = {{0, 0, 0}, {64, 64, 64}, {128, 128, 128}, {192, 192, 192}, {255, 255, 255}, {255, 0, 0}, {0, 255, 0}, {0, 0, 255},
                                  {255, 255, 0}, {0, 255, 255}, {255, 0, 255}, {224, 172, 105}, {198, 134, 66}, {141, 85, 36}, {255, 219, 172},
                                  {135, 206, 235}, {34, 139, 34}, {139, 69, 19}, {200, 80, 120}, {30, 40, 90}, {240, 230, 140}, {100, 100, 160},
                                  {180, 60, 40}, {90, 160, 120}};
  std::vector<uint8_t> p;
  for (auto& t : c) { p.push_back(t[0]); p.push_back(t[1]); p.push_back(t[2]); p.push_back(255); }
  return p;
}
static std::vector<uint8_t> runColor(const ColorParams& p, int* code = nullptr) {
  std::vector<uint8_t> px = palette();
  int c = applyColor(px.data(), px.size(), 6, 4, p, nullptr);
  if (code) *code = c;
  return px;
}
static double meanDiff(const std::vector<uint8_t>& a, const std::vector<uint8_t>& b) {
  double s = 0; size_t n = 0;
  for (size_t i = 0; i < a.size(); i += 4) for (int k = 0; k < 3; ++k) { s += std::abs(int(a[i + k]) - int(b[i + k])); ++n; }
  return s / n;
}
static double meanLuma(const std::vector<uint8_t>& a) {
  double s = 0;
  for (size_t i = 0; i < a.size(); i += 4) s += 0.2126 * a[i] + 0.7152 * a[i + 1] + 0.0722 * a[i + 2];
  return s / (a.size() / 4);
}
static double meanChroma(const std::vector<uint8_t>& a) {
  double s = 0;
  for (size_t i = 0; i < a.size(); i += 4) {
    int mx = std::max({a[i], a[i + 1], a[i + 2]}), mn = std::min({a[i], a[i + 1], a[i + 2]});
    s += mx - mn;
  }
  return s / (a.size() / 4);
}

int main() {
  // ============================================================ codes + spec round trip
  CHECK(std::strcmp(codeName(kOk), "ok") == 0 && std::strcmp(codeName(kCancelled), "cancelled") == 0 && std::strcmp(codeName(99), "unknown") == 0,
        "code names");
  {
    ClipSpec c = clip(1280, 720);
    c.metaRotation = 90; c.turn = 180; c.flipH = 1; c.crop = Crop{100, 200, 5000, 6000};
    c.trimStartUs = 1000000; c.trimEndUs = 9000000; c.speedMilli = 1500; c.hasAudio = 0;
    int64_t a[kSpecLongs]; specToLongs(c, a);
    ClipSpec d; CHECK(specFromLongs(a, kSpecLongs, &d), "specFromLongs ok");
    int64_t b[kSpecLongs]; specToLongs(d, b);
    CHECK(std::memcmp(a, b, sizeof a) == 0, "spec round trip keeps every field");
    CHECK(!specFromLongs(a, kSpecLongs - 1, &d) && !specFromLongs(nullptr, kSpecLongs, &d), "wrong length / null refused");
  }

  // ============================================================ clip output size + duration
  {
    ClipOut o = clipOutput(clip());
    CHECK(o.code == kOk && o.w == 1920 && o.h == 1080 && o.durationUs == 10000000, "1080p normal clip unchanged");
    ClipSpec p = clip(); p.metaRotation = 90;
    o = clipOutput(p);
    CHECK(o.w == 1080 && o.h == 1920, "phone-rotated 90: displayed portrait");
    p.turn = 270;  // 90 + 270 = 360 = upright again
    o = clipOutput(p);
    CHECK(o.w == 1920 && o.h == 1080, "meta 90 + turn 270 = 0");
    p.metaRotation = 0; p.turn = 180;
    o = clipOutput(p);
    CHECK(o.w == 1920 && o.h == 1080, "180 does not swap");
    ClipSpec k = clip(); k.crop = Crop{2500, 0, 5000, 5000};
    o = clipOutput(k);
    CHECK(o.w == 960 && o.h == 540, "crop 50 percent x 50 percent");
    ClipSpec odd = clip(1001, 601);
    o = clipOutput(odd);
    CHECK(o.w == 1000 && o.h == 600, "odd sizes are made even (encoders need it)");
    const int64_t sp[] = {250, 500, 1000, 2000, 4000};
    const int64_t want[] = {40000000, 20000000, 10000000, 5000000, 2500000};
    for (int i = 0; i < 5; ++i) { ClipSpec s = clip(); s.speedMilli = sp[i]; CHECK(clipOutput(s).durationUs == want[i], "speed changes the duration"); }
    ClipSpec t = clip(); t.trimStartUs = 2000000; t.trimEndUs = 5000000;
    CHECK(clipOutput(t).durationUs == 3000000, "trim keeps only the chosen part");
  }

  // ============================================================ validation: every bad value is refused with the right code
  {
    struct Bad { const char* what; ClipSpec c; int code; };
    std::vector<Bad> bad;
    auto add = [&](const char* w, ClipSpec c, int code) { bad.push_back({w, c, code}); };
    ClipSpec c;
    c = clip(8, 1080); add("width too small", c, kBadSize);
    c = clip(1920, 8193); add("height too big", c, kBadSize);
    c = clip(); c.metaRotation = 45; add("meta rotation 45", c, kBadArgument);
    c = clip(); c.turn = 360; add("turn 360", c, kBadArgument);
    c = clip(); c.flipH = 2; add("flip 2", c, kBadArgument);
    c = clip(); c.hasAudio = 5; add("hasAudio 5", c, kBadArgument);
    c = clip(); c.crop = Crop{0, 0, 400, 10000}; add("crop narrower than 5 percent", c, kBadRange);
    c = clip(); c.crop = Crop{6000, 0, 5000, 10000}; add("crop outside right edge", c, kBadRange);
    c = clip(); c.crop = Crop{-1, 0, 5000, 5000}; add("crop negative", c, kBadRange);
    c = clip(); c.trimStartUs = 5000000; c.trimEndUs = 5000000; add("empty trim", c, kBadRange);
    c = clip(); c.trimStartUs = 6000000; c.trimEndUs = 5000000; add("trim backwards", c, kBadRange);
    c = clip(); c.trimEndUs = 10000001; add("trim past the end", c, kBadRange);
    c = clip(); c.trimStartUs = -1; add("trim before the start", c, kBadRange);
    c = clip(); c.trimStartUs = 0; c.trimEndUs = 99999; add("shorter than 0.1 s", c, kBadRange);
    c = clip(); c.srcDurationUs = 0; add("no duration", c, kBadRange);
    c = clip(); c.speedMilli = 249; add("speed under 0.25x", c, kBadRange);
    c = clip(); c.speedMilli = 4001; add("speed over 4x", c, kBadRange);
    c = clip(32, 32); c.crop = Crop{0, 0, 3000, 3000}; add("output under 16 px", c, kBadSize);
    for (auto& b : bad) { ClipOut o = clipOutput(b.c); CHECK(o.code == b.code, b.what); CHECK(validateClip(b.c) == b.code, b.what); }
    CHECK(validateClip(clip()) == kOk, "normal clip valid");
    ClipSpec edge = clip(); edge.trimEndUs = 100000;
    CHECK(validateClip(edge) == kOk, "exactly 0.1 s is allowed");
  }

  // ============================================================ split + merge order
  {
    ClipSpec c = clip();
    SplitResult s = splitClip(c, 4000000);
    CHECK(s.code == kOk && s.left.trimStartUs == 0 && s.left.trimEndUs == 4000000 && s.right.trimStartUs == 4000000 && s.right.trimEndUs == 10000000,
          "split cuts at the chosen point");
    CHECK(clipOutput(s.left).durationUs + clipOutput(s.right).durationUs == clipOutput(c).durationUs, "the two pieces add up to the whole");
    CHECK(s.left.srcW == c.srcW && s.right.speedMilli == c.speedMilli && s.right.crop.w == c.crop.w, "everything else is copied");
    CHECK(splitClip(c, 50000).code == kBadRange && splitClip(c, 9950000).code == kBadRange, "no piece shorter than 0.1 s");
    CHECK(splitClip(c, 100000).code == kOk && splitClip(c, 9900000).code == kOk, "0.1 s from the ends is allowed");
    ClipSpec t = clip(); t.trimStartUs = 2000000; t.trimEndUs = 6000000;
    CHECK(splitClip(t, 1000000).code == kBadRange && splitClip(t, 7000000).code == kBadRange, "split must be inside the kept part");
    ClipSpec bad = clip(); bad.speedMilli = 1;
    CHECK(splitClip(bad, 5000000).code == kBadRange, "invalid clip cannot be split");
    int32_t ok[] = {2, 0, 1}, dup[] = {0, 0, 1}, out[] = {0, 1, 3}, neg[] = {0, -1, 1};
    CHECK(isPermutation(ok, 3) && !isPermutation(dup, 3) && !isPermutation(out, 3) && !isPermutation(neg, 3) && !isPermutation(nullptr, 3) &&
              !isPermutation(ok, 0),
          "merge order must use every clip exactly once");
  }

  // ============================================================ audio behaviour of speed
  {
    ClipSpec c = clip();
    const int64_t sp[] = {250, 499, 500, 1000, 2000, 2001, 4000};
    const int want[] = {kAudioMute, kAudioMute, kAudioKeep, kAudioKeep, kAudioKeep, kAudioMute, kAudioMute};
    for (int i = 0; i < 7; ++i) { c.speedMilli = sp[i]; CHECK(audioPolicy(c) == want[i], "audio policy by speed"); }
    c.hasAudio = 0;
    CHECK(audioPolicy(c) == kAudioNone, "no audio track -> none");
  }

  // ============================================================ export plan + presets
  {
    DeviceCaps full{4096, 1, 1};
    CHECK(availablePresets(1080, full) == (1 | 2 | 4), "1080p source: original, 720, 1080 (no 4K)");
    CHECK(availablePresets(2160, full) == (1 | 2 | 4 | 8), "4K source on a capable phone: all four");
    CHECK(availablePresets(2160, DeviceCaps{4096, 1, 0}) == (1 | 2 | 4), "4K never offered when the phone cannot");
    CHECK(availablePresets(480, full) == 1, "480p source: only the original");
    CHECK(availablePresets(1080, DeviceCaps{4096, 0, 0}) == (1 | 2), "no 1080 offered when the phone cannot");

    ClipSpec c = clip();
    ExportPlan p = exportPlan(&c, 1, kPreset720, full);
    CHECK(p.code == kOk && p.canvasW == 1280 && p.canvasH == 720 && !p.downgraded && p.presetUsed == 720 && p.rects.size() == 1, "1080p -> 720 keeps 16:9");
    p = exportPlan(&c, 1, kPreset1080, full);
    CHECK(p.canvasW == 1920 && p.canvasH == 1080 && !p.downgraded, "1080 on 1080");
    p = exportPlan(&c, 1, kPreset2160, full);
    CHECK(p.canvasW == 1920 && p.canvasH == 1080 && p.downgraded == 1 && p.presetUsed == 1080, "4K asked on a 1080p source: falls back, flagged, never upscaled");
    p = exportPlan(&c, 1, kPreset1080, DeviceCaps{4096, 0, 0});
    CHECK(p.canvasH == 720 && p.downgraded == 1, "phone without 1080: 720 with a flag");
    ClipSpec small = clip(854, 480);
    p = exportPlan(&small, 1, kPreset720, full);
    CHECK(p.code == kOk && p.canvasW == 854 && p.canvasH == 480 && p.downgraded == 1 && p.presetUsed == 0, "480p source asked 720: kept original, flagged");
    p = exportPlan(&c, 1, kPresetOriginal, full);
    CHECK(p.canvasW == 1920 && p.canvasH == 1080 && !p.downgraded, "original");
    ClipSpec portrait = clip(); portrait.metaRotation = 90;
    p = exportPlan(&portrait, 1, kPreset720, full);
    CHECK(p.canvasW == 720 && p.canvasH == 1280, "portrait phone video -> 720x1280");
    ClipSpec k4 = clip(3840, 2160);
    p = exportPlan(&k4, 1, kPreset2160, full);
    CHECK(p.canvasW == 3840 && p.canvasH == 2160 && !p.downgraded, "real 4K on a capable phone");
    p = exportPlan(&k4, 1, kPreset2160, DeviceCaps{3000, 1, 1});
    CHECK(p.code == kOk && p.presetUsed == 1080 && p.downgraded == 1, "limit 3000 px: steps down to 1080");
    ClipSpec huge = clip(8000, 4500);
    p = exportPlan(&huge, 1, kPresetOriginal, DeviceCaps{4096, 1, 1});
    CHECK(p.code == kUnsupported, "original bigger than the phone allows -> unsupported (caller shows the fallback)");
    CHECK(exportPlan(&c, 1, 999, full).code == kBadArgument && exportPlan(&c, 0, 720, full).code == kBadArgument &&
              exportPlan(nullptr, 1, 720, full).code == kBadArgument,
          "bad preset / empty / null");

    ClipSpec a = clip(1920, 1080), b = clip(1080, 1920), d = clip(1280, 720);
    b.trimEndUs = 4000000; d.speedMilli = 2000;
    ClipSpec three[3] = {a, b, d};
    p = exportPlan(three, 3, kPreset720, full);
    CHECK(p.code == kOk && p.rects.size() == 3, "three clips");
    CHECK(p.totalUs == 10000000 + 4000000 + 5000000, "total = sum of every clip after trim and speed");
    bool inside = true, centred = true, aspect = true;
    const ClipOut outs[3] = {clipOutput(a), clipOutput(b), clipOutput(d)};
    for (int i = 0; i < 3; ++i) {
      const Rect& r = p.rects[i];
      if (r.x < 0 || r.y < 0 || r.x + r.w > p.canvasW || r.y + r.h > p.canvasH) inside = false;
      if (std::llabs((p.canvasW - r.w) - 2 * r.x) > 2 || std::llabs((p.canvasH - r.h) - 2 * r.y) > 2) centred = false;
      const double want = double(outs[i].w) / outs[i].h, got = double(r.w) / r.h;
      if (std::abs(want - got) / want > 0.02) aspect = false;
    }
    CHECK(inside, "every clip sits inside the canvas");
    CHECK(centred, "every clip is centred");
    CHECK(aspect, "no clip is stretched (aspect kept within 2 percent)");
    ClipSpec broken = clip(); broken.speedMilli = 10;
    ClipSpec mix[3] = {a, b, broken};
    p = exportPlan(mix, 3, kPreset720, full);
    CHECK(p.code == kBadRange && p.failedClip == 2, "the plan names the clip that failed");
    std::vector<ClipSpec> many(kMaxClips + 1, clip());
    CHECK(exportPlan(many.data(), many.size(), 720, full).code == kBadArgument, "more than 100 clips refused");
  }

  // ============================================================ frame transform (real pixels) against the reference grid
  {
    // 3 x 2 grid: rotations without crop (odd width becomes even: the last column is dropped, like clipOutput does)
    Grid g = makeGrid(8, 6);
    std::vector<uint8_t> src = toBytes(g);
    Grid r[4]; r[0] = g; for (int i = 1; i < 4; ++i) r[i] = rot90cw(r[i - 1]);
    const int64_t rots[4] = {0, 90, 180, 270};
    for (int i = 0; i < 4; ++i) {
      for (int flip = 0; flip < 2; ++flip) {
        Grid want = flip ? flipLR(r[i]) : r[i];
        // full crop: size is even already for 8x6 and 6x8
        std::vector<uint8_t> dst(8 * 6 * 4, 0xEE);
        int64_t ow = 0, oh = 0;
        int code = transformFrame(src.data(), src.size(), 8, 6, rots[i], flip, Crop{0, 0, kCropFull, kCropFull}, dst.data(), dst.size(), &ow, &oh, nullptr);
        CHECK(code == kOk && ow == (int64_t)want[0].size() && oh == (int64_t)want.size(), "rotation size");
        dst.resize(size_t(ow * oh * 4));
        CHECK(sameBytes(dst, toBytes(want)), "rotation + mirror pixels match the reference");
      }
    }
    // one corner pixel checked by hand: turning 90 clockwise puts the top-left pixel at the top-right
    {
      std::vector<uint8_t> dst(8 * 6 * 4);
      int64_t ow, oh;
      transformFrame(src.data(), src.size(), 8, 6, 90, 0, Crop{0, 0, kCropFull, kCropFull}, dst.data(), dst.size(), &ow, &oh, nullptr);
      CHECK(ow == 6 && oh == 8, "90 swaps width and height");
      CHECK(std::memcmp(&dst[(0 * 6 + 5) * 4], &src[0], 4) == 0, "top-left goes to top-right after a clockwise turn");
      CHECK(std::memcmp(&dst[(7 * 6 + 0) * 4], &src[(5 * 8 + 7) * 4], 4) == 0, "bottom-right goes to bottom-left");
    }
    // odd size: 3 x 2 -> 2 x 2, last column dropped
    {
      Grid odd = makeGrid(3, 2);
      std::vector<uint8_t> s = toBytes(odd), dst(3 * 2 * 4);
      int64_t ow, oh;
      int code = transformFrame(s.data(), s.size(), 3, 2, 0, 0, Crop{0, 0, kCropFull, kCropFull}, dst.data(), dst.size(), &ow, &oh, nullptr);
      CHECK(code == kOk && ow == 2 && oh == 2, "odd width made even");
      dst.resize(size_t(ow * oh * 4));
      CHECK(sameBytes(dst, toBytes(cropGrid(odd, 0, 0, 2, 2))), "odd width: the last column is the one dropped");
    }
    // crop
    {
      Grid big = makeGrid(16, 8);
      std::vector<uint8_t> s = toBytes(big), dst(16 * 8 * 4);
      int64_t ow, oh;
      int code = transformFrame(s.data(), s.size(), 16, 8, 0, 0, Crop{2500, 5000, 5000, 5000}, dst.data(), dst.size(), &ow, &oh, nullptr);
      CHECK(code == kOk && ow == 8 && oh == 4, "crop size");
      dst.resize(size_t(ow * oh * 4));
      CHECK(sameBytes(dst, toBytes(cropGrid(big, 4, 4, 8, 4))), "crop picks the right window");
    }
    // random: rotation + mirror + crop vs reference, and the size equals what clipOutput promised
    {
      std::mt19937 rng(12345);
      int mismatchPix = 0, mismatchSize = 0;
      for (int it = 0; it < 3000; ++it) {
        const size_t w = 16 + rng() % 40, h = 16 + rng() % 40;
        const int64_t rot = int64_t(rng() % 4) * 90;
        const int flip = int(rng() % 2);
        Crop cr;
        cr.w = kCropMin + int64_t(rng() % (kCropFull - kCropMin + 1));
        cr.h = kCropMin + int64_t(rng() % (kCropFull - kCropMin + 1));
        cr.x = int64_t(rng() % (kCropFull - cr.w + 1));
        cr.y = int64_t(rng() % (kCropFull - cr.h + 1));
        Grid g = makeGrid(w, h);
        std::vector<uint8_t> s = toBytes(g), dst(w * h * 4 + 64);
        int64_t ow = 0, oh = 0;
        int code = transformFrame(s.data(), s.size(), int64_t(w), int64_t(h), rot, flip, cr, dst.data(), dst.size(), &ow, &oh, nullptr);
        ClipSpec spec = clip(int64_t(w), int64_t(h));  // sizes < 16 are refused by clipOutput, so keep >= 16 here
        spec.turn = rot; spec.flipH = flip; spec.crop = cr;
        ClipOut co = clipOutput(spec);
        if (code != kOk) { if (co.code == kOk) ++mismatchSize; continue; }  // tiny crops may legally be refused (< 2 px)
        if (co.code == kOk && (co.w != ow || co.h != oh)) ++mismatchSize;
        Grid t = g; for (int64_t k = 0; k < rot / 90; ++k) t = rot90cw(t);
        if (flip) t = flipLR(t);
        const size_t rw = t[0].size(), rh = t.size();
        const size_t x0 = size_t(int64_t(rw) * cr.x / kCropFull), y0 = size_t(int64_t(rh) * cr.y / kCropFull);
        Grid want = cropGrid(t, x0, y0, size_t(ow), size_t(oh));
        dst.resize(size_t(ow * oh * 4));
        if (!sameBytes(dst, toBytes(want))) ++mismatchPix;
      }
      CHECK(mismatchPix == 0, "3000 random turn/mirror/crop frames equal the reference");
      CHECK(mismatchSize == 0, "frame size always equals clipOutput's promise (preview = export)");
    }
    // refusals
    {
      std::vector<uint8_t> s(8 * 6 * 4), d(8 * 6 * 4);
      int64_t ow, oh;
      Crop full{0, 0, kCropFull, kCropFull};
      CHECK(transformFrame(s.data(), 10, 8, 6, 0, 0, full, d.data(), d.size(), &ow, &oh, nullptr) == kBadBuffer, "source buffer too small");
      CHECK(transformFrame(s.data(), s.size(), 8, 6, 0, 0, full, d.data(), 8, &ow, &oh, nullptr) == kBadBuffer, "destination too small");
      CHECK(transformFrame(nullptr, 0, 8, 6, 0, 0, full, d.data(), d.size(), &ow, &oh, nullptr) == kBadBuffer, "null source");
      CHECK(transformFrame(s.data(), s.size(), 8, 6, 45, 0, full, d.data(), d.size(), &ow, &oh, nullptr) == kBadArgument, "rotation 45");
      CHECK(transformFrame(s.data(), s.size(), 8, 6, 0, 3, full, d.data(), d.size(), &ow, &oh, nullptr) == kBadArgument, "flip 3");
      CHECK(transformFrame(s.data(), s.size(), 0, 6, 0, 0, full, d.data(), d.size(), &ow, &oh, nullptr) == kBadSize, "zero width");
      CHECK(transformFrame(s.data(), s.size(), 8, 6, 0, 0, Crop{6000, 0, 5000, 5000}, d.data(), d.size(), &ow, &oh, nullptr) == kBadRange, "crop outside");
      Session cancelled; cancelled.cancel();
      CHECK(transformFrame(s.data(), s.size(), 8, 6, 0, 0, full, d.data(), d.size(), &ow, &oh, &cancelled) == kCancelled, "cancel is honoured");
      Session ok; 
      CHECK(transformFrame(s.data(), s.size(), 8, 6, 0, 0, full, d.data(), d.size(), &ow, &oh, &ok) == kOk && ok.progress() == 1000, "progress reaches 1000");
    }
  }

  // ============================================================ colour: filters + Adjust
  {
    std::vector<uint8_t> orig = palette();
    // identity is BYTE-FOR-BYTE untouched
    ColorParams id; 
    CHECK(sameBytes(runColor(id), orig), "Original + zeros leaves every byte untouched");
    ColorParams zeroStrength; zeroStrength.filterId = 5; zeroStrength.strength = 0;
    CHECK(sameBytes(runColor(zeroStrength), orig), "0 percent strength = no change");
    // alpha never changes (random alpha)
    {
      std::mt19937 rng(7);
      bool alphaOk = true;
      for (int f = 0; f < kFilterCount; ++f) {
        std::vector<uint8_t> px(64 * 4);
        for (auto& v : px) v = uint8_t(rng());
        std::vector<uint8_t> before = px;
        ColorParams p; p.filterId = f; p.brightness = 20; p.contrast = -15; p.saturation = 30; p.temperature = -10;
        applyColor(px.data(), px.size(), 8, 8, p, nullptr);
        for (size_t i = 3; i < px.size(); i += 4) if (px[i] != before[i]) alphaOk = false;
      }
      CHECK(alphaOk, "alpha is never changed by any filter");
    }
    // 12 real filters: each clearly different from Original, and from every other filter
    double minVsOrig = 1e9, minPair = 1e9;
    std::vector<std::vector<uint8_t>> res(kFilterCount);
    for (int f = 0; f < kFilterCount; ++f) { ColorParams p; p.filterId = f; res[f] = runColor(p); }
    for (int f = 1; f < kFilterCount; ++f) minVsOrig = std::min(minVsOrig, meanDiff(res[f], orig));
    for (int a = 0; a < kFilterCount; ++a) for (int b = a + 1; b < kFilterCount; ++b) minPair = std::min(minPair, meanDiff(res[a], res[b]));
    std::printf("info: smallest filter-vs-Original difference %.2f / 255, smallest difference between any two filters %.2f / 255\n", minVsOrig, minPair);
    CHECK(minVsOrig >= 4.0, "every filter differs from Original by at least 4/255 on 24 sample colours");
    CHECK(minPair >= 4.0, "every two filters differ by at least 4/255 (no duplicates)");
    // names
    {
      bool names = std::strcmp(filterName(0), "Original") == 0 && std::strcmp(filterName(6), "Sepia") == 0 && std::strcmp(filterName(13), "") == 0;
      for (int a = 0; a < kFilterCount; ++a) for (int b = a + 1; b < kFilterCount; ++b) if (std::strcmp(filterName(a), filterName(b)) == 0) names = false;
      CHECK(names, "13 unique names");
    }
    // strength: never less change with more strength; 100 = full filter
    for (int f = 1; f < kFilterCount; ++f) {
      double last = -1; bool mono = true;
      for (int s = 0; s <= 100; s += 25) { ColorParams p; p.filterId = f; p.strength = s; double d = meanDiff(runColor(p), orig); if (d + 1e-9 < last) mono = false; last = d; }
      CHECK(mono, "more strength never means less change");
    }
    // known values
    {
      uint8_t white[4] = {255, 255, 255, 255};
      ColorParams sep; sep.filterId = 6;
      applyColor(white, 4, 1, 1, sep, nullptr);
      CHECK(white[0] == 255 && white[1] == 255 && std::abs(int(white[2]) - 239) <= 1, "Sepia turns white into (255,255,239)");
      for (int f : {4, 5}) {
        ColorParams m; m.filterId = f;
        std::vector<uint8_t> px = runColor(m);
        bool grey = true;
        for (size_t i = 0; i < px.size(); i += 4) if (std::abs(px[i] - px[i + 1]) > 1 || std::abs(px[i + 1] - px[i + 2]) > 1) grey = false;
        CHECK(grey, "Mono / Noir give R = G = B");
      }
    }
    // Adjust directions
    {
      ColorParams b; b.brightness = 40; CHECK(meanLuma(runColor(b)) > meanLuma(orig) + 5, "brightness + makes it brighter");
      b.brightness = -40; CHECK(meanLuma(runColor(b)) < meanLuma(orig) - 5, "brightness - makes it darker");
      ColorParams c; c.contrast = 50;
      uint8_t lo[4] = {64, 64, 64, 255}, hi[4] = {192, 192, 192, 255};
      applyColor(lo, 4, 1, 1, c, nullptr); applyColor(hi, 4, 1, 1, c, nullptr);
      CHECK(lo[0] < 64 && hi[0] > 192, "contrast + darkens darks and lightens lights");
      c.contrast = -50;
      uint8_t lo2[4] = {64, 64, 64, 255}, hi2[4] = {192, 192, 192, 255};
      applyColor(lo2, 4, 1, 1, c, nullptr); applyColor(hi2, 4, 1, 1, c, nullptr);
      CHECK(lo2[0] > 64 && hi2[0] < 192, "contrast - pulls both toward grey");
      ColorParams s; s.saturation = -100;
      std::vector<uint8_t> g = runColor(s); bool grey = true;
      for (size_t i = 0; i < g.size(); i += 4) if (std::abs(g[i] - g[i + 1]) > 1 || std::abs(g[i + 1] - g[i + 2]) > 1) grey = false;
      CHECK(grey, "saturation -100 = grey");
      s.saturation = 50; CHECK(meanChroma(runColor(s)) > meanChroma(orig), "saturation + adds colour");
      ColorParams t; t.temperature = 60;
      uint8_t mid[4] = {128, 128, 128, 255};
      applyColor(mid, 4, 1, 1, t, nullptr);
      CHECK(mid[0] > 128 && mid[2] < 128 && mid[1] == 128, "temperature + = warmer (more red, less blue, green same)");
      t.temperature = -60;
      uint8_t mid2[4] = {128, 128, 128, 255};
      applyColor(mid2, 4, 1, 1, t, nullptr);
      CHECK(mid2[0] < 128 && mid2[2] > 128 && mid2[1] == 128, "temperature - = cooler");
    }
    // refusals + cancel
    {
      uint8_t px[16] = {0};
      ColorParams p; p.strength = 101; CHECK(applyColor(px, 16, 2, 2, p, nullptr) == kBadRange, "strength 101");
      p = ColorParams(); p.filterId = 13; CHECK(applyColor(px, 16, 2, 2, p, nullptr) == kBadRange, "filter 13");
      p = ColorParams(); p.filterId = -1; CHECK(applyColor(px, 16, 2, 2, p, nullptr) == kBadRange, "filter -1");
      p = ColorParams(); p.contrast = 101; CHECK(applyColor(px, 16, 2, 2, p, nullptr) == kBadRange, "adjust 101");
      p = ColorParams(); p.temperature = -101; CHECK(applyColor(px, 16, 2, 2, p, nullptr) == kBadRange, "adjust -101");
      p = ColorParams(); p.brightness = 10; CHECK(applyColor(px, 8, 2, 2, p, nullptr) == kBadBuffer, "buffer too small");
      CHECK(applyColor(nullptr, 16, 2, 2, p, nullptr) == kBadBuffer, "null buffer");
      CHECK(applyColor(px, 16, 0, 2, p, nullptr) == kBadSize, "zero size");
      std::vector<uint8_t> big(64 * 64 * 4, 100);
      Session s; s.cancel();
      CHECK(applyColor(big.data(), big.size(), 64, 64, p, &s) == kCancelled, "cancel is honoured");
    }
  }

  // ============================================================ ONE colour matrix = the same colour as applyColor (preview and export on the GPU)
  {
    float id[16];
    CHECK(colorMatrix(ColorParams(), id) == kOk, "matrix for no edit");
    bool isId = true;
    for (int i = 0; i < 16; ++i) if (std::abs(id[i] - ((i % 5 == 0) ? 1.f : 0.f)) > 1e-6f) isId = false;
    CHECK(isId, "no edit = identity matrix (column-major, last row 0 0 0 1)");
    // layout: red gain only in the first column
    ColorParams warm; warm.filterId = 1;
    float w[16];
    colorMatrix(warm, w);
    CHECK(w[3] == 0.f && w[7] == 0.f && w[11] == 0.f && w[15] == 1.f, "bottom row stays 0 0 0 1");
    CHECK(w[0] > w[5] && w[5] > w[10], "Warm: red gain > green gain > blue gain on the diagonal");
    // equals applyColor for every filter and several Adjust / strength choices, on the 24 sample colours
    std::mt19937 rng(99);
    double worst = 0;
    auto compare = [&](const ColorParams& p) {
      float m[16];
      if (colorMatrix(p, m) != kOk) return 999.0;
      std::vector<uint8_t> px = palette();
      applyColor(px.data(), px.size(), 6, 4, p, nullptr);
      const std::vector<uint8_t> src = palette();
      double mx = 0;
      for (size_t i = 0; i < src.size(); i += 4) {
        const float in[3] = {src[i] / 255.f, src[i + 1] / 255.f, src[i + 2] / 255.f};
        for (int ch = 0; ch < 3; ++ch) {
          float v = m[ch] * in[0] + m[4 + ch] * in[1] + m[8 + ch] * in[2] + m[12 + ch];
          v = v < 0.f ? 0.f : (v > 1.f ? 1.f : v);
          mx = std::max(mx, std::abs(double(v) * 255.0 - px[i + ch]));
        }
      }
      return mx;
    };
    for (int f = 0; f < kFilterCount; ++f) {
      for (int variant = 0; variant < 4; ++variant) {
        ColorParams p; p.filterId = f;
        if (variant == 1) { p.brightness = 20; p.contrast = -15; p.saturation = 30; p.temperature = -10; }
        if (variant == 2) p.strength = 60;
        if (variant == 3) { p.strength = 35; p.brightness = -50; p.contrast = 80; p.saturation = -40; p.temperature = 70; }
        worst = std::max(worst, compare(p));
      }
    }
    for (int it = 0; it < 500; ++it) {
      ColorParams p;
      p.filterId = int(rng() % kFilterCount); p.strength = int(rng() % 101);
      p.brightness = int(rng() % 201) - 100; p.contrast = int(rng() % 201) - 100; p.saturation = int(rng() % 201) - 100; p.temperature = int(rng() % 201) - 100;
      worst = std::max(worst, compare(p));
    }
    std::printf("info: colour matrix vs per-pixel colour: largest difference %.2f / 255 (rounding)\n", worst);
    CHECK(worst <= 1.0, "matrix and per-pixel colour agree within 1/255 for all filters and 500 random settings");
    ColorParams bad; bad.filterId = 13;
    float m[16];
    CHECK(colorMatrix(bad, m) == kBadRange, "matrix: filter 13 refused");
    bad = ColorParams(); bad.contrast = 101; CHECK(colorMatrix(bad, m) == kBadRange, "matrix: adjust 101 refused");
    bad = ColorParams(); bad.strength = -1; CHECK(colorMatrix(bad, m) == kBadRange, "matrix: strength -1 refused");
    CHECK(colorMatrix(ColorParams(), nullptr) == kBadBuffer, "matrix: null output");
  }

  // ============================================================ crop to a ratio
  {
    Crop c;
    CHECK(cropForRatio(1920, 1080, 16, 9, &c) == kOk && c.w == 10000 && c.h == 10000 && c.x == 0 && c.y == 0, "same ratio = the whole picture");
    CHECK(cropForRatio(1920, 1080, 1, 1, &c) == kOk && c.h == 10000 && c.w == 5625 && c.x == (10000 - 5625) / 2 && c.y == 0, "16:9 -> 1:1 keeps the full height, centred");
    CHECK(cropForRatio(1080, 1920, 1, 1, &c) == kOk && c.w == 10000 && c.h == 5625 && c.y == (10000 - 5625) / 2, "9:16 -> 1:1 keeps the full width, centred");
    CHECK(cropForRatio(1920, 1080, 9, 16, &c) == kOk && c.h == 10000 && c.w == 3164, "16:9 -> 9:16 (vertical)");
    // the resulting PIXEL shape really has the ratio (checked through clipOutput, the same size the export gets)
    const int64_t sizes[][2] = {{1920, 1080}, {1080, 1920}, {1280, 720}, {3840, 2160}, {1000, 1000}, {720, 1280}, {1600, 1200}};
    const int64_t ratios[][2] = {{1, 1}, {4, 5}, {3, 4}, {9, 16}, {16, 9}};
    bool allOk = true, centred = true;
    for (auto& sz : sizes) for (auto& r : ratios) {
      Crop k;
      if (cropForRatio(sz[0], sz[1], r[0], r[1], &k) != kOk) { allOk = false; continue; }
      ClipSpec spec = clip(sz[0], sz[1]);
      spec.crop = k;
      ClipOut o = clipOutput(spec);
      if (o.code != kOk) { allOk = false; continue; }
      const double want = double(r[0]) / r[1], got = double(o.w) / o.h;
      if (std::abs(got - want) / want > 0.01) allOk = false;
      if (std::llabs(2 * k.x + k.w - kCropFull) > 1 || std::llabs(2 * k.y + k.h - kCropFull) > 1) centred = false;
    }
    CHECK(allOk, "35 picture-size / ratio pairs give the right pixel shape (within 1 percent)");
    CHECK(centred, "the crop is centred");
    CHECK(cropForRatio(1920, 1080, 0, 9, &c) == kBadArgument && cropForRatio(1920, 1080, 16, -1, &c) == kBadArgument && cropForRatio(1920, 1080, 16, 9, nullptr) == kBadArgument,
          "bad ratio / null refused");
    CHECK(cropForRatio(8, 1080, 1, 1, &c) == kBadSize && cropForRatio(9000, 1080, 1, 1, &c) == kBadSize, "bad picture size refused");
    CHECK(cropForRatio(8000, 800, 1, 2, &c) == kOk && c.w == 500 && cropForRatio(8000, 800, 1, 3, &c) == kBadRange, "exactly 5 percent is allowed, under 5 percent is refused");
  }

  // ============================================================ output check
  {
    OutputInfo good{10000000, 1280, 720, 5000000, kContainerMp4, kCodecH264, 1, 1};
    OutputExpect e{10000000, 1280, 720, 50000000, 1};
    OutputCheck r = validateOutput(good, e);
    CHECK(r.code == kOk && r.problems == kProbNone, "a correct file passes");
    OutputInfo o = good; o.playable = 0; CHECK(validateOutput(o, e).problems == kProbNotPlayable, "not playable");
    o = good; o.container = 7; CHECK(validateOutput(o, e).problems == kProbContainer, "wrong container");
    o = good; o.codec = 9; CHECK(validateOutput(o, e).problems == kProbCodec, "wrong codec");
    o = good; o.codec = kCodecHevc; CHECK(validateOutput(o, e).code == kOk, "HEVC accepted");
    o = good; o.durationUs = 10140000; CHECK(validateOutput(o, e).code == kOk, "0.14 s off is inside the tolerance");
    o = good; o.durationUs = 10300000; CHECK(validateOutput(o, e).problems == kProbDuration, "0.3 s off on 10 s is refused");
    o = good; o.durationUs = 0; CHECK((validateOutput(o, e).problems & kProbDuration) != 0, "zero duration");
    OutputExpect longE{600000000, 1280, 720, 0, 1}; OutputInfo longO = good; longO.durationUs = 611000000;
    CHECK(validateOutput(longO, longE).code == kOk, "2 percent tolerance on a long video (11 s of 600 s)");
    longO.durationUs = 613000000; CHECK(validateOutput(longO, longE).problems == kProbDuration, "13 s of 600 s is refused");
    o = good; o.w = 1920; CHECK(validateOutput(o, e).problems == kProbSize, "wrong width");
    o = good; o.h = 1280; CHECK(validateOutput(o, e).problems == kProbSize, "wrong height");
    o = good; o.sizeBytes = 0; CHECK(validateOutput(o, e).problems == kProbFileSize, "empty file");
    o = good; o.sizeBytes = 60000000; CHECK(validateOutput(o, e).problems == kProbFileSize, "file too big");
    o = good; o.hasAudio = 0; CHECK(validateOutput(o, e).problems == kProbAudio, "audio lost");
    OutputExpect silent{10000000, 1280, 720, 50000000, 0}; CHECK(validateOutput(good, silent).problems == kProbAudio, "audio appeared that the user did not ask for");
    o = good; o.playable = 0; o.w = 1; o.hasAudio = 0;
    int p = validateOutput(o, e).problems;
    CHECK((p & kProbNotPlayable) && (p & kProbSize) && (p & kProbAudio) && validateOutput(o, e).code == kOutputMismatch, "several problems are all reported");
  }

  // ============================================================ session
  {
    Session s;
    CHECK(!s.cancelled() && s.progress() == 0, "new session");
    s.setProgress(-5); CHECK(s.progress() == 0, "progress floor");
    s.setProgress(5000); CHECK(s.progress() == 1000, "progress ceiling");
    s.setProgress(420); CHECK(s.progress() == 420, "progress value");
    s.cancel(); CHECK(s.cancelled(), "cancel flag");
  }

  // ============================================================ speed (information only, this computer - NOT a phone)
  {
    const int64_t w = 1920, h = 1080;
    std::vector<uint8_t> a(size_t(w * h * 4), 120), b(size_t(w * h * 4));
    ColorParams p; p.filterId = 7; p.brightness = 10;
    auto t0 = std::chrono::steady_clock::now();
    applyColor(a.data(), a.size(), w, h, p, nullptr);
    auto t1 = std::chrono::steady_clock::now();
    int64_t ow, oh;
    transformFrame(a.data(), a.size(), w, h, 90, 1, Crop{0, 0, kCropFull, kCropFull}, b.data(), b.size(), &ow, &oh, nullptr);
    auto t2 = std::chrono::steady_clock::now();
    std::printf("info: 1080p frame on THIS computer (sanitizers on, not a phone): colour %.1f ms, turn+mirror %.1f ms\n",
                std::chrono::duration<double, std::milli>(t1 - t0).count(), std::chrono::duration<double, std::milli>(t2 - t1).count());
  }

  // ============================================================ cut parts out (Step 81)
  {
    std::vector<Segment> out;
    auto S = [](int64_t a, int64_t b) { return Segment{a, b}; };
    const int64_t sec = 1000000;
    // no cuts: the whole kept range
    CHECK(cutSegments(0, 10 * sec, nullptr, 0, &out) == kOk && out.size() == 1 && out[0].startUs == 0 && out[0].endUs == 10 * sec, "no cut = one piece");
    int64_t one[] = {3 * sec, 5 * sec};
    CHECK(cutSegments(0, 10 * sec, one, 1, &out) == kOk && out.size() == 2 && out[0].endUs == 3 * sec && out[1].startUs == 5 * sec && out[1].endUs == 10 * sec, "one cut in the middle = two pieces");
    int64_t atStart[] = {0, 2 * sec};
    CHECK(cutSegments(0, 10 * sec, atStart, 1, &out) == kOk && out.size() == 1 && out[0].startUs == 2 * sec, "cut at the start = the video starts later");
    int64_t atEnd[] = {8 * sec, 10 * sec};
    CHECK(cutSegments(0, 10 * sec, atEnd, 1, &out) == kOk && out.size() == 1 && out[0].endUs == 8 * sec, "cut at the end = the video ends sooner");
    // the order of the cuts does not matter; overlapping and touching cuts are merged
    int64_t two[] = {6 * sec, 7 * sec, 2 * sec, 3 * sec};
    CHECK(cutSegments(0, 10 * sec, two, 2, &out) == kOk && out.size() == 3 && out[0].endUs == 2 * sec && out[1].startUs == 3 * sec && out[1].endUs == 6 * sec && out[2].startUs == 7 * sec, "cuts in any order give three pieces in time order");
    int64_t overlap[] = {2 * sec, 5 * sec, 4 * sec, 7 * sec};
    CHECK(cutSegments(0, 10 * sec, overlap, 2, &out) == kOk && out.size() == 2 && out[0].endUs == 2 * sec && out[1].startUs == 7 * sec, "overlapping cuts are merged");
    int64_t touch[] = {2 * sec, 4 * sec, 4 * sec, 6 * sec};
    CHECK(cutSegments(0, 10 * sec, touch, 2, &out) == kOk && out.size() == 2 && out[1].startUs == 6 * sec, "touching cuts are merged (no empty piece between them)");
    // cuts are clamped to the kept range (the trim); a cut outside it is ignored
    int64_t outside[] = {0, 2 * sec, 9 * sec, 12 * sec, 4 * sec, 5 * sec};
    CHECK(cutSegments(3 * sec, 10 * sec, outside, 3, &out) == kOk && out.size() == 2 && out[0].startUs == 3 * sec && out[0].endUs == 4 * sec && out[1].startUs == 5 * sec && out[1].endUs == 9 * sec, "cuts are clamped to the kept range");
    int64_t fully[] = {0, 2 * sec};
    CHECK(cutSegments(3 * sec, 10 * sec, fully, 1, &out) == kOk && out.size() == 1 && out[0].startUs == 3 * sec && out[0].endUs == 10 * sec, "a cut fully outside the kept range changes nothing");
    // total length = range - cut parts
    int64_t many[] = {sec, 2 * sec, 4 * sec, 5 * sec, 7 * sec, 9 * sec};
    CHECK(cutSegments(0, 10 * sec, many, 3, &out) == kOk, "three cuts");
    int64_t total = 0; for (const Segment& k : out) total += k.endUs - k.startUs;
    CHECK(total == 10 * sec - 4 * sec, "the pieces add up to the range minus the cut parts");
    // refusals
    int64_t tiny[] = {sec, 10 * sec - 50000};
    CHECK(cutSegments(0, 10 * sec, tiny, 1, &out) == kBadRange, "a piece under 0.1 s at the end is refused");
    int64_t tiny2[] = {50000, 5 * sec};
    CHECK(cutSegments(0, 10 * sec, tiny2, 1, &out) == kBadRange, "a piece under 0.1 s at the start is refused");
    int64_t tiny3[] = {sec, 2 * sec, 2 * sec + 99999, 5 * sec};
    CHECK(cutSegments(0, 10 * sec, tiny3, 2, &out) == kBadRange, "a piece under 0.1 s between two cuts is refused");
    int64_t okEdge[] = {sec, 2 * sec, 2 * sec + 100000, 5 * sec};
    CHECK(cutSegments(0, 10 * sec, okEdge, 2, &out) == kOk, "a piece of exactly 0.1 s is allowed");
    int64_t all[] = {0, 10 * sec};
    CHECK(cutSegments(0, 10 * sec, all, 1, &out) == kBadRange, "cutting everything is refused");
    int64_t back[] = {5 * sec, 3 * sec};
    CHECK(cutSegments(0, 10 * sec, back, 1, &out) == kBadRange, "a backwards cut is refused");
    int64_t empty[] = {5 * sec, 5 * sec};
    CHECK(cutSegments(0, 10 * sec, empty, 1, &out) == kBadRange, "an empty cut is refused");
    int64_t neg[] = {-1, 5 * sec};
    CHECK(cutSegments(0, 10 * sec, neg, 1, &out) == kBadRange, "a negative time is refused");
    CHECK(cutSegments(0, 50000, nullptr, 0, &out) == kBadRange && cutSegments(-1, 10 * sec, nullptr, 0, &out) == kBadRange, "a kept range under 0.1 s / negative is refused");
    CHECK(cutSegments(0, 10 * sec, one, 1, nullptr) == kBadArgument && cutSegments(0, 10 * sec, nullptr, 1, &out) == kBadArgument, "null refused");
    int64_t nine[18]; for (int i = 0; i < 9; ++i) { nine[2 * i] = (2 * i + 1) * sec; nine[2 * i + 1] = (2 * i + 1) * sec + 500000; }
    CHECK(cutSegments(0, 30 * sec, nine, 9, &out) == kBadArgument && cutSegments(0, 30 * sec, nine, 8, &out) == kOk && out.size() == 9, "8 cuts allowed, 9 refused");
    // the pieces never overlap, never leave the range, are in time order and are never shorter than 0.1 s (random cuts)
    bool good = true;
    unsigned seed = 12345;
    auto rnd = [&seed]() { seed = seed * 1103515245u + 12345u; return (seed >> 8) & 0xFFFFFF; };
    for (int round = 0; round < 3000; ++round) {
      int n = rnd() % 9;  // 0..8
      int64_t cuts[16];
      for (int i = 0; i < n; ++i) { int64_t a = rnd() % 20000000, b = a + 1 + rnd() % 6000000; cuts[2 * i] = a; cuts[2 * i + 1] = b; }
      int64_t ts = rnd() % 3000000, te = ts + 100000 + rnd() % 15000000;
      std::vector<Segment> r;
      int code = cutSegments(ts, te, n ? cuts : nullptr, n, &r);
      if (code == kOk) {
        int64_t prevEnd = ts;
        for (const Segment& k : r) {
          if (k.startUs < prevEnd || k.endUs > te || k.endUs - k.startUs < kMinPieceUs) good = false;
          prevEnd = k.endUs;
          for (int i = 0; i < n; ++i) if (k.startUs < cuts[2 * i + 1] && k.endUs > cuts[2 * i]) good = false;  // a kept piece never overlaps a cut
        }
      } else if (code != kBadRange) good = false;
    }
    CHECK(good, "3000 random cut sets: pieces in order, inside the range, never under 0.1 s, never overlapping a cut");
    (void)S;
  }

  // ============================================================ text on the video (Step 80)
  {
    int64_t px = 0, l = 0, t = 0;
    CHECK(textFontPx(1080, 1920, 60, &px) == kOk && px == 65, "font = 6 percent of the SHORT side (1080 -> 65 px)");
    CHECK(textFontPx(1920, 1080, 60, &px) == kOk && px == 65, "landscape: the short side is the height, same size");
    CHECK(textFontPx(720, 1280, 150, &px) == kOk && px == 108 && textFontPx(720, 1280, 20, &px) == kOk && px == 14, "smallest and biggest size");
    CHECK(textFontPx(16, 16, 20, &px) == kOk && px == kTextMinFontPx, "never under the minimum font");
    CHECK(textFontPx(1080, 1920, 19, &px) == kBadRange && textFontPx(1080, 1920, 151, &px) == kBadRange, "size outside the range refused");
    CHECK(textFontPx(8, 1920, 60, &px) == kBadSize && textFontPx(9000, 1920, 60, &px) == kBadSize && textFontPx(1080, 1920, 60, nullptr) == kBadArgument, "bad picture / null refused");
    // centre of the picture: the box is centred
    CHECK(textPlacement(1000, 2000, 500, 500, 400, 100, &l, &t) == kOk && l == 300 && t == 950, "centre of the picture");
    // too far left / top / right / bottom: pulled inside the 2 percent margin (20 px on a 1000 px short side)
    CHECK(textPlacement(1000, 2000, 0, 0, 400, 100, &l, &t) == kOk && l == 20 && t == 20, "top-left is kept inside the margin");
    CHECK(textPlacement(1000, 2000, 1000, 1000, 400, 100, &l, &t) == kOk && l == 1000 - 20 - 400 && t == 2000 - 20 - 100, "bottom-right is kept inside the margin");
    CHECK(textPlacement(1000, 2000, 500, 800, 960, 100, &l, &t) == kOk && l == 20, "a box exactly as wide as the margins allow fits");
    CHECK(textPlacement(1000, 2000, 500, 500, 961, 100, &l, &t) == kBadRange && textPlacement(1000, 2000, 500, 500, 100, 1961, &l, &t) == kBadRange, "a box that does not fit is refused (the font is made smaller)");
    CHECK(textPlacement(1000, 2000, -1, 500, 100, 100, &l, &t) == kBadRange && textPlacement(1000, 2000, 500, 1001, 100, 100, &l, &t) == kBadRange, "centre outside 0..1000 refused");
    CHECK(textPlacement(1000, 2000, 500, 500, 0, 100, &l, &t) == kBadArgument && textPlacement(1000, 2000, 500, 500, 100, 100, nullptr, &t) == kBadArgument, "empty box / null refused");
    CHECK(textPlacement(8, 2000, 500, 500, 4, 4, &l, &t) == kBadSize, "bad picture size refused");
    // the box is ALWAYS inside the picture, for every corner of the position grid and several shapes
    bool inside = true;
    const int64_t frames[][2] = {{1080, 1920}, {1920, 1080}, {720, 1280}, {1000, 1000}, {3840, 2160}, {16, 16}};
    for (auto& f : frames) for (int cx = 0; cx <= 1000; cx += 125) for (int cy = 0; cy <= 1000; cy += 125) for (int64_t bw : {1, 50, 300}) for (int64_t bh : {1, 40, 200}) {
      int code = textPlacement(f[0], f[1], cx, cy, bw, bh, &l, &t);
      if (code == kBadRange) continue;  // too big for a tiny frame: refused, never placed outside
      if (code != kOk || l < 0 || t < 0 || l + bw > f[0] || t + bh > f[1]) inside = false;
    }
    CHECK(inside, "over 1000 frame / position / box combinations: the box never leaves the picture");
  }

  std::printf("%d checks, %d failed\n", g_checks, g_failed);
  return g_failed == 0 ? 0 : 1;
}
