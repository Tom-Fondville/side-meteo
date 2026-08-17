package fr.sidemeteo

import android.content.Context
import kotlinx.serialization.encodeToString

/**
 * SharedPreferences-backed state: the selected city, and the last successful
 * response body with its timestamp.
 *
 * The cache holds the raw body, so a cached read goes through the same
 * parsing path as a network read.
 */
class Store(context: Context) {

    private val prefs = context.getSharedPreferences("side-meteo", Context.MODE_PRIVATE)

    var city: City?
        get() = prefs.getString(KEY_CITY, null)
            ?.let { runCatching { lenientJson.decodeFromString<City>(it) }.getOrNull() }
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_CITY) else putString(KEY_CITY, lenientJson.encodeToString(value))
            }.apply()
        }

    fun saveCache(body: String) {
        prefs.edit()
            .putString(KEY_BODY, body)
            .putLong(KEY_AT, System.currentTimeMillis())
            .apply()
    }

    fun cachedBody(): String? = prefs.getString(KEY_BODY, null)

    fun cachedAt(): Long? = prefs.getLong(KEY_AT, 0L).takeIf { it > 0L }

    fun clearCache() {
        prefs.edit()
            .remove(KEY_BODY)
            .remove(KEY_AT)
            .apply()
    }

    private companion object {
        const val KEY_CITY = "city"
        const val KEY_BODY = "cache_body"
        const val KEY_AT = "cache_at"
    }
}
