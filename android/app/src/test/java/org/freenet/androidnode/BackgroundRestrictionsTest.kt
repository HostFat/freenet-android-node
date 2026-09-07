package org.freenet.androidnode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundRestrictionsTest {
    @Test
    fun mapsManufacturerAndBrandToVendor() {
        assertEquals(OemVendor.Xiaomi, oemVendor("Xiaomi", "Redmi"))
        assertEquals(OemVendor.Xiaomi, oemVendor("POCO", "poco"))
        assertEquals(OemVendor.Samsung, oemVendor("samsung", "Samsung"))
        assertEquals(OemVendor.Honor, oemVendor("HUAWEI", "honor"))
        assertEquals(OemVendor.Huawei, oemVendor("HUAWEI", "HUAWEI"))
        assertEquals(OemVendor.Oppo, oemVendor("OPPO", "OPPO"))
        assertEquals(OemVendor.Vivo, oemVendor("vivo", "iQOO"))
        assertEquals(OemVendor.OnePlus, oemVendor("OnePlus", "OnePlus"))
        assertEquals(OemVendor.Realme, oemVendor("realme", "realme"))
        assertEquals(OemVendor.Asus, oemVendor("asus", "ASUS"))
        assertEquals(OemVendor.Generic, oemVendor("Google", "pixel"))
    }

    @Test
    fun dontKillMyAppUrlUsesVendorSlug() {
        assertEquals(
            "https://dontkillmyapp.com/xiaomi?app=Freenet%20Android%20Node",
            dontKillMyAppUrl(OemVendor.Xiaomi),
        )
        assertEquals(
            "https://dontkillmyapp.com/?app=Freenet%20Android%20Node",
            dontKillMyAppUrl(OemVendor.Generic),
        )
    }

    @Test
    fun stockLimitsKeepTheButtonInNeedsFix() {
        assertEquals(
            RestrictionLook.NeedsFix,
            restrictionLook(
                ignoringBatteryOptimizations = false,
                backgroundRestricted = false,
                xiaomiAutostart = XiaomiAutostartHint.NotXiaomi,
            ),
        )
        assertEquals(
            RestrictionLook.NeedsFix,
            restrictionLook(
                ignoringBatteryOptimizations = true,
                backgroundRestricted = true,
                xiaomiAutostart = XiaomiAutostartHint.NotXiaomi,
            ),
        )
    }

    @Test
    fun xiaomiAutostartOffKeepsNeedsFixEvenIfStockIsUnrestricted() {
        assertEquals(
            RestrictionLook.NeedsFix,
            restrictionLook(
                ignoringBatteryOptimizations = true,
                backgroundRestricted = false,
                xiaomiAutostart = XiaomiAutostartHint.Disabled,
            ),
        )
    }

    @Test
    fun xiaomiUnknownOrEnabledDoesNotOverrideACleanStockState() {
        assertEquals(
            RestrictionLook.Ok,
            restrictionLook(
                ignoringBatteryOptimizations = true,
                backgroundRestricted = false,
                xiaomiAutostart = XiaomiAutostartHint.Enabled,
            ),
        )
        assertEquals(
            RestrictionLook.Ok,
            restrictionLook(
                ignoringBatteryOptimizations = true,
                backgroundRestricted = false,
                xiaomiAutostart = XiaomiAutostartHint.Unknown,
            ),
        )
    }

    @Test
    fun snapshotLookFollowsTheSameRules() {
        val needsFix = RestrictionSnapshot(
            ignoringBatteryOptimizations = true,
            backgroundRestricted = false,
            vendor = OemVendor.Xiaomi,
            xiaomiAutostart = XiaomiAutostartHint.Disabled,
        )
        assertEquals(RestrictionLook.NeedsFix, needsFix.look)
        assertTrue(needsFix.isAggressiveOem)

        val pixel = RestrictionSnapshot(
            ignoringBatteryOptimizations = true,
            backgroundRestricted = false,
            vendor = OemVendor.Generic,
            xiaomiAutostart = XiaomiAutostartHint.NotXiaomi,
        )
        assertEquals(RestrictionLook.Ok, pixel.look)
        assertFalse(pixel.isAggressiveOem)
    }
}
