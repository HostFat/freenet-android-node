package org.freenet.androidnode

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import java.util.Locale

internal enum class OemVendor(val slug: String, val displayName: String) {
    Generic("generic", "Android"),
    Xiaomi("xiaomi", "Xiaomi"),
    Samsung("samsung", "Samsung"),
    Huawei("huawei", "Huawei"),
    Honor("honor", "Honor"),
    Oppo("oppo", "Oppo"),
    Vivo("vivo", "Vivo"),
    OnePlus("oneplus", "OnePlus"),
    Realme("realme", "Realme"),
    Asus("asus", "Asus"),
}

internal enum class XiaomiAutostartHint {
    NotXiaomi,
    Enabled,
    Disabled,
    Unknown,
}

internal enum class RestrictionLook {
    NeedsFix,
    Ok,
}

internal data class RestrictionSnapshot(
    val ignoringBatteryOptimizations: Boolean,
    val backgroundRestricted: Boolean,
    val vendor: OemVendor,
    val xiaomiAutostart: XiaomiAutostartHint,
) {
    val look: RestrictionLook
        get() = restrictionLook(
            ignoringBatteryOptimizations,
            backgroundRestricted,
            xiaomiAutostart,
        )

    val isAggressiveOem: Boolean
        get() = vendor != OemVendor.Generic
}

internal fun restrictionLook(
    ignoringBatteryOptimizations: Boolean,
    backgroundRestricted: Boolean,
    xiaomiAutostart: XiaomiAutostartHint,
): RestrictionLook {
    if (!ignoringBatteryOptimizations || backgroundRestricted) {
        return RestrictionLook.NeedsFix
    }
    if (xiaomiAutostart == XiaomiAutostartHint.Disabled) {
        return RestrictionLook.NeedsFix
    }
    return RestrictionLook.Ok
}

internal fun oemVendor(manufacturer: String, brand: String): OemVendor {
    val tokens = listOf(manufacturer, brand).map { it.lowercase(Locale.US).trim() }
    fun has(vararg names: String): Boolean =
        tokens.any { token -> names.any { name -> token == name || token.contains(name) } }
    return when {
        has("xiaomi", "redmi", "poco", "blackshark") -> OemVendor.Xiaomi
        has("samsung") -> OemVendor.Samsung
        has("honor") -> OemVendor.Honor
        has("huawei") -> OemVendor.Huawei
        has("oppo") -> OemVendor.Oppo
        has("vivo", "iqoo") -> OemVendor.Vivo
        has("oneplus") -> OemVendor.OnePlus
        has("realme") -> OemVendor.Realme
        has("asus") -> OemVendor.Asus
        else -> OemVendor.Generic
    }
}

internal fun dontKillMyAppUrl(vendor: OemVendor): String {
    val query = "app=Freenet%20Android%20Node"
    return if (vendor == OemVendor.Generic) {
        "https://dontkillmyapp.com/?$query"
    } else {
        "https://dontkillmyapp.com/${vendor.slug}?$query"
    }
}

internal fun readRestrictionSnapshot(context: Context): RestrictionSnapshot {
    val app = context.applicationContext
    val vendor = oemVendor(Build.MANUFACTURER.orEmpty(), Build.BRAND.orEmpty())
    val power = app.getSystemService(PowerManager::class.java)
    val ignoring = power?.isIgnoringBatteryOptimizations(app.packageName) == true
    val restricted = app.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true
    val xiaomi = if (vendor == OemVendor.Xiaomi) {
        probeXiaomiAutostart(app)
    } else {
        XiaomiAutostartHint.NotXiaomi
    }
    return RestrictionSnapshot(
        ignoringBatteryOptimizations = ignoring,
        backgroundRestricted = restricted,
        vendor = vendor,
        xiaomiAutostart = xiaomi,
    )
}

/**
 * MIUI/HyperOS AppOps code 10008 is the undocumented Autostart switch.
 * Hidden APIs are blocked on newer Android; treat any failure as Unknown.
 */
internal fun probeXiaomiAutostart(context: Context): XiaomiAutostartHint {
    return try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) ?: return XiaomiAutostartHint.Unknown
        val method = appOps.javaClass.getMethod(
            "checkOpNoThrow",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            String::class.java,
        )
        val mode = method.invoke(appOps, MIUI_OP_AUTO_START, Process.myUid(), context.packageName) as? Int
            ?: return XiaomiAutostartHint.Unknown
        when (mode) {
            0 -> XiaomiAutostartHint.Enabled
            1 -> XiaomiAutostartHint.Disabled
            else -> XiaomiAutostartHint.Unknown
        }
    } catch (_: Throwable) {
        XiaomiAutostartHint.Unknown
    }
}

internal fun openRestrictionSettings(context: Context, snapshot: RestrictionSnapshot): Boolean {
    val pkg = context.packageName
    if (!snapshot.ignoringBatteryOptimizations) {
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$pkg")
        }
        if (tryStart(context, request)) return true
    }
    if (openOemScreens(context, snapshot.vendor)) return true
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:$pkg")
    }
    if (tryStart(context, details)) return true
    return tryStart(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
}

internal fun openOemScreens(context: Context, vendor: OemVendor): Boolean {
    for (intent in oemIntents(vendor)) {
        if (tryStart(context, intent)) return true
    }
    return false
}

internal fun openDontKillMyAppGuide(context: Context, vendor: OemVendor): Boolean {
    val view = Intent(Intent.ACTION_VIEW, Uri.parse(dontKillMyAppUrl(vendor)))
    return tryStart(context, view)
}

internal fun tryStart(context: Context, intent: Intent): Boolean {
    return try {
        val launch = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        true
    } catch (_: Exception) {
        false
    }
}

private fun oemIntents(vendor: OemVendor): List<Intent> = when (vendor) {
    OemVendor.Xiaomi -> listOf(
        component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
        component("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"),
    )
    OemVendor.Samsung -> listOf(
        component("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        component("com.samsung.android.sm", "com.samsung.android.sm.battery.ui.BatteryActivity"),
        component("com.samsung.android.sm", "com.samsung.android.sm.battery.ui.usage.CheckableAppListActivity"),
    )
    OemVendor.Huawei, OemVendor.Honor -> listOf(
        component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        component("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
        component("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
    )
    OemVendor.Oppo, OemVendor.Realme -> listOf(
        component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        component("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
    )
    OemVendor.Vivo -> listOf(
        component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
        component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
    )
    OemVendor.OnePlus -> listOf(
        component("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        Intent("com.android.settings.action.BACKGROUND_OPTIMIZE"),
    )
    OemVendor.Asus -> listOf(
        component("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"),
        component("com.asus.mobilemanager", "com.asus.mobilemanager.powersaver.PowerSaverSettings"),
    )
    OemVendor.Generic -> emptyList()
}

private fun component(packageName: String, className: String): Intent =
    Intent().setComponent(ComponentName(packageName, className))

private const val MIUI_OP_AUTO_START = 10008
