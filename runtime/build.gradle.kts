import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("rikkahub.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "me.rerere.rikkahub.runtime"

    testOptions {
        // Return default values for Android framework calls in JVM unit tests instead of
        // throwing "not mocked". Runtime code routes Android effects through LoopHostBridge,
        // but a stray android.util.Log during the transition must not kill a JVM test.
        // Same reason and same setting as :ai / :local-llm.
        unitTests.isReturnDefaultValues = true
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.optIn.add("kotlin.uuid.ExperimentalUuidApi")
        compilerOptions.optIn.add("kotlin.time.ExperimentalTime")
    }
}

dependencies {
    api(project(":ai"))
    api(project(":common"))

    // kotlinx
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)

    // tests (JVM; runBlocking style — coroutines-test is deliberately not on the classpath)
    testImplementation(libs.junit)
}
