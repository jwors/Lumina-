package com.lumina.probe

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.Brush
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
val WarnBrown = Color(0xFF8A4A2A)

/** JPEG 判定（Master 图库/Pass 入队/收尾覆盖三处口径统一；GLOSSARY 惯例：仅此一处判断）。 */
internal fun isJpegName(name: String): Boolean {
    val up = name.uppercase()
    return up.endsWith(".JPG") || up.endsWith(".JPEG")
}

// Tab key 与显示名分离（key 稳定，显示名随便改；07 号教训再延伸——切换在 key 上做）
const val TAB_CAMERA = "camera"
const val TAB_GALLERY = "gallery"
const val TAB_RECIPE = "recipe"
const val TAB_SETTINGS = "settings"
const val LABEL_CAMERA = "相机"
const val LABEL_GALLERY = "图库"
const val LABEL_RECIPE = "滤镜"
const val LABEL_SETTINGS = "设置"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(state: ProbeUiState, controller: ProbeController) {
    var tab by remember { mutableStateOf(TAB_CAMERA) }
    // M3 交互：滤镜包执行弹层（半屏），全局浮标在弹层收起时可见
    var runSheetOpen by remember { mutableStateOf(false) }
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
    state.cardRun?.let { run ->
        if (!runSheetOpen) {
            RunFloatingChip(run, onOpen = { runSheetOpen = true })
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
            TabPill(TAB_CAMERA, LABEL_CAMERA, tab, onSelect = { tab = it; controller.emitCameraUiRefresh() })
            TabPill(TAB_GALLERY, LABEL_GALLERY, tab, onSelect = { tab = it; controller.loadGallery() })
            TabPill(TAB_RECIPE, LABEL_RECIPE, tab, onSelect = { tab = it; controller.loadCards() })
            TabPill(TAB_SETTINGS, LABEL_SETTINGS, tab, onSelect = { tab = it; controller.loadSettings() })
        }

        when (tab) {
            TAB_CAMERA -> CameraPage(Modifier.weight(1f), state, controller)
            TAB_GALLERY -> GalleryPage(Modifier.weight(1f), state, controller,
                onGoRecipe = { tab = TAB_RECIPE; controller.loadCards() })
            TAB_RECIPE -> RecipePage(Modifier.weight(1f), state, controller, onRunStarted = { runSheetOpen = true })
            else -> SettingsPage(Modifier.weight(1f), state, controller)
        }
    }
    // 半屏执行/收尾弹层（数据源 = state.cardRun；任何 Tab 打开都看得见）
    state.cardRun?.let { run ->
        if (runSheetOpen) {
            ModalBottomSheet(
                onDismissRequest = { runSheetOpen = false },
                containerColor = FilmBackground,
            ) {
                CardRunSheetBody(state, run, controller)
            }
        }
    }
}

/** 全局浮标：执行进行中 → 深色条；收尾待保存 → 同位提示。 */
@Composable
private fun RunFloatingChip(run: CardRunUi, onOpen: () -> Unit) {
    val total = run.targets.size
    val inFlight = if (run.phase == CardRunPhase.RUNNING && run.currentItem != null) 1 else 0
    val i = run.doneOk + run.doneFail + inFlight
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(InkTeal)
            .clickable { onOpen() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (run.phase == CardRunPhase.RUNNING) "⏳ ${run.cardName}（$i/$total）"
                    else "✓ ${run.cardName} 完成 · 待保存 · 点此继续",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = FilmBackground,
                )
            }
            if (run.phase == CardRunPhase.RUNNING && total > 0) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { i.toFloat() / total },
                    modifier = Modifier.fillMaxWidth(),
                    color = FilmBackground,
                    trackColor = DividerCream,
                )
            }
        }
    }
}

