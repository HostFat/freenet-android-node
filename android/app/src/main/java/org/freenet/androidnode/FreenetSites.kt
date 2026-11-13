package org.freenet.androidnode

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal enum class SiteViewer { INHERIT, APP, BROWSER }

internal enum class OpenWith { APP, BROWSER, ASK }

internal data class FreenetSite(
    val name: String,
    val url: String,
    val predefined: Boolean = false,
    val viewer: SiteViewer = SiteViewer.INHERIT,
)

internal object FreenetSites {
    private const val PREFS = "freenet_sites"
    private const val KEY_CUSTOM = "custom_sites"
    private const val KEY_OVERRIDES = "default_overrides"
    private const val KEY_REMOVED = "removed_defaults"
    private const val KEY_OPEN_WITH = "open_with"

    private data class SiteOverride(
        val name: String,
        val url: String,
        val viewer: SiteViewer,
    )

    val defaults = listOf(
        FreenetSite(
            "River Chat",
            "http://127.0.0.1:7509/v1/contract/web/raAqMhMG7KUpXBU2SxgCQ3Vh4PYjttxdSWd9ftV7RLv/",
            true,
        ),
        FreenetSite(
            "Atlas",
            "http://127.0.0.1:7509/v1/contract/web/771DvtPMwt2PumPyrFvsz7fpvU1gogcmb5qtS1yYEEH9/",
            true,
        ),
        FreenetSite("Telemetry", "https://telemetry.freenet.org/", true),
        FreenetSite(
            "Freenet Contracts Radar",
            "http://127.0.0.1:7509/v1/contract/web/5cdjJ85tJ7VGrLbHEvofxtyrAowsTyXuj4UZ7ZjQ2QnL/",
            true,
        ),
        FreenetSite(
            "Delta",
            "http://127.0.0.1:7509/v1/contract/web/EqJ5YpEEV3XLqEvKWLQHFhGAac2qXzSUoE6k2zbdnXBr/",
            true,
        ),
        FreenetSite(
            "Ghostkey Identity Vault",
            "http://127.0.0.1:7509/v1/contract/web/DLog47hEsrtuGT4N5XCeMBG45m4n1aWM89tBZXue2E1N/",
            true,
        ),
        FreenetSite(
            "Freenet Mail",
            "http://127.0.0.1:7509/v1/contract/web/122f6AR7PyF8d8mhczuNQM4xrLtBf5t8g2iB7PEVT7KC/",
            true,
        ),
        FreenetSite(
            "FreePinion",
            "http://127.0.0.1:7509/v1/contract/web/9ji1n5Pnuhje4k4GtsyW33tMp4CGGkhLAM6KCPCAQVrY/",
            true,
        ),
    )

    fun openWith(context: Context): OpenWith =
        when (prefs(context).getString(KEY_OPEN_WITH, null)) {
            "browser" -> OpenWith.BROWSER
            "ask" -> OpenWith.ASK
            else -> OpenWith.APP
        }

    fun setOpenWith(context: Context, value: OpenWith) {
        val raw = when (value) {
            OpenWith.APP -> "app"
            OpenWith.BROWSER -> "browser"
            OpenWith.ASK -> "ask"
        }
        prefs(context).edit().putString(KEY_OPEN_WITH, raw).apply()
    }

    fun load(context: Context): List<FreenetSite> {
        val overrides = overrides(context)
        val removed = removedDefaults(context)
        val edited = defaults
            .filterNot { it.url in removed }
            .map { site ->
                overrides[site.url]?.let {
                    FreenetSite(it.name, it.url, true, it.viewer)
                } ?: site
            }
        return edited + customSites(context)
    }

    fun add(
        context: Context,
        name: String,
        url: String,
        viewer: SiteViewer = SiteViewer.INHERIT,
    ): List<FreenetSite> {
        val clean = url.trim()
        if (clean.isBlank()) return load(context)
        if (load(context).any { it.url == clean }) return load(context)
        val custom = customSites(context).toMutableList()
        custom.add(FreenetSite(name.trim().ifBlank { clean }, clean, false, viewer))
        saveCustom(context, custom)
        return load(context)
    }

    fun update(
        context: Context,
        index: Int,
        name: String,
        url: String,
        viewer: SiteViewer,
    ): List<FreenetSite> {
        val clean = url.trim()
        if (clean.isBlank()) return load(context)
        val cleanName = name.trim().ifBlank { clean }
        val current = load(context)
        val site = current.getOrNull(index) ?: return current
        if (site.predefined) {
            val overrides = overrides(context).toMutableMap()
            val original = defaultOriginal(overrides, site.url) ?: return current
            overrides[original] = SiteOverride(cleanName, clean, viewer)
            saveOverrides(context, overrides)
        } else {
            val custom = customSites(context).toMutableList()
            val customIndex = custom.indexOfFirst { it.url == site.url }
            if (customIndex >= 0) {
                custom[customIndex] = FreenetSite(cleanName, clean, false, viewer)
                saveCustom(context, custom)
            }
        }
        return load(context)
    }

