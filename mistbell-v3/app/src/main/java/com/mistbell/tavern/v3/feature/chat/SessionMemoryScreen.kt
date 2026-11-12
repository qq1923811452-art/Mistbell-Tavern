@file:Suppress("FunctionNaming", "LongMethod", "MagicNumber", "MatchingDeclarationName", "TooManyFunctions")

package com.mistbell.tavern.v3.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mistbell.tavern.v3.core.memory.EffectiveMemory
import com.mistbell.tavern.v3.core.memory.MemoryOrigin
import com.mistbell.tavern.v3.feature.memory.MemoryStatusDiagnosis
import com.mistbell.tavern.v3.feature.memory.MemoryStatusFormat
import com.mistbell.tavern.v3.feature.memory.MemoryStatusSnapshot
import com.mistbell.tavern.v3.ui.tavern.TavernGlassCard
import com.mistbell.tavern.v3.ui.tavern.TavernGlyphName
import com.mistbell.tavern.v3.ui.tavern.TavernIconButton
import com.mistbell.tavern.v3.ui.tavern.TavernPageHeader
import com.mistbell.tavern.v3.ui.tavern.TavernPalette
import com.mistbell.tavern.v3.ui.tavern.TavernPill
import com.mistbell.tavern.v3.ui.tavern.TavernSectionLabel
import com.mistbell.tavern.v3.ui.tavern.TavernShape

/** Screen state is owned by the caller so scope changes can discard it wholesale. */
internal sealed interface SessionMemoryUiState {
    data object Loading : SessionMemoryUiState

    data class Ready(
        val entries: List<EffectiveMemory>,
        val revision: Long,
        val notice: String? = null,
        val issue: String? = null,
    ) : SessionMemoryUiState

    data class Failed(val message: String) : SessionMemoryUiState
}

/**
 * Read-only list plus an explicit edit form. Nothing is written until the user confirms, and the
 * revision captured when the form opened travels with the save so a concurrent edit cannot be
 * silently overwritten.
 */
