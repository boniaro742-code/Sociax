#!/usr/bin/env python3
"""Step 77 (plan v4.0.3 section 5): the Kotlin <-> C++ (JNI) border must agree. The native library can not be run without a phone,
so this checks, WITHOUT building, the mistakes that would only show up as a crash on the phone:
  1. every `external fun` of NativeVideoEngine.kt has a JNI function with the exact name, the same number of parameters and the same
     types (Long-jlong, Int-jint, Boolean-jboolean, LongArray-jlongArray, IntArray-jintArray, FloatArray-jfloatArray, ByteBuffer-jobject) and return type;
     and no JNI function exists that Kotlin does not declare;
  2. the API number agrees (VideoEngine.EXPECTED_API == kApiVersion in engine.h) and the clip layout length agrees
     (EngineClip.LONGS == kSpecLongs);
  3. System.loadLibrary name == the CMake library name; CMake builds engine.cpp and sociax_jni.cpp and sets the 16 KB page size flag;
  4. app/build.gradle.kts points at the CMake file and lists the ABIs;
  5. sociax_jni.cpp compiles on this computer (against tools/jni_stub/jni.h, a minimal stand-in for the NDK header) when g++ exists.
Run:  python3 tools/check_jni.py        (exit code 1 = a problem, printed below)"""
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
if len(sys.argv) > 1:
    ROOT = Path(sys.argv[1]).resolve()
problems = []


def read(rel):
    p = ROOT / rel
    if not p.exists():
        problems.append(f"{rel}: file not found")
        return ""
    return p.read_text(encoding="utf-8")


KT = read("app/src/main/java/com/sociax/app/data/VideoEngine.kt")
JNI = read("app/src/main/cpp/sociax_jni.cpp")
ENG_H = read("app/src/main/cpp/sociax_engine/engine.h")
CMAKE = read("app/src/main/cpp/CMakeLists.txt")
GRADLE = read("app/build.gradle.kts")

KT_TO_JNI = {"Long": "jlong", "Int": "jint", "Boolean": "jboolean", "LongArray": "jlongArray", "IntArray": "jintArray", "FloatArray": "jfloatArray", "ByteBuffer": "jobject", "Unit": "void"}

# ---- 1. Kotlin external funs
kotlin = {}
for m in re.finditer(r"external\s+fun\s+(native\w+)\s*\((.*?)\)\s*(?::\s*(\w+))?\s*(?=\n\s*(?:/\*\*|@JvmStatic|\}))", KT, re.S):
    name, params, ret = m.group(1), m.group(2), m.group(3) or "Unit"
    types = []
    for p in [x.strip() for x in params.split(",") if x.strip()]:
        mm = re.match(r"\w+\s*:\s*(\w+)$", p)
        if not mm:
            problems.append(f"VideoEngine.kt: cannot read parameter '{p}' of {name}")
            continue
        types.append(mm.group(1))
    kotlin[name] = (types, ret)
if not kotlin:
    problems.append("VideoEngine.kt: no `external fun native...` found")

# ---- JNI functions
jni = {}
for m in re.finditer(r"JNIEXPORT\s+(\w+)\s+JNICALL\s+Java_com_sociax_app_data_NativeVideoEngine_(native\w+)\s*\((.*?)\)\s*\{", JNI, re.S):
    ret, name, params = m.group(1), m.group(2), m.group(3)
    ps = [x.strip() for x in params.split(",") if x.strip()]
    if len(ps) < 2 or not ps[0].startswith("JNIEnv") or not ps[1].startswith("jclass"):
        problems.append(f"sociax_jni.cpp: {name} must start with (JNIEnv*, jclass ...)")
        continue
    types = [re.match(r"(\w+)", p).group(1) for p in ps[2:]]
    jni[name] = (types, ret)