    fun setViewer(
        context: Context,
        site: FreenetSite,
        viewer: SiteViewer,
    ): List<FreenetSite> {
        val current = load(context)
        if (site.predefined) {
            val overrides = overrides(context).toMutableMap()
            val original = defaultOriginal(overrides, site.url) ?: return current
            val existing = overrides[original]
            overrides[original] = SiteOverride(
                existing?.name ?: site.name,
                existing?.url ?: site.url,
                viewer,
            )
            saveOverrides(context, overrides)
        } else {
            val custom = customSites(context).toMutableList()
            val index = custom.indexOfFirst { it.url == site.url }
            if (index >= 0) {
                custom[index] = custom[index].copy(viewer = viewer)
                saveCustom(context, custom)
            }
        }
        return load(context)
    }

    fun remove(context: Context, site: FreenetSite): List<FreenetSite> {
        if (site.predefined) {
            val overrides = overrides(context)
            val original = defaultOriginal(overrides, site.url)
            if (original != null) {
                val removed = removedDefaults(context).toMutableSet()
                removed.add(original)
                saveRemovedDefaults(context, removed)
                if (overrides.containsKey(original)) {
                    val edited = overrides.toMutableMap()
                    edited.remove(original)
                    saveOverrides(context, edited)
                }
            }
        } else {
            val custom = customSites(context).filterNot { it.url == site.url }
            saveCustom(context, custom)
        }
        return load(context)
    }

    fun restoreDefaults(context: Context): List<FreenetSite> {
        prefs(context).edit().remove(KEY_REMOVED).remove(KEY_OVERRIDES).apply()
        return load(context)
    }

    private fun defaultOriginal(
        overrides: Map<String, SiteOverride>,
        currentUrl: String,
    ): String? = defaults.firstOrNull { default ->
        (overrides[default.url]?.url ?: default.url) == currentUrl
    }?.url

    private fun customSites(context: Context): List<FreenetSite> =
        decode(prefs(context).getString(KEY_CUSTOM, null), predefined = false)

    private fun overrides(context: Context): Map<String, SiteOverride> {
        val raw = prefs(context).getString(KEY_OVERRIDES, null) ?: return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            buildMap {
                for (key in root.keys()) {
                    val item = root.optJSONObject(key) ?: continue
                    val url = item.optString("url")
                    if (url.isNotBlank()) {
                        put(
                            key,
                            SiteOverride(
                                item.optString("name", url),
                                url,
                                viewerOf(item.optString("viewer")),
                            ),
                        )
                    }
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun removedDefaults(context: Context): Set<String> {
        val raw = prefs(context).getString(KEY_REMOVED, null) ?: return emptySet()
        return runCatching {
            val array = JSONArray(raw)
            buildSet {
                for (index in 0 until array.length()) {
                    val url = array.optString(index)
                    if (url.isNotBlank()) add(url)
                }
            }
        }.getOrDefault(emptySet())
    }

    private fun decode(raw: String?, predefined: Boolean): List<FreenetSite> {
        if (raw == null) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val url = item.optString("url")
                    if (url.isNotBlank()) {
                        add(
                            FreenetSite(
                                item.optString("name", url),
                                url,
                                predefined,
                                viewerOf(item.optString("viewer")),
                            ),
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveCustom(context: Context, sites: List<FreenetSite>) {
        val array = JSONArray()
        for (site in sites) {
            array.put(
                JSONObject()
                    .put("name", site.name)
                    .put("url", site.url)
                    .put("viewer", viewerName(site.viewer)),
            )
        }
        prefs(context).edit().putString(KEY_CUSTOM, array.toString()).apply()
    }

    private fun saveOverrides(context: Context, overrides: Map<String, SiteOverride>) {
        val root = JSONObject()
        for ((original, value) in overrides) {
            root.put(
                original,
                JSONObject()
                    .put("name", value.name)
                    .put("url", value.url)
                    .put("viewer", viewerName(value.viewer)),
            )
        }
        prefs(context).edit().putString(KEY_OVERRIDES, root.toString()).apply()
    }

    private fun saveRemovedDefaults(context: Context, removed: Set<String>) {
        val array = JSONArray()
        for (url in removed) {
            array.put(url)
        }
        prefs(context).edit().putString(KEY_REMOVED, array.toString()).apply()
    }

    private fun viewerOf(raw: String): SiteViewer = when (raw) {
        "app" -> SiteViewer.APP
        "browser" -> SiteViewer.BROWSER
        else -> SiteViewer.INHERIT
    }

    private fun viewerName(viewer: SiteViewer): String = when (viewer) {
        SiteViewer.APP -> "app"
        SiteViewer.BROWSER -> "browser"
        SiteViewer.INHERIT -> "inherit"
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
