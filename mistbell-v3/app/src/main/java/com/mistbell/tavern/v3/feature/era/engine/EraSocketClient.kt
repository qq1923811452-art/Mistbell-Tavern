@file:Suppress("TooGenericExceptionCaught", "SwallowedException")

package com.mistbell.tavern.v3.feature.era.engine

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

internal class EraSocketClient {
    private val lock = Any()
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null

    suspend fun open(
        port: Int,
        timeoutMs: Long,
    ): BufferedReader? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (currentCoroutineContext().isActive && System.currentTimeMillis() < deadline) {
            val candidate = Socket()
            try {
                candidate.connect(InetSocketAddress(LOOPBACK, port), CONNECT_TIMEOUT_MS)
                candidate.tcpNoDelay = true
                val out = BufferedWriter(OutputStreamWriter(candidate.getOutputStream(), Charsets.UTF_8))
                val input = BufferedReader(InputStreamReader(candidate.getInputStream(), Charsets.UTF_8))
                synchronized(lock) {
                    closeLocked()
                    socket = candidate
                    writer = out
                }
                return input
            } catch (_: IOException) {
                runCatching { candidate.close() }
                delay(RETRY_INTERVAL_MS)
            }
        }
        return null
    }

    fun send(line: String) {
        synchronized(lock) {
            val out = writer ?: return
            try {
                out.write(line)
                out.write("\n")
                out.flush()
            } catch (_: IOException) {
                runCatching { closeLocked() }
            }
        }
    }

    fun close() {
        synchronized(lock) { closeLocked() }
    }

    private fun closeLocked() {
        runCatching { writer?.close() }
        runCatching { socket?.close() }
        writer = null
        socket = null
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val CONNECT_TIMEOUT_MS = 1000
        const val RETRY_INTERVAL_MS = 200L
    }
}
