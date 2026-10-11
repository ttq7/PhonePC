plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.phonepc.explorer"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.phonepc.explorer"
        minSdk = 26
        targetSdk = 36
        versionCode = 17
        versionName = "2.6"
        resourceConfigurations += listOf("zh", "en")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // x86_64 only exists so the emulator can run it; release drops it
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // NOTE: the on-device embedding runtime is NOT wired in yet.
    // Measured with clean builds (an earlier un-cleaned measurement was wrong):
    //   com.microsoft.onnxruntime:onnxruntime-android  -> +30.0 MB (arm64+v7a)
    //                                                      +17.6 MB (arm64 only)
    //   com.microsoft.onnxruntime:onnxruntime-mobile   -> does not resolve at 1.19.2
    //   org.tensorflow:tensorflow-lite                 -> +5.4 MB (arm64+v7a)
    // Shipping an unused runtime would bloat the APK for no capability, so the
    // dependency goes back in together with the model integration.
    // NOTE: the on-device LLM runtime is deliberately NOT wired in yet.
    // Measured cost, so the decision is informed rather than guessed:
    //   com.google.mediapipe:tasks-genai  -> +18.8 MB (arm64+v7a)
    //   org.tensorflow:tensorflow-lite    -> +5.4 MB (arm64+v7a)
    // Shipping an unused native runtime would bloat the APK for no capability,
    // so the dependency goes back in together with the model integration.
}
