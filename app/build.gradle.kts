plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Firebase plugin is applied only when google-services.json exists, so the app still builds without it.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

// Backend address. The GitHub build sets SOCIAX_API_URL (your Render https address).
// Step 51: an address typed without "https://" (for example "sociax-backend.onrender.com") gets "https://" added.
// Step 65 (report v0.64.0, P1): the emulator address http://10.0.2.2:8000 is now a DEBUG-ONLY fallback. A RELEASE build
// (assembleRelease / bundleRelease) STOPS with a clear message unless SOCIAX_API_URL is a real https address, so a release APK
// can never ship pointing at the emulator or at plain http (see the check at the bottom of this file).
val apiUrlGiven: String? = System.getenv("SOCIAX_API_URL")?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
    ?.let { if (it.startsWith("https://") || it.startsWith("http://")) it else "https://$it" }
val apiUrl: String = apiUrlGiven ?: "http://10.0.2.2:8000"

// Step 41 (plan v3.8 section 28), Start.io since v0.82.0: your Start.io App ID (portal.start.io > My Apps > Sociax).
// The App ID is NOT a secret (every app that shows ads contains it). REAL ads are the default.
// To use another App ID set the GitHub variable SOCIAX_STARTIO_APP_ID (digits only). A wrongly typed value is ignored.
// TEST ads (they earn nothing) only when you set SOCIAX_STARTIO_TEST_ADS=1.
// NEVER tap your own real ads: Start.io can close an account for that.
val startioDefaultAppId = "210216857"
val startioAppId: String = System.getenv("SOCIAX_STARTIO_APP_ID")?.trim()
    ?.takeIf { Regex("^[0-9]{6,12}$").matches(it) } ?: startioDefaultAppId
val startioTestAds: Boolean = System.getenv("SOCIAX_STARTIO_TEST_ADS")?.trim() == "1"

// Step 65: release signing inputs (all four are needed).
val releaseKeystore: String? = System.getenv("SOCIAX_RELEASE_KEYSTORE")?.trim()?.takeIf { it.isNotEmpty() }
val releaseStorePassword: String? = System.getenv("SOCIAX_RELEASE_STORE_PASSWORD")?.takeIf { it.isNotEmpty() }
val releaseKeyAlias: String? = System.getenv("SOCIAX_RELEASE_KEY_ALIAS")?.trim()?.takeIf { it.isNotEmpty() }
val releaseKeyPassword: String? = System.getenv("SOCIAX_RELEASE_KEY_PASSWORD")?.takeIf { it.isNotEmpty() }

