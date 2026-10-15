package com.mistbell.tavern.android.util

import android.content.Context
import android.net.Uri
import com.mistbell.tavern.android.data.local.entity.CharacterEntity
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * CHARX 格式导入器 (Character Card V3 ZIP 容器格式)
 *
 * CHARX 是一个 ZIP 文件，包含：
 * - card.json (或 card.png) - 角色卡主文件
 * - assets/ - 多媒体资源文件夹
 *   - avatar.png - 头像
 *   - expressions/ - 表情立绘
 *   - background.png - 背景图
 *   - audio/ - 音频文件
 *
 * 规范参考：https://github.com/kwaroran/character-card-spec-v3
 */
object CharxImporter {
    private const val MAX_CHARX_ENTRIES = 2048
    private const val MAX_CHARX_ENTRY_BYTES = 32L * 1024L * 1024L
    private const val MAX_CHARX_TOTAL_BYTES = 128L * 1024L * 1024L

    data class CharxImportResult(
        val character: CharacterEntity,
        val assets: CharxAssets,
        val warnings: List<String> = emptyList(),
    )

    // 表情名 -> 图片数据；文件名 -> 音频数据
    data class CharxAssets(
        val avatar: ByteArray? = null,
        val expressions: Map<String, ByteArray> = emptyMap(),
        val background: ByteArray? = null,
        val audioFiles: Map<String, ByteArray> = emptyMap(),
    )

