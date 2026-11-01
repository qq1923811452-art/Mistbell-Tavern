@file:Suppress("MatchingDeclarationName")

package com.mistbell.tavern.v3.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.v3.core.memory.EffectiveMemory
import com.mistbell.tavern.v3.core.memory.MemoryOrigin
import com.mistbell.tavern.v3.feature.memory.MemoryStatusDiagnosis
import com.mistbell.tavern.v3.feature.memory.MemoryStatusFormat
import com.mistbell.tavern.v3.feature.memory.MemoryStatusSnapshot

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
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = actions.onBack) { Text("返回") }
            Text("会话记忆", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = actions.onReload, enabled = state !is SessionMemoryUiState.Loading) {
                Text("刷新")
            }
        }
        Text(scopeTitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
        status?.let { MemoryStatusBlock(it) }
        when (state) {
            SessionMemoryUiState.Loading -> Text(
                "正在读取本会话记忆…",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            is SessionMemoryUiState.Failed -> Text(
                "记忆暂时无法读取：${state.message}\n原数据已保留，未做任何修改。",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            is SessionMemoryUiState.Ready -> MemoryList(
                state = state,
                onEdit = { editing = it },
                onRevert = { actions.onRevert(it.factId, it.revision) },
            )
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
private fun MemoryStatusBlock(status: MemoryStatusSnapshot) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(Color.White.copy(alpha = 0.04f))
            .padding(10.dp),
    ) {
        Text(
            MemoryStatusDiagnosis.hint(status),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "抽取队列：待处理 ${status.queuedJobs} · 重试 ${status.retryJobs} · " +
                "执行中 ${status.runningJobs} · 失败 ${status.failedJobs}",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.65f),
        )
        Text(
            "事实 ${status.factsCurrent}/${status.factsTotal} · 文本 ${status.textRecords} · " +
                "向量 ${MemoryStatusFormat.bytes(status.vectorBytes)}" +
                (status.lastRetrieval?.let { " · 最近：${it.take(PREVIEW_CHARS)}" } ?: ""),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.65f),
        )
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
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        state.issue?.let {
            Text("手动修订文件读取异常：$it（自动记忆仍可查看）", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "有效 ${current.size} 条 · 历史 ${history.size} 条",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        if (state.entries.isEmpty()) {
            Text(
                "暂无已保存记忆。继续聊天，抽取成功后这里会出现条目。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(current, key = { it.factId }) { entry ->
                MemoryEntryCard(entry, editable = true, onEdit = onEdit, onRevert = onRevert)
            }
            if (history.isNotEmpty()) {
                item { Text("历史版本（只读）", style = MaterialTheme.typography.labelLarge) }
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
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.06f))
            .padding(12.dp),
    ) {
        Text(entry.subject, style = MaterialTheme.typography.titleSmall)
        Text(entry.predicate, style = MaterialTheme.typography.labelSmall)
        Text(entry.value, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            val origin = if (entry.origin == MemoryOrigin.MANUAL) "手动修改" else "自动抽取"
            Text(
                "$origin · 来源轮 ${entry.sourceTurnId.take(ID_PREVIEW_CHARS)}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.weight(1f),
            )
            if (editable) {
                TextButton(onClick = { onEdit(entry) }) { Text("编辑") }
                if (entry.origin == MemoryOrigin.MANUAL) {
                    TextButton(onClick = { onRevert(entry) }) { Text("恢复自动") }
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
    val tooLong = subject.length > MAX_FIELD_CHARS || predicate.length > MAX_FIELD_CHARS ||
        value.length > MAX_FIELD_CHARS
    val invalid = subject.isBlank() || predicate.isBlank() || value.isBlank() || tooLong
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑记忆") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "只修改这条记忆的文字；原始抽取记录与来源轮次会保留。",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(subject, { subject = it }, label = { Text("主体") }, singleLine = true)
                OutlinedTextField(predicate, { predicate = it }, label = { Text("关系") }, singleLine = true)
                OutlinedTextField(value, { value = it }, label = { Text("内容") })
                if (tooLong) {
                    Text("单个字段最多 $MAX_FIELD_CHARS 字符", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(subject.trim(), predicate.trim(), value.trim()) }, enabled = !invalid) {
                Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** Called by the screen; grouped so the composable stays within the parameter-count budget. */
internal data class SessionMemoryActions(
    val onBack: () -> Unit,
    val onReload: () -> Unit,
    val onSave: (factId: String, revision: Long, subject: String, predicate: String, value: String) -> Unit,
    val onRevert: (factId: String, revision: Long) -> Unit,
)

private const val MAX_FIELD_CHARS = 2_000
private const val PREVIEW_CHARS = 60
private const val ID_PREVIEW_CHARS = 8
