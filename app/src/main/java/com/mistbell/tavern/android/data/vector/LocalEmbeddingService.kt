package com.mistbell.tavern.android.data.vector

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.mistbell.tavern.android.data.api.ApiCallTrace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * 本地语义向量服务（FOUNDATION F3 落地）：onnxruntime-android 跑量化 BGE 中文模型，
 * 无需 API Key 即可获得真实语义 embedding——兑现 README"基于向量检索的语义记忆"的承诺。
 *
 * - 模型：bge-small-zh-v1.5（int8 量化 ≈24MB，MIT，assets 内置免下载）；
 * - 分词：[BertWordPieceTokenizer]（纯 Kotlin 自移植，同资产内置 vocab.txt）；
 * - 池化：bge 系列取 [CLS] 位置向量，L2 归一化（FlagEmbedding 规范）；
 * - 输出维度 512；单线程推理（OrtSession.run 线程安全，会话级串行足够聊天场景）。
 *
 * 任何初始化/推理失败都向调用方抛异常——由路由层（TavernApplication）兜底回退 Mock，
 * 保证向量记忆可用性开关（VectorMemoryService.available）永远可判。
 */
class LocalEmbeddingService private constructor(
    private val session: OrtSession,
    private val tokenizer: BertWordPieceTokenizer,
) : EmbeddingService {
    companion object {
        private const val MODEL_ASSET = "models/bge-small-zh-v1.5-q.onnx"
        private const val VOCAB_ASSET = "models/bge-small-zh-vocab.txt"
        private const val MAX_SEQ_LEN = 512
        private const val DIM = 512
        private const val INPUT_IDS = "input_ids"
        private const val ATTENTION_MASK = "attention_mask"
        private const val TOKEN_TYPE_IDS = "token_type_ids"
        private const val TAG = "ApiCallTrace"

        /**
         * 从 assets 构建服务（模型加载约 1-2s，须在 IO 线程）。
         * 失败抛异常——调用方负责回退。
         */
        suspend fun create(context: Context): LocalEmbeddingService =
            withContext(Dispatchers.IO) {
                val modelBytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
                val vocabText = context.assets.open(VOCAB_ASSET).use { it.bufferedReader().readText() }
                // bge-small-zh 为中文模型：不 lower-case、不去重音（词表含大小写词条）
                val tokenizer = BertWordPieceTokenizer.fromVocabText(vocabText)
                val env = OrtEnvironment.getEnvironment()
                val session = env.createSession(modelBytes)
                LocalEmbeddingService(session, tokenizer)
            }
    }

    private val env: OrtEnvironment get() = OrtEnvironment.getEnvironment()

    override fun getDimension(): Int = DIM

    override suspend fun embed(text: String): FloatArray = embed(text, "LOCAL_EMBEDDING_UNSPECIFIED")

    @Suppress("TooGenericExceptionCaught")
    override suspend fun embed(
        text: String,
        source: String,
    ): FloatArray {
        val traceId = ApiCallTrace.newId()
        val startedAt = System.currentTimeMillis()
        ApiCallTrace.log(traceId, source, "START", "engine=ONNX mode=local network=false")
        return try {
            withContext(Dispatchers.Default) {
                val ids = tokenizer.encode(text, MAX_SEQ_LEN)
                val seqLen = ids.size
                val inputIds = LongArray(seqLen) { ids[it].toLong() }
                val attentionMask = LongArray(seqLen) { 1L }
                val tokenTypeIds = LongArray(seqLen) { 0L }

                val shape = longArrayOf(1, seqLen.toLong())
                val inputs =
                    mapOf(
                        INPUT_IDS to OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), shape),
                        ATTENTION_MASK to OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMask), shape),
                        TOKEN_TYPE_IDS to OnnxTensor.createTensor(env, LongBuffer.wrap(tokenTypeIds), shape),
                    )
                session.run(inputs).use { result ->
                    // 输出 [1, seqLen, 512]：bge 取 [CLS]（首位）做池化，再 L2 归一化
                    @Suppress("UNCHECKED_CAST")
                    val output = result.get(0).value as Array<Array<FloatArray>>
                    val cls = output[0][0]
                    normalize(cls)
                }
            }.also {
                ApiCallTrace.log(
                    traceId,
                    source,
                    "SUCCESS",
                    "engine=ONNX network=false elapsedMs=${System.currentTimeMillis() - startedAt} dimension=${it.size}",
                )
            }
        } catch (e: Exception) {
            ApiCallTrace.log(
                traceId,
                source,
                "FAILURE",
                "engine=ONNX network=false elapsedMs=${System.currentTimeMillis() - startedAt} error=${e.javaClass.simpleName}",
            )
            throw e
        }
    }

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> = texts.map { embed(it) }

    override suspend fun embedBatch(
        texts: List<String>,
        source: String,
    ): List<FloatArray> = texts.map { embed(it, source) }

    private fun normalize(vector: FloatArray): FloatArray {
        var norm = 0.0
        for (v in vector) norm += v * v.toDouble()
        norm = sqrt(norm)
        if (norm == 0.0) return vector
        return FloatArray(vector.size) { (vector[it] / norm).toFloat() }
    }
}
