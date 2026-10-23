package com.mistbell.tavern.v3.core.settings

import android.content.SharedPreferences

/**
 * Reads an Int preference that may have been persisted with the wrong type by an older build.
 *
 * A String value is parsed and rewritten as an Int so the bad entry does not crash later reads.
 */
fun SharedPreferences.getIntCompat(key: String, defaultValue: Int): Int {
    val value = runCatching { getInt(key, defaultValue) }.getOrElse {
        val raw = runCatching { getString(key, null) }.getOrNull()
        raw?.trim()?.toIntOrNull() ?: defaultValue
    }
    if (runCatching { getInt(key, Int.MIN_VALUE) }.getOrNull() == Int.MIN_VALUE) {
        edit().putInt(key, value).apply()
    }
    return value
}
