# SociaX native video-editing engine (plan v4.0.3 section 5). Built by Gradle (externalNativeBuild) for each ABI in app/build.gradle.kts.
# The engine core (sociax_engine/engine.cpp) has no Android code and is also compiled + tested on a computer: tools/engine_test.cpp.
cmake_minimum_required(VERSION 3.22.1)
project(sociax_engine LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(sociax_engine SHARED
    sociax_engine/engine.cpp
    sociax_jni.cpp)

target_include_directories(sociax_engine PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(sociax_engine PRIVATE -Wall -Wextra -fvisibility=hidden -fno-rtti)

# Google Play needs 16 KB page-size aligned native libraries (Android 15+ phones). NDK r28+ does it by default; this makes older NDKs do it too.
target_link_options(sociax_engine PRIVATE "-Wl,-z,max-page-size=16384")
