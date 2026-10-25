// Top-level build file where you can add configuration options common to all sub-projects/modules.
// AGP 9 uses built-in Kotlin (kotlin-android plugin removed). Declaring kotlin-jvm/serialization/
// parcelize here (apply false) keeps Kotlin Gradle Plugin 2.3.10 on the shared build classpath,
// so built-in Kotlin uses 2.3.10 instead of AGP's default 2.2.10.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
    alias(libs.plugins.ksp) apply false
}
