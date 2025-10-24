@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.publish)
}

group = "de.pmenke"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()
}

// .gitignored file, which contains the reference to the publishing configuration
// (a private repo for development, a public repo for releases, etc.)
val publishing = file("publishing.gradle.kts")
if (publishing.exists()) {
    apply(from = publishing)
} else {
    // if not configured, publish to local maven repo
    publishing {
        repositories {
            mavenLocal()
        }
    }
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    wasmJs {
        browser {
            commonWebpackConfig {
                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).apply {
                    static = (static ?: mutableListOf()).apply {
                        add(project.rootDir.path)
                    }
                }
                output
                sourceMaps = true
                cssSupport {
                    enabled = true
                }
            }
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(libs.koin.core)
            implementation(libs.kotlin.browser)
            implementation(libs.kotlin.web)
            implementation(libs.kotlinx.browser)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.html)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        wasmJsMain.dependencies {
            implementation(npm("@js-joda/timezone", "2.22.0"))
        }
        wasmJsTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}