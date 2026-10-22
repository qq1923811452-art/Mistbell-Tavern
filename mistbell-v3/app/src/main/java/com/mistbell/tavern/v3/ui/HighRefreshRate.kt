package com.mistbell.tavern.v3.ui

import android.app.Activity
import android.os.Build
import android.view.WindowManager

/**
 * Requests the display's highest refresh rate for this window.
 *
 * Android defaults many windows to 60 Hz; 120 Hz panels therefore need an explicit request.
 * The call is best-effort: unsupported modes or vendor restrictions are ignored so the app keeps
 * running at the system default.
 */
object HighRefreshRate {

    private const val MIN_HIGH_REFRESH_RATE = 90f
    private const val MAX_REQUESTED_RATE = 120f

    fun apply(activity: Activity) {
        val window = activity.window ?: return
        val display = window.decorView.display ?: return
        val bestRate = display.supportedModes
            .maxOfOrNull { it.refreshRate }
            ?.takeIf { it >= MIN_HIGH_REFRESH_RATE }
            ?: return
        val requested = bestRate.coerceAtMost(MAX_REQUESTED_RATE)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setFrameRate(requested, WindowManager.LayoutParams.FRAME_RATE_COMPATIBILITY_DEFAULT)
            } else {
                window.attributes = window.attributes.apply { preferredRefreshRate = requested }
            }
        }
    }
}
