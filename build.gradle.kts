@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig
import org.gradle.api.publish.maven.MavenPublication

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
        optIn.add("kotlin.js.ExperimentalWasmJsInterop")
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    wasmJs {
        browser {
            commonWebpackConfig {
                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).apply {
                    static(project.rootDir.path)
                }
                sourceMaps = true
                cssSupport {
                    enabled = true
                }
            }
        }
    }
    sourceSets {
        commonMain.dependencies {
            api(libs.koin.core)
            api(libs.kotlin.browser)
            api(libs.kotlin.web)
            api(libs.kotlinx.browser)
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.datetime)
            api(libs.kotlinx.html)
            api(libs.kotlinx.serialization.json)
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

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("WebKt")
            description.set("A small component framework for Kotlin/Wasm browser applications")
            url.set("https://github.com/pmenke-de/web-kt")
            licenses {
                license {
                    name.set("Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set("pmenke")
                    name.set("Philipp Menke")
                }
            }
            scm {
                connection.set("scm:git:https://github.com/pmenke-de/web-kt.git")
                developerConnection.set("scm:git:ssh://git@github.com/pmenke-de/web-kt.git")
                url.set("https://github.com/pmenke-de/web-kt")
            }
        }
    }
}
