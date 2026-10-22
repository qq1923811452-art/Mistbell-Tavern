package com.mistbell.tavern.v3.feature.image

/**
 * Builds an image prompt from a character card and a scene description.
 *
 * The result is deliberately plain text: every OpenAI-compatible image endpoint accepts a prompt,
 * while reference-image upload depends on provider support and is added separately.
 */
object ScenePromptComposer {

    fun compose(
        characterName: String,
        characterDescription: String,
        scene: String,
        style: String = DEFAULT_STYLE,
    ): String {
        val name = characterName.trim()
        val description = characterDescription.trim().take(MAX_DESCRIPTION_CHARS)
        val sceneText = scene.trim()
        val styleText = style.trim()
        return buildString {
            if (sceneText.isNotEmpty()) append(sceneText)
            if (name.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("角色：").append(name)
                if (description.isNotEmpty()) append("（").append(description).append("）")
            }
            if (styleText.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("画风：").append(styleText)
            }
        }.trim()
    }

    const val DEFAULT_STYLE = "日系动画风格，柔和光效，竖版构图"
    private const val MAX_DESCRIPTION_CHARS = 300
}
