package com.mistbell.tavern.android

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mistbell.tavern.android.navigation.AppNavigation
import com.mistbell.tavern.android.ui.theme.MistbellThemeWithSettings

class MainActivity : ComponentActivity() {
    companion object {
        private const val PREFERRED_REFRESH_RATE_HZ = 120f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 请求系统优先使用 120Hz；设备/系统不支持时由 Android 自动选择可用刷新率。
        @Suppress("DEPRECATION")
        window.attributes = window.attributes.apply { preferredRefreshRate = PREFERRED_REFRESH_RATE_HZ }
        // Android 15（targetSdk 35）已默认启用 edge-to-edge；旧系统保留显式配置。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            enableEdgeToEdge()
        }
        setContent {
            MistbellThemeWithSettings {
                AppNavigation()
            }
        }
    }
}
