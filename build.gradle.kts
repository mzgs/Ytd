// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.chaquopy.python) apply false
}

val jitpackGroup = providers.environmentVariable("GROUP")
    .map { "$it.${rootProject.name}" }
    .orElse("com.github.mzgs.${rootProject.name}")

val jitpackVersion = providers.environmentVariable("VERSION")
    .orElse("LOCAL-SNAPSHOT")

allprojects {
    group = jitpackGroup.get()
    version = jitpackVersion.get()
}
