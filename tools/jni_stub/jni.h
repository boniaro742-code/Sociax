// MINIMAL stand-in for the NDK's jni.h, ONLY so tools/check_jni.py can compile sociax_jni.cpp on a computer that has no Android NDK.
// It declares just the types and calls sociax_jni.cpp uses, with the signatures from the JNI specification. The real build uses the NDK header.
#pragma once
#include <cstdint>
typedef uint8_t jboolean;
typedef int32_t jint;
typedef int64_t jlong;
typedef float jfloat;
typedef jint jsize;
class _jobject {};
typedef _jobject* jobject;
typedef _jobject* jclass;
typedef _jobject* jarray;
typedef _jobject* jlongArray;
typedef _jobject* jintArray;
typedef _jobject* jfloatArray;
#define JNIEXPORT __attribute__((visibility("default")))
#define JNICALL
struct JNIEnv {
  jsize GetArrayLength(jarray) { return 0; }
  jlongArray NewLongArray(jsize) { return nullptr; }
  void SetLongArrayRegion(jlongArray, jsize, jsize, const jlong*) {}
  void GetLongArrayRegion(jlongArray, jsize, jsize, jlong*) {}
  jfloatArray NewFloatArray(jsize) { return nullptr; }
  void SetFloatArrayRegion(jfloatArray, jsize, jsize, const jfloat*) {}
  void SetIntArrayRegion(jintArray, jsize, jsize, const jint*) {}
  void* GetDirectBufferAddress(jobject) { return nullptr; }
  jlong GetDirectBufferCapacity(jobject) { return -1; }
};
