plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace   = "com.screenmirror.tv"
    compileSdk  = 34

    defaultConfig {
        applicationId  = "com.screenmirror.tv"
        minSdk         = 21
        targetSdk      = 34
        versionCode    = 1
        versionName    = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled   = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // Android TV Leanback
    implementation("androidx.leanback:leanback:1.2.0-alpha04")

    // Core / UI
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // WebSocket (OkHttp)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // WebRTC — Google's pre-built Android AAR (via JitPack mirror)
    // This is the standard open-source build used by countless apps.
    implementation("io.github.webrtc-sdk:android:104.5112.09")
}
