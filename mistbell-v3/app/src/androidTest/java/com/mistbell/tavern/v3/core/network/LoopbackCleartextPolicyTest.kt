package com.mistbell.tavern.v3.core.network

import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Guards both the platform policy and actual HTTP transport for local relays. */
@RunWith(AndroidJUnit4::class)
class LoopbackCleartextPolicyTest {
    private val policy = NetworkSecurityPolicy.getInstance()

    @Test
    fun loopbackHostsAllowCleartext() {
        listOf("127.0.0.1", "localhost", "::1").forEach { host ->
            assertTrue(host, policy.isCleartextTrafficPermitted(host))
        }
    }

    @Test
    fun nonLoopbackHostsStayHttpsOnly() {
        listOf("example.com", "api.deepseek.com", "10.0.2.2", "192.168.1.1", "sub.localhost", "::2")
            .forEach { host -> assertFalse(host, policy.isCleartextTrafficPermitted(host)) }
        assertFalse(policy.isCleartextTrafficPermitted())
    }

    @Test
    fun actualHttpRequestsReachIpv4AndIpv6Loopback() {
        assertLoopbackRequest("127.0.0.1", "127.0.0.1")
        assertLoopbackRequest("localhost", "127.0.0.1")
        assertLoopbackRequest("[::1]", "::1")
    }

    private fun assertLoopbackRequest(
        urlHost: String,
        bindHost: String,
    ) {
        val executor = Executors.newSingleThreadExecutor()
        val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
        try {
            ServerSocket(0, 1, InetAddress.getByName(bindHost)).use { server ->
                server.soTimeout = 10_000
                val served =
                    executor.submit<String> {
                        server.accept().use { socket ->
                            socket.soTimeout = 10_000
                            val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                            val requestLine = reader.readLine()
                            while (!reader.readLine().isNullOrEmpty()) {
                                // Consume HTTP headers before sending the response.
                            }
                            socket.getOutputStream().write(
                                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK"
                                    .toByteArray(Charsets.US_ASCII),
                            )
                            requestLine
                        }
                    }
                val validation = EndpointPolicy.validateBaseUrl("http://$urlHost:${server.localPort}/v1")
                assertTrue(validation.reason, validation.isAllowed)
                val request = Request.Builder().url(requireNotNull(validation.normalizedBaseUrl)).build()
                client.newCall(request).execute().use { response ->
                    assertEquals(200, response.code)
                    assertEquals("OK", response.body?.string())
                }
                assertEquals("GET /v1 HTTP/1.1", served.get(10, TimeUnit.SECONDS))
            }
        } finally {
            executor.shutdownNow()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }
}