android {
    namespace = "com.sociax.app"
    ndkVersion = "27.0.12077973"   // Step 77: the Android NDK for the C++ video engine (CI installs the same version)
    compileSdk = 36   // Step 3 (report v0.67.0, R4): Google Play needs API 36 (Android 16) for new apps and updates since 2026-08-31

    defaultConfig {
        applicationId = "com.sociax.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 83
        versionName = "0.82.1"
        // Backend URL only. NEVER put AI provider keys in the app.
        // 10.0.2.2 = your computer when using the Android emulator. Use your server URL for a real phone.
        buildConfigField("String", "API_BASE_URL", "\"$apiUrl\"")
        buildConfigField("String", "STARTIO_APP_ID", "\"$startioAppId\"")
        buildConfigField("boolean", "STARTIO_TEST_ADS", startioTestAds.toString())

        // Step 77 (plan v4.0.3 section 5): the native C++ video-editing engine (app/src/main/cpp), built with the Android NDK + CMake
        // for every ABI listed here. Only the editor loads it; login, Home, chat and notifications never depend on it.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // DEBUG signing: a FIXED test key (password "android" is the Android default for test keys), so the APK from every GitHub build
    // has the SAME SHA-1 and Google sign-in keeps working while you test. It is for testing ONLY and is never used for release.
    // Step 65 (report v0.64.0, P1): RELEASE signing uses your OWN private key, given only through environment variables
    // (GitHub Secrets), never written in this repository:
    //   SOCIAX_RELEASE_KEYSTORE (path to your .jks file), SOCIAX_RELEASE_STORE_PASSWORD, SOCIAX_RELEASE_KEY_ALIAS, SOCIAX_RELEASE_KEY_PASSWORD
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("sociax-debug.keystore")
            storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android"
        }
        if (releaseKeystore != null && releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null) {
            create("release") {
                storeFile = rootProject.file(releaseKeystore)
                storePassword = releaseStorePassword; keyAlias = releaseKeyAlias; keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with YOUR release key when the four variables above are set; otherwise left unsigned and the build stops
            // with a message (the debug test key is never used for release any more).
            signingConfig = signingConfigs.findByName("release")
            // A release app never falls back to the emulator address: empty when SOCIAX_API_URL is missing (the build stops first).
            buildConfigField("String", "API_BASE_URL", "\"${apiUrlGiven ?: ""}\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
}

// v0.82.1: some libraries ask for a NEWER Kotlin standard library (2.4.x) than this project's compiler (Kotlin 1.9.24) can read
// ("Class 'kotlin.Unit' was compiled with an incompatible version of Kotlin"). Every Kotlin standard-library piece is held at 1.9.24.
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin" && requested.name.startsWith("kotlin-stdlib")) {
            useVersion("1.9.24")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Firebase Auth + Google sign-in (Credential Manager)
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-messaging-ktx")      // Step 46: push (FCM)
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Short Video: playback (ExoPlayer) + on-phone trim / compress before upload (Transformer)
    implementation("androidx.media3:media3-common:1.3.1")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("androidx.media3:media3-transformer:1.3.1")
    implementation("androidx.media3:media3-effect:1.3.1")
    implementation("com.google.guava:guava:33.2.1-android")      // Step 80: ImmutableList for the text overlay (Media3 asks for it)

    // Step 41: Start.io (official Android SDK, native ads). Replaces AdMob (v0.82.0). Pinned to 5.3.1 (the floating "5.+" pulled a Kotlin 2.4 library that this project's Kotlin 1.9.24 cannot read).
    implementation("com.startapp:inapp-sdk:5.3.1")

    // Backend calls + image loading
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.6.0")
}

// Step 65 (report v0.64.0): RELEASE GATE. Only when a release build is requested (assembleRelease / bundleRelease / packageRelease);
// debug builds (the normal GitHub APK) are never blocked by this.
gradle.taskGraph.whenReady {
    val releaseRequested = allTasks.any { it.project == project && it.name in setOf("assembleRelease", "bundleRelease", "packageRelease") }
    if (releaseRequested) {
        val problems = mutableListOf<String>()
        val u = apiUrlGiven
        if (u == null || !u.startsWith("https://") || u.contains("10.0.2.2") || u.contains("localhost")) {
            problems += "SOCIAX_API_URL must be your real https server address (for example https://sociax-backend.onrender.com). Now: ${u ?: "not set"}."
        }
        if (releaseKeystore == null || releaseStorePassword == null || releaseKeyAlias == null || releaseKeyPassword == null) {
            problems += "Release signing key missing: set SOCIAX_RELEASE_KEYSTORE, SOCIAX_RELEASE_STORE_PASSWORD, SOCIAX_RELEASE_KEY_ALIAS and SOCIAX_RELEASE_KEY_PASSWORD (your own private key, not the debug key)."
        } else if (!rootProject.file(releaseKeystore).exists()) {
            problems += "The release keystore file was not found at: $releaseKeystore"
        }
        if (startioTestAds && System.getenv("SOCIAX_ALLOW_TEST_ADS") != "1") {
            problems += "Start.io TEST ads are switched on (they earn nothing): remove SOCIAX_STARTIO_TEST_ADS (or set SOCIAX_ALLOW_TEST_ADS=1 for a test-only release candidate)."
        }
        if (problems.isNotEmpty()) {
            throw GradleException("RELEASE BUILD STOPPED:\n - " + problems.joinToString("\n - "))
        }
    }
}
