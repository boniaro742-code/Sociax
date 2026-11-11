// SociaX native video-editing engine: the JNI bridge (plan v4.0.3 section 2: "a small, documented JNI interface").
// Rules kept here:
//  * NARROW: only flat numbers, LongArray and direct ByteBuffer cross the border. No objects, no callbacks, no strings.
//  * VERSIONED: nativeApiVersion() must equal VideoEngine.kt's EXPECTED_API; if not, Kotlin refuses to use the engine.
//  * VALIDATED: every array length, buffer and number is checked before native work (engine.cpp checks the ranges).
//  * NEVER THROWS: every function catches everything and returns a structured code (kInternal). A native problem
//    can not crash login, Home, chat or notifications: the library is only loaded when the editor is opened.
//  * Session ids are numbers (not pointers) held in a table, so a late cancel() after destroy() is harmless.
// Layout of the arrays is documented in app/src/main/java/com/sociax/app/data/VideoEngine.kt.
#include <jni.h>

#include <cstdint>
#include <map>
#include <memory>
#include <mutex>
#include <vector>

#include "sociax_engine/engine.h"

using namespace sociax;

namespace {
std::mutex g_mu;
std::map<int64_t, std::shared_ptr<Session>> g_sessions;
int64_t g_nextId = 1;

std::shared_ptr<Session> findSession(int64_t id) {
  if (id == 0) return nullptr;
  std::lock_guard<std::mutex> lock(g_mu);
  auto it = g_sessions.find(id);
  return it == g_sessions.end() ? nullptr : it->second;
}

jlongArray makeArray(JNIEnv* env, const std::vector<int64_t>& v) {
  jlongArray out = env->NewLongArray(static_cast<jsize>(v.size()));
  if (out == nullptr) return nullptr;
  std::vector<jlong> tmp(v.begin(), v.end());
  env->SetLongArrayRegion(out, 0, static_cast<jsize>(tmp.size()), tmp.data());
  return out;
}

bool readLongs(JNIEnv* env, jlongArray arr, size_t expectedMin, std::vector<int64_t>* out) {
  if (arr == nullptr) return false;
  const jsize n = env->GetArrayLength(arr);
  if (n < 0 || static_cast<size_t>(n) < expectedMin || n > 100000) return false;
  std::vector<jlong> tmp(static_cast<size_t>(n));
  if (n > 0) env->GetLongArrayRegion(arr, 0, n, tmp.data());
  out->assign(tmp.begin(), tmp.end());
  return true;
}

jlongArray codeOnly(JNIEnv* env, int code) { return makeArray(env, {code}); }
}  // namespace

