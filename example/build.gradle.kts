@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlin.serialization)
}

group = "de.pmenke"
version = rootProject.version

repositories {
    mavenCentral()
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.js.ExperimentalWasmJsInterop")
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    wasmJs {
        browser {
            commonWebpackConfig {
                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).apply {
                    static(project.projectDir.path)
                }
                sourceMaps = true
                cssSupport {
                    enabled = true
                }
            }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        wasmJsTest.dependencies {
            implementation(project(":web-kt-testing"))
        }
    }
}
