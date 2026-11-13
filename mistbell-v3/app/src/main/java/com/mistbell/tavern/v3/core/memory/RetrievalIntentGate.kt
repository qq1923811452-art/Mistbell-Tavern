package com.mistbell.tavern.v3.core.memory

import java.util.Locale

/** Cheap pre-retrieval gate: skip embedding work for greetings and punctuation-only messages. */
object RetrievalIntentGate {
    private val NON_WORD = Regex("[\\p{P}\\p{Z}\\p{So}\\p{Cn}]+")
    private val QUESTION_HINTS =
        listOf(
            "?", "？", "吗", "呢", "什么", "啥", "谁", "哪", "为什么", "怎么", "如何", "多少", "记得",
        )
    private val PURE_FILLERS =
        setOf(
            "早", "早安", "早上好", "午安", "晚安", "你好", "您好", "哈喽", "嗨",
            "嗯", "嗯嗯", "嗯啊", "哦", "哦哦", "噢", "啊", "好", "好的", "好吧", "行", "行吧",
            "收到", "明白", "了解", "知道了", "哈哈", "哈哈哈", "呵呵", "嘿嘿", "继续", "然后呢",
            "接着说", "hi", "hello", "hey", "ok", "okay", "yes", "no", "lol", "hmm",
        )

    fun shouldTriggerRetrieval(
        userMessage: String,
        activeEntityNames: Set<String> = emptySet(),
    ): Boolean {
        val normalized = userMessage.replace(NON_WORD, "").lowercase(Locale.ROOT)
        val entityMentioned =
            activeEntityNames.any { name ->
                name.length >= MIN_ENTITY_NAME_CHARS && userMessage.contains(name, ignoreCase = true)
            }
        return when {
            normalized.isEmpty() -> false
            QUESTION_HINTS.any { it in userMessage } -> true
            entityMentioned -> true
            else -> normalized !in PURE_FILLERS
        }
    }

    private const val MIN_ENTITY_NAME_CHARS = 2
}
