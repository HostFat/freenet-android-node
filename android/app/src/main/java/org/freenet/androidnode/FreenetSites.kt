package org.freenet.androidnode

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class FreenetSite(
    val name: String,
    val url: String,
    val predefined: Boolean = false,
)

internal object FreenetSites {
    private const val PREFS = "freenet_sites"
    private const val KEY_CUSTOM = "custom_sites"
    private const val KEY_OVERRIDES = "default_overrides"
    private const val KEY_REMOVED = "removed_defaults"

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

    fun load(context: Context): List<FreenetSite> {
        val overrides = overrides(context)
        val removed = removedDefaults(context)
        val edited = defaults
            .filterNot { it.url in removed }
            .map { site ->
                overrides[site.url]?.let { FreenetSite(it.first, it.second, true) } ?: site
            }
        return edited + customSites(context).map { FreenetSite(it.name, it.url) }
    }

    fun add(context: Context, name: String, url: String): List<FreenetSite> {
        val clean = url.trim()
        if (clean.isBlank()) return load(context)
        if (load(context).any { it.url == clean }) return load(context)
        val custom = customSites(context).toMutableList()
        custom.add(FreenetSite(name.trim().ifBlank { clean }, clean))
        saveCustom(context, custom)
        return load(context)
    }

    fun update(context: Context, index: Int, name: String, url: String): List<FreenetSite> {
        val clean = url.trim()
        if (clean.isBlank()) return load(context)
        val cleanName = name.trim().ifBlank { clean }
        val current = load(context)
        val site = current.getOrNull(index) ?: return current
        if (site.predefined) {
            val overrides = overrides(context).toMutableMap()
            val original = defaults.firstOrNull { default ->
                (overrides[default.url]?.second ?: default.url) == site.url
            }?.url ?: return current
            overrides[original] = cleanName to clean
            saveOverrides(context, overrides)
        } else {
            val custom = customSites(context).toMutableList()
            val customIndex = custom.indexOfFirst { it.url == site.url }
            if (customIndex >= 0) {
                custom[customIndex] = FreenetSite(cleanName, clean)
                saveCustom(context, custom)
            }
        }
        return load(context)
    }

    fun remove(context: Context, site: FreenetSite): List<FreenetSite> {
        if (site.predefined) {
            val overrides = overrides(context)
            val original = defaults.firstOrNull { default ->
                (overrides[default.url]?.second ?: default.url) == site.url
            }?.url
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

    private fun customSites(context: Context): List<FreenetSite> =
        decode(prefs(context).getString(KEY_CUSTOM, null), predefined = false)

    private fun overrides(context: Context): Map<String, Pair<String, String>> {
        val raw = prefs(context).getString(KEY_OVERRIDES, null) ?: return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            buildMap {
                for (key in root.keys()) {
                    val item = root.optJSONObject(key) ?: continue
                    val url = item.optString("url")
                    if (url.isNotBlank()) {
                        put(key, item.optString("name", url) to url)
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
                        add(FreenetSite(item.optString("name", url), url, predefined))
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveCustom(context: Context, sites: List<FreenetSite>) {
        val array = JSONArray()
        for (site in sites) {
            array.put(JSONObject().put("name", site.name).put("url", site.url))
        }
        prefs(context).edit().putString(KEY_CUSTOM, array.toString()).apply()
    }

    private fun saveOverrides(context: Context, overrides: Map<String, Pair<String, String>>) {
        val root = JSONObject()
        for ((original, values) in overrides) {
            root.put(original, JSONObject().put("name", values.first).put("url", values.second))
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

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
