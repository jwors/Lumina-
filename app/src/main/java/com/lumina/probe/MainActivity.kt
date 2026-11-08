package com.lumina.probe

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.ptp.CameraObject
import java.time.LocalDate

/**
 * M1 相机页（工单 03）：M0 探针升级为双 Tab（相机/图库）。
 * 相机页支持逐张勾选 + 全选本日/清空 + 底部"导出 N 个" + 队列状态行。
 * 图库（工单 04）此版仅占位。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        var state by mutableStateOf(ProbeUiState("未连接 · 请插入相机"))
        val controller = ProbeController(this) { next ->
            Log.i("Lumina.UI", "onState: conn='${next.connectionText.take(40)}' " +
                "cameraItems=${next.cameraItems.size} checked=${next.checkedNames.size} " +
                "engineReady=${next.engineReady} gallery=${next.galleryItems.size}")
            state = next
        }
        setContent {
            Surface(color = FilmBackground) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AppScaffold(state, controller)
                }
            }
        }
    }
}

val FilmBackground = Color(0xFFF4F2EA)
val InkTeal = Color(0xFF20251F)
val SageText = Color(0xFF767F72)
val CreamFaint = Color(0xFFB9B3A6)
val DividerCream = Color(0xFFE3DFD3)
val PillActive = Color(0xFFE0E5D8)

@Composable
fun AppScaffold(state: ProbeUiState, controller: ProbeController) {
    var tab by remember { mutableStateOf("相机") }
    // 补跑横幅（顶部：网络可用 + 队列非空 + 总开关联动）
    state.catchupBanner?.let { banner ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PillActive)
                .clickable { controller.runCatchUp() }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(banner, fontSize = 13.sp, color = InkTeal, fontWeight = FontWeight.SemiBold)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        // 顶部名字 + Tab
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Lumina", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = InkTeal)
            Modifier.weight(1f).let { Spacer(modifier = it) }
            Text("M2", fontSize = 10.sp, color = CreamFaint)
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .background(DividerCream)
                .padding(4.dp),
        ) {
            TabPill("相机", tab, onSelect = { tab = it; if (it == "相机") controller.emitCameraUiRefresh() })
            TabPill("图库", tab, onSelect = { tab = it; if (it == "图库") controller.loadGallery() })
            TabPill("配方", tab, onSelect = { tab = it; if (it == "配方") controller.loadCards() })
            TabPill("设置", tab, onSelect = { tab = it; if (it == "设置") controller.loadSettings() })
        }

        if (tab == "相机") {
            CameraPage(Modifier.weight(1f), state, controller)
        } else if (tab == "图库") {
            GalleryPage(Modifier.weight(1f), state, controller)
        } else if (tab == "配方") {
            RecipePage(Modifier.weight(1f), state, controller)
        } else {
            SettingsPage(Modifier.weight(1f), state, controller)
        }
    }
}

@Composable
private fun TabPill(name: String, current: String, onSelect: (String) -> Unit) {
    val active = name == current
    Box(
        modifier = Modifier
            .background(if (active) PillActive else FilmBackground)
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .clickable { onSelect(name) },
    ) {
        Text(
            name,
            fontSize = 16.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) InkTeal else SageText,
        )
    }
}

@Composable
private fun GalleryPage(modifier: Modifier = Modifier, state: ProbeUiState, controller: ProbeController) {
    Column(modifier = modifier) {
        Text(state.connectionText, fontSize = 14.sp, color = SageText)
        if (state.galleryStatus.isNotBlank()) {
            Text(state.galleryStatus, fontSize = 12.sp, color = CreamFaint)
        }
        Spacer(Modifier.height(8.dp))
        if (state.galleryItems.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Master 图库还没有内容", fontSize = 16.sp, color = SageText)
                Spacer(Modifier.height(4.dp))
                Text("先切到相机 Tab 勾选并导出", fontSize = 12.sp, color = CreamFaint)
            }
            return@Column
        }
        var selected by remember { mutableStateOf<GalleryItem?>(null) }
        var viewFeed by remember { mutableStateOf(false) }
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                if (viewFeed) "← 返回图库" else "⚡ 智能结果全文",
                fontSize = 13.sp,
                color = InkTeal,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { controller.loadSmartFeed(); viewFeed = !viewFeed },
            )
        }
        if (viewFeed) {
            SmartFeedPage(Modifier.weight(1f), state)
            return@Column
        }
        // 智能徽章：有结果的行打角标
        LazyColumn(modifier = Modifier.weight(1f).padding(top = 8.dp)) {
            state.galleryItems.groupBy { it.dateKey }.forEach { (dateKey, items) ->
                item(key = "gday-$dateKey") {
                    Text(
                        dateKey + if (items.any { it.smartCount > 0 }) "  ⚡" else "",
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = SageText,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                }
                items(items, key = { "g-${it.relPath}" }) { gi ->
                    GalleryRow(gi, onClick = { selected = gi })
                }
            }
        }
        selected?.let { sel ->
            AlertDialog(
                onDismissRequest = { selected = null },
                title = { Text("详情", fontSize = 14.sp, color = SageText) },
                text = {
                    Column {
                        Text(sel.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = InkTeal)
                        Spacer(Modifier.height(8.dp))
                        Text("日期：${sel.dateKey}", fontSize = 13.sp, color = SageText)
                        Text("体积：${humanBytes(sel.sizeBytes)}", fontSize = 13.sp, color = SageText)
                        if (sel.smartTexts.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Text("智能（M2）", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = InkTeal)
                            sel.smartTexts.forEach { line ->
                                Text(line, fontSize = 12.sp, color = SageText, modifier = Modifier.padding(top = 2.dp))
                            }
                        }
                        if (sel.relPath.uppercase().endsWith(".NEF") || sel.relPath.uppercase().endsWith(".MP4")) {
                            Spacer(Modifier.height(8.dp))
                            Text("仅存原片（不解析 RAW）", fontSize = 12.sp, color = CreamFaint)
                        }
                    }
                },
                confirmButton = {
                    Text(
                        "关闭",
                        fontSize = 14.sp,
                        color = InkTeal,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { selected = null }.padding(8.dp),
                    )
                },
            )
        }
    }
}

/** 智能结果全文页（一条 = 一卡一步对一 Master）。 */
@Composable
private fun SmartFeedPage(modifier: Modifier = Modifier, state: ProbeUiState) {
    if (state.smartFeed.isEmpty()) {
        Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("还没有智能结果", fontSize = 15.sp, color = SageText)
            Text("先在配方 Tab 执行一张卡", fontSize = 12.sp, color = CreamFaint)
        }
        return
    }
    LazyColumn(modifier = modifier.padding(top = 8.dp)) {
        items(state.smartFeed, key = { "${it.name}|${it.kind}|${it.createdAtMs}" }) { item ->
            Column(
                modifier = Modifier.fillMaxWidth()
                    .background(CreamFaint.copy(alpha = 0.08f))
                    .padding(horizontal = 4.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = InkTeal)
                    Spacer(Modifier.weight(1f))
                    Text("[${item.kind}]", fontSize = 11.sp, color = CreamFaint)
                }
                Text(item.text, fontSize = 13.sp, color = InkTeal, modifier = Modifier.padding(top = 4.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(DividerCream))
            }
        }
    }
}

