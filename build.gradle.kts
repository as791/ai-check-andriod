// Top-level build file; per-module configuration lives in app/build.gradle.kts
// and domain/build.gradle.kts. Plugins are declared here with apply false so
// each module can opt in without re-resolving plugin versions.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