for name, (ktypes, kret) in kotlin.items():
    if name not in jni:
        problems.append(f"sociax_jni.cpp: no JNI function for Kotlin `{name}` (the app would crash with UnsatisfiedLinkError when it is called)")
        continue
    jtypes, jret = jni[name]
    want = []
    for t in ktypes:
        if t not in KT_TO_JNI:
            problems.append(f"VideoEngine.kt: {name} uses type {t}, which the JNI layer does not support (allowed: {', '.join(sorted(KT_TO_JNI))})")
            want.append("?")
        else:
            want.append(KT_TO_JNI[t])
    if len(jtypes) != len(want):
        problems.append(f"{name}: Kotlin has {len(want)} parameters, C++ has {len(jtypes)}")
    elif jtypes != want:
        problems.append(f"{name}: parameter types differ. Kotlin needs {want}, C++ has {jtypes}")
    if kret not in KT_TO_JNI:
        problems.append(f"VideoEngine.kt: {name} returns {kret}, not supported")
    elif KT_TO_JNI[kret] != jret:
        problems.append(f"{name}: Kotlin returns {kret} (needs {KT_TO_JNI[kret]}), C++ returns {jret}")
for name in jni:
    if name not in kotlin:
        problems.append(f"sociax_jni.cpp: JNI function {name} has no Kotlin `external fun` (dead code or a typo in the name)")

# ---- 2. numbers that must agree
m1 = re.search(r"EXPECTED_API\s*=\s*(\d+)", KT)
m2 = re.search(r"kApiVersion\s*=\s*(\d+)", ENG_H)
if not m1 or not m2 or m1.group(1) != m2.group(1):
    problems.append(f"API number differs: VideoEngine.EXPECTED_API={m1.group(1) if m1 else '?'} vs engine.h kApiVersion={m2.group(1) if m2 else '?'}")
m3 = re.search(r"const val LONGS\s*=\s*(\d+)", KT)
m4 = re.search(r"kSpecLongs\s*=\s*(\d+)", ENG_H)
if not m3 or not m4 or m3.group(1) != m4.group(1):
    problems.append(f"clip layout length differs: EngineClip.LONGS={m3.group(1) if m3 else '?'} vs engine.h kSpecLongs={m4.group(1) if m4 else '?'}")
ktcodes = dict(re.findall(r"const val ([A-Z_]+)\s*=\s*(\d+)\n", KT.split("object EngineCode")[1].split("sealed class")[0])) if "object EngineCode" in KT else {}
hcodes = dict(re.findall(r"\bk(\w+)\s*=\s*(\d+)", ENG_H.split("enum Code")[1].split("};")[0])) if "enum Code" in ENG_H else {}
norm = lambda s: s.replace("_", "").lower()
kt_n = {norm(k): v for k, v in ktcodes.items()}
h_n = {norm(k): v for k, v in hcodes.items()}
if not kt_n or kt_n != h_n:
    problems.append(f"result codes differ between EngineCode (Kotlin) {kt_n} and engine.h Code {h_n}")
kprob = {norm(k): v for k, v in re.findall(r"const val ([A-Z_]+)\s*=\s*(\d+)\n", KT.split("object EngineProblem")[1].split("}")[0])} if "object EngineProblem" in KT else {}
hprob = {norm(k): v for k, v in re.findall(r"kProb(\w+)\s*=\s*(\d+)", ENG_H) if k != "None"}
hprob = {k.replace("notplayable", "notplayable"): v for k, v in hprob.items()}
if kprob != hprob:
    problems.append(f"problem flags differ between EngineProblem (Kotlin) {kprob} and engine.h OutputProblem {hprob}")

# ---- 3. library name + CMake
lib = re.search(r'System\.loadLibrary\("([\w]+)"\)', KT)
cm = re.search(r"add_library\(\s*(\w+)\s+SHARED", CMAKE)
if not lib or not cm or lib.group(1) != cm.group(1):
    problems.append(f"library name differs: loadLibrary(\"{lib.group(1) if lib else '?'}\") vs CMake add_library({cm.group(1) if cm else '?'})")
for src in ("sociax_engine/engine.cpp", "sociax_jni.cpp"):
    if src not in CMAKE:
        problems.append(f"CMakeLists.txt does not build {src}")