    /**
     * 从 CHARX ZIP 文件导入角色卡和资源
     */
    fun importFromCharx(
        context: Context,
        uri: Uri,
    ): CharxImportResult? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            importFromCharxStream(inputStream)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 从 ZIP 流导入 CHARX
     */
    private fun importFromCharxStream(inputStream: InputStream): CharxImportResult? {
        val warnings = mutableListOf<String>()
        var cardJson: String? = null
        var cardPng: ByteArray? = null
        var avatarData: ByteArray? = null
        var backgroundData: ByteArray? = null
        val expressions = mutableMapOf<String, ByteArray>()
        val audioFiles = mutableMapOf<String, ByteArray>()
        var totalBytes = 0L
        var entryCount = 0

        ZipInputStream(inputStream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entryCount++
                require(entryCount <= MAX_CHARX_ENTRIES) { "CHARX 文件非法：条目数量超出限制" }
                val name = entry.name
                val remainingBytes = MAX_CHARX_TOTAL_BYTES - totalBytes
                require(remainingBytes > 0L) { "CHARX 文件非法：解压内容超出大小限制" }
                val data = readBoundedEntry(zip, minOf(MAX_CHARX_ENTRY_BYTES, remainingBytes))
                totalBytes += data.size

                when {
                    // 主卡片文件
                    name == "card.json" -> {
                        cardJson = String(data, Charsets.UTF_8)
                    }
                    name == "card.png" -> {
                        cardPng = data
                    }

                    // 头像
                    name.matches(Regex("assets/avatar\\.(png|jpg|jpeg|webp)", RegexOption.IGNORE_CASE)) -> {
                        avatarData = data
                    }

                    // 表情立绘
                    name.matches(Regex("assets/expressions/.*\\.(png|jpg|jpeg|webp)", RegexOption.IGNORE_CASE)) -> {
                        val expressionName = File(name).nameWithoutExtension
                        expressions[expressionName] = data
                    }

                    // 背景图
                    name.matches(Regex("assets/background\\.(png|jpg|jpeg|webp)", RegexOption.IGNORE_CASE)) -> {
                        backgroundData = data
                    }

                    // 音频文件
                    name.matches(Regex("assets/audio/.*\\.(mp3|wav|ogg|m4a)", RegexOption.IGNORE_CASE)) -> {
                        val fileName = File(name).name
                        audioFiles[fileName] = data
                    }
                }

                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        // 优先使用 card.json，其次 card.png
        val characterEntity =
            when {
                cardJson != null -> {
                    parseJsonCard(cardJson!!, warnings)
                }
                cardPng != null -> {
                    parsePngCard(cardPng!!, warnings)
                }
                else -> {
                    warnings.add("CHARX 文件中未找到 card.json 或 card.png")
                    return null
                }
            }

        characterEntity ?: return null

        // 如果有 avatar，覆盖角色实体的头像
        val finalCharacter =
            if (avatarData != null) {
                val base64Avatar = java.util.Base64.getEncoder().encodeToString(avatarData)
                characterEntity.copy(avatarData = base64Avatar)
            } else {
                characterEntity
            }

        val assets =
            CharxAssets(
                avatar = avatarData,
                expressions = expressions,
                background = backgroundData,
                audioFiles = audioFiles,
            )

        // 添加资源统计信息
        if (expressions.isNotEmpty()) {
            warnings.add("导入了 ${expressions.size} 个表情立绘")
        }
        if (audioFiles.isNotEmpty()) {
            warnings.add("导入了 ${audioFiles.size} 个音频文件")
        }
        if (backgroundData != null) {
            warnings.add("导入了背景图")
        }

        return CharxImportResult(
            character = finalCharacter,
            assets = assets,
            warnings = warnings,
        )
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
            require(total <= maxBytes) { "CHARX 文件非法：单个条目超出解压大小限制" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    /**
     * 解析 JSON 格式的角色卡
     */
    private fun parseJsonCard(
        jsonString: String,
        warnings: MutableList<String>,
    ): CharacterEntity? {
        return try {
            val result = CardParser.parse(jsonString)
            if (result != null) {
                warnings.addAll(result.warnings)
                result.character
            } else {
                warnings.add("JSON 解析失败")
                null
            }
        } catch (e: Exception) {
            warnings.add("JSON 解析失败: ${e.message}")
            null
        }
    }

    /**
     * 解析 PNG 格式的角色卡（从 chara 或 ccv3 块提取）
     */
    private fun parsePngCard(
        pngData: ByteArray,
        warnings: MutableList<String>,
    ): CharacterEntity? {
        return try {
            // 优先尝试 V3 格式 (ccv3)
            val v3Chunk = PngCard.readTextChunk(pngData, PngCard.CHUNK_KEYWORD_V3)
            if (v3Chunk != null) {
                warnings.add("检测到 V3 格式角色卡 (ccv3)")
                val v3Json = PngCard.decodeCardJson(v3Chunk) ?: v3Chunk
                return parseJsonCard(v3Json, warnings)
            }

            // 回退到 V2 格式 (chara)
            val v2Chunk = PngCard.readTextChunk(pngData, PngCard.CHUNK_KEYWORD)
            if (v2Chunk != null) {
                warnings.add("检测到 V2 格式角色卡 (chara)")
                val v2Json = PngCard.decodeCardJson(v2Chunk) ?: v2Chunk
                return parseJsonCard(v2Json, warnings)
            }

            warnings.add("PNG 文件中未找到角色卡数据")
            null
        } catch (e: Exception) {
            warnings.add("PNG 解析失败: ${e.message}")
            null
        }
    }

    /**
     * 保存 CHARX 资源到本地存储
     * @return 资源文件路径映射
     */
    fun saveAssets(
        context: Context,
        characterId: String,
        assets: CharxAssets,
    ): Map<String, String> {
        val assetPaths = mutableMapOf<String, String>()
        val baseDir = File(context.filesDir, "character_assets/$characterId")

        try {
            // 创建目录
            baseDir.mkdirs()

            // 保存头像
            assets.avatar?.let { data ->
                val file = File(baseDir, "avatar.png")
                AtomicWrite.bytes(file, data)
                assetPaths["avatar"] = file.absolutePath
            }

            // 保存表情立绘
            if (assets.expressions.isNotEmpty()) {
                val expressionsDir = File(baseDir, "expressions")
                expressionsDir.mkdirs()

                assets.expressions.forEach { (name, data) ->
                    val file = File(expressionsDir, "$name.png")
                    AtomicWrite.bytes(file, data)
                    assetPaths["expression_$name"] = file.absolutePath
                }
            }

            // 保存背景图
            assets.background?.let { data ->
                val file = File(baseDir, "background.png")
                AtomicWrite.bytes(file, data)
                assetPaths["background"] = file.absolutePath
            }

            // 保存音频文件
            if (assets.audioFiles.isNotEmpty()) {
                val audioDir = File(baseDir, "audio")
                audioDir.mkdirs()

                assets.audioFiles.forEach { (fileName, data) ->
                    val file = File(audioDir, fileName)
                    AtomicWrite.bytes(file, data)
                    assetPaths["audio_$fileName"] = file.absolutePath
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return assetPaths
    }

    /**
     * 删除角色的所有资源文件
     */
    fun deleteAssets(
        context: Context,
        characterId: String,
    ) {
        val baseDir = File(context.filesDir, "character_assets/$characterId")
        if (baseDir.exists()) {
            baseDir.deleteRecursively()
        }
    }
}
