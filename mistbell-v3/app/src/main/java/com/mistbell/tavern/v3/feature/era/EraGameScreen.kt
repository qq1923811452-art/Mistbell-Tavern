@file:Suppress("FunctionNaming", "MagicNumber")

package com.mistbell.tavern.v3.feature.era

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mistbell.tavern.v3.R
import com.mistbell.tavern.v3.ui.TavernBackground
import com.mistbell.tavern.v3.ui.tavern.TavernGlyphName
import com.mistbell.tavern.v3.ui.tavern.TavernIconButton
import com.mistbell.tavern.v3.ui.tavern.TavernPalette
import com.mistbell.tavern.v3.ui.tavern.TavernPill
import com.mistbell.tavern.v3.ui.tavern.TavernShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SCREEN_PADDING = 20.dp
private val CARD_PADDING = 18.dp
private val HERO_TOP_GAP = 24.dp
private val CARD_GAP = 14.dp
private val STATUS_ROW_GAP = 12.dp
private val STATUS_DIVIDER_HEIGHT = 1.dp
private val PROGRESS_SIZE = 16.dp

private val ERA_CARD_TOP = Color(0xE6341B33)
private val ERA_CARD_BOTTOM = Color(0xE6191026)
private val ERA_GOLD_BORDER = Color(0x4DFFCE8A)
private val ERA_GOLD_SOFT = Color(0x26FFCE8A)
private val ERA_GOLD_TEXT = Color(0xFFFFCE8A)
private val ERA_DIVIDER = Color(0x1FFFFFFF)

/**
 * First landing surface for the 4.0 Gensokyo adaptation.
 *
 * The engine is not bundled yet; this screen owns the entry contract, the ZIP import path, and the
 * status the session page links to, so the EWP bridge can replace the placeholder without moving
 * routes.
 */
@Suppress("LongMethod")
@Composable
fun EraGameScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val repository = remember(context) { EraGameRepository(context) }
    val scope = rememberCoroutineScope()
    var installed by remember(repository) { mutableStateOf(repository.current()) }
    var importing by remember { mutableStateOf(false) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    val zipPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                importing = true
                importMessage = null
                try {
                    val result =
                        withContext(Dispatchers.IO) {
                            repository.importZip(uri, queryDisplayName(context, uri))
                        }
                    installed = result
                    importMessage = "已导入 ${result.name}，共 ${result.fileCount} 个文件。"
                } catch (error: Exception) {
                    importMessage = "导入失败：${error.message ?: error.javaClass.simpleName}"
                } finally {
                    importing = false
                }
            }
        }

    TavernBackground(
        drawable = R.drawable.bg_tavern,
        topAlpha = 0.24f,
        middleAlpha = 0.20f,
        bottomAlpha = 0.74f,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = SCREEN_PADDING),
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            EraTopBar(onBack = onBack)
            Spacer(modifier = Modifier.height(HERO_TOP_GAP))
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
            ) {
                EraHeroCard()
                Spacer(modifier = Modifier.height(CARD_GAP))
                EraStatusCard(installed = installed)
                Spacer(modifier = Modifier.height(CARD_GAP))
                EraImportCard(
                    installed = installed,
                    importing = importing,
                    message = importMessage,
                    onImport = {
                        zipPicker.launch(
                            arrayOf(
                                "application/zip",
                                "application/x-zip-compressed",
                                "application/octet-stream",
                            ),
                        )
                    },
                )
                Spacer(modifier = Modifier.height(24.dp))
            }
            TavernPill(
                label = "返回会话",
                onClick = onBack,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                filled = true,
                contentDescription = "返回会话列表",
            )
            Text(
                text = "4.0 幻想乡适配 · ZIP 导入不校验哈希，便于频繁更新",
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp),
                color = TavernPalette.TextTertiary,
                fontSize = 11.sp,
                letterSpacing = 0.4.sp,
            )
        }
    }
}

@Composable
private fun EraTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TavernIconButton(
            glyph = TavernGlyphName.Back,
            onClick = onBack,
            contentDescription = "返回会话列表",
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "幻想乡",
                color = TavernPalette.TextPrimary,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
                letterSpacing = 1.sp,
            )
            Text(
                text = "eraTW · 东方角色扮演",
                color = TavernPalette.TextTertiary,
                fontSize = 12.sp,
                letterSpacing = 0.4.sp,
            )
        }
        Box(
            modifier =
                Modifier
                    .clip(CircleShape)
                    .background(ERA_GOLD_SOFT)
                    .border(1.dp, ERA_GOLD_BORDER, CircleShape)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                text = "适配中",
                color = ERA_GOLD_TEXT,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.6.sp,
            )
        }
    }
}