if "max-page-size=16384" not in CMAKE:
    problems.append("CMakeLists.txt: missing the 16 KB page-size link flag (Google Play requires 16 KB aligned native libraries)")
if "CMAKE_CXX_STANDARD 17" not in CMAKE:
    problems.append("CMakeLists.txt: C++17 is not set")

# ---- 4. Gradle
if "externalNativeBuild" not in GRADLE or "src/main/cpp/CMakeLists.txt" not in GRADLE:
    problems.append("app/build.gradle.kts: externalNativeBuild { cmake { path = file(\"src/main/cpp/CMakeLists.txt\") } } is missing")
for abi in ("arm64-v8a", "armeabi-v7a", "x86_64"):
    if abi not in GRADLE:
        problems.append(f"app/build.gradle.kts: ABI {abi} is not listed in abiFilters")

ndk = re.search(r'ndkVersion\s*=\s*"([\d.]+)"', GRADLE)
ci = read(".github/workflows/build.yml")
if not ndk:
    problems.append("app/build.gradle.kts: ndkVersion is not pinned")
elif ci.count(f"ndk;{ndk.group(1)}") < 2:
    problems.append(f"build.yml: the NDK {ndk.group(1)} must be installed in BOTH jobs (debug APK and release APK)")
if ci and "tools/engine_test.cpp" not in ci:
    problems.append("build.yml: the engine test (tools/engine_test.cpp) is not run by the build")
if ci and "tools/check_jni.py" not in ci:
    problems.append("build.yml: tools/check_jni.py is not run by the build")

# ---- plan section 6: C++ is NOT a dependency of login, Home start-up, chat, notifications or navigation
kt_files = list((ROOT / "app/src/main/java").rglob("*.kt"))
loads = [f for f in kt_files if "System.loadLibrary" in f.read_text(encoding="utf-8")]
if len(loads) != 1 or loads[0].name != "VideoEngine.kt":
    problems.append("System.loadLibrary must appear ONLY in VideoEngine.kt (found in: " + ", ".join(f.name for f in loads) + ")")
for name in ("MainActivity.kt", "AuthRepository.kt", "Push.kt", "AuthScreens.kt", "HomeScreen.kt", "ChatScreens.kt", "NotificationScreens.kt", "ShortVideoScreens.kt"):
    for f in kt_files:
        if f.name == name and re.search(r"\bVideoEngine\b|NativeVideoEngine", f.read_text(encoding="utf-8")):
            problems.append(f"{name} must not use the native video engine (login, Home, chat, notifications and Short Video playback never depend on C++)")

# ---- Step 78: the video editor never becomes a gate
SV = read("app/src/main/java/com/sociax/app/ui/screens/ShortVideoScreens.kt")
if "readVideoSource" in SV:
    if "prepare(uri)" not in SV or "if (e.isDefault) prepare(editUri)" not in SV:
        problems.append("ShortVideoScreens.kt: a video with NO edits (or an unreadable length) must keep the old exportShort path via prepare(uri)")
    if "VideoEngine.isAvailable()" not in read("app/src/main/java/com/sociax/app/ui/screens/VideoEditorScreen.kt"):
        problems.append("VideoEditorScreen.kt: must check VideoEngine.isAvailable() and offer only Trim + Turn when the engine is missing")
    ve = read("app/src/main/java/com/sociax/app/data/VideoEdit.kt")
    if "VideoEngine.validateOutput" not in ve or "OUTPUT_MISMATCH" not in ve:
        problems.append("VideoEdit.kt: an edited file must be checked with VideoEngine.validateOutput before it is accepted")
    if "out.delete()" not in ve:
        problems.append("VideoEdit.kt: a failed / refused export must delete the new file")

