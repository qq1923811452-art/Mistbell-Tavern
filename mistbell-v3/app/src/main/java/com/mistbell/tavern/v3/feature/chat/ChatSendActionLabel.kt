package com.mistbell.tavern.v3.feature.chat

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
internal fun ChatSendActionLabel(
    historyReady: Boolean,
    historyLoadFailed: Boolean,
    worldbooksReady: Boolean,
    busy: Boolean,
) {
    Text(
        when {
            !historyReady -> "恢复记录中…"
            historyLoadFailed -> "记录异常，请先新建会话"
            !worldbooksReady -> "加载世界书中…"
            busy -> "生成中…"
            else -> "发送消息"
        },
    )
}
