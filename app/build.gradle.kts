plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Firebase plugin is applied only when google-services.json exists, so the app still builds without it.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

// Backend address. The GitHub build sets SOCIAX_API_URL (your Render https address); otherwise the emulator address is used.
val apiUrl: String = System.getenv("SOCIAX_API_URL")?.trim()?.trimEnd('/')
    ?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: "http://10.0.2.2:8000"

android {
    namespace = "com.sociax.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sociax.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 29
        versionName = "0.34.0"
        // Backend URL only. NEVER put AI provider keys in the app.
        // 10.0.2.2 = your computer when using the Android emulator. Use your server URL for a real phone.
        buildConfigField("String", "API_BASE_URL", "\"$apiUrl\"")
    }

    // A FIXED test-signing key (password "android" is the Android default for test keys), so the APK from every
    // GitHub build has the SAME SHA-1 and Google sign-in keeps working. Not for the Play Store.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("sociax-debug.keystore")
            storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android"
        }
    }

    buildTypes {
        // Release is signed with the same fixed test key and is NOT shrunk, so "assembleRelease" gives an APK
        // that installs on a phone (an unsigned release APK cannot be installed).
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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

    // Backend calls + image loading
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.6.0")
}
