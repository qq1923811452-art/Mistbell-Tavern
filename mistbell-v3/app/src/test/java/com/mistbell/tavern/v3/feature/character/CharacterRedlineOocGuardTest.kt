package com.mistbell.tavern.v3.feature.character

import com.mistbell.tavern.v3.feature.embedding.EmbeddingTaskType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterRedlineOocGuardTest {
    @Test
    fun flagsClaimsSimilarToAnExplicitRedline() =
        runBlocking {
            val card = CharacterCard(name = "Luna", redlines = listOf("绝对不能伤害人类"))
            val result =
                CharacterRedlineOocGuard.evaluate(
                    card = card,
                    latestUserMessage = "我要攻击村民",
                    previousAssistantReply = null,
                    embed = { _, _, _ -> listOf(listOf(1f, 0f)) },
                )

            assertTrue(result!!.contains("[角色核心铁律 - 绝对禁止违反]"))
            assertTrue(result.contains("绝对不能伤害人类"))
        }

    @Test
    fun extractsImplicitRulesFromPromptText() {
        val card =
            CharacterCard(
                name = "Luna",
                systemPrompt = "严禁背叛同伴。保持安静。",
                postHistoryInstructions = "红线：不可泄露真名。",
            )
        val rules = CharacterRedlineOocGuard.extractRules(card)
        assertTrue(rules.any { it.contains("严禁背叛同伴") })
        assertTrue(rules.any { it.contains("红线") })
        assertTrue(rules.none { it.contains("保持安静") })
    }

    @Test
    fun skipsClaimsBelowThresholdAndCachesEvidence() =
        runBlocking {
            var documentCalls = 0
            val card = CharacterCard(name = "Luna", redlines = listOf("绝对不能伤害人类"))
            val embed: suspend (List<String>, EmbeddingTaskType, List<String?>?) -> List<List<Float>> =
                { _, task, _ ->
                    if (task == EmbeddingTaskType.RETRIEVAL_DOCUMENT) {
                        documentCalls++
                        listOf(listOf(1f, 0f))
                    } else {
                        listOf(listOf(0f, 1f))
                    }
                }
            assertNull(CharacterRedlineOocGuard.evaluate(card, "你好", null, embed))
            assertNull(CharacterRedlineOocGuard.evaluate(card, "再见", null, embed))
            assertEquals(1, documentCalls)
        }
}
