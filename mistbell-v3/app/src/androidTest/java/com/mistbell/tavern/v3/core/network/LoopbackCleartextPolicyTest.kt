package com.mistbell.tavern.v3.core.network

import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the platform half of the loopback exception: EndpointPolicy accepts
 * http://127.0.0.1 for local relays, so res/xml/network_security_config.xml must
 * actually permit cleartext there, otherwise requests fail with
 * "CLEARTEXT communication to 127.0.0.1 not permitted".
 */
@RunWith(AndroidJUnit4::class)
class LoopbackCleartextPolicyTest {
    private val policy = NetworkSecurityPolicy.getInstance()

    @Test
    fun loopbackHostsAllowCleartext() {
        assertTrue(policy.isCleartextTrafficPermitted("127.0.0.1"))
        assertTrue(policy.isCleartextTrafficPermitted("localhost"))
    }

    @Test
    fun everyOtherHostStaysHttpsOnly() {
        assertFalse(policy.isCleartextTrafficPermitted("example.com"))
        assertFalse(policy.isCleartextTrafficPermitted("api.deepseek.com"))
        assertFalse(policy.isCleartextTrafficPermitted("10.0.2.2"))
    }
}