/** JPEG 缩略图行 / NEF RAW 占位行。 */
@Composable
private fun GalleryRow(item: GalleryItem, onClick: () -> Unit) {
    val isRawImg = !item.name.uppercase().endsWith(".JPG") && !item.name.uppercase().endsWith(".JPEG")
    val showWork = item.workRelPath != null
    val isRaw = isRawImg && !showWork
    Column(modifier = Modifier.fillMaxWidth().background(CreamFaint.copy(alpha = 0.08f))) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .height(64.dp)
                    .padding(end = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (showWork || !isRaw) {
                    if (showWork) {
                        // Work 成片缩略（滤镜已应用）
                        WorkThumb(item.workRelPath!!)
                    } else {
                        GalleryThumb(item)
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .background(PillActive)
                            .padding(10.dp),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("RAW", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = InkTeal)
                            Text("胶片框", fontSize = 10.sp, color = SageText)
                        }
                    }
                }
            }
            Text(item.name, fontSize = 14.sp, color = InkTeal, modifier = Modifier.weight(1f))
            Text(humanBytes(item.sizeBytes), fontSize = 12.sp, color = SageText)
        }
        Box(
            Modifier.fillMaxWidth().height(1.dp).background(DividerCream),
        )
    }
}

/** 就地解码缩略图（BitmapFactory inSampleSize；每次进入页面重算——M1 无 LruCache，内存 8 项够用）。 */
@Composable
private fun GalleryThumb(item: GalleryItem) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, key1 = item.relPath) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            decodeSampled(java.io.File(context.filesDir, item.relPath).absolutePath, target = 128)
        }
    }
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = item.name,
            modifier = Modifier.height(56.dp),
        )
    } ?: CircularProgressIndicator(
        modifier = Modifier.height(24.dp),
        color = InkTeal,
        trackColor = DividerCream,
        strokeWidth = 2.dp,
    )
}

