package com.mistbell.tavern.android.util

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 有界读取工具。**所有从外部输入导入文件的路径都必须使用它。**
 *
 * 直接用 `readText()` / `readBytes()` 会把「用户选中一个超大文件」变成 OOM：
 * 这条路径的输入来自 SAF / 分享 / 第三方文件，其大小不可信任。
 *
 * 超限时抛 [IllegalArgumentException]；调用方应把异常当作「导入失败」处理，
 * 不要吞掉后继续用不完整的数据。
 */
object BoundedRead {
    /** 单次导入的默认上限，与 [SessionImporter] 保持一致。 */
    const val DEFAULT_MAX_BYTES: Int = 32 * 1024 * 1024

    private const val IO_BUFFER_SIZE_BYTES = 8 * 1024
    private const val BYTES_PER_MIB = 1024 * 1024

    /** 读取为 UTF-8 文本；超过 [maxBytes] 抛 [IllegalArgumentException]。 */
    fun text(
        input: InputStream,
        maxBytes: Int = DEFAULT_MAX_BYTES,
        label: String = "导入文件",
    ): String = String(bytes(input, maxBytes, label), Charsets.UTF_8)

    /** 读取为字节数组；超过 [maxBytes] 抛 [IllegalArgumentException]。 */
    fun bytes(
        input: InputStream,
        maxBytes: Int = DEFAULT_MAX_BYTES,
        label: String = "导入文件",
    ): ByteArray {
        require(maxBytes >= 0) { "maxBytes must not be negative" }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(IO_BUFFER_SIZE_BYTES)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read.toLong()
            require(total <= maxBytes.toLong()) { "${label}超过 ${maxBytes / BYTES_PER_MIB} MiB 限制" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}