/** Tab 药丸：key = 状态值（稳定），label = 显示名（可改），激活态用 currentKey 比对。 */
@Composable
private fun TabPill(key: String, label: String, currentKey: String, onSelect: (String) -> Unit) {
    val active = key == currentKey
    Box(
        modifier = Modifier
            .background(if (active) PillActive else FilmBackground)
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .clickable { onSelect(key) },
    ) {
        Text(
            label,
            fontSize = 16.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) InkTeal else SageText,
        )
    }
}

/** 半屏弹层内容：RUNNING → 执行视图；AWAIT_SAVE → 保存收尾视图。 */
@Composable
private fun CardRunSheetBody(state: ProbeUiState, run: CardRunUi, controller: ProbeController) {
    if (run.phase == CardRunPhase.RUNNING) {
        RunSheetView(run, controller)
    } else {
        SaveSheetView(run, controller)
    }
}

/** 执行视图：整体进度 + 逐张结果清单 + 取消。收起弹层后由顶部浮标接管进度。 */
@Composable
private fun RunSheetView(run: CardRunUi, controller: ProbeController) {
    val total = run.targets.size
    val done = run.doneOk + run.doneFail
    Column(modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 20.dp)) {
        Text("正在处理 · ${run.cardName}", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = InkTeal)
        Spacer(Modifier.height(4.dp))
        Text(
            if (run.cancelRequested) "已请求取消：在途的这一张跑完即停，未处理项留队列（补跑横幅会接管）"
            else "可先收起去干别的，执行在后台继续（顶部浮标可见）",
            fontSize = 12.sp, color = SageText,
        )
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { if (total > 0) done.toFloat() / total else 0f },
            modifier = Modifier.fillMaxWidth(),
            color = InkTeal, trackColor = DividerCream,
        )
        Text("$done/$total", fontSize = 11.sp, color = CreamFaint, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(10.dp))
        Column(
            modifier = Modifier
                .heightIn(max = 300.dp)
                .verticalScroll(rememberScrollState())
                .fillMaxWidth(),
        ) {
            run.targets.forEach { name ->
                val st = run.itemStatus[name]
                val current = run.currentItem == name
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when {
                            st == true -> "✓"
                            st == false -> "✗"
                            current -> "⏳"
                            else -> "·"
                        },
                        fontSize = 13.sp,
                        color = when {
                            st == true -> SageText
                            st == false -> WarnBrown
                            else -> CreamFaint
                        },
                        modifier = Modifier.width(24.dp),
                    )
                    Text(
                        name,
                        fontSize = 13.sp,
                        color = if (current) InkTeal else SageText,
                        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        PillButton(
            label = if (run.cancelRequested) "已请求取消…" else "取消执行",
            filled = false,
            danger = true,
            enabled = !run.cancelRequested,
        ) { controller.cancelCardRun() }
    }
}

/** 保存收尾视图（对标 Snapseed「保存 / 保存副本」双分支）：另存 Work（推荐）或覆盖 Master。 */
@Composable
private fun SaveSheetView(run: CardRunUi, controller: ProbeController) {
    var saveMode by remember { mutableStateOf<SaveMode?>(null) }
    Column(modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 20.dp)) {
        Text("${run.cardName}", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = InkTeal)
        Spacer(Modifier.height(6.dp))
        Text(
            "成功 ${run.doneOk} · 失败 ${run.doneFail}　成片已暂存为 Work 预览",
            fontSize = 13.sp, color = SageText,
        )
        Spacer(Modifier.height(14.dp))
        Text("保存方式", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = InkTeal)
        Spacer(Modifier.height(6.dp))
        SaveOptionRow(
            title = "另存副本（推荐）",
            desc = "Master 保留不动；Work 成片入库，与 Master 并存",
            tag = "无损", tagColor = SageText,
            selected = saveMode == SaveMode.COPY_WORK,
        ) { saveMode = SaveMode.COPY_WORK }
        SaveOptionRow(
            title = "覆盖 Master（原 JPEG）",
            desc = "把 Work 成片写入 Master 位置；不可恢复（仅 JPEG，NEF 会跳过）",
            tag = "谨慎", tagColor = WarnBrown,
            selected = saveMode == SaveMode.OVERWRITE,
        ) { saveMode = SaveMode.OVERWRITE }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "暂不保存",
                fontSize = 14.sp, color = SageText, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable { controller.dismissCardRun() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
            Spacer(Modifier.weight(1f))
            val picked = saveMode
            PillButton(
                label = if (picked == SaveMode.OVERWRITE) "确认覆盖 ${run.doneOk} 张" else "保存 ${run.doneOk} 张",
                filled = true,
                enabled = picked != null,
            ) { picked?.let { controller.finalizeCardRun(it) } }
        }
    }
}

