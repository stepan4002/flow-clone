import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.flowkey.ime"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.flowkey.ime"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "1.3.0"
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
    buildFeatures {
        viewBinding = true
        compose = true
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

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // The app screens (Compose). The keyboard itself stays on Views.
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // real org.json (android.jar's is a stub in JVM tests)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
