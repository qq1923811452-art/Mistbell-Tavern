package com.mistbell.tavern.v3

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CiSmokeInstrumentedTest {
    @Test
    fun applicationContextStartsOnAndroidRuntime() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("com.mistbell.tavern.v3", context.packageName)
    }
}