@Suppress("FunctionNaming", "LongMethod")
@Composable
internal fun SessionMemoryScreen(
    scopeTitle: String,
    state: SessionMemoryUiState,
    status: MemoryStatusSnapshot?,
    actions: SessionMemoryActions,
) {
    var editing by remember { mutableStateOf<EffectiveMemory?>(null) }
    val loading = state is SessionMemoryUiState.Loading
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp, bottom = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TavernIconButton(
                glyph = TavernGlyphName.Back,
                onClick = actions.onBack,
                contentDescription = "返回",
            )
            TavernPageHeader(
                title = "会话记忆",
                subtitle = scopeTitle,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            TavernIconButton(
                glyph = TavernGlyphName.Refresh,
                onClick = { if (!loading) actions.onReload() },
                contentDescription = "刷新",
                tint = if (loading) TavernPalette.TextTertiary else TavernPalette.PurpleSoft,
            )
        }
        status?.let { MemoryStatusBlock(it, onRetryFailed = actions.onRetryFailed) }
        when (state) {
            SessionMemoryUiState.Loading -> {
                Text(
                    text = "正在读取本会话记忆…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TavernPalette.TextSecondary,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            is SessionMemoryUiState.Failed -> {
                Text(
                    text = "记忆暂时无法读取：${state.message}\n原数据已保留，未做任何修改。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TavernPalette.Danger,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            is SessionMemoryUiState.Ready -> {
                MemoryList(
                    state = state,
                    onEdit = { editing = it },
                    onRevert = { actions.onRevert(it.factId, it.revision) },
                )
            }
        }
    }

    val target = editing
    if (target != null) {
        MemoryEditDialog(
            entry = target,
            onDismiss = { editing = null },
            onConfirm = { subject, predicate, value ->
                editing = null
                actions.onSave(target.factId, target.revision, subject, predicate, value)
            },
        )
    }
}

/** The pipeline state the user otherwise cannot see: queue, projection and index health. */
@Suppress("FunctionNaming")
@Composable
private fun MemoryStatusBlock(
    status: MemoryStatusSnapshot,
    onRetryFailed: () -> Unit,
) {
    TavernGlassCard(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            TavernSectionLabel("运行状态")
            Text(
                text = MemoryStatusDiagnosis.hint(status),
                style = MaterialTheme.typography.bodySmall,
                color = TavernPalette.PurpleSoft,
                modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                "抽取队列：待处理 ${status.queuedJobs} · 重试 ${status.retryJobs} · " +
                    "执行中 ${status.runningJobs} · 失败 ${status.failedJobs}",
                style = MaterialTheme.typography.labelSmall,
                color = TavernPalette.TextSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
            status.lastQueueError?.let {
                Text(
                    "最近队列错误：${it.take(PREVIEW_CHARS)}（详情见诊断日志）",
                    style = MaterialTheme.typography.labelSmall,
                    color = TavernPalette.Danger,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (status.failedJobs > 0) {
                TavernPill(
                    label = "重试 ${status.failedJobs} 条失败任务",
                    onClick = onRetryFailed,
                    glyph = TavernGlyphName.Refresh,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            Text(
                "事实 ${status.factsCurrent}/${status.factsTotal} · 文本 ${status.textRecords} · " +
                    "向量 ${MemoryStatusFormat.bytes(status.vectorBytes)}" +
                    (status.lastRetrieval?.let { " · 最近：${it.take(PREVIEW_CHARS)}" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = TavernPalette.TextTertiary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun MemoryList(
    state: SessionMemoryUiState.Ready,
    onEdit: (EffectiveMemory) -> Unit,
    onRevert: (EffectiveMemory) -> Unit,
) {
    val current = state.entries.filter(EffectiveMemory::isCurrent)
    val history = state.entries.filterNot(EffectiveMemory::isCurrent)
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        state.notice?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = TavernPalette.PurpleSoft,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        state.issue?.let {
            Text(
                text = "手动修订文件读取异常：$it（自动记忆仍可查看）",
                style = MaterialTheme.typography.bodySmall,
                color = TavernPalette.Danger,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        TavernSectionLabel(
            text = "有效 ${current.size} 条 · 历史 ${history.size} 条",
            modifier = Modifier.padding(vertical = 8.dp),
        )
        if (state.entries.isEmpty()) {
            Text(
                text = "暂无已保存记忆。继续聊天，抽取成功后这里会出现条目。",
                style = MaterialTheme.typography.bodyMedium,
                color = TavernPalette.TextSecondary,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
        LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(current, key = { it.factId }) { entry ->
                MemoryEntryCard(entry, editable = true, onEdit = onEdit, onRevert = onRevert)
            }
            if (history.isNotEmpty()) {
                item {
                    TavernSectionLabel(
                        text = "历史版本（只读）",
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                    )
                }
                items(history, key = { it.factId }) { entry ->
                    MemoryEntryCard(entry, editable = false, onEdit = onEdit, onRevert = onRevert)
                }
            }
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun MemoryEntryCard(
    entry: EffectiveMemory,
    editable: Boolean,
    onEdit: (EffectiveMemory) -> Unit,
    onRevert: (EffectiveMemory) -> Unit,
) {
    TavernGlassCard(Modifier.fillMaxWidth(), shape = TavernShape.CardSmall) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.subject,
                    color = TavernPalette.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (editable) {
                    TavernPill(label = "编辑", onClick = { onEdit(entry) })
                }
            }
            Text(
                text = entry.predicate,
                color = TavernPalette.TextTertiary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                text = entry.value,
                color = TavernPalette.TextPrimary,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val origin = if (entry.origin == MemoryOrigin.MANUAL) "手动修改" else "自动抽取"
                Text(
                    text = "$origin · 来源轮 ${entry.sourceTurnId.take(ID_PREVIEW_CHARS)}",
                    color = TavernPalette.TextTertiary,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                )
                if (editable && entry.origin == MemoryOrigin.MANUAL) {
                    TavernPill(label = "恢复自动", onClick = { onRevert(entry) })
                }
            }
        }
    }
}

@Suppress("FunctionNaming", "LongMethod")
@Composable
private fun MemoryEditDialog(
    entry: EffectiveMemory,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit,
) {
    var subject by remember(entry.factId) { mutableStateOf(entry.subject) }
    var predicate by remember(entry.factId) { mutableStateOf(entry.predicate) }
    var value by remember(entry.factId) { mutableStateOf(entry.value) }
    val tooLong =
        subject.length > MAX_FIELD_CHARS || predicate.length > MAX_FIELD_CHARS ||
            value.length > MAX_FIELD_CHARS
    val invalid = subject.isBlank() || predicate.isBlank() || value.isBlank() || tooLong
    val fieldColors =
        OutlinedTextFieldDefaults.colors(
            focusedBorderColor = TavernPalette.Purple,
            unfocusedBorderColor = TavernPalette.Border,
            focusedTextColor = TavernPalette.TextPrimary,
            unfocusedTextColor = TavernPalette.TextPrimary,
            cursorColor = TavernPalette.Purple,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
        )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TavernPalette.DeepGrape,
        title = {
            Text(
                text = "编辑记忆",
                style = MaterialTheme.typography.titleLarge,
                color = TavernPalette.TextPrimary,
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "只修改这条记忆的文字；原始抽取记录与来源轮次会保留。",
                    style = MaterialTheme.typography.bodySmall,
                    color = TavernPalette.TextTertiary,
                )
                OutlinedTextField(
                    value = subject,
                    onValueChange = { subject = it },
                    label = { Text("主体") },
                    singleLine = true,
                    colors = fieldColors,
                )
                OutlinedTextField(
                    value = predicate,
                    onValueChange = { predicate = it },
                    label = { Text("关系") },
                    singleLine = true,
                    colors = fieldColors,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("内容") },
                    colors = fieldColors,
                )
                if (tooLong) {
                    Text(
                        text = "单个字段最多 $MAX_FIELD_CHARS 字符",
                        style = MaterialTheme.typography.bodySmall,
                        color = TavernPalette.Danger,
                    )
                }
            }
        },
        confirmButton = {
            TavernPill(
                label = "保存",
                onClick = { onConfirm(subject.trim(), predicate.trim(), value.trim()) },
                filled = true,
                enabled = !invalid,
            )
        },
        dismissButton = {
            TavernPill(label = "取消", onClick = onDismiss)
        },
    )
}

/** Called by the screen; grouped so the composable stays within the parameter-count budget. */
internal data class SessionMemoryActions(
    val onBack: () -> Unit,
    val onReload: () -> Unit,
    val onSave: (factId: String, revision: Long, subject: String, predicate: String, value: String) -> Unit,
    val onRevert: (factId: String, revision: Long) -> Unit,
    val onRetryFailed: () -> Unit,
)

private const val MAX_FIELD_CHARS = 2_000
private const val PREVIEW_CHARS = 60
private const val ID_PREVIEW_CHARS = 8
