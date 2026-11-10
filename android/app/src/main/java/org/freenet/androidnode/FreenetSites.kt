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
    private const val KEY = "custom_sites"

    val defaults = listOf(
        FreenetSite("Dashboard", "http://127.0.0.1:7509/", true),
        FreenetSite(
            "River",
            "http://127.0.0.1:7509/v1/contract/web/raAqMhMG7KUpXBU2SxgCQ3Vh4PYjttxdSWd9ftV7RLv/",
            true,
        ),
        FreenetSite(
            "FreePinion",
            "http://127.0.0.1:7509/v1/contract/web/9ji1n5Pnuhje4k4GtsyW33tMp4CGGkhLAM6KCPCAQVrY/",
            true,
        ),
        FreenetSite("Freenet website", "https://freenet.org/", true),
    )

    fun load(context: Context): List<FreenetSite> {
        val raw = prefs(context).getString(KEY, null) ?: return defaults
        val custom = runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val url = item.optString("url")
                    if (url.isNotBlank()) {
                        add(FreenetSite(item.optString("name", url), url, false))
                    }
                }
            }
        }.getOrDefault(emptyList())
        return defaults + custom
    }

    fun add(context: Context, name: String, url: String): List<FreenetSite> {
        val clean = url.trim()
        if (clean.isBlank()) return load(context)
        val custom = load(context)
            .filterNot { it.predefined }
            .filterNot { it.url == clean }
            .toMutableList()
        custom.add(FreenetSite(name.trim().ifBlank { clean }, clean, false))
        save(context, custom)
        return load(context)
    }

    fun remove(context: Context, site: FreenetSite): List<FreenetSite> {
        if (site.predefined) return load(context)
        val custom = load(context)
            .filterNot { it.predefined }
            .filterNot { it.url == site.url }
        save(context, custom)
        return load(context)
    }

    private fun save(context: Context, custom: List<FreenetSite>) {
        val array = JSONArray()
        for (site in custom) {
            array.put(JSONObject().put("name", site.name).put("url", site.url))
        }
        prefs(context).edit().putString(KEY, array.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
