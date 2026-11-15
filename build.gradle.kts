plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

// Runs the core engine's JVM tests together with the app's checks.
tasks.register("coreTest") {
    group = "verification"
    description = "Runs the unit tests of the included :core build."
    dependsOn(gradle.includedBuild("core").task(":test"))
}
