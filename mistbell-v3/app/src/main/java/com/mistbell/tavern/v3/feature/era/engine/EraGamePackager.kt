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

    fun pack(game: EraGameInstall): File {
        val key = versionKey(game).hashCode().toUInt().toString(HEX_RADIX)
        val zip = File(cacheDir, "era-pack-$key.zip")
        if (zip.isFile && zip.length() > 0L) return zip

        val temp = File(cacheDir, "${zip.name}.part")
        val files =
            game.directory
                .walkTopDown()
                .filter { it.isFile && it.length() >= 0 }
                .sortedBy { it.path }
        ZipOutputStream(BufferedOutputStream(temp.outputStream(), BUFFER_SIZE)).use { output ->
            output.setLevel(Deflater.NO_COMPRESSION)
            files.forEach { file -> writeEntry(output, game.directory, file) }
        }
        if (!temp.renameTo(zip)) {
            temp.copyTo(zip, overwrite = true)
            temp.delete()
        }
        cacheDir.listFiles { file -> file.name.startsWith("era-pack-") && file != zip }
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
    }
}
