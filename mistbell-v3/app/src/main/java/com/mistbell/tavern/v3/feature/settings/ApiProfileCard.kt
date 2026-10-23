package com.mistbell.tavern.v3.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private const val CARD_CORNER_RADIUS_DP = 16
private const val CARD_CONTENT_PADDING_DP = 12
private const val CARD_ITEM_SPACING_DP = 8
private const val CARD_VERTICAL_MARGIN_DP = 4
private const val CARD_BORDER_WIDTH_DP = 1
private const val CARD_LABEL_MAX_LINES = 1

/**
 * 多 API 配置列表中的单张卡片。
 *
 * 卡片只展示配置摘要并通过回调上报交互，不读取设置、不发起网络请求。
 *
 * @param profile 要展示的配置。
 * @param selected 是否为当前选中的配置；选中时高亮边框。
 * @param onTest 点击“测试连接”时触发；具体测试逻辑由上层实现。
 * @param onClick 点击卡片主体时触发，通常用于切换当前配置。
 * @param onSetDefault 点击星标时触发，用于把该配置设为默认。
 */
@Suppress("FunctionNaming")
@Composable
fun ApiProfileCard(
    profile: ApiProfile,
    selected: Boolean,
    onTest: () -> Unit,
    onClick: () -> Unit,
    onSetDefault: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = CARD_VERTICAL_MARGIN_DP.dp)
                .clickable(onClick = onClick),
        shape = RoundedCornerShape(CARD_CORNER_RADIUS_DP.dp),
        color = if (selected) colors.secondaryContainer else colors.surface,
        border =
            if (selected) {
                BorderStroke(CARD_BORDER_WIDTH_DP.dp, colors.primary)
            } else {
                null
            },
    ) {
        Column(
            modifier = Modifier.padding(CARD_CONTENT_PADDING_DP.dp),
            verticalArrangement = Arrangement.spacedBy(CARD_ITEM_SPACING_DP.dp),
        ) {
            ApiProfileCardHeader(profile = profile, onSetDefault = onSetDefault)
            Text(
                text = "模型：${profile.modelId.ifBlank { "未选择" }}",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                maxLines = CARD_LABEL_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "接口：${profile.baseUrl.ifBlank { "未填写" }}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = CARD_LABEL_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )
            ApiProfileCardFooter(selected = selected, isDefault = profile.isDefault, onTest = onTest)
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun ApiProfileCardHeader(
    profile: ApiProfile,
    onSetDefault: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CARD_ITEM_SPACING_DP.dp),
    ) {
        Text(
            text = profile.name.ifBlank { "未命名配置" },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            maxLines = CARD_LABEL_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = onSetDefault, enabled = !profile.isDefault) {
            Text(if (profile.isDefault) "★ 默认" else "☆ 设为默认")
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun ApiProfileCardFooter(
    selected: Boolean,
    isDefault: Boolean,
    onTest: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text =
                when {
                    selected -> "当前使用"
                    isDefault -> "默认配置"
                    else -> "未选中"
                },
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onTest) {
            Text("测试连接")
        }
    }
}