@Composable
private fun EraHeroCard() {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(TavernShape.Card)
                .background(Brush.verticalGradient(listOf(ERA_CARD_TOP, ERA_CARD_BOTTOM)))
                .border(1.dp, ERA_GOLD_BORDER, TavernShape.Card)
                .padding(CARD_PADDING),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "幻想乡",
                color = TavernPalette.TextPrimary,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 30.sp,
                letterSpacing = 1.sp,
            )
            Text(
                text = "eraTW",
                color = ERA_GOLD_TEXT,
                fontSize = 10.sp,
                letterSpacing = 1.sp,
                modifier =
                    Modifier
                        .clip(CircleShape)
                        .background(ERA_GOLD_SOFT)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Text(
            text =
                "把 eraTW 的世界状态带进 Mistbell 的叙事里。" +
                    "角色、记忆和聊天仍由 Mistbell 管理，游戏规则由引擎结算。",
            modifier = Modifier.padding(top = 10.dp),
            color = TavernPalette.TextSecondary,
            fontSize = 14.sp,
            lineHeight = 21.sp,
        )
        Row(
            modifier = Modifier.padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EraTag("本地引擎")
            EraTag("EWP 桥接")
            EraTag("会话记忆")
        }
    }
}

@Composable
private fun EraTag(label: String) {
    Text(
        text = label,
        color = TavernPalette.TextSecondary,
        fontSize = 11.sp,
        modifier =
            Modifier
                .clip(CircleShape)
                .background(TavernPalette.Glass)
                .border(1.dp, TavernPalette.Border, CircleShape)
                .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

@Composable
private fun EraStatusCard(installed: EraGameInstall?) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(TavernShape.Card)
                .background(TavernPalette.Surface.copy(alpha = 0.86f))
                .border(1.dp, TavernPalette.Border, TavernShape.Card)
                .padding(horizontal = CARD_PADDING, vertical = 6.dp),
    ) {
        EraStatusRow(label = "会话入口", value = "已接入", accent = true)
        EraStatusDivider()
        EraStatusRow(label = "引擎核心", value = "Emuera Skia + EWP（待接入）")
        EraStatusDivider()
        EraStatusRow(label = "游戏包", value = installed?.name ?: "尚未导入")
        EraStatusDivider()
        EraStatusRow(label = "记忆", value = "沿用 Mistbell 会话记忆")
    }
}

@Composable
private fun EraStatusRow(
    label: String,
    value: String,
    accent: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = STATUS_ROW_GAP),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = label,
            color = TavernPalette.TextTertiary,
            fontSize = 12.sp,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            color = if (accent) ERA_GOLD_TEXT else TavernPalette.TextSecondary,
            fontSize = 13.sp,
            fontWeight = if (accent) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
private fun EraStatusDivider() {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(STATUS_DIVIDER_HEIGHT)
                .background(ERA_DIVIDER),
    )
}

@Suppress("LongMethod", "LongParameterList")
@Composable
private fun EraImportCard(
    installed: EraGameInstall?,
    importing: Boolean,
    message: String?,
    onImport: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(TavernShape.Card)
                .background(TavernPalette.Surface.copy(alpha = 0.86f))
                .border(1.dp, TavernPalette.Border, TavernShape.Card)
                .padding(CARD_PADDING),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "游戏包",
                color = TavernPalette.TextPrimary,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
            )
            if (installed != null) {
                Text(
                    text = "${installed.fileCount} 个文件",
                    modifier = Modifier.weight(1f),
                    color = TavernPalette.TextTertiary,
                    fontSize = 11.sp,
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
        Text(
            text =
                if (installed == null) {
                    "选择 eraTW 的 ZIP 包，解压到应用私有目录；不需要联网。"
                } else {
                    "当前：${installed.name}。再次导入同名 ZIP 即可更新，sav/ 存档会保留。"
                },
            modifier = Modifier.padding(top = 8.dp),
            color = TavernPalette.TextSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        TavernPill(
            label = if (installed == null) "导入游戏 ZIP" else "更新游戏 ZIP",
            onClick = onImport,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(44.dp),
            filled = true,
            enabled = !importing,
            contentDescription = "选择 eraTW ZIP 包",
        )
        if (importing) {
            Row(
                modifier = Modifier.padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(PROGRESS_SIZE),
                    color = ERA_GOLD_TEXT,
                    strokeWidth = 2.dp,
                )
                Text(
                    text = "正在解压，请勿离开…",
                    color = TavernPalette.TextSecondary,
                    fontSize = 12.sp,
                )
            }
        }
        message?.let { text ->
            Text(
                text = text,
                modifier = Modifier.padding(top = 8.dp),
                color = if (text.startsWith("导入失败")) TavernPalette.Danger else ERA_GOLD_TEXT,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
        Text(
            text = "不做哈希校验；更新时保留 sav/ 与根目录 save*.sav。",
            modifier = Modifier.padding(top = 8.dp),
            color = TavernPalette.TextTertiary,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
    }
}

private fun queryDisplayName(
    context: Context,
    uri: Uri,
): String {
    val fallback =
        uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.substringAfterLast(':')
            ?.takeIf(String::isNotBlank)
            ?: "eraTW"
    return runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
    }.getOrNull()?.takeIf(String::isNotBlank) ?: fallback
}
