package com.mistbell.tavern.v3.feature.image

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Base64
import java.util.UUID

/** Metadata for an image stored in the app-private image library. */
data class StoredGeneratedImage(
    val id: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val createdAtEpochMillis: Long,
    val prompt: String,
    val model: String?,
)

/**
 * App-private storage for image-generation responses.
 *
 * This store accepts only image bytes returned inline by the image API. Remote URLs are never
 * fetched implicitly: callers must use an explicit, separately reviewed download flow if they
 * later choose to support remote images.
 */
class GeneratedImageStore(
    private val directory: File,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    fun save(image: GeneratedImage, prompt: String, model: String?): StoredGeneratedImage {
        require(prompt.isNotBlank()) { "Image prompt must not be blank." }
        val encoded = image.base64Data
            ?: throw IllegalArgumentException("Remote image URLs are not downloaded automatically.")
        val bytes = try {
            Base64.getDecoder().decode(encoded)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Image API returned invalid base64 image data.", error)
        }
        require(bytes.isNotEmpty() && bytes.size <= MAX_IMAGE_BYTES) {
            "Image data is empty or exceeds the configured storage limit."
        }
        val format = detectFormat(bytes)
            ?: throw IllegalArgumentException("Image data is not a supported PNG, JPEG, or WebP file.")
        val timestamp = clockMillis().also {
            require(it >= 0L) { "Image timestamp must not be negative." }
        }
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Image library directory could not be created.")
        }
        if (!directory.isDirectory) throw IOException("Image library path is not a directory.")

        val id = UUID.randomUUID().toString()
        val fileName = "image-$id.${format.extension}"
        val imageFile = File(directory, fileName)
        val imageTemp = File(directory, "$fileName.tmp")
        val metadataFile = File(directory, "$id.json")
        val metadataTemp = File(directory, "$id.json.tmp")
        try {
            FileOutputStream(imageTemp).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            if (!imageTemp.renameTo(imageFile)) throw IOException("Image file could not be committed.")
            val metadata = StoredGeneratedImage(
                id = id,
                fileName = fileName,
                mimeType = format.mimeType,
                sizeBytes = bytes.size.toLong(),
                createdAtEpochMillis = timestamp,
                prompt = prompt,
                model = model?.takeIf(String::isNotBlank),
            )
            val json = JSONObject()
                .put("id", metadata.id)
                .put("fileName", metadata.fileName)
                .put("mimeType", metadata.mimeType)
                .put("sizeBytes", metadata.sizeBytes)
                .put("createdAtEpochMillis", metadata.createdAtEpochMillis)
                .put("prompt", metadata.prompt)
                .put("model", metadata.model)
            FileOutputStream(metadataTemp).use { output ->
                output.write(json.toString().toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            if (!metadataTemp.renameTo(metadataFile)) throw IOException("Image metadata could not be committed.")
            return metadata
        } catch (error: Exception) {
            imageTemp.delete()
            metadataTemp.delete()
            imageFile.delete()
            metadataFile.delete()
            throw error
        }
    }

    /** Lists valid records newest first; corrupt sidecars are ignored rather than crashing the library. */
    fun list(): List<StoredGeneratedImage> {
        if (!directory.isDirectory) return emptyList()
        return directory.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            .orEmpty()
            .mapNotNull { file -> runCatching { readMetadata(file) }.getOrNull() }
            .filter { metadata ->
                metadata.id.matches(UUID_PATTERN) &&
                    metadata.fileName == imageFileName(metadata.id, metadata.mimeType) &&
                    File(directory, metadata.fileName).isFile &&
                    File(directory, metadata.fileName).length() == metadata.sizeBytes
            }
            .sortedWith(compareByDescending<StoredGeneratedImage> { it.createdAtEpochMillis }.thenBy { it.id })
    }

    /** Copies a saved image to the caller-owned stream without closing that stream. */
    @Throws(IOException::class)
    fun export(id: String, output: OutputStream) {
        require(id.matches(UUID_PATTERN)) { "Image ID is invalid." }
        val metadata = File(directory, "$id.json").takeIf(File::isFile)?.let(::readMetadata)
            ?: throw IOException("Saved image was not found.")
        val expectedName = imageFileName(metadata.id, metadata.mimeType)
        require(metadata.id == id && metadata.fileName == expectedName) { "Image metadata is invalid." }
        val source = File(directory, expectedName)
        if (!source.isFile || source.length() != metadata.sizeBytes) {
            throw IOException("Saved image is missing or has an unexpected size.")
        }
        source.inputStream().use { it.copyTo(output) }
        output.flush()
    }

    fun delete(id: String): Boolean {
        require(id.matches(UUID_PATTERN)) { "Image ID is invalid." }
        val metadataFile = File(directory, "$id.json")
        val metadata = runCatching { readMetadata(metadataFile) }.getOrNull()
        if (metadata == null || metadata.id != id) return false
        val imageFile = File(directory, imageFileName(id, metadata.mimeType))
        val imageDeleted = !imageFile.exists() || imageFile.delete()
        val metadataDeleted = !metadataFile.exists() || metadataFile.delete()
        return imageDeleted && metadataDeleted
    }

    private fun readMetadata(file: File): StoredGeneratedImage {
        val json = JSONObject(file.readText(Charsets.UTF_8))
        val id = json.getString("id")
        val fileName = json.getString("fileName")
        val mimeType = json.getString("mimeType")
        val sizeBytes = json.getLong("sizeBytes")
        val timestamp = json.getLong("createdAtEpochMillis")
        val prompt = json.getString("prompt")
        val model = json.optString("model", "").takeIf(String::isNotBlank)
        require(id.matches(UUID_PATTERN) && file.name == "$id.json")
        require(fileName == imageFileName(id, mimeType))
        require(sizeBytes in 1..MAX_IMAGE_BYTES.toLong() && timestamp >= 0L && prompt.isNotBlank())
        return StoredGeneratedImage(id, fileName, mimeType, sizeBytes, timestamp, prompt, model)
    }

    private fun imageFileName(id: String, mimeType: String): String {
        val extension = when (mimeType) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            else -> throw IllegalArgumentException("Unsupported stored image MIME type.")
        }
        return "image-$id.$extension"
    }

    private fun detectFormat(bytes: ByteArray): ImageFormat? = when {
        bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte() &&
            bytes[4] == 0x0D.toByte() &&
            bytes[5] == 0x0A.toByte() &&
            bytes[6] == 0x1A.toByte() &&
            bytes[7] == 0x0A.toByte() -> ImageFormat("png", "image/png")
        bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte() -> ImageFormat("jpg", "image/jpeg")
        bytes.size >= 12 &&
            bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0x52, 0x49, 0x46, 0x46)) &&
            bytes.copyOfRange(8, 12).contentEquals(byteArrayOf(0x57, 0x45, 0x42, 0x50)) ->
            ImageFormat("webp", "image/webp")
        else -> null
    }

    private data class ImageFormat(val extension: String, val mimeType: String)

    companion object {
        const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
        private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}
