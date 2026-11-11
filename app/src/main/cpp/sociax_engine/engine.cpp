// SociaX native video-editing engine (plan v4.0.3). See engine.h. Pure C++17, no Android classes.
#include "engine.h"

#include <algorithm>
#include <cmath>

namespace sociax {

const char* codeName(int code) {
  switch (code) {
    case kOk: return "ok";
    case kBadArgument: return "bad_argument";
    case kBadRange: return "bad_range";
    case kBadSize: return "bad_size";
    case kCancelled: return "cancelled";
    case kUnsupported: return "unsupported";
    case kBadBuffer: return "bad_buffer";
    case kInternal: return "internal";
    case kOutputMismatch: return "output_mismatch";
    default: return "unknown";
  }
}

static bool isRot(int64_t r) { return r == 0 || r == 90 || r == 180 || r == 270; }
static int64_t even(int64_t v) { return v & ~static_cast<int64_t>(1); }

// ------------------------------------------------------------------ clips
bool specFromLongs(const int64_t* a, size_t n, ClipSpec* out) {
  if (a == nullptr || out == nullptr || n != static_cast<size_t>(kSpecLongs)) return false;
  out->srcW = a[0]; out->srcH = a[1]; out->metaRotation = a[2]; out->turn = a[3]; out->flipH = a[4];
  out->crop = Crop{a[5], a[6], a[7], a[8]};
  out->srcDurationUs = a[9]; out->trimStartUs = a[10]; out->trimEndUs = a[11]; out->speedMilli = a[12]; out->hasAudio = a[13];
  return true;
}

void specToLongs(const ClipSpec& c, int64_t* o) {
  o[0] = c.srcW; o[1] = c.srcH; o[2] = c.metaRotation; o[3] = c.turn; o[4] = c.flipH;
  o[5] = c.crop.x; o[6] = c.crop.y; o[7] = c.crop.w; o[8] = c.crop.h;
  o[9] = c.srcDurationUs; o[10] = c.trimStartUs; o[11] = c.trimEndUs; o[12] = c.speedMilli; o[13] = c.hasAudio;
}

int64_t totalRotation(const ClipSpec& c) { return (c.metaRotation + c.turn) % 360; }

static ClipOut fail(int code) { return ClipOut{code, 0, 0, 0}; }

ClipOut clipOutput(const ClipSpec& c) {
  if (c.srcW < kMinSide || c.srcH < kMinSide || c.srcW > kMaxSide || c.srcH > kMaxSide) return fail(kBadSize);
  if (!isRot(c.metaRotation) || !isRot(c.turn) || (c.flipH != 0 && c.flipH != 1) || (c.hasAudio != 0 && c.hasAudio != 1))
    return fail(kBadArgument);
  const Crop& k = c.crop;
  if (k.w < kCropMin || k.h < kCropMin || k.w > kCropFull || k.h > kCropFull || k.x < 0 || k.y < 0 || k.x + k.w > kCropFull ||
      k.y + k.h > kCropFull)
    return fail(kBadRange);
  if (c.srcDurationUs <= 0 || c.trimStartUs < 0 || c.trimStartUs >= c.trimEndUs || c.trimEndUs > c.srcDurationUs ||
      c.trimEndUs - c.trimStartUs < kMinPieceUs)
    return fail(kBadRange);
  if (c.speedMilli < kSpeedMinMilli || c.speedMilli > kSpeedMaxMilli) return fail(kBadRange);
  const int64_t rot = totalRotation(c);
  const bool swap = (rot == 90 || rot == 270);
  const int64_t dw = swap ? c.srcH : c.srcW;
  const int64_t dh = swap ? c.srcW : c.srcH;
  const int64_t ow = even(dw * k.w / kCropFull);
  const int64_t oh = even(dh * k.h / kCropFull);
  if (ow < kMinSide || oh < kMinSide) return fail(kBadSize);
  const int64_t dur = (c.trimEndUs - c.trimStartUs) * 1000 / c.speedMilli;
  return ClipOut{kOk, ow, oh, dur};
}

int validateClip(const ClipSpec& c) { return clipOutput(c).code; }

SplitResult splitClip(const ClipSpec& c, int64_t at) {
  SplitResult r{kOk, c, c};
  const int v = validateClip(c);
  if (v != kOk) { r.code = v; return r; }
  if (at < c.trimStartUs + kMinPieceUs || at > c.trimEndUs - kMinPieceUs) { r.code = kBadRange; return r; }
  r.left.trimEndUs = at;
  r.right.trimStartUs = at;
  return r;
}

bool isPermutation(const int32_t* order, size_t n) {
  if (order == nullptr || n == 0 || n > static_cast<size_t>(kMaxClips)) return false;
  std::vector<char> seen(n, 0);
  for (size_t i = 0; i < n; ++i) {
    if (order[i] < 0 || static_cast<size_t>(order[i]) >= n || seen[order[i]]) return false;
    seen[order[i]] = 1;
  }
  return true;
}

int audioPolicy(const ClipSpec& c) {
  if (!c.hasAudio) return kAudioNone;
  return (c.speedMilli >= 500 && c.speedMilli <= 2000) ? kAudioKeep : kAudioMute;
}

// ------------------------------------------------------------------ export plan
int availablePresets(int64_t shortSide, const DeviceCaps& caps) {
  int mask = 1;  // the original size can always be offered
  if (shortSide >= 720) mask |= 2;
  if (shortSide >= 1080 && caps.supports1080) mask |= 4;
  if (shortSide >= 2160 && caps.supports4k) mask |= 8;
  return mask;
}

static int presetBit(int preset) {
  switch (preset) {
    case kPresetOriginal: return 1;
    case kPreset720: return 2;
    case kPreset1080: return 4;
    case kPreset2160: return 8;
    default: return 0;
  }
}

ExportPlan exportPlan(const ClipSpec* clips, size_t n, int preset, const DeviceCaps& caps) {
  ExportPlan plan;
  if (clips == nullptr || n == 0 || n > static_cast<size_t>(kMaxClips) || presetBit(preset) == 0 || caps.maxSide < kMinSide) {
    plan.code = kBadArgument;
    return plan;
  }
  std::vector<ClipOut> outs;
  outs.reserve(n);
  for (size_t i = 0; i < n; ++i) {
    ClipOut o = clipOutput(clips[i]);
    if (o.code != kOk) { plan.code = o.code; plan.failedClip = static_cast<int>(i); return plan; }
    outs.push_back(o);
    plan.totalUs += o.durationUs;
  }
  const int64_t baseW = outs[0].w, baseH = outs[0].h;  // the canvas follows the FIRST clip's shape
  const int64_t baseShort = std::min(baseW, baseH);
  const int64_t limit = std::min<int64_t>(kMaxSide, caps.maxSide);

  // walk down from the requested preset to the first one this source + phone really support
  static const int order[] = {kPreset2160, kPreset1080, kPreset720};
  int chosen = -1;
  if (preset == kPresetOriginal) {
    chosen = 0;
  } else {
    const int mask = availablePresets(baseShort, caps);
    for (int cand : order) {
      if (cand > preset) continue;
      if (!(mask & presetBit(cand))) continue;
      const int64_t cw = even((baseW * cand + baseShort / 2) / baseShort), ch = even((baseH * cand + baseShort / 2) / baseShort);
      if (std::max(cw, ch) > limit) continue;
      chosen = cand;
      break;
    }
    if (chosen < 0) chosen = 0;  // nothing lower fits: keep the original size
  }
  plan.downgraded = (chosen != preset) ? 1 : 0;
  plan.presetUsed = chosen;
  if (chosen == 0) {
    plan.canvasW = baseW; plan.canvasH = baseH;
  } else {
    plan.canvasW = even((baseW * chosen + baseShort / 2) / baseShort);
    plan.canvasH = even((baseH * chosen + baseShort / 2) / baseShort);
  }
  if (std::max(plan.canvasW, plan.canvasH) > limit || plan.canvasW < kMinSide || plan.canvasH < kMinSide) {
    plan.code = kUnsupported;  // even the original is too big for this phone: the caller shows the fallback
    return plan;
  }
  for (const ClipOut& o : outs) {
    const double s = std::min(static_cast<double>(plan.canvasW) / o.w, static_cast<double>(plan.canvasH) / o.h);
    int64_t w = std::min(plan.canvasW, even(static_cast<int64_t>(std::floor(o.w * s))));
    int64_t h = std::min(plan.canvasH, even(static_cast<int64_t>(std::floor(o.h * s))));
    if (w < 2) w = 2;
    if (h < 2) h = 2;
    plan.rects.push_back(Rect{even((plan.canvasW - w) / 2), even((plan.canvasH - h) / 2), w, h});
  }
  return plan;
}

// ------------------------------------------------------------------ session
void Session::setProgress(int permille) { progress_.store(std::max(0, std::min(1000, permille))); }

// ------------------------------------------------------------------ frame transform
int transformFrame(const uint8_t* src, size_t srcCap, int64_t w, int64_t h, int64_t rotation, int flipH, const Crop& crop,
                   uint8_t* dst, size_t dstCap, int64_t* outW, int64_t* outH, Session* session) {
  if (src == nullptr || dst == nullptr || outW == nullptr || outH == nullptr) return kBadBuffer;
  if (w < 1 || h < 1 || w > kMaxSide || h > kMaxSide) return kBadSize;
  if (!isRot(rotation) || (flipH != 0 && flipH != 1)) return kBadArgument;
  if (crop.w < 1 || crop.h < 1 || crop.x < 0 || crop.y < 0 || crop.x + crop.w > kCropFull || crop.y + crop.h > kCropFull)
    return kBadRange;
  if (srcCap < static_cast<size_t>(w) * static_cast<size_t>(h) * 4) return kBadBuffer;
  const bool swap = (rotation == 90 || rotation == 270);
  const int64_t rw = swap ? h : w, rh = swap ? w : h;       // size after the turn
  const int64_t ow = even(rw * crop.w / kCropFull), oh = even(rh * crop.h / kCropFull);
  if (ow < 2 || oh < 2) return kBadSize;
  const int64_t cx0 = rw * crop.x / kCropFull, cy0 = rh * crop.y / kCropFull;
  if (dstCap < static_cast<size_t>(ow) * static_cast<size_t>(oh) * 4) return kBadBuffer;
  for (int64_t j = 0; j < oh; ++j) {
    if ((j & 15) == 0 && session != nullptr) {
      if (session->cancelled()) return kCancelled;
      session->setProgress(static_cast<int>(j * 1000 / oh));
    }
    const int64_t y = cy0 + j;
    for (int64_t i = 0; i < ow; ++i) {
      int64_t x = cx0 + i;
      if (flipH) x = rw - 1 - x;
      int64_t sx, sy;  // where this turned pixel lives in the source
      switch (rotation) {
        case 90: sx = y; sy = h - 1 - x; break;
        case 180: sx = w - 1 - x; sy = h - 1 - y; break;
        case 270: sx = w - 1 - y; sy = x; break;
        default: sx = x; sy = y; break;
      }
      const uint8_t* s = src + (static_cast<size_t>(sy) * static_cast<size_t>(w) + static_cast<size_t>(sx)) * 4;
      uint8_t* d = dst + (static_cast<size_t>(j) * static_cast<size_t>(ow) + static_cast<size_t>(i)) * 4;
      d[0] = s[0]; d[1] = s[1]; d[2] = s[2]; d[3] = s[3];
    }
  }
  *outW = ow;
  *outH = oh;
  if (session != nullptr) session->setProgress(1000);
  return kOk;
}

// ------------------------------------------------------------------ colour
namespace {
struct Look {
  const char* name;
  float sat, contrast, bright, rGain, gGain, bGain, fade;
  bool sepia;
};
const Look kLooks[kFilterCount] = {
    {"Original", 1.00f, 1.00f, 0.00f, 1.00f, 1.00f, 1.00f, 0.00f, false},
    {"Warm", 1.05f, 1.00f, 0.02f, 1.10f, 1.00f, 0.90f, 0.00f, false},
    {"Cool", 1.00f, 1.00f, 0.00f, 0.92f, 1.00f, 1.10f, 0.00f, false},
    {"Vivid", 1.45f, 1.12f, 0.00f, 1.00f, 1.00f, 1.00f, 0.00f, false},
    {"Mono", 0.00f, 1.00f, 0.00f, 1.00f, 1.00f, 1.00f, 0.00f, false},
    {"Noir", 0.00f, 1.35f, -0.05f, 1.00f, 1.00f, 1.00f, 0.00f, false},
    {"Sepia", 1.00f, 1.00f, 0.00f, 1.00f, 1.00f, 1.00f, 0.00f, true},
    {"Vintage", 0.75f, 0.92f, 0.00f, 1.06f, 1.00f, 0.88f, 0.06f, false},
    {"Fade", 0.85f, 1.00f, 0.00f, 1.00f, 1.00f, 1.00f, 0.10f, false},
    {"Dramatic", 1.15f, 1.30f, -0.04f, 1.00f, 1.00f, 1.00f, 0.00f, false},
    {"Golden", 1.10f, 1.00f, 0.00f, 1.12f, 1.04f, 0.85f, 0.00f, false},
    {"Pastel", 0.80f, 0.90f, 0.08f, 1.00f, 1.00f, 1.00f, 0.00f, false},
    {"Night", 0.90f, 1.10f, -0.12f, 1.00f, 1.00f, 1.08f, 0.00f, false},
};
inline float luma(float r, float g, float b) { return 0.2126f * r + 0.7152f * g + 0.0722f * b; }
inline float clamp01(float v) { return v < 0.f ? 0.f : (v > 1.f ? 1.f : v); }
}  // namespace

const char* filterName(int id) { return (id >= 0 && id < kFilterCount) ? kLooks[id].name : ""; }

int applyColor(uint8_t* px, size_t cap, int64_t w, int64_t h, const ColorParams& p, Session* session) {
  if (px == nullptr) return kBadBuffer;
  if (w < 1 || h < 1 || w > kMaxSide || h > kMaxSide) return kBadSize;
  if (p.filterId < 0 || p.filterId >= kFilterCount || p.strength < 0 || p.strength > 100) return kBadRange;
  const int adj[4] = {p.brightness, p.contrast, p.saturation, p.temperature};
  for (int v : adj) if (v < -100 || v > 100) return kBadRange;
  if (cap < static_cast<size_t>(w) * static_cast<size_t>(h) * 4) return kBadBuffer;
  const bool noFilter = (p.filterId == 0 || p.strength == 0);
  if (noFilter && p.brightness == 0 && p.contrast == 0 && p.saturation == 0 && p.temperature == 0) return kOk;  // untouched

  const Look& L = kLooks[p.filterId];
  const float mix = noFilter ? 0.f : p.strength / 100.f;
  const float dB = p.brightness / 100.f * 0.30f;
  const float kC = 1.f + p.contrast / 100.f * 0.60f;
  const float kS = 1.f + p.saturation / 100.f;
  const float dT = p.temperature / 100.f * 0.12f;

  for (int64_t y = 0; y < h; ++y) {
    if ((y & 15) == 0 && session != nullptr) {
      if (session->cancelled()) return kCancelled;
      session->setProgress(static_cast<int>(y * 1000 / h));
    }
    uint8_t* row = px + static_cast<size_t>(y) * static_cast<size_t>(w) * 4;
    for (int64_t x = 0; x < w; ++x) {
      uint8_t* q = row + x * 4;
      const float r0 = q[0] / 255.f, g0 = q[1] / 255.f, b0 = q[2] / 255.f;
      float r = r0, g = g0, b = b0;
      if (mix > 0.f) {
        float fr, fg, fb;
        if (L.sepia) {
          fr = 0.393f * r0 + 0.769f * g0 + 0.189f * b0;
          fg = 0.349f * r0 + 0.686f * g0 + 0.168f * b0;
          fb = 0.272f * r0 + 0.534f * g0 + 0.131f * b0;
        } else {
          const float l = luma(r0, g0, b0);
          fr = l + (r0 - l) * L.sat; fg = l + (g0 - l) * L.sat; fb = l + (b0 - l) * L.sat;
          fr = (fr - 0.5f) * L.contrast + 0.5f; fg = (fg - 0.5f) * L.contrast + 0.5f; fb = (fb - 0.5f) * L.contrast + 0.5f;
          fr = fr * L.rGain + L.bright; fg = fg * L.gGain + L.bright; fb = fb * L.bGain + L.bright;
          fr = fr * (1.f - L.fade) + L.fade; fg = fg * (1.f - L.fade) + L.fade; fb = fb * (1.f - L.fade) + L.fade;
        }
        r = r0 + (fr - r0) * mix; g = g0 + (fg - g0) * mix; b = b0 + (fb - b0) * mix;  // no clamp here: clamped ONCE at the end, so it equals colorMatrix()
      }
      // Adjust: brightness -> contrast -> saturation -> temperature
      r += dB; g += dB; b += dB;
      r = (r - 0.5f) * kC + 0.5f; g = (g - 0.5f) * kC + 0.5f; b = (b - 0.5f) * kC + 0.5f;
      const float l2 = luma(r, g, b);
      r = l2 + (r - l2) * kS; g = l2 + (g - l2) * kS; b = l2 + (b - l2) * kS;
      r += dT; b -= dT;
      q[0] = static_cast<uint8_t>(clamp01(r) * 255.f + 0.5f);
      q[1] = static_cast<uint8_t>(clamp01(g) * 255.f + 0.5f);
      q[2] = static_cast<uint8_t>(clamp01(b) * 255.f + 0.5f);
      // q[3] (alpha) is never touched
    }
  }
  if (session != nullptr) session->setProgress(1000);
  return kOk;
}

// ------------------------------------------------------------------ one colour matrix + ratio crop
namespace {
struct Aff {
  float m[3][3];
  float o[3];
};
Aff affIdentity() {
  Aff a{};
  for (int i = 0; i < 3; ++i) a.m[i][i] = 1.f;
  return a;
}
// "a first, then b":  out = b.m * (a.m * x + a.o) + b.o
Aff affThen(const Aff& a, const Aff& b) {
  Aff r{};
  for (int i = 0; i < 3; ++i) {
    for (int j = 0; j < 3; ++j) {
      float v = 0.f;
      for (int k = 0; k < 3; ++k) v += b.m[i][k] * a.m[k][j];
      r.m[i][j] = v;
    }
    float t = b.o[i];
    for (int k = 0; k < 3; ++k) t += b.m[i][k] * a.o[k];
    r.o[i] = t;
  }
  return r;
}
Aff affSaturation(float k) {  // x' = luma + (x - luma) * k
  const float lc[3] = {0.2126f, 0.7152f, 0.0722f};
  Aff a{};
  for (int i = 0; i < 3; ++i)
    for (int j = 0; j < 3; ++j) a.m[i][j] = (i == j ? k : 0.f) + (1.f - k) * lc[j];
  return a;
}
Aff affContrast(float k) {  // x' = (x - 0.5) * k + 0.5
  Aff a = affIdentity();
  for (int i = 0; i < 3; ++i) { a.m[i][i] = k; a.o[i] = 0.5f * (1.f - k); }
  return a;
}
Aff affGain(float r, float g, float b) {
  Aff a{};
  a.m[0][0] = r; a.m[1][1] = g; a.m[2][2] = b;
  return a;
}
Aff affOffset(float r, float g, float b) {
  Aff a = affIdentity();
  a.o[0] = r; a.o[1] = g; a.o[2] = b;
  return a;
}
Aff affFade(float f) {  // x' = x * (1 - f) + f
  Aff a = affIdentity();
  for (int i = 0; i < 3; ++i) { a.m[i][i] = 1.f - f; a.o[i] = f; }
  return a;
}
}  // namespace

int colorMatrix(const ColorParams& p, float out[16]) {
  if (out == nullptr) return kBadBuffer;
  if (p.filterId < 0 || p.filterId >= kFilterCount || p.strength < 0 || p.strength > 100) return kBadRange;
  const int adj[4] = {p.brightness, p.contrast, p.saturation, p.temperature};
  for (int v : adj) if (v < -100 || v > 100) return kBadRange;
  const Look& L = kLooks[p.filterId];
  const bool noFilter = (p.filterId == 0 || p.strength == 0);
  const float mix = noFilter ? 0.f : p.strength / 100.f;

  Aff total = affIdentity();
  if (mix > 0.f) {
    Aff f;
    if (L.sepia) {
      f = Aff{};
      const float s[3][3] = {{0.393f, 0.769f, 0.189f}, {0.349f, 0.686f, 0.168f}, {0.272f, 0.534f, 0.131f}};
      for (int i = 0; i < 3; ++i) for (int j = 0; j < 3; ++j) f.m[i][j] = s[i][j];
    } else {
      f = affSaturation(L.sat);
      f = affThen(f, affContrast(L.contrast));
      f = affThen(f, affGain(L.rGain, L.gGain, L.bGain));
      f = affThen(f, affOffset(L.bright, L.bright, L.bright));
      f = affThen(f, affFade(L.fade));
    }
    for (int i = 0; i < 3; ++i) {
      for (int j = 0; j < 3; ++j) total.m[i][j] = mix * f.m[i][j] + (1.f - mix) * (i == j ? 1.f : 0.f);
      total.o[i] = mix * f.o[i];
    }
  }
  const float dB = p.brightness / 100.f * 0.30f;
  const float kC = 1.f + p.contrast / 100.f * 0.60f;
  const float kS = 1.f + p.saturation / 100.f;
  const float dT = p.temperature / 100.f * 0.12f;
  total = affThen(total, affOffset(dB, dB, dB));
  total = affThen(total, affContrast(kC));
  total = affThen(total, affSaturation(kS));
  total = affThen(total, affOffset(dT, 0.f, -dT));

  for (int i = 0; i < 16; ++i) out[i] = 0.f;
  for (int row = 0; row < 3; ++row) {
    for (int col = 0; col < 3; ++col) out[col * 4 + row] = total.m[row][col];
    out[12 + row] = total.o[row];
  }
  out[15] = 1.f;
  return kOk;
}

int cropForRatio(int64_t dispW, int64_t dispH, int64_t ratioW, int64_t ratioH, Crop* out) {
  if (out == nullptr || ratioW < 1 || ratioH < 1 || ratioW > 1000 || ratioH > 1000) return kBadArgument;
  if (dispW < kMinSide || dispH < kMinSide || dispW > kMaxSide || dispH > kMaxSide) return kBadSize;
  int64_t w = kCropFull, h = kCropFull;
  if (dispW * ratioH >= dispH * ratioW) {  // the picture is wider than the ratio: keep the full height, narrow the width
    const int64_t den = ratioH * dispW;
    w = (static_cast<int64_t>(kCropFull) * dispH * ratioW + den / 2) / den;
  } else {  // taller: keep the full width, shorten the height
    const int64_t den = ratioW * dispH;
    h = (static_cast<int64_t>(kCropFull) * dispW * ratioH + den / 2) / den;
  }
  if (w > kCropFull) w = kCropFull;
  if (h > kCropFull) h = kCropFull;
  if (w < kCropMin || h < kCropMin) return kBadRange;
  *out = Crop{(kCropFull - w) / 2, (kCropFull - h) / 2, w, h};
  return kOk;
}

// ------------------------------------------------------------------ cut parts out
int cutSegments(int64_t trimStartUs, int64_t trimEndUs, const int64_t* cuts, size_t nCuts, std::vector<Segment>* out) {
  if (out == nullptr || (nCuts > 0 && cuts == nullptr) || nCuts > static_cast<size_t>(kMaxCuts)) return kBadArgument;
  if (trimStartUs < 0 || trimEndUs - trimStartUs < kMinPieceUs) return kBadRange;
  std::vector<Segment> c;
  for (size_t i = 0; i < nCuts; ++i) {
    int64_t from = cuts[2 * i], to = cuts[2 * i + 1];
    if (from < 0 || to <= from) return kBadRange;   // backwards or empty
    if (from < trimStartUs) from = trimStartUs;      // clamp to the kept range
    if (to > trimEndUs) to = trimEndUs;
    if (to > from) c.push_back(Segment{from, to});   // a cut fully outside the range is simply ignored
  }
  std::sort(c.begin(), c.end(), [](const Segment& a, const Segment& b) { return a.startUs < b.startUs; });
  std::vector<Segment> merged;
  for (const Segment& s : c) {
    if (!merged.empty() && s.startUs <= merged.back().endUs) {
      if (s.endUs > merged.back().endUs) merged.back().endUs = s.endUs;
    } else {
      merged.push_back(s);
    }
  }
  std::vector<Segment> keep;
  int64_t pos = trimStartUs;
  for (const Segment& m : merged) {
    if (m.startUs > pos) keep.push_back(Segment{pos, m.startUs});
    pos = m.endUs;
  }
  if (pos < trimEndUs) keep.push_back(Segment{pos, trimEndUs});
  if (keep.empty()) return kBadRange;                // everything was cut
  for (const Segment& k : keep) if (k.endUs - k.startUs < kMinPieceUs) return kBadRange;
  *out = keep;
  return kOk;
}

// ------------------------------------------------------------------ text on the video
int textFontPx(int64_t frameW, int64_t frameH, int64_t sizePermille, int64_t* outPx) {
  if (outPx == nullptr) return kBadArgument;
  if (frameW < kMinSide || frameH < kMinSide || frameW > kMaxSide || frameH > kMaxSide) return kBadSize;
  if (sizePermille < kTextSizeMinPermille || sizePermille > kTextSizeMaxPermille) return kBadRange;
  const int64_t shortSide = frameW < frameH ? frameW : frameH;
  int64_t px = (shortSide * sizePermille + 500) / 1000;
  if (px < kTextMinFontPx) px = kTextMinFontPx;
  *outPx = px;
  return kOk;
}

int textPlacement(int64_t frameW, int64_t frameH, int64_t cxPermille, int64_t cyPermille, int64_t boxW, int64_t boxH,
                  int64_t* outLeft, int64_t* outTop) {
  if (outLeft == nullptr || outTop == nullptr || boxW < 1 || boxH < 1) return kBadArgument;
  if (frameW < kMinSide || frameH < kMinSide || frameW > kMaxSide || frameH > kMaxSide) return kBadSize;
  if (cxPermille < 0 || cxPermille > 1000 || cyPermille < 0 || cyPermille > 1000) return kBadRange;
  const int64_t shortSide = frameW < frameH ? frameW : frameH;
  const int64_t margin = (shortSide * kTextMarginPermille) / 1000;
  if (boxW > frameW - 2 * margin || boxH > frameH - 2 * margin) return kBadRange;
  int64_t left = (frameW * cxPermille) / 1000 - boxW / 2;
  int64_t top = (frameH * cyPermille) / 1000 - boxH / 2;
  if (left < margin) left = margin;
  if (left > frameW - margin - boxW) left = frameW - margin - boxW;
  if (top < margin) top = margin;
  if (top > frameH - margin - boxH) top = frameH - margin - boxH;
  *outLeft = left;
  *outTop = top;
  return kOk;
}

// ------------------------------------------------------------------ output check
OutputCheck validateOutput(const OutputInfo& o, const OutputExpect& e) {
  int prob = kProbNone;
  if (!o.playable) prob |= kProbNotPlayable;
  if (o.container != kContainerMp4) prob |= kProbContainer;
  if (o.codec != kCodecH264 && o.codec != kCodecHevc) prob |= kProbCodec;
  const int64_t tol = std::max<int64_t>(150000, e.durationUs / 50);  // 0.15 s or 2 %, whichever is larger
  const int64_t diff = o.durationUs > e.durationUs ? o.durationUs - e.durationUs : e.durationUs - o.durationUs;
  if (o.durationUs <= 0 || diff > tol) prob |= kProbDuration;
  if (o.w != e.w || o.h != e.h) prob |= kProbSize;
  if (o.sizeBytes <= 0 || (e.maxBytes > 0 && o.sizeBytes > e.maxBytes)) prob |= kProbFileSize;
  if ((e.expectAudio != 0) != (o.hasAudio != 0)) prob |= kProbAudio;
  return OutputCheck{prob == kProbNone ? kOk : kOutputMismatch, prob};
}

}  // namespace sociax
