package me.rerere.locallm

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import me.rerere.locallm.npu.NpuRuntimePlan

/**
 * Decides which accelerator to use for each runtime. Two layers:
 *
 *  - Pure decision function ([pickLiteRt]) takes a capability snapshot and
 *    returns the chosen label. JVM unit-testable.
 *  - Production probe ([probeLiteRt]) reads live device state and feeds the
 *    decision function.
 *
 * The cached choice persists in [LocalRuntimePreferences]; the probe runs once
 * on first model load and again only when the user taps "Re-detect".
 */
object AcceleratorProbe {

    data class LiteRtCapabilities(
        val isQualcomm: Boolean,
        val qnnLibrarySupported: Boolean,
        val gpuDelegateSupported: Boolean,
        val nnapiSupported: Boolean,
    )

    fun pickLiteRt(caps: LiteRtCapabilities): String = when {
        caps.isQualcomm && caps.qnnLibrarySupported -> "QNN"
        caps.gpuDelegateSupported -> "GPU"
        caps.nnapiSupported -> "NNAPI"
        else -> "CPU"
    }

    /**
     * Whether LiteRT should DEFAULT to forcing CPU (GPU opt-in) on a device, before the
     * user has expressed any preference in the "Try GPU acceleration" toggle.
     *
     * GPU is the fast path and works on the vast majority of devices - typically 3-10x
     * faster than CPU for these models. The one known-bad class is Google Tensor (Pixel
     * 6 and later): LiteRT-LM 0.11.0's GPU/NNAPI path has a native SIGSEGV there. For
     * that SoC family we keep CPU as the safe default; every other device defaults to
     * GPU and gets the speedup out of the box.
     *
     * This is only the *initial* default. The per-device crash sweep in RikkaHubApp still
     * backstops any device that crashes anyway by persisting forceCpu=true, and the
     * runtime's own GPU->CPU fallback handles a GPU that fails to initialise. So a wrong
     * guess here self-corrects; it is never load-bearing for safety.
     *
     * @param socManufacturer `Build.SOC_MANUFACTURER` (API 31+), or null on older devices.
     * @param socModel `Build.SOC_MODEL` (API 31+), or null on older devices.
     */
    fun defaultForceCpu(socManufacturer: String?, socModel: String?): Boolean {
        // Google Tensor SoCs report SOC_MANUFACTURER = "Google"; SOC_MODEL is checked as a
        // belt-and-braces signal ("Tensor G1".."Tensor G5"). Any positive match keeps the
        // conservative CPU default. Everything else - including pre-API-31 devices where
        // both args are null (no Google Tensor device runs an OS that old) - gets GPU.
        return socManufacturer?.equals("Google", ignoreCase = true) == true ||
            socModel?.contains("Tensor", ignoreCase = true) == true
    }

    /**
     * Read the live device capabilities for the LiteRT runtime. Production callers
     * use this; unit tests pass synthesised [LiteRtCapabilities] to [pickLiteRt]
     * directly.
     *
     * @param forceCpu short-circuits to "CPU" without probing — set when the user has
     *   the "Try GPU acceleration" toggle off, OR when the auto-recovery sweep saw a
     *   prior native crash inside liblitertlm and flipped the flag for us.
     * @param socFamily the installed flavour's SOC_FAMILY. NPU is opt-in per
     *   *build*, so this is checked before any device-side probe. See [NpuRuntimePlan].
     */
    fun probeLiteRt(
        context: Context,
        forceCpu: Boolean = false,
        socFamily: String = "generic",
    ): String {
        if (forceCpu) return "CPU"
        // NPU is opt-in per *build*, not per device: only a soc flavour compiled for this
        // device's vendor may offer that vendor's runtime, so a generic APK answers "no"
        // here whatever the phone is. See NpuRuntimePlan for why the release is split.
        val npuOfferedByBuild = NpuRuntimePlan.isUsableOn(
            family = NpuRuntimePlan.familyOf(socFamily),
            // SOC_MANUFACTURER is API 31+; older devices read as null, which the plan treats
            // as "not this build's vendor".
            socManufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MANUFACTURER
            } else {
                null
            },
        )
        val isQualcomm = Build.HARDWARE.contains("qcom", ignoreCase = true) ||
            Build.MANUFACTURER.equals("Qualcomm", ignoreCase = true)
        val qnnLibrarySupported = isQualcomm && npuOfferedByBuild && runCatching {
            // This used to claim the QNN delegate ships inside the LiteRT-LM AAR. It does
            // not: unpacking the published AAR shows only liblitertlm_jni.so, and the
            // installed APK carries no QNN or dispatch library at all. LiteRT-LM reaches a
            // vendor NPU through a *dispatch* library the app itself has to supply
            // (NpuRuntimePlan.dispatchLibraryFor), so this stays false until one has been
            // fetched. Kept for the case where a future build ships the libraries.
            System.loadLibrary("qnn_delegate_jni")
            true
        }.getOrDefault(false)
        // FEATURE_OPENGLES_EXTENSION_PACK is a reasonable proxy for GPU-delegate capability but
        // is only advisory — the LiteRT-LM runtime may still fail to initialise the GPU backend
        // at model-load time even if this returns true. The AcceleratorProbe is therefore
        // intentionally optimistic: prefer GPU when the feature flag suggests it's present, and
        // let the runtime's own error path trigger a re-probe if load fails.
        val gpuDelegateSupported = context.packageManager.hasSystemFeature(
            PackageManager.FEATURE_OPENGLES_EXTENSION_PACK,
        )
        val nnapiSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1
        return pickLiteRt(
            LiteRtCapabilities(
                isQualcomm = isQualcomm,
                qnnLibrarySupported = qnnLibrarySupported,
                gpuDelegateSupported = gpuDelegateSupported,
                nnapiSupported = nnapiSupported,
            )
        )
    }
}
