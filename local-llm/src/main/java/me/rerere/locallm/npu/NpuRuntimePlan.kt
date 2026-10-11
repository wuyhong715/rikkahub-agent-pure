package me.rerere.locallm.npu

/**
 * Which NPU runtime a build is allowed to offer, and whether the running device can use it.
 *
 * NPU acceleration on Android is not one feature but a *per-vendor* one, and that is why the
 * release is split by SoC instead of shipping one APK for everyone:
 *
 *  - LiteRT reaches a neural accelerator through a **dispatch** library that belongs to the
 *    silicon vendor ([DISPATCH_QUALCOMM] for Qualcomm). `litertlm-android` does not contain
 *    one — verified by unpacking the published AAR, which ships only `liblitertlm_jni.so` —
 *    so `Backend.NPU(...)` silently degrades unless the app supplies it.
 *  - Behind the dispatch library sit the vendor's own libraries (Qualcomm's
 *    `libQnnHtp*.so`, `libQnnHtpV*Skel.so`, `libQnnSystem.so`), which come from that
 *    vendor's SDK under that vendor's licence. Google publishes
 *    `litert_npu_runtime_libraries.zip` on LiteRT's releases, but it is a *fetcher*: the
 *    Qualcomm half is a shell script that downloads the QAIRT SDK (~1 MB of dispatch
 *    libraries, plus a download of the vendor SDK).
 *
 * So this file deliberately splits the decision in two, and both halves are pure:
 *
 *  - [familyOf] / [vendorFor] — what the installed build was **compiled** to offer, from the
 *    `soc` flavour's `SOC_FAMILY` BuildConfig field.
 *  - [isUsableOn]             — whether the **device** has that vendor's silicon.
 *
 * A build only ever offers its own vendor's runtime, so a `generic` APK cannot be talked into
 * fetching a proprietary runtime it was never built for. That asymmetry is the whole point:
 * it keeps the licence question on the device's own side of the fence.
 */
enum class SocFamily(val id: String) {
    /** GPU/CPU only. Offers no vendor runtime at all. */
    GENERIC("generic"),

    /** Qualcomm Snapdragon: may offer the Qualcomm (Hexagon HTP) runtime. */
    SNAPDRAGON("snapdragon"),

    /** MediaTek Dimensity: reserved. See [NpuRuntimePlan.dispatchLibraryFor]. */
    DIMENSITY("dimensity"),
    ;

    companion object {
        /**
         * Unknown or missing ids fall back to [GENERIC]. A build that cannot read its own
         * flavour must under-promise, never over-promise.
         */
        fun fromId(id: String?): SocFamily =
            entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) } ?: GENERIC
    }
}

/** Who built the neural accelerator a runtime talks to. */
enum class NpuVendor(val id: String) {
    QUALCOMM("qualcomm"),
    MEDIATEK("mediatek"),
    ;

    companion object {
        fun fromId(id: String?): NpuVendor? =
            entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }
    }
}

object NpuRuntimePlan {

    const val DISPATCH_QUALCOMM = "libLiteRtDispatch_Qualcomm.so"
    const val DISPATCH_GOOGLE_TENSOR = "libLiteRtDispatch_GoogleTensor.so"

    /**
     * The only ABI a vendor NPU runtime exists for. NPU acceleration is arm64-v8a only, so
     * an x86_64 emulator can never be offered it — see the `soc` flavours in
     * `app/build.gradle.kts`.
     */
    const val NPU_ABI = "arm64-v8a"

    /** What the app has to end up with on disk before `Backend.NPU(...)` is worth trying. */
    data class Runtime(
        val vendor: NpuVendor,
        val dispatchLibrary: String,
        /** Qualcomm's HTP generation, when it can be told apart. Null for vendors without one. */
        val htpGeneration: Int? = null,
    )

    fun familyOf(socFamily: String?): SocFamily = SocFamily.fromId(socFamily)

    /** The vendor runtime a build offers, or null when it offers none. */
    fun vendorFor(family: SocFamily): NpuVendor? = when (family) {
        SocFamily.SNAPDRAGON -> NpuVendor.QUALCOMM
        SocFamily.DIMENSITY -> NpuVendor.MEDIATEK
        SocFamily.GENERIC -> null
    }

    /** The vendor a device's SoC belongs to, from `Build.SOC_MANUFACTURER` (API 31+). */
    fun vendorOfDevice(socManufacturer: String?): NpuVendor? = NpuVendor.fromId(socManufacturer)

    /**
     * Whether this build may offer its NPU runtime on this device.
     *
     * False for every `generic` build, and false when the device's SoC vendor is not the one
     * the build was made for — a Snapdragon build refuses to install a MediaTek runtime and
     * vice versa. `socManufacturer` is null on API < 31, which is the same answer as
     * "different vendor": no.
     */
    fun isUsableOn(family: SocFamily, socManufacturer: String?): Boolean {
        val wanted = vendorFor(family) ?: return false
        return vendorOfDevice(socManufacturer) == wanted
    }

    /**
     * The dispatch library that has to be present in the native library directory LiteRT is
     * pointed at. Only Qualcomm's is published by Google today; MediaTek's runtime is listed
     * in `runtime_strings` of the LiteRT bundle but no dispatch library ships with it, which
     * is why the `dimensity` flavour is defined but not built.
     */
    fun dispatchLibraryFor(vendor: NpuVendor): String? = when (vendor) {
        NpuVendor.QUALCOMM -> DISPATCH_QUALCOMM
        NpuVendor.MEDIATEK -> null
    }

    /**
     * Qualcomm's Hexagon Tensor Processor generation, which is fixed per SoC and selects
     * which `libQnnHtpV<NN>Skel.so` the runtime needs. The dispatch library itself is shared
     * across generations; the QNN skeleton is not.
     *
     * Only the SoCs we can reason about are listed. An unknown SoC returns null, which makes
     * the caller decline rather than guess: loading the wrong Hexagon skeleton is the kind of
     * mistake that ends in a native fault, not an exception.
     *
     * SM8750 (Snapdragon 8 Elite) is the device this work is verified against; the mapping is
     * still confirmed at runtime through `ModelInfo.from(path).supportedBackends(Modality)`.
     */
    fun qualcommHtpGeneration(socModel: String?): Int? =
        when (socModel?.trim()?.uppercase()) {
            "SM8550" -> 69 // Snapdragon 8 Gen 2
            "SM8650" -> 73 // Snapdragon 8 Gen 3
            "SM8750" -> 79 // Snapdragon 8 Elite
            "SM8850" -> 81 // Snapdragon 8 Elite Gen 5
            else -> null
        }

    /**
     * The full plan for a build/device pair, or null when there is nothing to offer.
     * `socModel` only refines the Qualcomm answer; it never widens the gate [isUsableOn] sets.
     */
    fun planFor(
        family: SocFamily,
        socManufacturer: String?,
        socModel: String?,
    ): Runtime? {
        if (!isUsableOn(family, socManufacturer)) return null
        val vendor = vendorFor(family) ?: return null
        val dispatch = dispatchLibraryFor(vendor) ?: return null
        return Runtime(
            vendor = vendor,
            dispatchLibrary = dispatch,
            htpGeneration = if (vendor == NpuVendor.QUALCOMM) qualcommHtpGeneration(socModel) else null,
        )
    }
}
