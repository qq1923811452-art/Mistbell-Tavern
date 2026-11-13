package com.mistbell.tavern.v3.feature.era

import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.util.UUID
import java.util.zip.ZipFile

/**
 * Installs an eraTW ZIP into the app-private game library.
 *
 * There is deliberately no hash check: these packs change often, and the archive is the source of
 * truth. The installer still guards against path traversal, zip bombs, and accidental loss of the
 * player's `sav/` folder when a pack is updated.
 */
class EraGameZipInstaller(
    private val gamesRoot: File,
) {
    fun install(
        zipFile: File,
        displayName: String,
    ): EraGameInstall {
        require(zipFile.isFile) { "所选 ZIP 不存在或无法读取" }
        gamesRoot.mkdirs()
        require(gamesRoot.isDirectory) { "无法创建游戏目录" }

        val name = sanitizeDisplayName(displayName.ifBlank { zipFile.nameWithoutExtension })
        val target = File(gamesRoot, name)
        val token = UUID.randomUUID().toString()
        val staging = File(gamesRoot, ".staging-$token")
        val backup = File(gamesRoot, ".backup-$token")
        val preserve = File(gamesRoot, ".preserve-$token")
        val hadExisting = target.isDirectory
        var targetMovedToBackup = false
        var targetReplaced = false

        try {
            check(staging.mkdirs()) { "无法创建临时解压目录" }
            val fileCount = extract(zipFile, staging)
            require(fileCount > 0) { "ZIP 中没有可导入的文件" }
            unwrapSingleGameRoot(staging)

            if (hadExisting) {
                check(preserve.mkdirs()) { "无法创建存档备份目录" }
                moveSaves(target, preserve)
                check(target.renameTo(backup)) { "无法替换旧游戏目录" }
                targetMovedToBackup = true
            }

            check(staging.renameTo(target)) { "无法写入游戏目录" }
            targetReplaced = true
            restoreSaves(preserve, target)

            backup.deleteRecursively()
            preserve.deleteRecursively()
            return EraGameInstall(
                name = name,
                directory = target,
                importedAtEpochMillis = System.currentTimeMillis(),
                fileCount = fileCount,
            )
        } catch (error: Throwable) {
            if (targetReplaced) {
                target.deleteRecursively()
            }
            if (targetMovedToBackup && backup.exists() && !target.exists()) {
                backup.renameTo(target)
            }
            if (preserve.exists() && target.exists()) {
                restoreSaves(preserve, target)
            }
            preserve.deleteRecursively()
            throw error
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun extract(
        zipFile: File,
        destination: File,
    ): Int {
        val root = destination.canonicalFile
        val rootPrefix = root.path + File.separator
        var fileCount = 0
        var totalBytes = 0L

        openZip(zipFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val entryName = normalizeEntryName(entry.name)
                if (entryName == null || entry.isDirectory) continue
                if (entryName.startsWith("__MACOSX/") || entryName.endsWith(".DS_Store")) continue

                val outputFile = File(root, entryName)
                check(outputFile.canonicalPath.startsWith(rootPrefix)) { "ZIP 包含非法路径" }
                outputFile.parentFile?.mkdirs()

                zip.getInputStream(entry).use { input ->
                    outputFile.outputStream().buffered().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            totalBytes += read
                            check(totalBytes <= MAX_TOTAL_BYTES) { "ZIP 解压后超过大小上限" }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                fileCount++
                check(fileCount <= MAX_ENTRIES) { "ZIP 文件数量超过上限" }
            }
        }
        return fileCount
    }


    /**
     * A lot of Windows ZIPs contain one top-level folder (`eraTW/erb`, `eraTW/csv`, ...). The
     * engine expects the game root itself, so lift that folder's children up when it is clearly a
     * game root. Archives with several top-level entries are left alone.
     */
    private fun unwrapSingleGameRoot(staging: File) {
        val children =
            staging.listFiles()
                ?.filterNot { it.name == "__MACOSX" || it.name == ".DS_Store" }
                ?: return
        val root = children.singleOrNull()?.takeIf { it.isDirectory } ?: return
        val looksLikeGame =
            File(root, "erb").isDirectory ||
                File(root, "csv").isDirectory ||
                File(root, "emuera.config").isFile
        if (!looksLikeGame) return
        root.listFiles()?.forEach { child ->
            val destination = File(staging, child.name)
            destination.deleteRecursively()
            if (!child.renameTo(destination)) {
                child.copyRecursively(destination, overwrite = true)
                child.deleteRecursively()
            }
        }
        root.deleteRecursively()
    }

    private fun openZip(zipFile: File): ZipFile =
        runCatching { ZipFile(zipFile, Charset.forName("MS932")) }.getOrElse { ZipFile(zipFile) }

    private fun normalizeEntryName(rawName: String): String? {
        val unified = rawName.replace('\\', '/')
        if (unified.startsWith("/") || DRIVE_PATTERN.containsMatchIn(unified) || unified.contains('\u0000')) {
            throw IOException("ZIP 包含非法路径")
        }
        val parts = unified.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty()) return null
        if (parts.any { it == ".." }) throw IOException("ZIP 包含非法路径")
        return parts.joinToString("/")
    }

    private fun moveSaves(
        source: File,
        destination: File,
    ) {
        val saveDirectory = File(source, "sav")
        if (saveDirectory.isDirectory) {
            val target = File(destination, "sav")
            target.deleteRecursively()
            if (!saveDirectory.renameTo(target)) {
                saveDirectory.copyRecursively(target, overwrite = true)
                saveDirectory.deleteRecursively()
            }
        }
        source.listFiles()
            ?.filter { it.isFile && SAVE_FILE.matches(it.name) }
            ?.forEach { saveFile ->
                val target = File(destination, saveFile.name)
                target.delete()
                if (!saveFile.renameTo(target)) {
                    saveFile.copyTo(target, overwrite = true)
                    saveFile.delete()
                }
            }
    }

    private fun restoreSaves(
        preserve: File,
        target: File,
    ) {
        if (!preserve.isDirectory) return
        val saveDirectory = File(preserve, "sav")
        if (saveDirectory.isDirectory) {
            val destination = File(target, "sav")
            destination.deleteRecursively()
            if (!saveDirectory.renameTo(destination)) {
                saveDirectory.copyRecursively(destination, overwrite = true)
            }
        }
        preserve.listFiles()
            ?.filter { it.isFile && SAVE_FILE.matches(it.name) }
            ?.forEach { saveFile ->
                val destination = File(target, saveFile.name)
                destination.delete()
                if (!saveFile.renameTo(destination)) {
                    saveFile.copyTo(destination, overwrite = true)
                }
            }
        preserve.deleteRecursively()
    }

    private fun sanitizeDisplayName(rawName: String): String {
        val trimmed = rawName.trim()
        val withoutExtension = if (trimmed.endsWith(".zip", ignoreCase = true)) trimmed.dropLast(4) else trimmed
        val cleaned = withoutExtension.replace(INVALID_NAME_CHARS, "_").trim().trim('.')
        return cleaned.take(MAX_NAME_LENGTH).ifBlank { DEFAULT_GAME_NAME }
    }

    private companion object {
        const val DEFAULT_GAME_NAME = "eraTW"
        const val MAX_NAME_LENGTH = 64
        const val BUFFER_SIZE = 64 * 1024
        const val MAX_ENTRIES = 60_000
        const val MAX_TOTAL_BYTES = 1_500_000_000L
        val INVALID_NAME_CHARS = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
        val DRIVE_PATTERN = Regex("^[A-Za-z]:")
        val SAVE_FILE = Regex("^save.*\\.sav$", RegexOption.IGNORE_CASE)
    }
}