/** 收尾的两个单选项行（圆点单选 + 右侧意图标签）。 */
@Composable
private fun SaveOptionRow(
    title: String,
    desc: String,
    tag: String,
    tagColor: Color,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(if (selected) PillActive else DividerCream.copy(alpha = 0.4f))
            .clickable { onSelect() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(18.dp)) {
            if (selected) Text("●", fontSize = 13.sp, color = InkTeal)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = InkTeal)
            Text(desc, fontSize = 11.5.sp, color = SageText)
        }
        Text(tag, fontSize = 11.sp, color = tagColor, modifier = Modifier.padding(start = 8.dp))
    }
}

/** 药丸按钮（执行/收尾 CTA 用）。 */
@Composable
private fun PillButton(
    label: String,
    filled: Boolean,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val bg = when {
        !enabled -> DividerCream
        danger -> DividerCream
        filled -> InkTeal
        else -> PillActive
    }
    val fg = when {
        !enabled -> SageText
        danger -> WarnBrown
        filled -> FilmBackground
        else -> InkTeal
    }
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(22.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = fg)
    }
}

@Composable
private fun GalleryPage(
    modifier: Modifier = Modifier,
    state: ProbeUiState,
    controller: ProbeController,
    onGoRecipe: () -> Unit = {},
) {
    Column(modifier = modifier) {
        if (state.galleryLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(),
                color = InkTeal, trackColor = DividerCream)
            Text("图库对账中…", fontSize = 12.sp, color = SageText,
                modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(4.dp))
        } else {
            Text(state.connectionText, fontSize = 14.sp, color = SageText)
        }
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
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (viewFeed) "← 返回图库" else "⚡ 智能结果全文",
                fontSize = 13.sp,
                color = InkTeal,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { controller.loadSmartFeed(); viewFeed = !viewFeed },
            )
            Modifier.weight(1f).let { Spacer(modifier = it) }
            if (!viewFeed && state.passSelectedNames.isNotEmpty()) {
                Text(
                    "已选 ${state.passSelectedNames.size} · 去滤镜执行",
                    fontSize = 13.sp,
                    color = InkTeal,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { controller.clearPassSelection(); onGoRecipe() },
                )
            }
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
                    GalleryRow(
                        gi, state,
                        checked = state.passSelectedNames.contains(gi.name),
                        onCheck = { controller.togglePassSelection(gi.name) },
                        onClick = { selected = gi },
                    )
                }
            }
        }
        selected?.let { sel ->
            var busyStep by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { selected = null },
                title = { Text(sel.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = InkTeal) },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text("日期：${sel.dateKey}　体积：${humanBytes(sel.sizeBytes)}", fontSize = 13.sp, color = SageText)
                        if (sel.workRelPath != null) {
                            Text("+ Work 预览已生成", fontSize = 12.sp, color = SageText)
                        }
                        Spacer(Modifier.height(10.dp))
                        Text("智能（M2）", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = InkTeal)
                        if (sel.smartTexts.isEmpty()) {
                            Text("还没有结果：点下方工具栏跑一步", fontSize = 12.sp, color = CreamFaint)
                        }
                        sel.smartTexts.forEach { line ->
                            Text(line, fontSize = 12.sp, color = InkTeal, modifier = Modifier.padding(top = 4.dp))
                        }
                        if (busyStep.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text("⏳ $busyStep", fontSize = 12.sp, color = SageText)
                        }
                    }
                },
                confirmButton = {
                    Row {
                        listOf(
                            "标注" to com.lumina.cards.StepKind.ANNOTATE,
                            "文案" to com.lumina.cards.StepKind.CAPTION,
                            "滤镜" to com.lumina.cards.StepKind.FILTER_SUGGEST,
                        ).forEach { (label, kind) ->
                            Text(
                                label,
                                fontSize = 13.sp, color = InkTeal,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clickable {
                                        busyStep = "$label 执行中…"
                                        controller.runSingleStep(sel.name, kind)
                                    }
                                    .padding(vertical = 8.dp, horizontal = 10.dp),
                            )
                        }
                        Text("关闭", fontSize = 14.sp, color = InkTeal,
                            modifier = Modifier.clickable { selected = null }.padding(8.dp))
                    }
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

/** JPEG 缩略图行 / NEF RAW 占位行（+ 智能处理勾选，M2 UX 流）。 */
@Composable
private fun GalleryRow(
    item: GalleryItem,
    state: ProbeUiState,
    checked: Boolean,
    onCheck: () -> Unit,
    onClick: () -> Unit,
) {
    val isRawImg = !item.name.uppercase().endsWith(".JPG") && !item.name.uppercase().endsWith(".JPEG")
    val showWork = item.workRelPath != null
    val isRaw = isRawImg && !showWork
    Column(modifier = Modifier.fillMaxWidth().background(CreamFaint.copy(alpha = 0.08f))) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = { onCheck() })
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

/** 滤镜库页（M3 交互）：滤镜包卡片（封面+步链）→ 点卡片开确认弹层 → 选择范围执行。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecipePage(
    modifier: Modifier = Modifier,
    state: ProbeUiState,
    controller: ProbeController,
    onRunStarted: () -> Unit = {},
) {
    // 确认弹层（点卡片 → 应用范围决策）
    var confirmCard: CardUi? by remember { mutableStateOf<CardUi?>(null) }
    // 导出双段流程：先记下要导的卡，launcher 回调再把 JSON 写 uri
    var pendingExport: CardUi? by remember { mutableStateOf<CardUi?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: android.net.Uri? ->
        pendingExport?.let { card -> uri?.let { u -> controller.exportCardTo(u, card.cardId) } }
        pendingExport = null
    }
    val importLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri: android.net.Uri? -> uri?.let { controller.importCard(it) } }

    Column(modifier = modifier) {
        if (state.cardsLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(),
                color = InkTeal, trackColor = DividerCream)
            Text("配方库盘点中…", fontSize = 12.sp, color = SageText,
                modifier = Modifier.padding(top = 4.dp))
        }
        Text(if (state.cardsLoading) "" else state.connectionText, fontSize = 14.sp, color = SageText)
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
        if (state.passSelectedNames.isNotEmpty()) {
            Text(
                "图库已选 ${state.passSelectedNames.size} 张 · 点卡片可直接应用到已选",
                fontSize = 11.5.sp, color = SageText, modifier = Modifier.padding(bottom = 8.dp),
            )
        }
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
                CardTile(
                    card = card,
                    onClick = { confirmCard = card },
                    onExport = {
                        pendingExport = card
                        exportLauncher.launch("${card.name}.json")
                    },
                )
            }
        }
    }
    confirmCard?.let { card ->
        ModalBottomSheet(
            onDismissRequest = { confirmCard = null },
            containerColor = FilmBackground,
        ) {
            // 口径与 startCardRun 一致：图库勾选里的非 JPEG（NEF 等）会由入队层自动滤掉，计数先对齐展示
            val selJpeg = state.passSelectedNames.count { isJpegName(it) }
            val selTotal = state.passSelectedNames.size
            CardConfirmSheet(
                card = card,
                selectedCount = selJpeg,
                totalJpeg = state.galleryItems.count { isJpegName(it.name) },
                hintCount = if (selTotal > selJpeg) selTotal - selJpeg else 0,
                onConfirm = { selectedOnly ->
                    controller.startCardRun(card.cardId, selectedOnly = selectedOnly)
                    confirmCard = null
                    onRunStarted()
                },
            )
        }
    }
}

/** 确认弹层：滤镜包摘要 + 步链 + 两个范围 CTA（已选 JPEG 为 0 时禁用）。 */
@Composable
private fun CardConfirmSheet(
    card: CardUi,
    selectedCount: Int,
    totalJpeg: Int,
    hintCount: Int = 0,
    onConfirm: (selectedOnly: Boolean) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 20.dp)) {
        Text("${card.name}${if (card.imported) "（导入）" else ""}", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = InkTeal)
        Spacer(Modifier.height(4.dp))
        Text("v${card.version} · ${card.stepsCount} 步 · ${card.steps.joinToString(" → ")}", fontSize = 13.sp, color = SageText)
        Spacer(Modifier.height(4.dp))
        Text("对所选 Master 逐张串行执行；可中途返回，后台继续。", fontSize = 12.sp, color = CreamFaint)
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PillButton(
                label = "应用到已选 ${selectedCount} 张",
                filled = false,
                enabled = selectedCount > 0,
            ) { onConfirm(true) }
            Spacer(Modifier.width(12.dp))
            PillButton(
                label = "应用到全部 ${totalJpeg} 张",
                filled = true,
                enabled = totalJpeg > 0,
            ) { onConfirm(false) }
        }
        if (selectedCount == 0) {
            Text("图库尚未勾选 JPEG；或直接应用全部。", fontSize = 11.sp, color = CreamFaint,
                modifier = Modifier.padding(top = 6.dp))
        } else if (hintCount > 0) {
            Text("勾选中另有 ${hintCount} 张非 JPEG（NEF 等），执行时会自动跳过。",
                fontSize = 11.sp, color = CreamFaint, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** 滤镜包卡片（点击进确认弹层；封面取卡 id 的确定性双色渐变）。 */
@Composable
private fun CardTile(
    card: CardUi,
    onClick: () -> Unit,
    onExport: () -> Unit,
) {
    val palettes = listOf(
        listOf(0xFF7A8A80, 0xFF2C352F),   // 山雾（冷灰绿）
        listOf(0xFFC9A06A, 0xFF4D3222),   // 街角胶片（暖棕）
        listOf(0xFF6B7F8E, 0xFF2A3440),   // 通用蓝灰
    )
    val pair = palettes[card.cardId.hashCode().mod(palettes.size)]
    val cover1 = Color(pair[0])
    val cover2 = Color(pair[1])
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp, horizontal = 4.dp)
            .background(CreamFaint.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(64.dp)
                .height(64.dp)
                .background(Brush.linearGradient(listOf(cover1, cover2)), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.BottomStart,
        ) {
            Text(
                "v${card.version}",
                fontSize = 9.sp, color = Color(0xB3FFFFFF),
                modifier = Modifier.padding(4.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                card.name + if (card.imported) "（导入）" else "",
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = InkTeal,
            )
            Text("${card.stepsCount} 步 · ${card.steps.joinToString(" → ")}",
                fontSize = 11.5.sp, color = SageText, modifier = Modifier.padding(top = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "导出",
                    fontSize = 12.sp, color = InkTeal, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable { onExport() }
                        .padding(top = 6.dp, end = 10.dp),
                )
                Text("共享配方属文件（无自有服务器）", fontSize = 10.sp, color = CreamFaint)
            }
        }
        Text("›", fontSize = 18.sp, color = CreamFaint)
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
