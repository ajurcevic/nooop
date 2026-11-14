// Root build file — declares plugin versions once; applied per-module in app/build.gradle.kts.
// Keep these versions aligned:
//   Android Gradle Plugin 9.x (built-in Kotlin) · Kotlin 2.x · Compose compiler plugin = Kotlin version
//   · KSP 2.x · Room 2.7+ (first line with KSP2 support).
plugins {
    id("com.android.application") version "9.5.0" apply false
    // AGP 9 compiles Kotlin itself, so this plugin is never applied. Declaring it here only pins the
    // Kotlin Gradle plugin (and so the Kotlin compiler) AGP runs, instead of AGP's bundled default.
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    // The Compose compiler ships with Kotlin since 2.0; its version must equal the Kotlin version.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("com.google.devtools.ksp") version "2.3.13" apply false
}
