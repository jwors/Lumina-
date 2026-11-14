package com.lumina.probe

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.work.CubeLut
import com.lumina.work.FilterCatalog
import com.lumina.work.FilterCategory
import com.lumina.work.FilterEntry
import com.lumina.work.FilterPreviewEngine
import com.lumina.work.FilterSelection
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * M3-07 滤镜台：主画布实时预览 + 题材分类条 + ⊘ 无滤镜 + 缩略图条（每条 = 源图套该滤镜的真渲染）。
 * 交互参照 2026-11-15 用户给的相册滤镜面板截图；只保留滤镜一档（裁剪/调整/水印等不做）。
 * 预览与缩略图各用一条 [FilterPreviewEngine]（GL 上下文按线程归属）。
 */
@Composable
internal fun FilterStudioOverlay(
    gi: GalleryItem,
    controller: ProbeController,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val metrics = context.resources.displayMetrics
    val screenPx = minOf(metrics.widthPixels, metrics.heightPixels)

    val previewEngine = remember {
        FilterPreviewEngine(context, screenPx) { android.util.Log.i("Lumina.Filter", "preview $it") }
    }
    val thumbEngine = remember {
        FilterPreviewEngine(context, THUMB_PX) { android.util.Log.i("Lumina.Filter", "thumb $it") }
    }
    DisposableEffect(Unit) {
        onDispose {
            previewEngine.close()
            thumbEngine.close()
        }
    }

    var catalog by remember { mutableStateOf<List<FilterEntry>>(emptyList()) }
    var category by remember { mutableStateOf<FilterCategory?>(null) }
    var selection by remember { mutableStateOf<FilterSelection?>(null) }
    var source by remember { mutableStateOf<Bitmap?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val thumbs = remember { mutableStateMapOf<String, Bitmap>() }
    val lutCache = remember { mutableMapOf<String, CubeLut>() }
    val srcFile = remember(gi.relPath) { File(context.filesDir, gi.relPath) }

    // 开屏：源图 → 目录 → AI 建议默认选中 → 缩略图逐支补渲
    LaunchedEffect(gi.relPath) {
        loading = true
        val src = previewEngine.load(srcFile)
        source = src
        preview = src
        catalog = controller.filterCatalog()
        controller.suggestedFilterEntry(gi.name)?.let { selection = FilterSelection(it, 1f) }
        thumbEngine.load(srcFile)
        loading = false
        message = if (src == null) "这张解不出图（NEF？先出 JPEG 再套滤镜）" else ""
        catalog.forEach { e ->
            if (thumbs.containsKey(e.id)) return@forEach
            val lut = runCatching { controller.filterLut(e) }.getOrNull() ?: return@forEach
            lutCache[e.id] = lut
            val bmp = thumbEngine.render(lut, 1f) ?: return@forEach
            thumbs[e.id] = bmp
        }
    }

    // 换滤镜 / 拖强度：重渲染主画布（LaunchedEffect 随 selection 变化取消，拖动时只跑最后一帧）
    LaunchedEffect(selection) {
        val sel = selection
        if (sel == null) {
            preview = source
            return@LaunchedEffect
        }
        delay(40)
        val lut = lutCache[sel.entry.id]
            ?: controller.filterLut(sel.entry)?.also { lutCache[sel.entry.id] = it }
        if (lut == null) {
            message = "这个滤镜读不出来（${sel.entry.name}）"
            return@LaunchedEffect
        }
        val out = previewEngine.render(lut, sel.strength)
        if (out != null) preview = out
    }

    fun save() {
        val sel = selection ?: return
        // 源图解不出来（NEF / 坏档）时不放行保存——渲染必然失败，别让用户等一圈
        if (saving || loading || source == null) return
        saving = true
        message = ""
        scope.launch {
            val rel = controller.saveFilterWork(gi.relPath, sel)
            saving = false
            message = if (rel != null) {
                "已保存：${sel.entry.name} ${sel.percent}%"
            } else {
                "保存失败，详见日志"
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FilmBackground)
            // 盖住整个查看器：没有指针目标时，点击/拖动会漏到下面的 pager（会换照片）
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "✕",
                    fontSize = 19.sp, color = InkTeal, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { onClose() }.padding(horizontal = 8.dp, vertical = 6.dp),
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    gi.name,
                    fontSize = 12.5.sp, color = CreamFaint,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (saving) "保存中…" else "保存",
                    fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
                    color = if (selection == null || saving) CreamFaint else Accent,
                    modifier = Modifier
                        .clickable(enabled = selection != null && !saving) { save() }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }

            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val bmp = preview
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = gi.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                    )
                }
                if (bmp == null || loading) {
                    CircularProgressIndicator(
                        color = InkTeal, trackColor = DividerCream,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            if (message.isNotBlank()) {
                Text(
                    message,
                    fontSize = 11.sp, color = SageText,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardBg, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .navigationBarsPadding(),
            ) {
                Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(38.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(DividerCream))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // ⊘ 清除：回到原图
                    CategoryChip(
                        label = "⊘",
                        active = selection == null,
                        onClick = { selection = null },
                    )
                    CategoryChip(
                        label = FilterCatalog.ALL_LABEL,
                        active = category == null,
                        onClick = { category = null },
                    )
                    FilterCategory.entries.forEach { cat ->
                        if (FilterCatalog.inCategory(catalog, cat).isEmpty()) return@forEach
                        CategoryChip(
                            label = cat.label,
                            active = category == cat,
                            onClick = { category = cat },
                        )
                    }
                }
                val visible = FilterCatalog.inCategory(catalog, category)
                if (visible.isEmpty()) {
                    Text(
                        "「我的导入」还没有内容：.cube 导入入口还没做（见 M3-02 余项）",
                        fontSize = 11.sp, color = CreamFaint,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                } else {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(visible, key = { it.id }) { e ->
                            FilterThumb(
                                entry = e,
                                bitmap = thumbs[e.id],
                                active = selection?.entry?.id == e.id,
                                // 换滤镜沿用当前强度：强度是滤镜的参数，不该每换一支就跳回 100%
                                onClick = { selection = FilterSelection(e, selection?.rawStrength ?: 1f) },
                            )
                        }
                    }
                }
                val sel = selection
                if (sel != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("强度", fontSize = 11.sp, color = SageText)
                        Slider(
                            value = sel.strength,
                            onValueChange = { v -> selection = sel.copy(rawStrength = v) },
                            valueRange = 0f..1f,
                            colors = SliderDefaults.colors(
                                thumbColor = Accent,
                                activeTrackColor = Accent,
                                inactiveTrackColor = DividerCream,
                            ),
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        )
                        Text(
                            "${sel.percent}%",
                            fontSize = 11.sp, color = InkTeal,
                            modifier = Modifier.widthIn(min = 34.dp),
                        )
                    }
                } else {
                    Text(
                        "点一张缩略图开始试；保存会覆盖这张的成片",
                        fontSize = 11.sp, color = CreamFaint,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

/** 分类条胶囊（「全部」与题材 Tab 共用）。 */
@Composable
private fun CategoryChip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 12.sp,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        color = if (active) AccentDeep else SageText,
        modifier = Modifier
            .background(if (active) PillActive else Color.Transparent, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/** 缩略图格：图 = 源图套该滤镜，名称压在底部黑角里（对齐截图）。 */
@Composable
private fun FilterThumb(entry: FilterEntry, bitmap: Bitmap?, active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .size(THUMB_DP.dp)
            .clip(shape)
            .background(PillActive)
            .border(2.dp, if (active) Accent else Color.Transparent, shape)
            .clickable { onClick() },
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = entry.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = DividerCream, modifier = Modifier.size(16.dp))
            }
        }
        Text(
            entry.name,
            fontSize = 9.sp,
            color = if (active) AccentDeep else InkTeal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, ThumbScrim)))
                .padding(horizontal = 6.dp, vertical = 5.dp),
        )
    }
}

/** 缩略图解码目标边：3x 屏上 78dp ≈ 234px，取 240。 */
private const val THUMB_PX = 240
private const val THUMB_DP = 78