/** 就地解码缩略图（BitmapFactory inSampleSize）。 */
private fun decodeSampled(path: String, target: Int): android.graphics.Bitmap? {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    return android.graphics.BitmapFactory.decodeFile(path, opts)
}

@Composable
private fun WorkThumb(workRelPath: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, key1 = workRelPath) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            WorkThumbDecode(java.io.File(context.filesDir, workRelPath).absolutePath, target = 160)
        }
    }
    bitmap?.let {
        androidx.compose.foundation.Image(
            bitmap = it.asImageBitmap(),
            contentDescription = "Work",
            modifier = Modifier.height(56.dp),
        )
    } ?: CircularProgressIndicator(
        modifier = Modifier.height(24.dp),
        color = InkTeal,
        trackColor = DividerCream,
        strokeWidth = 2.dp,
    )
}

private fun WorkThumbDecode(path: String, target: Int): android.graphics.Bitmap? {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    return android.graphics.BitmapFactory.decodeFile(path, opts)
}

@Composable
private fun CameraPage(
    modifier: Modifier = Modifier,
    state: ProbeUiState,
    controller: ProbeController,
) {
    Column(modifier = modifier) {
        Spacer(Modifier.height(12.dp))
        if (state.busy) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = InkTeal,
                trackColor = DividerCream,
            )
            Spacer(Modifier.height(12.dp))
        }
        Text(state.connectionText, fontSize = 14.sp, color = SageText)
        state.ingestProgress?.let { progress ->
            if (progress.running) {
                LinearProgressIndicator(
                    progress = {
                        if (progress.total > 0) progress.done.toFloat() / progress.total else 0f
                    },
                    modifier = Modifier.fillMaxWidth(),
                    color = InkTeal,
                    trackColor = DividerCream,
                )
                if (progress.currentName.isNotBlank()) {
                    Text(
                        "正在复制：${progress.currentName}",
                        fontSize = 12.sp,
                        color = SageText,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        if (state.pendingPermission) {
            Text(
                "重试授权",
                fontSize = 14.sp,
                color = InkTeal,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .clickable { controller.retryPermission() },
            )
        }

        // 排序 + 分组展开/收缩（用户决定：纯图标、零文字）
        var newestFirst by remember { mutableStateOf(true) }
        var collapsedDays by remember { mutableStateOf(setOf<String>()) }

        val grouped = state.cameraItems.groupBy { it.dateKey }.let { m ->
            if (newestFirst) m.entries.sortedByDescending { it.key } else m.entries.sortedBy { it.key }
        }
        Log.i("Lumina.UI", "CameraPage recompose: items=${state.cameraItems.size} groups=${grouped.size} checked=${state.checkedNames.size}")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 排序图标：↓ 新→旧 / ↑ 旧→新（点按翻转）
            Text(
                if (newestFirst) "↓" else "↑",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = InkTeal,
                modifier = Modifier
                    .clickable { newestFirst = !newestFirst }
                    .padding(6.dp),
            )
            Modifier.weight(1f).let { Spacer(modifier = it) }
            // 全部展开/收缩：单枚状态切换图标（▲=可展开 / ▼=可收缩）
            Text(
                if (collapsedDays.isNotEmpty()) "▲" else "▼",
                fontSize = 16.sp,
                color = InkTeal,
                modifier = Modifier
                    .clickable {
                        collapsedDays =
                            if (collapsedDays.isNotEmpty()) emptySet()
                            else state.cameraItems.map { it.dateKey }.toSet()
                    }
                    .padding(6.dp),
            )
        }
        LazyColumn(modifier = Modifier.weight(1f).padding(top = 8.dp)) {
            grouped.forEach { (dateKey, items) ->
                val collapsed = dateKey in collapsedDays
                item(key = "day-$dateKey") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp, bottom = 4.dp)
                            .clickable {
                                collapsedDays =
                                    if (collapsed) collapsedDays - dateKey
                                    else collapsedDays + dateKey
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 展开指示箭头
                        Text(
                            if (collapsed) "▸" else "▾",
                            fontSize = 12.sp,
                            color = SageText,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                        Text(dateKey, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = SageText)
                        Text(
                            "　${if (collapsed) "(${items.size} 张)" else ""}",
                            fontSize = 11.sp,
                            color = CreamFaint,
                        )
                        Modifier.weight(1f).let { Spacer(modifier = it) }
                        val dayAllChecked = items.all {
                            state.checkedNames.contains(it.name)
                        }
                        if (!collapsed) {
                            Text(
                                if (dayAllChecked) "清空本日" else "全选本日",
                                fontSize = 12.sp,
                                color = InkTeal,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable {
                                    controller.toggleDay(dateKey, !dayAllChecked)
                                },
                            )
                        }
                    }
                }
                if (!collapsed) {
                    val ordered = if (newestFirst) items.asReversed() else items
                    items(ordered, key = { "obj-${it.handle}:${it.name}" }) { obj ->
                        CameraRow(state, controller, obj,
                            checked = state.checkedNames.contains(obj.name),
                            onToggle = { controller.toggleCheck(obj) })
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        state.ingestStatus.let { status ->
            if (status.isNotBlank()) {
                Text(status, fontSize = 12.sp, color = SageText)
            }
        }
        // 底部固定：导出按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val n = state.checkedNames.size
            val enabled = state.engineReady && n > 0
            Box(
                modifier = Modifier
                    .background(if (enabled) InkTeal else DividerCream)
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .clickable(enabled = enabled) { controller.startIngest() },
            ) {
                Text(
                    if (state.engineReady) "导出 $n 个" else "引擎未就绪（需 MTP 会话）",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) FilmBackground else SageText,
                )
            }
            if (state.ingestStatus.contains("失败") && state.checkedNames.isNotEmpty()) {
                Text(
                    "重试失败",
                    fontSize = 14.sp,
                    color = InkTeal,
                    modifier = Modifier
                        .padding(start = 16.dp)
                        .clickable { controller.retryFailed() },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(DividerCream)
                .padding(vertical = 4.dp, horizontal = 8.dp),
        ) {
            Column {
                state.logTail.forEach { line ->
                    Text(line, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = SageText)
                }
            }
        }
    }
}

/** 对象行（勾选框 + 文件名 + 体积 + 状态/失败重试 + 分隔线）。 */
@Composable
private fun CameraRow(
    state: ProbeUiState,
    controller: ProbeController,
    obj: CameraItem,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(CreamFaint.copy(alpha = 0.08f))) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
            Column(modifier = Modifier.weight(1f)) {
                Text(obj.name, fontSize = 14.sp, color = InkTeal)
                state.ingestProgress?.statuses?.get(obj.name)?.let { status ->
                    when {
                        status.startsWith("✗") -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(status, fontSize = 11.sp, color = SageText, modifier = Modifier.padding(end = 10.dp))
                                Text(
                                    "重试",
                                    fontSize = 11.sp,
                                    color = InkTeal,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.clickable { controller.retryOne(obj) },
                                )
                            }
                        }
                        else -> Text(status, fontSize = 11.sp, color = SageText)
                    }
                }
            }
            Text(humanBytes(obj.sizeBytes), fontSize = 12.sp, color = SageText)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(DividerCream),
        )
    }
}

