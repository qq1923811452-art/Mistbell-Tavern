package com.mistbell.tavern.v3.feature.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenePromptComposerTest {

    @Test
    fun combinesSceneCharacterAndStyle() {
        val prompt = ScenePromptComposer.compose(
            characterName = "露娜",
            characterDescription = "白发紫瞳的剑士",
            scene = "夜晚的酒馆，烛光晃动",
        )
        assertTrue(prompt.startsWith("夜晚的酒馆，烛光晃动"))
        assertTrue(prompt.contains("角色：露娜（白发紫瞳的剑士）"))
        assertTrue(prompt.contains("画风："))
    }

    @Test
    fun keepsSceneOnlyWhenCharacterIsMissing() {
        assertEquals("雨中的街道", ScenePromptComposer.compose("", "", "雨中的街道", style = ""))
    }

    @Test
    fun truncatesLongDescriptions() {
        val prompt = ScenePromptComposer.compose("露娜", "长".repeat(1000), "场景", style = "")
        assertTrue(prompt.length < 400)
    }

    @Test
    fun returnsEmptyForEmptyInputs() {
        assertEquals("", ScenePromptComposer.compose("", "", "", style = ""))
    }
}
