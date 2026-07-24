rootProject.name = "web-kt"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":web-kt-testing")
include(":example")

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
