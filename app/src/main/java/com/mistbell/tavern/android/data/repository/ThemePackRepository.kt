package com.mistbell.tavern.android.data.repository

import android.content.Context
import android.net.Uri
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.local.entity.SettingsEntity
import com.mistbell.tavern.android.data.local.entity.ThemePackEntity
import com.mistbell.tavern.android.data.theme.ThemeSupport
import com.mistbell.tavern.android.data.theme.ThemeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 主题包 manifest（manifest.json） */
@Serializable
private data class ThemeManifest(
    val id: String,
    val name: String,
    val author: String = "",
    val version: String = "1",
    val capabilities: String = "cosmetic",
)

/**
 * 主题包仓库：导入/导出 zip、增删查、应用链 tokens 解析。
 * 皮肤级：纯数据 tokens，无代码执行。
 */
@Suppress("TooManyFunctions")
class ThemePackRepository(private val context: Context) {
    private val db get() = TavernApplication.instance.container.database

    private val manifestJson =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private val imageExtensions = setOf("png", "jpg", "jpeg", "webp")

    private companion object {
        const val MAX_THEME_ZIP_ENTRIES = 512
        const val MAX_THEME_ENTRY_BYTES = 16L * 1024L * 1024L
        const val MAX_THEME_TOTAL_BYTES = 64L * 1024L * 1024L
    }

    fun observePacks(): Flow<List<ThemePackEntity>> = db.themePackDao().observeAll()

    fun observeActiveThemeId(): Flow<String?> = db.settingsDao().observeValue("active_theme_id")

    /** null/空串 = 恢复默认 */
    suspend fun setActiveTheme(id: String?) =
        withContext(Dispatchers.IO) {
            val value = id?.trim().orEmpty()
            db.settingsDao().upsert(SettingsEntity("active_theme_id", value))
        }