/** 配方页（M2 工单 03）：卡列表 + SAF 导入导出。 */
@Composable
private fun RecipePage(
    modifier: Modifier = Modifier,
    state: ProbeUiState,
    controller: ProbeController,
) {
    // 导出双段流程：先记下要导的卡，launcher 回调再把 JSON 写 uri
    var pendingExport: CardUi? by remember { mutableStateOf<CardUi?>(null) }
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: android.net.Uri? ->
        pendingExport?.let { card -> uri?.let { u -> controller.exportCardTo(u, card.cardId) } }
        pendingExport = null
    }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri: android.net.Uri? -> uri?.let { controller.importCard(it) } }

    Column(modifier = modifier) {
        Text(state.connectionText, fontSize = 14.sp, color = SageText)
        if (state.cardStatus.isNotBlank()) {
            Text(state.cardStatus, fontSize = 12.sp, color = InkTeal,
                modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .background(InkTeal)
                    .clickable {
                        importLauncher.launch(arrayOf("application/json", "text/*"))
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text("导入卡片 (.json)", fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold, color = FilmBackground)
            }
            Spacer(Modifier.width(12.dp))
            Text("共享的配方属于文件（无自有服务器）", fontSize = 11.sp, color = CreamFaint)
        }
        Spacer(Modifier.height(8.dp))
        if (state.cards.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("配方库已空", fontSize = 15.sp, color = SageText)
            }
            return@Column
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(state.cards, key = { it.cardId }) { card ->
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .background(CreamFaint.copy(alpha = 0.08f))
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
            Text(card.name + if (card.imported) "（导入）" else "",
                fontSize = 15.sp, color = InkTeal, modifier = Modifier.weight(1f))
            Text(
                "v${card.version} · ${card.stepsCount} 步",
                fontSize = 11.sp, color = SageText,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "执行 Pass（全部 Master）",
                fontSize = 13.sp,
                color = InkTeal,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable { controller.runCardPass(card.cardId) }
                    .padding(4.dp),
            )
            Modifier.weight(1f).let { Spacer(modifier = it) }
            Text(
                "导出",
                fontSize = 13.sp, color = InkTeal, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable {
                        pendingExport = card
                        exportLauncher.launch("${card.name}.json")
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(DividerCream))
                }
            }
        }
    }
}