# ---- Step 79: video SPEED (picture by Media3, sound by the engine's rule, and the check expects exactly that)
if "readVideoSource" in SV:
    ve79 = read("app/src/main/java/com/sociax/app/data/VideoEdit.kt")
    ed79 = read("app/src/main/java/com/sociax/app/ui/screens/VideoEditorScreen.kt")
    if "SpeedChangeEffect(edits.speedMilli" not in ve79:
        problems.append("VideoEdit.kt: a speed choice must add SpeedChangeEffect to the export effects")
    if "withSpeed: Boolean" not in ve79 or "withSpeed = false" not in ed79:
        problems.append("the live preview must NOT use SpeedChangeEffect (it changes the player's speed instead, so picture and sound stay in step)")
    if not re.search(r"plan\.audio == EngineAudio\.KEEP[^\n]*listOf\(SonicAudioProcessor\(\)\.apply \{ setSpeed\(speed\) \}\)", ve79):
        problems.append("VideoEdit.kt: sound at 0.5x-2x must go through SonicAudioProcessor (pitch kept), as the engine rule audioPolicy says")
    if "setRemoveAudio(plan.audio == EngineAudio.MUTE)" not in ve79:
        problems.append("VideoEdit.kt: at speeds where the engine says MUTE the sound must be removed (never left at the wrong speed)")
    if "plan.audio != EngineAudio.MUTE" not in ve79:
        problems.append("VideoEdit.kt: the finished-file check must not expect sound when it was removed on purpose")
    if not re.search(r"trimEndUs = endMs \* 1000L,\s*speedMilli = edits\.speedMilli", ve79):
        problems.append("VideoEdit.kt: the engine clip must carry the chosen speed (so the expected length is after speed)")
    if "PlaybackParameters(edits.speedMilli" not in ed79 or "speedAudio(" not in ed79:
        problems.append("VideoEditorScreen.kt: the preview speed and the sound note (before export) are missing")

# ---- Step 80: TEXT on the video (the engine owns font size + place; the picture is frame-sized; the text goes LAST; preview = export)
if "readVideoSource" in SV:
    ve80 = read("app/src/main/java/com/sociax/app/data/VideoEdit.kt")
    ed80 = read("app/src/main/java/com/sociax/app/ui/screens/VideoEditorScreen.kt")
    if "VideoEngine.textFontPx(" not in ve80 or "VideoEngine.textPlacement(" not in ve80:
        problems.append("VideoEdit.kt: the text's font size and place must come from the C++ engine (textFontPx / textPlacement)")
    if "BitmapOverlay.createStaticBitmapOverlay" not in ve80 or "OverlayEffect(" not in ve80:
        problems.append("VideoEdit.kt: the text must be laid over the video with Media3 OverlayEffect + BitmapOverlay")
    if not re.search(r"Bitmap\.createBitmap\(w, h,", ve80):
        problems.append("VideoEdit.kt: the text picture must have the SAME size as the finished frame (w x h), so it can not be placed or scaled wrongly")
    i_pres = ve80.find("Presentation.createForWidthAndHeight")
    i_text = ve80.find("buildTextOverlay(edits, finalW, finalH)")
    if i_pres < 0 or i_text < 0 or i_text < i_pres:
        problems.append("VideoEdit.kt: the text overlay must be added AFTER the size is fixed (Presentation), with the final width and height")
    if "withSpeed = false" in ed80 and "planVideoEffects(edits, source, 720, withSpeed = false)" not in ed80:
        problems.append("VideoEditorScreen.kt: the preview must use planVideoEffects (the SAME effects as the export, so the text is seen before posting)")
    if "VideoText.clean(" not in ed80 or "edits.hasText" not in ed80:
        problems.append("VideoEditorScreen.kt: the text field must keep the limits (VideoText.clean) and the controls must follow hasText")
    if "val isDefault: Boolean get() = !hasText" not in ve80:
        problems.append("VideoEdit.kt: a typed-and-erased text must not count as an edit (isDefault)")
    if "implementation(\"com.google.guava:guava:" not in GRADLE:
        problems.append("app/build.gradle.kts: guava is needed for ImmutableList (OverlayEffect)")

