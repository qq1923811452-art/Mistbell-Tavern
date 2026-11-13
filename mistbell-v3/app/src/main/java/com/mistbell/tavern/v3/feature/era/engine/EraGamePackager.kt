package com.mistbell.tavern.v3.feature.era.engine

import com.mistbell.tavern.v3.feature.era.EraGameInstall
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 把已解包的游戏目录打成一个「不压缩」的 ZIP，交给引擎组件。
 *
 * 引擎跑在另一个 APK 里，读不到本应用的私有目录，所以走文件描述符传包；
 * 压缩级别设 0 是为了让打包在 16k 文件量级也能几秒内完成——引擎解包本来就是顺序读写。
 */
internal class EraGamePackager(
    private val cacheDir: File,
) {
    fun versionKey(game: EraGameInstall): String = "${game.importedAtEpochMillis}-${game.fileCount}"

    fun pack(
        game: EraGameInstall,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): File {
        val key = versionKey(game).hashCode().toUInt().toString(HEX_RADIX)
        val zip = File(cacheDir, "era-pack-$key.zip")
        if (zip.isFile && zip.length() > 0L) return zip

        // 唯一临时名：并发或重试时两份打包不会互相踩；清理也只删成品，不碰 .part。
        val temp = File(cacheDir, "${zip.name}.${System.nanoTime()}.part")
        try {
            val files =
                game.directory
                    .walkTopDown()
                    .filter { it.isFile }
                    .sortedBy { it.path }
                    .toList()
            ZipOutputStream(BufferedOutputStream(temp.outputStream(), BUFFER_SIZE)).use { output ->
                output.setLevel(Deflater.NO_COMPRESSION)
                files.forEachIndexed { index, file ->
                    writeEntry(output, game.directory, file)
                    if (index % PROGRESS_STEP == 0 || index == files.lastIndex) {
                        onProgress(index + 1, files.size)
                    }
                }
            }
            if (!temp.renameTo(zip) && !(zip.isFile && zip.length() > 0L)) {
                // 另一个任务可能已经产出了同名成品，那种情况直接复用
                error("无法保存打包结果：${zip.name}")
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
        cacheDir.listFiles { file -> file.name.startsWith("era-pack-") && file.name.endsWith(".zip") && file != zip }
            ?.forEach { stale -> stale.delete() }
        return zip
    }

    private fun writeEntry(
        output: ZipOutputStream,
        root: File,
        file: File,
    ) {
        val relative = file.relativeTo(root).invariantSeparatorsPath
        if (relative.isEmpty()) return
        output.putNextEntry(ZipEntry(relative))
        file.inputStream().use { input -> input.copyTo(output, BUFFER_SIZE) }
        output.closeEntry()
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val HEX_RADIX = 16
        const val PROGRESS_STEP = 500
    }
}
