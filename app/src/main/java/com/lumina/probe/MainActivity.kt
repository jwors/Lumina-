package com.lumina.probe

import android.os.Bundle
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
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
        val controller = ProbeController(this) { next -> state = next }
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        // 顶部名字 + Tab
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Lumina", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = InkTeal)
            Modifier.weight(1f).let { Spacer(modifier = it) }
            Text("M1", fontSize = 10.sp, color = CreamFaint)
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .background(DividerCream)
                .padding(4.dp),
        ) {
            TabPill("相机", tab, onSelect = { tab = it })
            TabPill("图库", tab, onSelect = { tab = it })
        }

        if (tab == "相机") {
            CameraPage(Modifier.weight(1f), state, controller)
        } else {
            controller.loadGallery()
            GalleryPage(Modifier.weight(1f), state)
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
private fun GalleryPage(modifier: Modifier = Modifier, state: ProbeUiState) {
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
        LazyColumn(modifier = Modifier.weight(1f).padding(top = 8.dp)) {
            state.galleryItems.groupBy { it.dateKey }.forEach { (dateKey, items) ->
                item(key = "gday-$dateKey") {
                    Text(
                        dateKey,
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

/** JPEG 缩略图行 / NEF RAW 占位行。 */
@Composable
private fun GalleryRow(item: GalleryItem, onClick: () -> Unit) {
    val isRaw = !item.name.uppercase().endsWith(".JPG") && !item.name.uppercase().endsWith(".JPEG")
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
                if (isRaw) {
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
                } else {
                    GalleryThumb(item)
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

        val grouped = state.cameraItems.groupBy { it.dateKey }
        LazyColumn(modifier = Modifier.weight(1f).padding(top = 8.dp)) {
            grouped.forEach { (dateKey, items) ->
                item(key = "day-$dateKey") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(dateKey, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = SageText)
                        Modifier.weight(1f).let { Spacer(modifier = it) }
                        val dayAllChecked = items.all {
                            state.checkedNames.contains(it.name)
                        }
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
                items(items, key = { "obj-${it.handle}:${it.name}" }) { obj ->
                    CameraRow(obj, checked = state.checkedNames.contains(obj.name),
                        onToggle = { controller.toggleCheck(obj) })
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

/** 对象行（勾选框 + 文件名 + 体积 + 分隔线）。 */
@Composable
private fun CameraRow(obj: CameraItem, checked: Boolean, onToggle: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().background(CreamFaint.copy(alpha = 0.08f))) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
            Text(obj.name, fontSize = 14.sp, color = InkTeal, modifier = Modifier.weight(1f))
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