# ---- Step 81: CUT parts out of the middle (the engine says which pieces stay; export = one item per piece; preview plays the pieces; Next waits for valid cuts)
if "readVideoSource" in SV:
    ve81 = read("app/src/main/java/com/sociax/app/data/VideoEdit.kt")
    ed81 = read("app/src/main/java/com/sociax/app/ui/screens/VideoEditorScreen.kt")
    if "VideoEngine.cutSegments(" not in ve81:
        problems.append("VideoEdit.kt: which pieces stay after the cuts must come from the C++ engine (cutSegments)")
    if "plan.segmentsMs.ifEmpty" not in ve81 or "plan.segmentsMs" not in ve81:
        problems.append("VideoEdit.kt: the export must make one item per piece that stays (plan.segmentsMs)")
    if "EditedMediaItemSequence(editedItems)" not in ve81 or "editedItems.size == 1" not in ve81:
        problems.append("VideoEdit.kt: several pieces go into ONE sequence; a single piece keeps the old single-item export")
    if not re.search(r"totalUs \+= r\.value\.durationUs", ve81) or "VideoEffectPlan(effects, finalW, finalH, totalUs" not in ve81:
        problems.append("VideoEdit.kt: the expected length of the finished file must be the SUM of the pieces (each after speed)")
    if "SonicAudioProcessor().apply" not in ve81 or ve81.find("SonicAudioProcessor().apply") < ve81.find("val editedItems = pieces.map"):
        problems.append("VideoEdit.kt: the sound processor must be made fresh for EACH piece (inside the per-piece loop)")
    if "hasText || cuts.isNotEmpty()" not in ve81:
        problems.append("VideoEdit.kt: cuts need the engine (needsEngine)")
    if "exo.setMediaItems(pieces)" not in ed81 or "editSegments(edits, source)" not in ed81:
        problems.append("VideoEditorScreen.kt: the preview must play the pieces that stay, one after another")
    if "if (!cutsInvalid) onDone(edits)" not in ed81:
        problems.append("VideoEditorScreen.kt: Next must wait while the cuts leave a piece under 0.1 s")
    if ed81.count("keptLengthMs(edits, source)") < 3:   # Trim tab length, Cut tab length, Speed tab result length
        problems.append("VideoEditorScreen.kt: the shown length must be the length AFTER the cuts")
    if "editSegments(candidate, source) is EngineResult.Ok" not in ed81:
        problems.append("VideoEditorScreen.kt: a new cut may only be added when the engine accepts it")

# ---- the engine core must stay free of Android / JNI (so it can be tested on a computer)
for rel in ("app/src/main/cpp/sociax_engine/engine.h", "app/src/main/cpp/sociax_engine/engine.cpp"):
    txt = read(rel)
    if re.search(r"#\s*include\s*[<\"](jni|android/[^>\"]+)[>\"]", txt):
        problems.append(f"{rel}: the engine core must not include jni.h or android headers (it is tested on a computer)")

# ---- 5. compile the bridge on this computer
if shutil.which("g++") and (ROOT / "tools/jni_stub/jni.h").exists() and JNI:
    with tempfile.TemporaryDirectory() as td:
        r = subprocess.run(["g++", "-std=c++17", "-Wall", "-Wextra", "-fno-rtti", "-I", str(ROOT / "tools/jni_stub"), "-I", str(ROOT / "app/src/main/cpp"),
                            "-c", str(ROOT / "app/src/main/cpp/sociax_jni.cpp"), "-o", td + "/jni.o"], capture_output=True, text=True)
        if r.returncode != 0:
            problems.append("sociax_jni.cpp does not compile:\n" + r.stderr[:1500])
        elif r.stderr.strip():
            problems.append("sociax_jni.cpp compiles with warnings:\n" + r.stderr[:800])
else:
    print("note: g++ or tools/jni_stub/jni.h not found, the compile check of sociax_jni.cpp was skipped")

if problems:
    print("JNI CHECK FAILED:")
    for p in problems:
        print(" -", p)
    sys.exit(1)
print(f"JNI check: OK ({len(kotlin)} external functions match the C++ side; API {m1.group(1)}; clip layout {m3.group(1)} numbers; library {lib.group(1)})")
