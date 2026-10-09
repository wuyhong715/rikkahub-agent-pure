plugins {
    id("com.android.application")
}

android {
    namespace = "com.avatarsize.lsposed"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.avatarsize.lsposed"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Xposed / LSPosed API. `compileOnly` — the framework provides it at runtime.
    // io.github.libxposed:api is the modern, LSPosed-maintained API and is on Maven Central.
    compileOnly("io.github.libxposed:api:102.0.0")
}
