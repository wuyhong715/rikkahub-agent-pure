plugins { id("com.android.library") }

// Qualcomm's Host-side QNN libraries plus the Hexagon skeleton for HTP v79 (Snapdragon 8
// Elite / SM8750). The .so files themselves are NOT in version control: they are Qualcomm's,
// and republishing them from a public repository is not ours to do. `fetch_qualcomm_library.sh`
// pulls them from the public QAIRT archive, exactly the way Google's own
// litert_npu_runtime_libraries bundle does; CI runs it for the snapdragon variant only.
//
// Google's upstream shape is a `com.android.dynamic-feature` with Play device-group targeting.
// We split by product flavour instead (see app/build.gradle.kts), so a plain library is enough:
// the flavour that may use an NPU is the only one that links this module, and the libraries
// then land in the app's own nativeLibraryDir — which is the one directory the dynamic linker
// searches by soname *and* the one ADSP_LIBRARY_PATH has to point at for the DSP skeleton.
android {
    namespace = "me.rerere.locallm.npu.qualcomm.v79"
    compileSdk = 37

    // NPU execution is arm64-only and API 31+ (see the NPU section of the LiteRT docs).
    defaultConfig { minSdk = 31 }

    sourceSets {
        getByName("main") {
            // Gradle packs everything under src/main/jni/<abi>/ into the APK.
            jniLibs.srcDirs("src/main/jni")
        }
    }
}