    /**
     * 从 zip 导入主题包，返回主题 id。
     * zip 结构: manifest.json / theme.json / 背景图放 assets/。
     * 同 id 覆盖安装；Entry 名含 ".." 一律拒绝（防路径穿越）。
     * 坏包抛 IllegalArgumentException（中文消息）。
     */
    suspend fun importFromUri(uri: Uri): String =
        withContext(Dispatchers.IO) {
            var manifest: ThemeManifest? = null
            var themeJson: String? = null
            var backgroundName: String? = null
            val backgroundBytes = mutableListOf<Pair<String, ByteArray>>()
            var totalBytes = 0L
            var entryCount = 0

            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    ZipInputStream(input.buffered()).use { zip ->
                        var entry: ZipEntry? = zip.nextEntry
                        if (entry == null) throw IllegalArgumentException("主题包无效：zip 内没有任何文件")
                        while (entry != null) {
                            val currentEntry = entry
                            val name = currentEntry.name
                            entryCount++
                            require(entryCount <= MAX_THEME_ZIP_ENTRIES) { "主题包非法：条目数量超出限制" }
                            require(
                                !name.startsWith("/") &&
                                    '\\' !in name &&
                                    name.removeSuffix("/").split('/').none { it == "." || it == ".." },
                            ) { "主题包非法：条目路径不安全" }
                            if (!currentEntry.isDirectory) {
                                val remainingBytes = MAX_THEME_TOTAL_BYTES - totalBytes
                                require(remainingBytes > 0L) { "主题包非法：解压内容超出大小限制" }
                                val content = readBoundedEntry(zip, minOf(MAX_THEME_ENTRY_BYTES, remainingBytes))
                                totalBytes += content.size
                                when {
                                    name == "manifest.json" ->
                                        manifest = parseManifest(content.toString(Charsets.UTF_8))
                                    name == "theme.json" ->
                                        themeJson = content.toString(Charsets.UTF_8)
                                    name.startsWith("assets/") -> {
                                        val fileName = name.removePrefix("assets/").substringAfterLast('/')
                                        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
                                        if (ext in imageExtensions && backgroundName == null) {
                                            backgroundName = fileName
                                            backgroundBytes.add(fileName to content)
                                        }
                                    }
                                }
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                } ?: throw IllegalArgumentException("主题包导入失败：无法打开所选文件")
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: Exception) {
                throw IllegalArgumentException("主题包导入失败：zip 解压异常（${e.message ?: "未知错误"}）")
            }

            val m = manifest ?: throw IllegalArgumentException("主题包无效：缺少 manifest.json")
            if (themeJson == null) throw IllegalArgumentException("主题包无效：缺少 theme.json")
            // 落库前校验 tokens 可解析，坏包直接拒绝（避免坏 JSON 入库后打死应用链）
            if (ThemeSupport.parseTokens(themeJson!!) == null) {
                throw IllegalArgumentException("主题包无效：theme.json 无法解析为有效的主题定义")
            }
            if (!Regex("^[A-Za-z0-9_-]{1,64}$").matches(m.id)) {
                throw IllegalArgumentException("主题包非法：manifest.id 不合法（仅允许 1-64 位字母/数字/下划线/连字符）")
            }

            // 覆盖安装采用"先写新、后清旧"顺序：中途失败时旧行仍在（最多背景文件被同名覆盖），
            // 不会出现"旧包已删、新包未落库"的数据丢失窗口
            val packDir = File(context.filesDir, "themes/${m.id}")
            packDir.mkdirs()
            var storedBackground: String? = null
            if (backgroundName != null) {
                val bg = backgroundBytes.first()
                File(packDir, bg.first).writeBytes(bg.second)
                storedBackground = bg.first
            }

            val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            db.themePackDao().upsert(
                ThemePackEntity(
                    id = m.id,
                    name = m.name,
                    author = m.author,
                    version = m.version,
                    tokensJson = themeJson!!,
                    backgroundFile = storedBackground,
                    createdAt = now,
                ),
            )

            // 清理包目录中不再被引用的遗留文件（覆盖安装后旧背景等）
            packDir.listFiles()?.forEach { f ->
                if (storedBackground == null || f.name != storedBackground) f.delete()
            }
            m.id
        }

    private fun readBoundedEntry(
        zip: ZipInputStream,
        maxBytes: Long,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = zip.read(buffer)
            if (read < 0) break
            total += read
            require(total <= maxBytes) { "主题包非法：单个文件超出解压大小限制" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun parseManifest(text: String): ThemeManifest {
        val m =
            try {
                manifestJson.decodeFromString<ThemeManifest>(text)
            } catch (e: Exception) {
                throw IllegalArgumentException("主题包无效：manifest.json 解析失败（${e.message ?: "格式错误"}）")
            }
        if (m.id.isBlank()) throw IllegalArgumentException("主题包非法：manifest.id 为空")
        return m
    }

    /** 删行 + 递归删 filesDir/themes/<id> */
    suspend fun deletePack(id: String) =
        withContext(Dispatchers.IO) {
            db.themePackDao().deleteById(id)
            File(context.filesDir, "themes/$id").deleteRecursively()
        }

    /** 背景图文件：filesDir/themes/<id>/<backgroundFile>，不存在返回 null */
    fun backgroundFile(pack: ThemePackEntity): File? {
        val name = pack.backgroundFile ?: return null
        val f = File(File(context.filesDir, "themes/${pack.id}"), name)
        return if (f.exists()) f else null
    }

    /**
     * 应用链命中的包实体（会话 → 角色 → 全局 → null）；tokens 与背景图共用此解析保证语义一致。
     * sessionId 空白时跳过会话层，不订阅 observeById（避免无谓查询）。
     */
    fun observeResolvedPack(
        sessionId: String?,
        characterId: String?,
    ): Flow<ThemePackEntity?> {
        val sid = sessionId?.trim().orEmpty()
        val sessionTheme =
            if (sid.isEmpty()) {
                kotlinx.coroutines.flow.flowOf<com.mistbell.tavern.android.data.local.entity.SessionEntity?>(null)
            } else {
                db.sessionDao().observeById(sid)
            }
        val charId = characterId?.trim().orEmpty()
        val characterTheme =
            if (charId.isEmpty()) {
                kotlinx.coroutines.flow.flowOf<String?>(null)
            } else {
                db.characterDao().getAll().map { list -> list.find { it.id == charId }?.themeId }
            }
        return combine(
            sessionTheme.map { it?.themeId },
            characterTheme,
            db.themePackDao().observeAll(),
            db.settingsDao().observeValue("active_theme_id"),
        ) { sessionThemeId, charThemeId, packs, activeId ->
            val map = packs.associateBy { it.id }
            ThemeSupport.resolvePackId(sessionThemeId, charThemeId, activeId, map)?.let { map[it] }
        }
    }

    /** 角色主题 tokens 流（保留原签名，供 Theme.kt 使用）：characterId 空 → 全局主题；无 → null */
    fun observeTokensForCharacter(characterId: String?): Flow<ThemeTokens?> =
        observeResolvedPack(null, characterId)
            .map { pack -> pack?.let { ThemeSupport.parseTokens(it.tokensJson) } }

    /** 重建 zip 到 cacheDir/themes/<id>.zip 供分享，不存在则抛 IllegalArgumentException */
    suspend fun exportPack(id: String): File =
        withContext(Dispatchers.IO) {
            val pack =
                db.themePackDao().getById(id)
                    ?: throw IllegalArgumentException("导出失败：主题包不存在（id=$id）")
            val outFile = File(File(context.cacheDir, "themes"), "$id.zip")
            outFile.parentFile?.mkdirs()
            ZipOutputStream(outFile.outputStream().buffered()).use { zip ->
                val manifest =
                    ThemeManifest(
                        id = pack.id,
                        name = pack.name,
                        author = pack.author,
                        version = pack.version,
                        capabilities = "cosmetic",
                    )
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifestJson.encodeToString(ThemeManifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("theme.json"))
                zip.write(pack.tokensJson.toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                val bg = backgroundFile(pack)
                if (bg != null) {
                    zip.putNextEntry(ZipEntry("assets/${bg.name}"))
                    bg.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            outFile
        }
}
