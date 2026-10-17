package com.mistbell.tavern.v3.feature.embedding

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Device-generated evidence. The packaged runtime manifest is never edited by this class. */
internal object NpuEvidenceStore {
    private const val EXPECTED_MODEL_SHA256 =
        "3b8a18c6d043b8132c4eda45611296e1ae455ada82cda8b591ec0ca141e041c5"
    private val requiredMappings = listOf("libQnnHtp.so", "libQnnHtpV75Stub.so", "libcdsprpc.so")
    private val runtimeLibraries = listOf(
        "libLiteRtDispatch_Qualcomm.so", "libQnnSystem.so", "libQnnHtp.so",
        "libQnnHtpPrepare.so", "libQnnHtpV75Stub.so", "libQnnHtpV75Skel.so",
        "libQnnHtpV75CalculatorStub.so", "libcdsprpc.so",
    )

    data class WriteResult(
        val file: File,
        val status: String,
        val successes: Int,
        val apkSha256MatchesCurrent: Boolean,
    )

    fun write(context: Context, modelFile: File, outcomes: List<String>): WriteResult {
        val apk = File(context.applicationInfo.sourceDir)
        val apkBefore = apk.sha256OrEmpty()
        val modelHash = modelFile.sha256OrEmpty()
        val mapsText = runCatching { File("/proc/self/maps").readText() }.getOrDefault("")
        val mappings = JSONObject().apply {
            requiredMappings.forEach { name -> put(name, mapsText.lineSequence().any { it.contains(name) }) }
        }
        val libraryHashes = collectLibraryHashes(context, mapsText)
        val cases = JSONArray().apply {
            outcomes.forEach { outcome ->
                val success = "; result=success;" in outcome
                put(
                    JSONObject()
                        .put("inputChars", Regex("""chars=(\d+)""").find(outcome)?.groupValues?.get(1)?.toIntOrNull() ?: JSONObject.NULL)
                        .put("result", if (success) "success" else "failed")
                        .put("dimensions", Regex("""dimensions=(\d+)""").find(outcome)?.groupValues?.get(1)?.toIntOrNull() ?: JSONObject.NULL)
                        .put("elapsedMs", Regex("""elapsedMs=(-?\d+)""").find(outcome)?.groupValues?.get(1)?.toLongOrNull() ?: JSONObject.NULL)
                        .put("detail", outcome.take(300)),
                )
            }
        }
        val apkAfter = apk.sha256OrEmpty()
        val apkMatches = apkBefore.isNotBlank() && apkBefore == apkAfter
        val successes = (0 until cases.length()).count { cases.optJSONObject(it)?.optString("result") == "success" }
        val status = if (
            NpuEvidenceStatusPolicy.shouldVerify(
                modelHashMatches = modelHash == EXPECTED_MODEL_SHA256,
                apkHashMatches = apkMatches,
                cases = cases,
                mappedLibraries = mappings,
                requiredMappings = requiredMappings,
            )
        ) "verified" else "packaged_not_device_verified"

        val directory = File(context.filesDir, "npu-evidence")
        check(directory.isDirectory || directory.mkdirs()) { "NPU evidence directory could not be created." }
        val file = File(directory, "npu-evidence-${System.currentTimeMillis()}.json")
        val checksum = File(directory, file.name + ".sha256")
        val json = JSONObject()
            .put("schemaVersion", 1)
            .put("createdAtEpochMillis", System.currentTimeMillis())
            .put("status", status)
            .put("modelSha256", modelHash)
            .put("expectedModelSha256", EXPECTED_MODEL_SHA256)
            .put("apkSha256", apkAfter)
            .put("apkSha256Rechecked", apkAfter)
            .put("apkSha256MatchesCurrent", apkMatches)
            .put("buildFingerprint", Build.FINGERPRINT)
            .put("sdkInt", Build.VERSION.SDK_INT)
            .put("litertLmVersion", "0.18.0")
            .put("qairtVersion", readQairtVersion(context))
            .put("successes", successes)
            .put("caseCount", cases.length())
            .put("mappedLibraries", mappings)
            .put("runtimeLibrarySha256", libraryHashes)
            .put("cases", cases)
        val temp = File(directory, file.name + ".tmp")
        temp.writeText(json.toString(2), Charsets.UTF_8)
        check(temp.renameTo(file)) { "NPU evidence JSON could not be finalized." }
        checksum.writeText(file.sha256() + "  " + file.name + "\n", Charsets.UTF_8)

        val currentApkHash = apk.sha256OrEmpty()
        val apkStillMatches = apkAfter.isNotBlank() && apkAfter == currentApkHash
        val evidenceHashMatches = runCatching {
            checksum.readText(Charsets.UTF_8).trim().substringBefore(' ') == file.sha256()
        }.getOrDefault(false)
        if (status == "verified" && (!apkStillMatches || !evidenceHashMatches)) {
            json.put("status", "packaged_not_device_verified")
                .put("apkSha256MatchesCurrent", apkStillMatches)
            file.writeText(json.toString(2), Charsets.UTF_8)
            checksum.writeText(file.sha256() + "  " + file.name + "\n", Charsets.UTF_8)
        }
        val finalStatus = runCatching {
            JSONObject(file.readText(Charsets.UTF_8)).optString("status", "packaged_not_device_verified")
        }.getOrDefault("packaged_not_device_verified")
        return WriteResult(file, finalStatus, successes, apkStillMatches)
    }

