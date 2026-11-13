plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.flowkey.ime"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.flowkey.ime"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.2.0"
    }

    buildTypes {
        debug {
            // A separate app next to the release: a debug build can never be installed over
            // the rotated release key (signature mismatch), so it gets its own package.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // Deliberately no signingConfig: releases are signed by scripts/build-release.ps1
            // with apksigner key rotation (debug key -> release key), which Gradle can't do.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    lint {
        abortOnError = false
    }

    testOptions {
        // Pure-Kotlin logic is unit-tested on the JVM; stub android.* calls (e.g. Log).
        unitTests.isReturnDefaultValues = true
    }

    if (project.hasProperty("noModel")) {
        // `-PnoModel` builds FlowKey-no-model.apk: aapt's default ignore list plus the
        // bundled speech models (assets/model/). The VAD and hotwords vocab stay in.
        androidResources {
            ignoreAssetsPattern =
                "!.svn:!.git:!.ds_store:!*.scc:.*:<dir>_*:!CVS:!thumbs.db:!picasa.ini:!*~:<dir>model"
        }
    }
}

dependencies {
    // sherpa-onnx: drop the AAR from the GitHub releases page into app/libs/ (see README.md)
    implementation(fileTree("libs") { include("*.aar") })

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
}
