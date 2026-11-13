@file:Suppress("TooGenericExceptionCaught", "SwallowedException")

package com.mistbell.tavern.v3.feature.era.engine

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 回环 TCP 上的 EWP/0.3 通道。
 *
 * 引擎（另一个进程）在 127.0.0.1 监听一个随机端口，只允许一个客户端；
 * 前端连上后先发 hello.ok，之后是「引擎出事件、前端回命令」的单行 JSON。
 */
internal class EwpClient {
    private val lock = Any()
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null

    /** 阻塞等待引擎开始监听（解包大包时可能要几十秒）。返回读端，超时返回 null。 */
    fun open(
        port: Int,
        timeoutMs: Long,
    ): BufferedReader? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
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
            } catch (error: IOException) {
                runCatching { candidate.close() }
                Thread.sleep(RETRY_INTERVAL_MS)
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
            } catch (error: IOException) {
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
        const val RETRY_INTERVAL_MS = 250L
    }
}
