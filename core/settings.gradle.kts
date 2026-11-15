// The core module is a standalone, Android-free Gradle build.
// It is consumed by the Android app through a composite build (see ../settings.gradle.kts)
// and can be built and tested on its own with: ./gradlew test  (from this directory)
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "core"
