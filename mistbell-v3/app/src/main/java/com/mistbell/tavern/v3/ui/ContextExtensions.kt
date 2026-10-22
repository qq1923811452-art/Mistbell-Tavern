package com.mistbell.tavern.v3.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * Compose exposes a theme-wrapped context, so a direct cast to Activity can fail. Walk the
 * wrapper chain to find the hosting activity.
 */
fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
