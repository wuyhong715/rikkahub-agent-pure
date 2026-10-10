package me.rerere.locallm

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

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

    /**
     * The litertlm release that replaced the OpenCL/GLES GPU accelerator with
     * Dawn/WebGPU-on-Vulkan. Everything at or above it is a different GPU backend with a
     * different failure mode - see [gpuBackendIsSafeByDefault].
     */
    const val VULKAN_GPU_SDK = "0.14.0"

    data class LiteRtCapabilities(
        val isQualcomm: Boolean,
        val qnnLibrarySupported: Boolean,
        val gpuDelegateSupported: Boolean,
        val nnapiSupported: Boolean,
        /**
         * The GPU accelerator bundled with the current litertlm is the OpenCL/GLES one, i.e.
         * the SDK is older than [VULKAN_GPU_SDK]. Defaults to true so a capability snapshot
         * written before the Vulkan move keeps its old meaning.
         */
        val gpuBackendSafe: Boolean = true,
    )

    fun pickLiteRt(caps: LiteRtCapabilities): String = when {
        caps.isQualcomm && caps.qnnLibrarySupported -> "QNN"
        caps.gpuDelegateSupported && caps.gpuBackendSafe -> "GPU"
        // NNAPI reaches the same vendor DSP through a different door, and it is no better
        // verified on the devices the Vulkan move breaks. When the GPU backend is the new
        // one, CPU is the only backend this code is willing to pick unattended. Note the NPU
        // branch above is deliberately untouched: QNN is what we are here to exercise.
        !caps.gpuBackendSafe -> "CPU"
        caps.nnapiSupported -> "NNAPI"
        else -> "CPU"
    }

    /**
     * Whether the GPU backend shipped with [sdkVersion] may be selected **by default**.
     *
     * 0.14.0 swapped the GPU accelerator from OpenCL/GLES to Dawn/WebGPU-on-Vulkan. On Adreno
     * that path does not fail cleanly: the delegate keeps submitting command buffers the
     * driver rejected, and the native thread ends up either SIGSEGV-ing (Adreno 6xx) or
     * **spinning at full CPU** (observed on Adreno 830 / SM8750). The second shape is the
     * dangerous one, because it never raises the native crash the auto-recovery sweep keys
     * off, so the GPU->CPU fallback never fires and the device simply burns until the user
     * kills it. A default that can wedge the phone is not a default we get to make, so the
     * pre-Vulkan answer for now is: CPU unless the user opts in via "Try GPU acceleration".
     *
     * Fails safe - a version string this cannot parse is treated as unsafe.
     */
    fun gpuBackendIsSafeByDefault(sdkVersion: String): Boolean =
        compareVersions(sdkVersion, VULKAN_GPU_SDK)?.let { it < 0 } ?: false

    /** Dotted-numeric version compare: negative/zero/positive, or null if unparseable. */
    internal fun compareVersions(a: String, b: String): Int? {
        val pa = parseVersion(a) ?: return null
        val pb = parseVersion(b) ?: return null
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
            if (d != 0) return if (d < 0) -1 else 1
        }
        return 0
    }

    private fun parseVersion(raw: String): List<Int>? {
        val parts = raw.trim().split('.')
        if (parts.isEmpty()) return null
        return parts.map { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return null }
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
     * @param gpuBackendSafe false when the bundled GPU accelerator is the post-0.14.0 Vulkan
     *   one. That is a much wider bad class than Google Tensor - see
     *   [gpuBackendIsSafeByDefault] - so it forces CPU before the SoC check even runs.
     */
    fun defaultForceCpu(
        socManufacturer: String?,
        socModel: String?,
        gpuBackendSafe: Boolean = true,
    ): Boolean {
        if (!gpuBackendSafe) return true
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
     */
    fun probeLiteRt(context: Context, forceCpu: Boolean = false): String {
        if (forceCpu) return "CPU"
        val isQualcomm = Build.HARDWARE.contains("qcom", ignoreCase = true) ||
            Build.MANUFACTURER.equals("Qualcomm", ignoreCase = true)
        val qnnLibrarySupported = isQualcomm && runCatching {
            // The QNN delegate is bundled in the LiteRT-LM AAR (litertlm-android). Attempting to
            // load it eagerly fails fast on non-Qualcomm devices or where the right ABI is absent.
            // A failed load leaves the class loader in a partially-initialised state for that
            // library name, but Android's JNI loader is idempotent for subsequent real loads of the
            // same name by the actual runtime — the side effect is acceptable.
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
                gpuBackendSafe = gpuBackendIsSafeByDefault(BuildConfig.LITERTLM_SDK_VERSION),
            )
        )
    }
}
