package me.rerere.locallm

import org.junit.Assert.assertEquals
import org.junit.Test

class AcceleratorProbeTest {

    @Test fun `LiteRT picks QNN when Qualcomm and QNN library is loadable`() {
        val caps = AcceleratorProbe.LiteRtCapabilities(
            isQualcomm = true,
            qnnLibrarySupported = true,
            gpuDelegateSupported = true,
            nnapiSupported = true,
        )
        assertEquals("QNN", AcceleratorProbe.pickLiteRt(caps))
    }

    @Test fun `LiteRT falls back to GPU on Qualcomm if QNN not loadable`() {
        val caps = AcceleratorProbe.LiteRtCapabilities(
            isQualcomm = true,
            qnnLibrarySupported = false,
            gpuDelegateSupported = true,
            nnapiSupported = true,
        )
        assertEquals("GPU", AcceleratorProbe.pickLiteRt(caps))
    }

    @Test fun `LiteRT picks GPU on non-Qualcomm Mali or Adreno when delegate works`() {
        val caps = AcceleratorProbe.LiteRtCapabilities(
            isQualcomm = false,
            qnnLibrarySupported = false,
            gpuDelegateSupported = true,
            nnapiSupported = true,
        )
        assertEquals("GPU", AcceleratorProbe.pickLiteRt(caps))
    }

    @Test fun `LiteRT falls back to NNAPI when GPU delegate is unavailable`() {
        val caps = AcceleratorProbe.LiteRtCapabilities(
            isQualcomm = false,
            qnnLibrarySupported = false,
            gpuDelegateSupported = false,
            nnapiSupported = true,
        )
        assertEquals("NNAPI", AcceleratorProbe.pickLiteRt(caps))
    }

    @Test fun `LiteRT falls back to CPU when nothing else works`() {
        val caps = AcceleratorProbe.LiteRtCapabilities(
            isQualcomm = false,
            qnnLibrarySupported = false,
            gpuDelegateSupported = false,
            nnapiSupported = false,
        )
        assertEquals("CPU", AcceleratorProbe.pickLiteRt(caps))
    }

    // -- defaultForceCpu: GPU is the default everywhere except the Google Tensor crash class --

    @Test fun `defaultForceCpu is true for Google Tensor by SOC manufacturer`() {
        assertEquals(true, AcceleratorProbe.defaultForceCpu("Google", "Tensor G3"))
        assertEquals(true, AcceleratorProbe.defaultForceCpu("google", "Tensor G5"))
    }

    @Test fun `defaultForceCpu is true when only the SOC model names Tensor`() {
        assertEquals(true, AcceleratorProbe.defaultForceCpu("UNKNOWN", "Tensor G2"))
    }

    @Test fun `defaultForceCpu is false for Qualcomm`() {
        assertEquals(false, AcceleratorProbe.defaultForceCpu("QTI", "SM7325"))
    }

    @Test fun `defaultForceCpu is false for non-Tensor vendors`() {
        assertEquals(false, AcceleratorProbe.defaultForceCpu("Samsung", "Exynos 2400"))
        assertEquals(false, AcceleratorProbe.defaultForceCpu("MediaTek", "Dimensity 9300"))
    }

    @Test fun `defaultForceCpu is false when SOC info is unavailable`() {
        // Pre-API-31 devices report null SOC_* - no Tensor device runs an OS that old.
        assertEquals(false, AcceleratorProbe.defaultForceCpu(null, null))
    }

    // -- pickLiteRt: the post-0.14.0 Vulkan GPU backend is never picked unattended --

    @Test fun `LiteRT refuses the Vulkan GPU backend and falls to CPU, not NNAPI`() {
        // NNAPI reaches the same DSP by another door and is no better verified; CPU is the
        // only backend this code picks on its own once the GPU path is the new one.
        val caps = AcceleratorProbe.LiteRtCapabilities(
            isQualcomm = false,
            qnnLibrarySupported = false,
            gpuDelegateSupported = true,
            nnapiSupported = true,
            gpuBackendSafe = false,
        )
        assertEquals("CPU", AcceleratorProbe.pickLiteRt(caps))
    }

    @Test fun `LiteRT still allows the NPU when the GPU backend is unsafe`() {
        // The NPU is the whole point of the exercise - the Vulkan move must not close it.
        val caps = AcceleratorProbe.LiteRtCapabilities(
            isQualcomm = true,
            qnnLibrarySupported = true,
            gpuDelegateSupported = true,
            nnapiSupported = true,
            gpuBackendSafe = false,
        )
        assertEquals("QNN", AcceleratorProbe.pickLiteRt(caps))
    }

    @Test fun `LiteRT still picks GPU on a pre-Vulkan SDK`() {
        val caps = AcceleratorProbe.LiteRtCapabilities(
            isQualcomm = false,
            qnnLibrarySupported = false,
            gpuDelegateSupported = true,
            nnapiSupported = true,
            gpuBackendSafe = true,
        )
        assertEquals("GPU", AcceleratorProbe.pickLiteRt(caps))
    }

    // -- gpuBackendIsSafeByDefault: only < 0.14.0 may default to the GPU --

    @Test fun `the pre-Vulkan SDKs are safe to default to GPU`() {
        assertEquals(true, AcceleratorProbe.gpuBackendIsSafeByDefault("0.13.1"))
        assertEquals(true, AcceleratorProbe.gpuBackendIsSafeByDefault("0.13"))
        assertEquals(true, AcceleratorProbe.gpuBackendIsSafeByDefault("0.12.0"))
    }

    @Test fun `0_14_0 and everything after it is not`() {
        assertEquals(false, AcceleratorProbe.gpuBackendIsSafeByDefault("0.14.0"))
        assertEquals(false, AcceleratorProbe.gpuBackendIsSafeByDefault("0.18.0"))
        assertEquals(false, AcceleratorProbe.gpuBackendIsSafeByDefault("1.0.0"))
    }

    @Test fun `an unparseable SDK version fails safe to CPU`() {
        assertEquals(false, AcceleratorProbe.gpuBackendIsSafeByDefault(""))
        assertEquals(false, AcceleratorProbe.gpuBackendIsSafeByDefault("garbage"))
        assertEquals(false, AcceleratorProbe.gpuBackendIsSafeByDefault("0.18.0-rc1"))
    }

    @Test fun `dotted versions compare numerically, not lexically`() {
        // "0.13.10" is below 0.14.0 numerically; a naive string compare would call it higher.
        assertEquals(-1, AcceleratorProbe.compareVersions("0.13.10", "0.14.0"))
        assertEquals(1, AcceleratorProbe.compareVersions("0.14.0", "0.13.10"))
        assertEquals(0, AcceleratorProbe.compareVersions("0.18", "0.18.0"))
        assertEquals(null, AcceleratorProbe.compareVersions("0.18.0-rc1", "0.14.0"))
    }

    // -- defaultForceCpu: the Vulkan move widens the bad class past Google Tensor --

    @Test fun `defaultForceCpu is forced on when the GPU backend is the Vulkan one`() {
        // The device this was actually observed on: Snapdragon 8 Elite, SDK 0.18.0.
        assertEquals(false, AcceleratorProbe.defaultForceCpu("QTI", "SM8750", gpuBackendSafe = true))
        assertEquals(true, AcceleratorProbe.defaultForceCpu("QTI", "SM8750", gpuBackendSafe = false))
    }
}
