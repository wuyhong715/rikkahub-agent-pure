package me.rerere.locallm.npu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that decides whether a build may offer a vendor NPU runtime on this device.
 *
 * These are the cases that matter: a `generic` build must never offer anything, a build must
 * never offer another vendor's runtime, and an unknown SoC must decline rather than guess.
 */
class NpuRuntimePlanTest {

    // ---- SocFamily.fromId -------------------------------------------------------------

    @Test
    fun `flavour ids round-trip`() {
        assertEquals(SocFamily.GENERIC, SocFamily.fromId("generic"))
        assertEquals(SocFamily.SNAPDRAGON, SocFamily.fromId("snapdragon"))
        assertEquals(SocFamily.DIMENSITY, SocFamily.fromId("dimensity"))
    }

    @Test
    fun `flavour id matching ignores case and padding`() {
        assertEquals(SocFamily.SNAPDRAGON, SocFamily.fromId(" SnapDragon "))
    }

    @Test
    fun `unknown flavour id falls back to generic`() {
        // Under-promise: a build that cannot read its own flavour offers nothing.
        assertEquals(SocFamily.GENERIC, SocFamily.fromId(null))
        assertEquals(SocFamily.GENERIC, SocFamily.fromId(""))
        assertEquals(SocFamily.GENERIC, SocFamily.fromId("exynos"))
    }

    // ---- vendorFor --------------------------------------------------------------------

    @Test
    fun `only the vendor flavours name a vendor`() {
        assertNull(NpuRuntimePlan.vendorFor(SocFamily.GENERIC))
        assertEquals(NpuVendor.QUALCOMM, NpuRuntimePlan.vendorFor(SocFamily.SNAPDRAGON))
        assertEquals(NpuVendor.MEDIATEK, NpuRuntimePlan.vendorFor(SocFamily.DIMENSITY))
    }

    // ---- vendorOfDevice ---------------------------------------------------------------

    @Test
    fun `device vendor comes from SOC_MANUFACTURER`() {
        assertEquals(NpuVendor.QUALCOMM, NpuRuntimePlan.vendorOfDevice("Qualcomm"))
        assertEquals(NpuVendor.MEDIATEK, NpuRuntimePlan.vendorOfDevice("MediaTek"))
    }

    @Test
    fun `unknown or absent device vendor is null`() {
        // Build.SOC_MANUFACTURER is API 31+; on anything older it is null.
        assertNull(NpuRuntimePlan.vendorOfDevice(null))
        assertNull(NpuRuntimePlan.vendorOfDevice(""))
        assertNull(NpuRuntimePlan.vendorOfDevice("Google"))
        assertNull(NpuRuntimePlan.vendorOfDevice("Samsung"))
    }

    // ---- isUsableOn: the gate ----------------------------------------------------------

    @Test
    fun `generic never offers a runtime, on any device`() {
        assertFalse(NpuRuntimePlan.isUsableOn(SocFamily.GENERIC, "Qualcomm"))
        assertFalse(NpuRuntimePlan.isUsableOn(SocFamily.GENERIC, "MediaTek"))
        assertFalse(NpuRuntimePlan.isUsableOn(SocFamily.GENERIC, null))
    }

    @Test
    fun `a vendor build only offers its own vendor`() {
        assertTrue(NpuRuntimePlan.isUsableOn(SocFamily.SNAPDRAGON, "Qualcomm"))
        assertFalse(NpuRuntimePlan.isUsableOn(SocFamily.SNAPDRAGON, "MediaTek"))
        assertTrue(NpuRuntimePlan.isUsableOn(SocFamily.DIMENSITY, "MediaTek"))
        assertFalse(NpuRuntimePlan.isUsableOn(SocFamily.DIMENSITY, "Qualcomm"))
    }

    @Test
    fun `a vendor build declines when the device vendor is unknown`() {
        assertFalse(NpuRuntimePlan.isUsableOn(SocFamily.SNAPDRAGON, null))
        assertFalse(NpuRuntimePlan.isUsableOn(SocFamily.SNAPDRAGON, "Google"))
    }

    // ---- dispatch library -------------------------------------------------------------

    @Test
    fun `only qualcomm publishes a dispatch library today`() {
        assertEquals(NpuRuntimePlan.DISPATCH_QUALCOMM, NpuRuntimePlan.dispatchLibraryFor(NpuVendor.QUALCOMM))
        assertNull(NpuRuntimePlan.dispatchLibraryFor(NpuVendor.MEDIATEK))
    }

    // ---- Qualcomm HTP generation ------------------------------------------------------

    @Test
    fun `known snapdragons map to a Hexagon generation`() {
        assertEquals(69, NpuRuntimePlan.qualcommHtpGeneration("SM8550"))
        assertEquals(73, NpuRuntimePlan.qualcommHtpGeneration("SM8650"))
        assertEquals(79, NpuRuntimePlan.qualcommHtpGeneration("SM8750"))
        assertEquals(81, NpuRuntimePlan.qualcommHtpGeneration("SM8850"))
        assertEquals(79, NpuRuntimePlan.qualcommHtpGeneration(" sm8750 "))
    }

    @Test
    fun `an unlisted SoC has no generation`() {
        // Guessing the wrong Hexagon skeleton faults natively instead of throwing.
        assertNull(NpuRuntimePlan.qualcommHtpGeneration(null))
        assertNull(NpuRuntimePlan.qualcommHtpGeneration("SM8450"))
        assertNull(NpuRuntimePlan.qualcommHtpGeneration("Tensor G5"))
    }

    // ---- planFor ----------------------------------------------------------------------

    @Test
    fun `a snapdragon build on a snapdragon device produces a full plan`() {
        val plan = NpuRuntimePlan.planFor(SocFamily.SNAPDRAGON, "Qualcomm", "SM8750")
        assertEquals(NpuVendor.QUALCOMM, plan?.vendor)
        assertEquals(NpuRuntimePlan.DISPATCH_QUALCOMM, plan?.dispatchLibrary)
        assertEquals(79, plan?.htpGeneration)
    }

    @Test
    fun `a plan still exists when only the generation is unknown`() {
        // The dispatch library is shared across generations, so an unlisted SoC is not fatal
        // to the plan -- only the QNN skeleton cannot be picked.
        val plan = NpuRuntimePlan.planFor(SocFamily.SNAPDRAGON, "Qualcomm", "SM8999")
        assertEquals(NpuVendor.QUALCOMM, plan?.vendor)
        assertNull(plan?.htpGeneration)
    }

    @Test
    fun `no plan for generic builds or mismatched devices`() {
        assertNull(NpuRuntimePlan.planFor(SocFamily.GENERIC, "Qualcomm", "SM8750"))
        assertNull(NpuRuntimePlan.planFor(SocFamily.SNAPDRAGON, "MediaTek", "MT6991"))
        assertNull(NpuRuntimePlan.planFor(SocFamily.SNAPDRAGON, null, null))
    }

    @Test
    fun `no plan for a vendor whose dispatch library is not published`() {
        // dimensity is defined but must stay dark until MediaTek's dispatch library ships.
        assertNull(NpuRuntimePlan.planFor(SocFamily.DIMENSITY, "MediaTek", "MT6991"))
    }
}