    fun hasValidEvidence(context: Context, modelFile: File): Boolean = runCatching {
        val directory = File(context.filesDir, "npu-evidence")
        val currentApkHash = File(context.applicationInfo.sourceDir).sha256()
        val currentModelHash = modelFile.sha256()
        directory.listFiles { candidate ->
            candidate.name.startsWith("npu-evidence-") && candidate.name.endsWith(".json")
        }.orEmpty().sortedByDescending(File::lastModified).any { file ->
            val sidecar = File(file.parentFile, file.name + ".sha256")
            val expectedFileHash = sidecar.readText(Charsets.UTF_8).trim().substringBefore(' ')
            if (expectedFileHash != file.sha256()) return@any false
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val mappings = json.optJSONObject("mappedLibraries") ?: return@any false
            val cases = json.optJSONArray("cases") ?: return@any false
            json.optString("status") == "verified" &&
                json.optString("apkSha256") == currentApkHash &&
                json.optString("apkSha256Rechecked") == currentApkHash &&
                json.optString("modelSha256") == currentModelHash &&
                currentModelHash == EXPECTED_MODEL_SHA256 &&
                json.optInt("sdkInt", -1) == Build.VERSION.SDK_INT &&
                json.optString("buildFingerprint") == Build.FINGERPRINT &&
                json.optString("litertLmVersion") == "0.18.0" &&
                NpuEvidenceStatusPolicy.shouldVerify(true, true, cases, mappings, requiredMappings)
        }
    }.getOrDefault(false)

    private fun collectLibraryHashes(context: Context, mapsText: String): JSONObject {
        val mappedFiles = mapsText.lineSequence().mapNotNull { line ->
            Regex("""\s(/\S+)$""").find(line)?.groupValues?.getOrNull(1)
                ?.removeSuffix(" (deleted)")?.let(::File)
        }.toList()
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val apks = buildList {
            add(File(context.applicationInfo.sourceDir))
            context.applicationInfo.splitSourceDirs.orEmpty().forEach { add(File(it)) }
        }
        return JSONObject().apply {
            runtimeLibraries.forEach { name ->
                val mapped = mappedFiles.firstOrNull { it.name == name && it.isFile && it.canRead() }
                val native = File(nativeDir, name).takeIf { it.isFile && it.canRead() }
                val hash = mapped?.sha256OrEmpty()?.takeIf(String::isNotBlank)
                    ?: native?.sha256OrEmpty()?.takeIf(String::isNotBlank)
                    ?: apks.firstNotNullOfOrNull { apk -> apkEntrySha256(apk, name) }
                put(name, hash ?: JSONObject.NULL)
            }
        }
    }

    private fun apkEntrySha256(apk: File, name: String): String? = runCatching {
        if (!apk.isFile) return@runCatching null
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry("lib/arm64-v8a/$name") ?: return@use null
            val digest = MessageDigest.getInstance("SHA-256")
            zip.getInputStream(entry).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().toHex()
        }
    }.getOrNull()

    private fun readQairtVersion(context: Context): String = runCatching {
        context.assets.open("native-runtime/qualcomm-sm8650.properties").bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                val split = line.indexOf('=')
                if (split > 0) line.substring(0, split).trim() to line.substring(split + 1).trim() else null
            }.toMap()["qairt_version"] ?: "unknown"
        }
    }.getOrDefault("unknown")

    private fun File.sha256OrEmpty(): String = runCatching { sha256() }.getOrDefault("")
    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(this).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

internal object NpuEvidenceStatusPolicy {
    fun shouldVerify(
        modelHashMatches: Boolean,
        apkHashMatches: Boolean,
        cases: JSONArray,
        mappedLibraries: JSONObject,
        requiredMappings: List<String>,
    ): Boolean {
        if (!modelHashMatches || !apkHashMatches || cases.length() != 7) return false
        val allSucceeded = (0 until cases.length()).all { index ->
            val case = cases.optJSONObject(index)
            case?.optString("result") == "success" && case.optInt("dimensions", -1) == 768
        }
        return allSucceeded && requiredMappings.all { mappedLibraries.optBoolean(it, false) }
    }
}
