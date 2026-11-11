// SociaX native video-editing engine (plan v4.0.3, section 2-3). Step 77: ENGINE CORE.
// Pure C++17. NO Android, NO JNI, NO Media3 in this file, so it can be compiled and tested on any computer
// (tools/engine_test.cpp). Kotlin owns the screens; this engine owns the maths and the pixel work.
// Nothing here opens, changes or deletes the user's media: it only computes from numbers / buffers it is given.
#pragma once
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace sociax {

constexpr int kApiVersion = 4;  // bump when the JNI layout (spec / plan arrays) changes; Kotlin checks it

// Structured result codes (plan 4.0.3 section 2: "reports structured success/error results"). Never an exception.
enum Code : int {
  kOk = 0,
  kBadArgument = 1,    // a value that makes no sense (unknown rotation, null pointer, empty list)
  kBadRange = 2,       // a number outside the allowed range (trim, speed, crop, strength)
  kBadSize = 3,        // picture too small / too large, or output smaller than an encoder can make
  kCancelled = 4,      // the user cancelled; the half-finished buffer must be thrown away
  kUnsupported = 5,    // this phone / these limits cannot do it (caller shows a fallback)
  kBadBuffer = 6,      // buffer missing or too small
  kInternal = 7,       // unexpected native failure (caught; the app keeps running)
  kOutputMismatch = 8  // the exported file does not match what the user chose
};
const char* codeName(int code);

constexpr int64_t kMaxSide = 8192;
constexpr int64_t kMinSide = 16;
constexpr int64_t kMinPieceUs = 100000;  // a clip (or a split piece) is never shorter than 0.1 s
constexpr int kSpeedMinMilli = 250;      // 0.25x
constexpr int kSpeedMaxMilli = 4000;     // 4x
constexpr int kCropFull = 10000;         // crop numbers are 1/10000 of the picture
constexpr int kCropMin = 500;            // a crop is never smaller than 5 % of the picture
constexpr int kMaxClips = 100;
constexpr int kSpecLongs = 14;           // length of one clip in the flat array that crosses JNI

struct Crop { int64_t x, y, w, h; };

// One clip on the timeline. Times are microseconds in SOURCE time. Nothing is applied until the user chose it.
struct ClipSpec {
  int64_t srcW = 0, srcH = 0;
  int64_t metaRotation = 0;   // rotation stored in the file (0/90/180/270), applied so the picture is upright
  int64_t turn = 0;           // extra clockwise turn chosen by the user (0/90/180/270)
  int64_t flipH = 0;          // 0 / 1, mirror left-right (after the turn)
  Crop crop{0, 0, kCropFull, kCropFull};  // on the turned picture
  int64_t srcDurationUs = 0;
  int64_t trimStartUs = 0, trimEndUs = 0;  // kept part (source time)
  int64_t speedMilli = 1000;  // 1000 = normal
  int64_t hasAudio = 0;       // 0 / 1
};

// flat array <-> struct (the order is the JNI contract, see VideoEngine.kt)
bool specFromLongs(const int64_t* a, size_t n, ClipSpec* out);
void specToLongs(const ClipSpec& c, int64_t* out);

struct ClipOut { int code; int64_t w, h, durationUs; };
int validateClip(const ClipSpec& c);          // kOk or the first problem
ClipOut clipOutput(const ClipSpec& c);        // size AFTER turn + crop (even numbers), duration AFTER speed
int64_t totalRotation(const ClipSpec& c);     // (metaRotation + turn) % 360

struct SplitResult { int code; ClipSpec left, right; };
SplitResult splitClip(const ClipSpec& c, int64_t atSourceUs);  // not closer than 0.1 s to either end

bool isPermutation(const int32_t* order, size_t n);  // merge order must use every clip exactly once

// Audio behaviour of a speed choice, so the screen can SHOW it before export (plan section 3 "speed").
enum AudioPolicy : int { kAudioNone = 0, kAudioKeep = 1, kAudioMute = 2 };
int audioPolicy(const ClipSpec& c);  // no audio track -> None; 0.5x..2x -> Keep (pitch-corrected); else Mute

// Export presets: short side in pixels. 0 = keep the original size.
enum Preset : int { kPresetOriginal = 0, kPreset720 = 720, kPreset1080 = 1080, kPreset2160 = 2160 };
struct DeviceCaps { int64_t maxSide; int supports1080; int supports4k; };
// which presets may be OFFERED for a source (bit0 original, bit1 720, bit2 1080, bit3 2160). 4K only when the source really is 4K.
int availablePresets(int64_t sourceShortSide, const DeviceCaps& caps);

struct Rect { int64_t x, y, w, h; };
struct ExportPlan {
  int code = kOk;
  int failedClip = -1;          // index of the clip that made the plan fail, or -1
  int64_t canvasW = 0, canvasH = 0, totalUs = 0;
  int presetUsed = 0;
  int downgraded = 0;           // 1 when the chosen preset was not possible and a lower one was used
  std::vector<Rect> rects;      // where each clip sits on the canvas (centred, aspect kept, never stretched)
};
ExportPlan exportPlan(const ClipSpec* clips, size_t n, int preset, const DeviceCaps& caps);

// ---- cancellation + progress (long work runs off the main thread; the UI cancels from the main thread) ----
class Session {
 public:
  void cancel() { cancelled_.store(true); }
  bool cancelled() const { return cancelled_.load(); }
  void setProgress(int permille);
  int progress() const { return progress_.load(); }
 private:
  std::atomic<bool> cancelled_{false};
  std::atomic<int> progress_{0};
};

// ---- real pixel work on RGBA_8888 frames (4 bytes per pixel, row after row, no padding) ----
// turn (clockwise 0/90/180/270) -> optional mirror -> crop. dst must hold outW*outH*4 bytes. On kCancelled dst is unusable.
int transformFrame(const uint8_t* src, size_t srcCap, int64_t w, int64_t h, int64_t rotation, int flipH, const Crop& crop,
                   uint8_t* dst, size_t dstCap, int64_t* outW, int64_t* outH, Session* session);

constexpr int kFilterCount = 13;  // id 0 = Original, 1..12 = real filters
const char* filterName(int id);
struct ColorParams {
  int filterId = 0;       // 0..12
  int strength = 100;     // 0..100 (%), how much of the filter
  int brightness = 0, contrast = 0, saturation = 0, temperature = 0;  // each -100..100
};
// In place. Alpha is never changed. Filter 0 / strength 0 / all zeros = the buffer is left byte-for-byte untouched.
int applyColor(uint8_t* px, size_t cap, int64_t w, int64_t h, const ColorParams& p, Session* session);

// The SAME look as applyColor, as ONE 4x4 colour matrix (OpenGL column-major: out[col*4+row]; rgb' = M * (r, g, b, 1)), so the GPU
// (Media3 RgbMatrix) can show it in the preview and in the export. All filters and Adjust steps are straight-line (affine) maths and applyColor clamps to 0..255 only ONCE, at the end,
// so applyColor and this matrix give the same colour (to within rounding, 1/255).
int colorMatrix(const ColorParams& p, float out[16]);

// Biggest centred crop of the (already turned) picture that has the ratio ratioW:ratioH. Numbers are 1/10000 of the picture.
// Same ratio as the picture -> the full picture. kBadRange if the result would be under the 5 % minimum.
int cropForRatio(int64_t dispW, int64_t dispH, int64_t ratioW, int64_t ratioH, Crop* out);

// ---- cut parts out of a video (Step 81) ----
constexpr int kMaxCuts = 8;
struct Segment { int64_t startUs, endUs; };
// The pieces that stay when the parts in [cuts] (pairs fromUs, toUs in SOURCE time, nCuts pairs) are removed from the kept range
// [trimStartUs, trimEndUs]. Cuts are clamped to the range, empty ones dropped, overlapping / touching ones merged, order does not matter.
// Result: the remaining pieces in order, each at least kMinPieceUs long. kBadRange when a cut is backwards / a piece would be shorter than
// 0.1 s / nothing would be left; kBadArgument for a null pointer or more than kMaxCuts cuts.
int cutSegments(int64_t trimStartUs, int64_t trimEndUs, const int64_t* cuts, size_t nCuts, std::vector<Segment>* out);

// ---- text on the video (Step 80): the size and the place of one line block, so preview and export agree ----
constexpr int kTextSizeMinPermille = 20;    // text size = this many thousandths of the picture's SHORT side
constexpr int kTextSizeMaxPermille = 150;
constexpr int kTextMarginPermille = 20;     // the text box keeps this distance (thousandths of the short side) from every edge
constexpr int kTextMinFontPx = 8;
// Font size in pixels for a picture of frameW x frameH: round(shortSide * sizePermille / 1000), never under kTextMinFontPx.
int textFontPx(int64_t frameW, int64_t frameH, int64_t sizePermille, int64_t* outPx);
// Top-left corner of a text box (boxW x boxH pixels) whose CENTRE the person put at (cxPermille, cyPermille) = 0..1000 of the picture.
// The box is moved inside the margin when needed, never outside the picture. kBadRange when the box does not fit inside the margins
// (the caller then makes the font smaller).
int textPlacement(int64_t frameW, int64_t frameH, int64_t cxPermille, int64_t cyPermille, int64_t boxW, int64_t boxH,
                  int64_t* outLeft, int64_t* outTop);

// ---- check of the finished file BEFORE it is called "exported" (plan section 6) ----
struct OutputInfo { int64_t durationUs, w, h, sizeBytes; int container; int codec; int hasAudio; int playable; };
enum Container : int { kContainerMp4 = 1 };
enum VideoCodec : int { kCodecH264 = 1, kCodecHevc = 2 };
struct OutputExpect { int64_t durationUs, w, h, maxBytes; int expectAudio; };
enum OutputProblem : int {  // bit flags
  kProbNone = 0, kProbNotPlayable = 1, kProbContainer = 2, kProbCodec = 4, kProbDuration = 8, kProbSize = 16, kProbFileSize = 32, kProbAudio = 64
};
struct OutputCheck { int code; int problems; };
OutputCheck validateOutput(const OutputInfo& o, const OutputExpect& e);

}  // namespace sociax
