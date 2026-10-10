pluginManagement {
    includeBuild("build-logic")

    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "rikkahub"
include(":app")

// The Qualcomm NPU runtime for HTP v79 (Snapdragon 8 Elite). Only the `snapdragon` product
// flavour depends on it, so the generic/pure APKs are unchanged. The libraries live under
// src/main/jni/arm64-v8a/ and are fetched by CI, never committed — see the module's
// build.gradle.kts.
include(":litert_npu_runtime_libraries:qualcomm_runtime_v79")
include(":highlight")
include(":ai")
include(":local-llm")
include(":llama-cpp")
include(":search")
include(":speech")
include(":common")
include(":document")
include(":web")
include(":material3")
include(":workspace")
include(":oauth")