extern "C" {

JNIEXPORT jint JNICALL Java_com_sociax_app_data_NativeVideoEngine_nativeApiVersion(JNIEnv*, jclass) { return kApiVersion; }

// spec (14 longs) -> [code, outW, outH, durationUs]
JNIEXPORT jlongArray JNICALL Java_com_sociax_app_data_NativeVideoEngine_nativeClipOutput(JNIEnv* env, jclass, jlongArray spec) {
  try {
    std::vector<int64_t> a;
    ClipSpec c;
    if (!readLongs(env, spec, kSpecLongs, &a) || !specFromLongs(a.data(), kSpecLongs, &c)) return codeOnly(env, kBadArgument);
    ClipOut o = clipOutput(c);
    return makeArray(env, {o.code, o.w, o.h, o.durationUs});
  } catch (...) {
    return codeOnly(env, kInternal);
  }
}

// spec, atSourceUs -> [code, left(14), right(14)]  (29 numbers)
JNIEXPORT jlongArray JNICALL Java_com_sociax_app_data_NativeVideoEngine_nativeSplit(JNIEnv* env, jclass, jlongArray spec, jlong atUs) {
  try {
    std::vector<int64_t> a;
    ClipSpec c;
    if (!readLongs(env, spec, kSpecLongs, &a) || !specFromLongs(a.data(), kSpecLongs, &c)) return codeOnly(env, kBadArgument);
    SplitResult r = splitClip(c, atUs);
    std::vector<int64_t> out(1 + 2 * kSpecLongs, 0);
    out[0] = r.code;
    if (r.code == kOk) {
      specToLongs(r.left, out.data() + 1);
      specToLongs(r.right, out.data() + 1 + kSpecLongs);
    }
    return makeArray(env, out);
  } catch (...) {
    return codeOnly(env, kInternal);
  }
}

// specs (count*14) -> [code, failedClip, canvasW, canvasH, totalUs, presetUsed, downgraded, count, then count * (x, y, w, h)]
JNIEXPORT jlongArray JNICALL Java_com_sociax_app_data_NativeVideoEngine_nativeExportPlan(JNIEnv* env, jclass, jlongArray specs, jint count,
                                                                                         jint preset, jlong maxSide, jboolean s1080,
                                                                                         jboolean s4k) {
  try {
    if (count < 1 || count > kMaxClips) return codeOnly(env, kBadArgument);
    std::vector<int64_t> a;
    if (!readLongs(env, specs, static_cast<size_t>(count) * kSpecLongs, &a)) return codeOnly(env, kBadArgument);
    std::vector<ClipSpec> clips(static_cast<size_t>(count));
    for (int i = 0; i < count; ++i)
      if (!specFromLongs(a.data() + static_cast<size_t>(i) * kSpecLongs, kSpecLongs, &clips[static_cast<size_t>(i)])) return codeOnly(env, kBadArgument);
    ExportPlan p = exportPlan(clips.data(), clips.size(), preset, DeviceCaps{maxSide, s1080 ? 1 : 0, s4k ? 1 : 0});
    std::vector<int64_t> out = {p.code, p.failedClip, p.canvasW, p.canvasH, p.totalUs, p.presetUsed, p.downgraded,
                                static_cast<int64_t>(p.rects.size())};
    for (const Rect& r : p.rects) { out.push_back(r.x); out.push_back(r.y); out.push_back(r.w); out.push_back(r.h); }
    return makeArray(env, out);
  } catch (...) {
    return codeOnly(env, kInternal);
  }
}

JNIEXPORT jint JNICALL Java_com_sociax_app_data_NativeVideoEngine_nativeAvailablePresets(JNIEnv*, jclass, jlong shortSide, jlong maxSide,
                                                                                         jboolean s1080, jboolean s4k) {
  try {
    return availablePresets(shortSide, DeviceCaps{maxSide, s1080 ? 1 : 0, s4k ? 1 : 0});
  } catch (...) {
    return 1;  // the original size only
  }
}

// filterId, strength, brightness, contrast, saturation, temperature -> 16 floats (4x4, OpenGL column-major) or an EMPTY array on error
JNIEXPORT jfloatArray JNICALL Java_com_sociax_app_data_NativeVideoEngine_nativeColorMatrix(JNIEnv* env, jclass, jint filterId, jint strength,
                                                                                           jint brightness, jint contrast, jint saturation,
                                                                                           jint temperature) {
  try {
    ColorParams cp;
    cp.filterId = filterId; cp.strength = strength; cp.brightness = brightness; cp.contrast = contrast; cp.saturation = saturation;
    cp.temperature = temperature;
    float m[16];
    const int code = colorMatrix(cp, m);
    const jsize n = (code == kOk) ? 16 : 0;
    jfloatArray out = env->NewFloatArray(n);
    if (out != nullptr && n > 0) env->SetFloatArrayRegion(out, 0, n, m);
    return out;
  } catch (...) {
    return nullptr;
  }
}

// displayW, displayH, ratioW, ratioH -> [code, x, y, w, h]  (1/10000 of the turned picture)
JNIEXPORT jlongArray JNICALL Java_com_sociax_app_data_NativeVideoEngine_nativeCropForRatio(JNIEnv* env, jclass, jint w, jint h, jint ratioW,
                                                                                           jint ratioH) {
  try {
    Crop c{0, 0, kCropFull, kCropFull};
    const int code = cropForRatio(w, h, ratioW, ratioH, &c);
    return makeArray(env, {code, c.x, c.y, c.w, c.h});
  } catch (...) {
    return codeOnly(env, kInternal);
  }
}

// trimStartUs, trimEndUs, cuts (2 longs per cut: fromUs, toUs; may be empty) -> [code, count, start0, end0, start1, end1, ...]
JNIEXPORT jlongArray JNICALL Java_com_sociax_app_data_NativeVideoEngine_nativeCutSegments(JNIEnv* env, jclass, jlong trimStartUs, jlong trimEndUs,
                                                                                        jlongArray cuts) {
  try {
    std::vector<int64_t> a;
    if (!readLongs(env, cuts, 0, &a) || a.size() % 2 != 0) return codeOnly(env, kBadArgument);
    std::vector<Segment> segs;
    const int code = cutSegments(trimStartUs, trimEndUs, a.empty() ? nullptr : a.data(), a.size() / 2, &segs);
    if (code != kOk) return codeOnly(env, code);
    std::vector<int64_t> r{kOk, static_cast<int64_t>(segs.size())};
    for (const Segment& s : segs) { r.push_back(s.startUs); r.push_back(s.endUs); }
    return makeArray(env, r);
  } catch (...) {
    return codeOnly(env, kInternal);
  }
}

// frameW, frameH, sizePermille -> [code, fontPx]
JNIEXPORT jlongArray JNICALL Java_com_sociax_app_data_NativeVideoEngine_nativeTextFontPx(JNIEnv* env, jclass, jint w, jint h, jint sizePermille) {
  try {
    int64_t px = 0;
    const int code = textFontPx(w, h, sizePermille, &px);
    return makeArray(env, {code, px});
  } catch (...) {
    return codeOnly(env, kInternal);
  }
}

// frameW, frameH, centreX/centreY (0..1000), boxW, boxH -> [code, left, top]
JNIEXPORT jlongArray JNICALL Java_com_sociax_app_data_NativeVideoEngine_nativeTextPlacement(JNIEnv* env, jclass, jint w, jint h, jint cx, jint cy,
                                                                                          jint boxW, jint boxH) {
  try {
    int64_t left = 0, top = 0;
    const int code = textPlacement(w, h, cx, cy, boxW, boxH, &left, &top);
    return makeArray(env, {code, left, top});
  } catch (...) {
    return codeOnly(env, kInternal);
  }
}

JNIEXPORT jint JNICALL Java_com_sociax_app_data_NativeVideoEngine_nativeAudioPolicy(JNIEnv* env, jclass, jlongArray spec) {
  try {
    std::vector<int64_t> a;
    ClipSpec c;
    if (!readLongs(env, spec, kSpecLongs, &a) || !specFromLongs(a.data(), kSpecLongs, &c)) return -kBadArgument;
    return audioPolicy(c);
  } catch (...) {
    return -kInternal;
  }
}

JNIEXPORT jlong JNICALL Java_com_sociax_app_data_NativeVideoEngine_nativeSessionCreate(JNIEnv*, jclass) {
  try {
    std::lock_guard<std::mutex> lock(g_mu);
    if (g_sessions.size() > 64) return 0;  // something leaks: refuse instead of growing for ever
    const int64_t id = g_nextId++;
    g_sessions[id] = std::make_shared<Session>();
    return id;
  } catch (...) {
    return 0;
  }
}

JNIEXPORT void JNICALL Java_com_sociax_app_data_NativeVideoEngine_nativeSessionCancel(JNIEnv*, jclass, jlong id) {
  try {
    if (auto s = findSession(id)) s->cancel();
  } catch (...) {
  }
}

JNIEXPORT jint JNICALL Java_com_sociax_app_data_NativeVideoEngine_nativeSessionProgress(JNIEnv*, jclass, jlong id) {
  try {
    auto s = findSession(id);
    return s ? s->progress() : -1;
  } catch (...) {
    return -1;
  }
}

JNIEXPORT void JNICALL Java_com_sociax_app_data_NativeVideoEngine_nativeSessionDestroy(JNIEnv*, jclass, jlong id) {
  try {
    std::lock_guard<std::mutex> lock(g_mu);
    g_sessions.erase(id);  // a worker that still holds the shared_ptr finishes safely
  } catch (...) {
  }
}

// Direct ByteBuffers only (RGBA_8888). outSize = IntArray(2) receives width, height.
JNIEXPORT jint JNICALL Java_com_sociax_app_data_NativeVideoEngine_nativeTransformFrame(JNIEnv* env, jclass, jlong sessionId, jobject src,
                                                                                       jint w, jint h, jint rotation, jboolean flipH,
                                                                                       jint cropX, jint cropY, jint cropW, jint cropH,
                                                                                       jobject dst, jintArray outSize) {
  try {
    if (src == nullptr || dst == nullptr || outSize == nullptr || env->GetArrayLength(outSize) < 2) return kBadBuffer;
    void* s = env->GetDirectBufferAddress(src);
    void* d = env->GetDirectBufferAddress(dst);
    const jlong sc = env->GetDirectBufferCapacity(src), dc = env->GetDirectBufferCapacity(dst);
    if (s == nullptr || d == nullptr || sc < 0 || dc < 0) return kBadBuffer;
    std::shared_ptr<Session> session = findSession(sessionId);  // may be null: then no cancel / progress
    int64_t ow = 0, oh = 0;
    const int code = transformFrame(static_cast<const uint8_t*>(s), static_cast<size_t>(sc), w, h, rotation, flipH ? 1 : 0,
                                    Crop{cropX, cropY, cropW, cropH}, static_cast<uint8_t*>(d), static_cast<size_t>(dc), &ow, &oh,
                                    session.get());
    if (code == kOk) {
      const jint sz[2] = {static_cast<jint>(ow), static_cast<jint>(oh)};
      env->SetIntArrayRegion(outSize, 0, 2, sz);
    }
    return code;
  } catch (...) {
    return kInternal;
  }
}

JNIEXPORT jint JNICALL Java_com_sociax_app_data_NativeVideoEngine_nativeApplyColor(JNIEnv* env, jclass, jlong sessionId, jobject buf, jint w,
                                                                                   jint h, jint filterId, jint strength, jint brightness,
                                                                                   jint contrast, jint saturation, jint temperature) {
  try {
    if (buf == nullptr) return kBadBuffer;
    void* p = env->GetDirectBufferAddress(buf);
    const jlong cap = env->GetDirectBufferCapacity(buf);
    if (p == nullptr || cap < 0) return kBadBuffer;
    ColorParams cp;
    cp.filterId = filterId; cp.strength = strength; cp.brightness = brightness; cp.contrast = contrast; cp.saturation = saturation;
    cp.temperature = temperature;
    std::shared_ptr<Session> session = findSession(sessionId);
    return applyColor(static_cast<uint8_t*>(p), static_cast<size_t>(cap), w, h, cp, session.get());
  } catch (...) {
    return kInternal;
  }
}

// info (8): durationUs, w, h, sizeBytes, container, codec, hasAudio, playable
// expect (5): durationUs, w, h, maxBytes, expectAudio       -> [code, problemFlags]
JNIEXPORT jlongArray JNICALL Java_com_sociax_app_data_NativeVideoEngine_nativeValidateOutput(JNIEnv* env, jclass, jlongArray info,
                                                                                             jlongArray expect) {
  try {
    std::vector<int64_t> i, e;
    if (!readLongs(env, info, 8, &i) || !readLongs(env, expect, 5, &e)) return codeOnly(env, kBadArgument);
    OutputInfo o{i[0], i[1], i[2], i[3], static_cast<int>(i[4]), static_cast<int>(i[5]), static_cast<int>(i[6]), static_cast<int>(i[7])};
    OutputExpect x{e[0], e[1], e[2], e[3], static_cast<int>(e[4])};
    OutputCheck r = validateOutput(o, x);
    return makeArray(env, {r.code, r.problems});
  } catch (...) {
    return codeOnly(env, kInternal);
  }
}

}  // extern "C"
