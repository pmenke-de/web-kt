rootProject.name = "web-kt"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":web-kt-testing")

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
