@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
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
        browser()
    }

    sourceSets {
        wasmJsMain.dependencies {
            api(project(":"))
        }
        wasmJsTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