/** 人类可读体积（M0 简单四段式；Locale.US 钉死小数点）。 */
internal fun humanBytes(bytes: Long): String {
    val GB = 1L shl 30
    val MB = 1L shl 20
    val KB = 1L shl 10
    return when {
        bytes >= GB -> String.format(java.util.Locale.US, "%.2f GB", bytes.toDouble() / GB)
        bytes >= MB -> String.format(java.util.Locale.US, "%.1f MB", bytes.toDouble() / MB)
        bytes >= KB -> String.format(java.util.Locale.US, "%.0f KB", bytes.toDouble() / KB)
        else -> "$bytes B"
    }
}

// ---------- 设置页（M2 工单 01） ----------

@Composable
private fun SettingsPage(
    modifier: Modifier = Modifier,
    state: ProbeUiState,
    controller: ProbeController,
) {
    val cfg = state.llmConfig
    var baseUrl by remember(cfg) { mutableStateOf(cfg?.baseUrl ?: "") }
    var apiKey by remember(cfg) { mutableStateOf(cfg?.apiKey ?: "") }
    var model by remember(cfg) { mutableStateOf(cfg?.model ?: "") }
    var templateName by remember(cfg) { mutableStateOf(cfg?.templateName ?: "") }
    var saved by remember { mutableStateOf("") }

    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        Text(state.connectionText, fontSize = 14.sp, color = SageText)

        Spacer(Modifier.height(16.dp))
        Text("云处理总开关", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = InkTeal)
        Text(
            if (state.cloudEnabled) "开启中：联网模型仅用于你配置的 API 域" else "关闭中：所有云端请求挂起（回网也不跑）",
            fontSize = 12.sp,
            color = if (state.cloudEnabled) InkTeal else CreamFaint,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = state.cloudEnabled,
                onCheckedChange = { controller.setCloudEnabled(it) },
            )
            Text(if (state.cloudEnabled) "开" else "关", fontSize = 14.sp, color = InkTeal,
                modifier = Modifier.padding(start = 8.dp))
        }

        Spacer(Modifier.height(20.dp))
        Text("平台模板（一键填选题）", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = InkTeal)
        Spacer(Modifier.height(6.dp))
        com.lumina.llm.LlmTemplates.presets.forEach { t ->
            val active = templateName == t.name
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .background(if (active) PillActive else CreamFaint.copy(alpha = 0.4f))
                    .clickable {
                        templateName = t.name
                        baseUrl = t.baseUrl
                        model = t.model
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    if (active) "● " else "○ ",
                    fontSize = 13.sp,
                    color = InkTeal,
                )
                Text(
                    "${t.name}　${t.model}",
                    fontSize = 13.sp,
                    color = if (active) InkTeal else SageText,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("base_url（OpenAI 兼容）") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("api_key") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("model") },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .background(InkTeal)
                .padding(horizontal = 24.dp, vertical = 10.dp)
                .clickable {
                    if (baseUrl.isBlank() || model.isBlank()) {
                        saved = "base_url 与 model 不能为空"
                    } else {
                        controller.saveLlmConfig(baseUrl, apiKey, model, templateName, state.cloudEnabled)
                        saved = "已保存 ✓"
                    }
                },
        ) {
            Text("保存配置", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = FilmBackground)
        }
        if (saved.isNotBlank()) {
            Text(saved, fontSize = 12.sp, color = InkTeal, modifier = Modifier.padding(top = 8.dp))
        }

        Spacer(Modifier.height(16.dp))
        // 连接测试：真实 ping 一次 chat.completions（文本 only，无图）
        Box(
            modifier = Modifier
                .background(if (state.cloudEnabled) PillActive else DividerCream)
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .clickable(enabled = state.cloudEnabled) { controller.testLlmConnection() },
        ) {
            Text(
                if (state.cloudEnabled) "测试连接 LLM" else "先开云处理总开关",
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = if (state.cloudEnabled) InkTeal else SageText,
            )
        }
        if (state.testResult.isNotBlank()) {
            Text(state.testResult, fontSize = 12.sp, color = SageText,
                modifier = Modifier.padding(top = 8.dp))
        }

        Spacer(Modifier.height(16.dp))
        Text("明细日志（最近 200 内）", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = InkTeal)
        Spacer(Modifier.height(4.dp))
        if (state.ledgerRecent.isEmpty()) {
            Text("还没有云端调用", fontSize = 12.sp, color = CreamFaint)
        } else {
            state.ledgerRecent.forEach { line ->
                Text(line, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = SageText)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "隐私姿态（ADR-0005）：联网仅指向你配置的模型 API 域；App 无自有服务器；" +
                "App 无其他任何敏感权限（无定位/媒体/相机/麦克风）。",
            fontSize = 11.sp,
            color = CreamFaint,
        )
    }
}
