// Ketvoice for Windows: a standalone Gradle build. The Android build at the
// repository root does not include it; run it with `./gradlew -p desktop …`.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}
rootProject.name = "ketvoice-desktop"
