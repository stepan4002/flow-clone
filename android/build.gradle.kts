// Top-level build file. Plugin versions are declared here and applied in :app.
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
    // Kotlin 2.x ships the Compose compiler as a Kotlin plugin (same version as Kotlin).
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}
