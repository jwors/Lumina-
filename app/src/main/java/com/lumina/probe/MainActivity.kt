package com.lumina.probe

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lumina.ui.theme.LuminaTheme
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.ptp.CameraObject
import com.lumina.work.PosterLayout
import com.lumina.work.ZinePosterRenderer
import java.time.LocalDate

/**
 * M1 相机页（工单 03）：M0 探针升级为双 Tab（相机/图库）。
 * 相机页支持逐张勾选 + 全选本日/清空 + 底部"导出 N 个" + 队列状态行。
 * 图库（工单 04）此版仅占位。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = FilmBackground.toArgb()
        window.navigationBarColor = FilmBackground.toArgb()
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        var state by mutableStateOf(ProbeUiState("未连接 · 请插入相机"))
        val controller = ProbeController(this) { next ->
            Log.i("Lumina.UI", "onState: conn='${next.connectionText.take(40)}' " +
                "cameraItems=${next.cameraItems.size} checked=${next.checkedNames.size} " +
                "engineReady=${next.engineReady} gallery=${next.galleryItems.size}")
            state = next
        }
        setContent {
            LuminaTheme {
                Surface(color = FilmBackground) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AppScaffold(state, controller)
                    }
                }
            }
        }
    }
}

// ── 清爽版 token（v2）：暖白画布 / 白卡 / 鼠尾草绿 ──
val FilmBackground = Color(0xFFF7F8F3)
val InkTeal = Color(0xFF1B231C)
val SageText = Color(0xFF56625A)
val CreamFaint = Color(0xFF66726A)
val DividerCream = Color(0xFFE1E7DC)
val PillActive = Color(0xFFDDEDE2)
val WarnBrown = Color(0xFFB4552F)
val Accent = Color(0xFF3F7B58)
val AccentDeep = Color(0xFF33553F)
val CardBg = Color(0xFFFFFFFF)
val Lightbox = Color(0xFFEEF1EA)

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
const val LABEL_RECIPE = "配方"
const val LABEL_SETTINGS = "设置"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(state: ProbeUiState, controller: ProbeController) {
    var tab by rememberSaveable { mutableStateOf(TAB_CAMERA) }
    // 运行弹层（半屏）；浮标在弹层收起时可见
    var runSheetOpen by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(FilmBackground).statusBarsPadding()) {
        // 补跑横幅：占位在主列里，不压标题
        state.catchupBanner?.let { banner ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PillActive)
                    .clickable { controller.runCatchUp() }
                    .padding(horizontal = 20.dp, vertical = 9.dp),
            ) {
                Text(banner, fontSize = 12.5.sp, color = AccentDeep, fontWeight = FontWeight.SemiBold)
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        ) {
            if (tab != TAB_GALLERY && tab != TAB_SETTINGS) {
                Text(
                    tabTitle(tab),
                    fontFamily = FontFamily.Serif,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = InkTeal,
                    modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                )
            }
            when (tab) {
                TAB_CAMERA -> CameraPage(Modifier.weight(1f), state, controller)
                TAB_GALLERY -> GalleryPage(Modifier.weight(1f), state, controller,
                    onGoRecipe = { tab = TAB_RECIPE; controller.loadCards() })
                TAB_RECIPE -> RecipePage(Modifier.weight(1f), state, controller,
                    onRunStarted = { runSheetOpen = true },
                    onOpenSettings = { tab = TAB_SETTINGS; controller.loadSettings() })
                else -> SettingsPage(Modifier.weight(1f), state, controller)
            }
        }
        // 执行中/待保存：贴在 Tab 栏上方的状态条
        state.cardRun?.let { run ->
            if (!runSheetOpen) RunFloatingChip(run, onOpen = { runSheetOpen = true })
        }
        LuminaTabBar(tab) { next ->
            tab = next
            when (next) {
                TAB_CAMERA -> controller.emitCameraUiRefresh()
                TAB_GALLERY -> controller.loadGallery()
                TAB_RECIPE -> controller.loadCards()
                TAB_SETTINGS -> controller.loadSettings()
            }
        }
    }
    // 半屏执行/收尾弹层（数据源 = state.cardRun；任何 Tab 打开都看得见）
    state.cardRun?.let { run ->
        if (runSheetOpen) {
            ModalBottomSheet(
                onDismissRequest = { runSheetOpen = false },
                containerColor = CardBg,
            ) {
                CardRunSheetBody(state, run, controller)
            }
        }
    }
}

private fun tabTitle(key: String): String = when (key) {
    TAB_CAMERA -> "相机"
    TAB_GALLERY -> "图库"
    TAB_RECIPE -> "配方"
    else -> "设置"
}

/** 底部四标签：白底、细线、绿色指示；主操作永远落在下半屏。 */
@Composable
private fun LuminaTabBar(current: String, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().background(CardBg)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(DividerCream))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(
                TAB_CAMERA to LABEL_CAMERA,
                TAB_GALLERY to LABEL_GALLERY,
                TAB_RECIPE to LABEL_RECIPE,
                TAB_SETTINGS to LABEL_SETTINGS,
            ).forEach { (key, label) ->
                val active = key == current
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelect(key) }
                        .padding(vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TabIcon(key, active)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        label,
                        fontSize = 10.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (active) Accent else CreamFaint,
                    )
                }
            }
        }
    }
}

/** 四个手绘图标（不引 icons-extended，保持包体小）。 */
@Composable
private fun TabIcon(kind: String, active: Boolean) {
    val color = if (active) Accent else CreamFaint
    Canvas(Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val sw = 1.8.dp.toPx()
        val stroke = Stroke(width = sw, cap = StrokeCap.Round)
        when (kind) {
            TAB_CAMERA -> {
                drawRoundRect(color, Offset(w * 0.08f, h * 0.28f), Size(w * 0.84f, h * 0.58f), CornerRadius(w * 0.13f), style = stroke)
                drawCircle(color, radius = w * 0.15f, center = Offset(w * 0.5f, h * 0.57f), style = stroke)
                drawLine(color, Offset(w * 0.3f, h * 0.28f), Offset(w * 0.38f, h * 0.16f), strokeWidth = sw, cap = StrokeCap.Round)
                drawLine(color, Offset(w * 0.38f, h * 0.16f), Offset(w * 0.62f, h * 0.16f), strokeWidth = sw, cap = StrokeCap.Round)
                drawLine(color, Offset(w * 0.62f, h * 0.16f), Offset(w * 0.7f, h * 0.28f), strokeWidth = sw, cap = StrokeCap.Round)
            }
            TAB_GALLERY -> {
                val s = w * 0.34f
                val g = w * 0.10f
                listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f).forEach { (cx, cy) ->
                    drawRoundRect(color, Offset(w * 0.12f + cx * (s + g), h * 0.12f + cy * (s + g)), Size(s, s), CornerRadius(w * 0.06f), style = stroke)
                }
            }
            TAB_RECIPE -> {
                for (i in 0..2) {
                    val y = h * (0.24f + i * 0.22f)
                    drawLine(color, Offset(w * 0.18f, y), Offset(w * 0.5f, y + h * 0.14f), strokeWidth = sw, cap = StrokeCap.Round)
                    drawLine(color, Offset(w * 0.5f, y + h * 0.14f), Offset(w * 0.82f, y), strokeWidth = sw, cap = StrokeCap.Round)
                }
            }
            else -> {
                drawCircle(color, radius = w * 0.17f, center = Offset(w * 0.5f, h * 0.5f), style = stroke)
                for (i in 0 until 8) {
                    val a = Math.PI * 2 * i / 8
                    val cx = w * 0.5f
                    val cy = h * 0.5f
                    drawLine(
                        color,
                        Offset(cx + (w * 0.27f) * Math.cos(a).toFloat(), cy + (h * 0.27f) * Math.sin(a).toFloat()),
                        Offset(cx + (w * 0.38f) * Math.cos(a).toFloat(), cy + (h * 0.38f) * Math.sin(a).toFloat()),
                        strokeWidth = sw, cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

/** 全局浮标：执行进行中 → 深绿条；收尾待保存 → 同位提示。 */
@Composable
private fun RunFloatingChip(run: CardRunUi, onOpen: () -> Unit) {
    val total = run.targets.size
    val inFlight = if (run.phase == CardRunPhase.RUNNING && run.currentItem != null) 1 else 0
    val i = run.doneOk + run.doneFail + inFlight
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(AccentDeep)
            .clickable { onOpen() }
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (run.phase == CardRunPhase.RUNNING) "⏳ ${run.cardName}（$i/$total）"
                    else "✓ ${run.cardName} 完成 · 待保存 · 点此继续",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = CardBg,
                )
            }
            if (run.phase == CardRunPhase.RUNNING && total > 0) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { i.toFloat() / total },
                    modifier = Modifier.fillMaxWidth(),
                    color = CardBg,
                    trackColor = Accent.copy(alpha = 0.35f),
                )
            }
        }
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
                            st == com.lumina.probe.ItemOutcome.OK -> "✓"
                            st == com.lumina.probe.ItemOutcome.FAIL -> "✗"
                            current -> "⏳"
                            else -> "·"
                        },
                        fontSize = 13.sp,
                        color = when {
                            st == com.lumina.probe.ItemOutcome.OK -> SageText
                            st == com.lumina.probe.ItemOutcome.FAIL -> WarnBrown
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
        danger -> PillActive
        filled -> Accent
        else -> PillActive
    }
    val fg = when {
        !enabled -> CreamFaint
        danger -> WarnBrown
        filled -> CardBg
        else -> AccentDeep
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
    // 查看/多选状态放函数体层：查看器覆盖层在 Column 收口后仍要能给整页上盖
    var viewerStart by remember { mutableStateOf<Int?>(null) }
    var viewFeed by remember { mutableStateOf(false) }
    val albumLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri: android.net.Uri? -> uri?.let { controller.importFromAlbum(it) } }
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "图库",
                    fontFamily = FontFamily.Serif,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = InkTeal,
                    modifier = Modifier.padding(top = 14.dp),
                )
                Text(
                    if (state.galleryItems.isEmpty()) "还没有照片"
                    else "${state.galleryItems.maxOfOrNull { it.dateKey } ?: ""} · 共 ${state.galleryItems.size} 张",
                    fontSize = 11.5.sp, color = CreamFaint,
                    modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
                )
            }
            RoundIcon("↥") {
                albumLauncher.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                    ),
                )
            }
            Spacer(Modifier.width(6.dp))
            RoundIcon("↯") { controller.loadSmartFeed(); viewFeed = !viewFeed }
        }
        if (state.galleryLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(),
                color = InkTeal, trackColor = DividerCream)
            Text("图库加载中…", fontSize = 12.sp, color = SageText,
                modifier = Modifier.padding(top = 4.dp))
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
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (viewFeed) "← 返回图库" else "⚡ 智能结果全文",
                fontSize = 12.sp,
                color = AccentDeep,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .background(PillActive, RoundedCornerShape(999.dp))
                    .clickable { controller.loadSmartFeed(); viewFeed = !viewFeed }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Modifier.weight(1f).let { Spacer(modifier = it) }
            Text(
                "${state.galleryItems.count { isJpegName(it.name) }} JPEG · " +
                    "${state.galleryItems.count { !isJpegName(it.name) }} NEF",
                fontSize = 11.sp, color = CreamFaint,
            )
        }
        if (viewFeed) {
            SmartFeedPage(Modifier.weight(1f), state)
            return@Column
        }
        // M3-01：3 列网格墙（日期=整行头），勾选圈进多选，点格开全屏查看器
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.weight(1f).padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            state.galleryItems.groupBy { it.dateKey }.forEach { (dateKey, items) ->
                item(key = "gday-$dateKey", span = { GridItemSpan(3) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            dateKey,
                            fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = Accent,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                        Text(
                            "  ${items.size} 张",
                            fontSize = 11.sp, color = CreamFaint,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                        if (items.any { it.smartCount > 0 }) {
                            Text(
                                "  ⚡",
                                fontSize = 11.sp, color = CreamFaint,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                    }
                }
                items.forEach { gi ->
                    item(key = "g-${gi.relPath}") {
                        GalleryCell(
                            gi = gi,
                            checked = state.passSelectedNames.contains(gi.name),
                            onCheck = { controller.togglePassSelection(gi.name) },
                            onOpen = {
                                viewerStart = state.galleryItems
                                    .indexOfFirst { it.relPath == gi.relPath }
                                    .takeIf { idx -> idx >= 0 }
                            },
                        )
                    }
                }
            }
        }
        // 多选底部固定操作栏（相册类 App 习惯：选中即起）
        if (!viewFeed && state.passSelectedNames.isNotEmpty()) {
            GallerySelectBar(
                state = state,
                controller = controller,
                onGo = onGoRecipe,
            )
        }
    } // Column
    viewerStart?.let { start ->
        Dialog(
            onDismissRequest = { viewerStart = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            GalleryViewerOverlay(
                state = state,
                startIndex = start,
                controller = controller,
                onClose = { viewerStart = null },
            )
        }
    }
}

/** 圆形图标按钮（图库右上角导入/智能）。 */
@Composable
private fun RoundIcon(icon: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .background(CardBg, RoundedCornerShape(50))
            .border(1.dp, DividerCream, RoundedCornerShape(50))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(icon, fontSize = 15.sp, color = InkTeal)
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
                    .background(CardBg)
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

/** 网格方格卡：缩略/RAW 占位 + Work/⚡ 徽标 + 勾选圈（M3-01）。 */
@Composable
private fun GalleryCell(
    gi: GalleryItem,
    checked: Boolean,
    onCheck: () -> Unit,
    onOpen: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isUntouchable = !isJpegName(gi.name) && gi.workRelPath == null
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(CardBg)
            .clickable { onOpen() },
    ) {
        when {
            isUntouchable -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("RAW", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = InkTeal)
                    Text("胶片框", fontSize = 10.sp, color = SageText)
                }
            }
            else -> {
                val src = gi.workRelPath ?: gi.relPath
                val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, key1 = src) {
                    val hit = ThumbCache.get<android.graphics.Bitmap>(src)
                    if (hit != null) { value = hit; return@produceState }
                    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        ThumbDecode.sampled(java.io.File(context.filesDir, src).absolutePath, target = 256)
                            ?.also { ThumbCache.put(src, it, it.byteCount.toLong()) }
                    }
                }
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = gi.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } ?: CircularProgressIndicator(
                    modifier = Modifier
                        .size(24.dp)
                        .align(Alignment.Center),
                    color = InkTeal, trackColor = DividerCream, strokeWidth = 2.dp,
                )
            }
        }
        // 徽标：Work 成片 / 智能结果（沿用 M2 数据层语义，视觉口径对齐 mockup v3）
        if (gi.workRelPath != null) {
            Text(
                "Work ✓",
                fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                color = FilmBackground,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .background(Accent)
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        if (gi.smartCount > 0) {
            Text(
                "⚡${gi.smartCount}",
                fontSize = 10.sp, color = FilmBackground,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .background(Color(0xCC1B231C))
                    .padding(horizontal = 3.dp, vertical = 1.dp),
            )
        }
        // 勾选圈（顶左；圈可点，格身打开查看器）
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(4.dp)
                .size(22.dp)
                .background(
                    if (checked) Accent else Color(0x33FFFFFF),
                    RoundedCornerShape(50),
                )
                .border(
                    1.5.dp,
                    if (checked) Accent else Color(0xE6FFFFFF),
                    RoundedCornerShape(50),
                )
                .clickable { onCheck() },
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Text("✓", fontSize = 12.sp, color = CardBg, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** 多选底部固定操作栏（相册类 App 习惯：选中即起；CTA 去滤镜页承接确认弹层）。 */
@Composable
private fun GallerySelectBar(
    state: ProbeUiState,
    controller: ProbeController,
    onGo: () -> Unit,
) {
    val totalJpeg = state.galleryItems.count { isJpegName(it.name) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBg)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "取消",
            fontSize = 13.sp, color = SageText, fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clickable { controller.clearPassSelection() }
                .padding(horizontal = 10.dp, vertical = 10.dp),
        )
        Text(
            if (state.passSelectedNames.size >= totalJpeg) "清空全选" else "全选 JPEG",
            fontSize = 12.sp, color = InkTeal, fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clickable {
                    if (state.passSelectedNames.size >= totalJpeg) {
                        controller.clearPassSelection()
                    } else {
                        state.galleryItems.filter { isJpegName(it.name) }.forEach { gi ->
                            if (!state.passSelectedNames.contains(gi.name)) {
                                controller.togglePassSelection(gi.name)
                            }
                        }
                    }
                }
                .padding(horizontal = 8.dp, vertical = 10.dp),
        )
        Spacer(Modifier.weight(1f))
        PillButton(
            label = "用配方处理 ${state.passSelectedNames.size} 张 →",
            filled = true,
        ) { onGo() }
    }
}

/** 全屏查看器：大图横滑 + 底部工具栏（M3-01；承接原详情弹窗的智能三步）。 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun GalleryViewerOverlay(
    state: ProbeUiState,
    startIndex: Int,
    controller: ProbeController,
    onClose: () -> Unit,
) {
    val items = state.galleryItems
    var shareItem by remember { mutableStateOf<GalleryItem?>(null) }
    val pagerState = rememberPagerState(initialPage = startIndex, pageCount = { items.size })
    Box(modifier = Modifier.fillMaxSize().background(Lightbox)) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    "✕",
                    fontSize = 18.sp, color = InkTeal, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .clickable { onClose() }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
                Text(
                    "${pagerState.currentPage + 1} / ${items.size}",
                    fontSize = 12.sp, color = CreamFaint,
                    modifier = Modifier.align(Alignment.Center),
                )
                var menuOpen by remember { mutableStateOf(false) }
                Box(Modifier.align(Alignment.CenterEnd)) {
                    Text(
                        "⋯",
                        fontSize = 18.sp, color = InkTeal,
                        modifier = Modifier
                            .clickable { menuOpen = true }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                    androidx.compose.material3.DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("分享成片") },
                            onClick = {
                                menuOpen = false
                                shareItem = items.getOrNull(pagerState.currentPage)
                            },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("关闭") },
                            onClick = { menuOpen = false; onClose() },
                        )
                    }
                }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
            ) { page ->
                ViewerImage(items.getOrNull(page) ?: return@HorizontalPager)
            }
            ViewerToolbar(
                gi = items.getOrNull(pagerState.currentPage),
                controller = controller,
                onShare = { shareItem = items.getOrNull(pagerState.currentPage) },
            )
        }
    }
    shareItem?.let { gi ->
        ModalBottomSheet(
            onDismissRequest = { shareItem = null },
            containerColor = CardBg,
        ) {
            ShareSheetBody(gi = gi, controller = controller)
        }
    }
}

/** 分享屏（M3-03）：两档预览（4:5 zine 海报 / 原比例）+ 配文编辑 + FileProvider 分享/存相册。 */
@Composable
private fun ShareSheetBody(gi: GalleryItem, controller: ProbeController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var mode by remember { mutableStateOf(ShareMode.POSTER) }
    var saveMsg by remember { mutableStateOf("") }
    val captionPrefill = gi.smartTexts.firstOrNull { it.startsWith("caption：") }
        ?.substringAfter("caption：") ?: ""
    var caption by remember { mutableStateOf(captionPrefill) }

    val workPath = gi.workRelPath
    val preview by produceState<android.graphics.Bitmap?>(initialValue = null, key1 = "${gi.relPath}-$mode") {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val path = workPath ?: gi.relPath
            val bmp = ThumbCache.get<android.graphics.Bitmap>("share-$path-$mode")
                ?: ThumbDecode.sampled(java.io.File(context.filesDir, path).absolutePath, target = 2048)
                    ?.also { ThumbCache.put("share-$path-$mode", it, it.byteCount.toLong()) }
                ?: return@withContext null
            when (mode) {
                ShareMode.POSTER ->
                    ZinePosterRenderer.render(bmp, "${gi.dateKey} · 行山记")
                ShareMode.ORIGINAL -> bmp
            }
        }
    }

    Column(modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("分享输出", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = InkTeal)
            Spacer(Modifier.weight(1f))
            Text(
                if (mode == ShareMode.POSTER) "原比例档 →" else "4:5 zine 海报 →",
                fontSize = 13.sp, color = InkTeal, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .background(PillActive, RoundedCornerShape(14.dp))
                    .clickable { mode = if (mode == ShareMode.POSTER) ShareMode.ORIGINAL else ShareMode.POSTER }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp)
                .background(PillActive, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val pv = preview
            if (pv != null) {
                Image(
                    bitmap = pv.asImageBitmap(),
                    contentDescription = "预览",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                )
            } else {
                CircularProgressIndicator(color = InkTeal, trackColor = DividerCream, modifier = Modifier.size(28.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = caption,
            onValueChange = { caption = it },
            placeholder = { Text("写一句这次徒步…") },
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = InkTeal),
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PillButton(
                label = "存相册（${if (mode == ShareMode.POSTER) "4:5 海报" else "原比例"}）",
                filled = false,
            ) {
                val pv = preview
                if (pv != null) {
                    val dir = java.io.File(context.cacheDir, "share").apply { mkdirs() }
                    val f = java.io.File(dir, "lumina_${gi.name.substringBeforeLast('.')}${if (mode == ShareMode.POSTER) "_zine" else ""}.jpg")
                    f.outputStream().use { pv.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
                    saveMsg = if (controller.exportToSystemGallery(f, f.name)) "已存相册 Pictures/Lumina：${f.name}" else "导出失败（API 版本或存储异常）"
                }
            }
            Spacer(Modifier.width(12.dp))
            PillButton(
                label = "分享 →",
                filled = true,
            ) {
                val pv = preview
                if (pv != null) {
                    val dir = java.io.File(context.cacheDir, "share").apply { mkdirs() }
                    val f = java.io.File(dir, "lumina_${gi.name.substringBeforeLast('.')}${if (mode == ShareMode.POSTER) "_zine" else ""}.jpg")
                    f.outputStream().use { pv.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", f,
                    )
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "image/jpeg"
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        if (caption.isNotBlank()) putExtra(android.content.Intent.EXTRA_TEXT, PosterLayout.sanitizeTitle(caption))
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(android.content.Intent.createChooser(send, "分享 Lumina 成片"))
                }
            }
        }
        if (saveMsg.isNotBlank()) {
            Text(saveMsg, fontSize = 12.sp, color = Accent, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** 两档（规格 §3.7）。 */
enum class ShareMode { POSTER, ORIGINAL }

/** 查看器大图：按屏宽 downsample 解码（走同一 ThumbCache 账本；NEF 无 Work 时提示）。 */
@Composable
private fun ViewerImage(gi: GalleryItem) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val cfg = LocalConfiguration.current
    val isRawNoWork = !isJpegName(gi.name) && gi.workRelPath == null
    if (isRawNoWork) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("RAW 不预览", fontSize = 15.sp, color = SageText)
            Text("成片生成后此处显示 Work", fontSize = 11.sp, color = CreamFaint)
        }
        return
    }
    val src = gi.workRelPath ?: gi.relPath
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, key1 = src) {
        val hit = ThumbCache.get<android.graphics.Bitmap>(src)
        if (hit != null) { value = hit; return@produceState }
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val target = (cfg.screenWidthDp * context.resources.displayMetrics.density).toInt()
            ThumbDecode.sampled(java.io.File(context.filesDir, src).absolutePath, target = target)
                ?.also { ThumbCache.put(src, it, it.byteCount.toLong()) }
        }
    }
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = gi.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
    } ?: Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = InkTeal, trackColor = DividerCream)
    }
}

/** 查看器底栏：文件信息 + 三步工具（标注/文案/滤镜同 M2 runSingleStep 链）+ 智能结果收合条。 */
@Composable
private fun ViewerToolbar(gi: GalleryItem?, controller: ProbeController, onShare: () -> Unit = {}) {
    if (gi == null) return
    var busyStep by remember { mutableStateOf("") }
    var smartOpen by remember(gi.relPath) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBg)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(gi.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = InkTeal)
            if (gi.workRelPath != null) {
                Text(
                    "  Work ✓",
                    fontSize = 10.sp, color = Accent, fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "${humanBytes(gi.sizeBytes)} · ${gi.dateKey}",
                fontSize = 11.sp, color = CreamFaint,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ViewerAction("标注", "tag") {
                busyStep = "标注 执行中…"
                controller.runSingleStep(gi.name, com.lumina.cards.StepKind.ANNOTATE)
            }
            ViewerAction("文案", "text") {
                busyStep = "文案 执行中…"
                controller.runSingleStep(gi.name, com.lumina.cards.StepKind.CAPTION)
            }
            ViewerAction("滤镜", "filter") {
                busyStep = "滤镜 执行中…"
                controller.runSingleStep(gi.name, com.lumina.cards.StepKind.FILTER_SUGGEST)
            }
            ViewerAction("分享", "share") { onShare() }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "智能结果 · ${gi.smartTexts.size}" + if (smartOpen) " ▴" else " ▾",
            fontSize = 11.sp, color = AccentDeep, fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .background(PillActive, RoundedCornerShape(999.dp))
                .clickable { smartOpen = !smartOpen }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
        if (busyStep.isNotBlank()) {
            Text("⏳ $busyStep", fontSize = 11.sp, color = SageText, modifier = Modifier.padding(top = 6.dp))
        }
        if (smartOpen) {
            Column(
                modifier = Modifier
                    .heightIn(max = 170.dp)
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth(),
            ) {
                if (gi.smartTexts.isEmpty()) {
                    Text(
                        "还没有结果：点上方工具栏跑一步",
                        fontSize = 11.sp, color = CreamFaint,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                gi.smartTexts.forEach { line ->
                    Text(
                        line, fontSize = 12.sp, color = InkTeal,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

/** 查看器工具项：图标在上、文字在下（对齐 v2 原型）。 */
@Composable
private fun ViewerAction(label: String, icon: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ViewerIcon(icon)
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 9.5.sp, color = InkTeal)
    }
}

@Composable
private fun ViewerIcon(kind: String) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val sw = 1.6.dp.toPx()
        when (kind) {
            "tag" -> {
                drawRoundRect(
                    color = InkTeal,
                    topLeft = Offset(w * 0.18f, h * 0.18f),
                    size = Size(w * 0.64f, h * 0.64f),
                    cornerRadius = CornerRadius(w * 0.12f),
                    style = Stroke(width = sw, cap = StrokeCap.Round),
                )
                drawCircle(InkTeal, radius = w * 0.07f, center = Offset(w * 0.36f, h * 0.36f), style = Stroke(width = sw))
            }
            "text" -> {
                drawLine(InkTeal, Offset(w * 0.2f, h * 0.3f), Offset(w * 0.8f, h * 0.3f), sw, StrokeCap.Round)
                drawLine(InkTeal, Offset(w * 0.2f, h * 0.5f), Offset(w * 0.65f, h * 0.5f), sw, StrokeCap.Round)
                drawLine(InkTeal, Offset(w * 0.2f, h * 0.7f), Offset(w * 0.8f, h * 0.7f), sw, StrokeCap.Round)
            }
            "filter" -> {
                drawCircle(InkTeal, radius = w * 0.24f, center = Offset(w * 0.38f, h * 0.5f), style = Stroke(width = sw))
                drawCircle(InkTeal, radius = w * 0.24f, center = Offset(w * 0.62f, h * 0.5f), style = Stroke(width = sw))
            }
            else -> {
                drawLine(InkTeal, Offset(w * 0.5f, h * 0.75f), Offset(w * 0.5f, h * 0.2f), sw, StrokeCap.Round)
                drawLine(InkTeal, Offset(w * 0.5f, h * 0.2f), Offset(w * 0.34f, h * 0.36f), sw, StrokeCap.Round)
                drawLine(InkTeal, Offset(w * 0.5f, h * 0.2f), Offset(w * 0.66f, h * 0.36f), sw, StrokeCap.Round)
                drawLine(InkTeal, Offset(w * 0.25f, h * 0.55f), Offset(w * 0.25f, h * 0.8f), sw, StrokeCap.Round)
                drawLine(InkTeal, Offset(w * 0.25f, h * 0.8f), Offset(w * 0.75f, h * 0.8f), sw, StrokeCap.Round)
                drawLine(InkTeal, Offset(w * 0.75f, h * 0.8f), Offset(w * 0.75f, h * 0.55f), sw, StrokeCap.Round)
            }
        }
    }
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
        Text(state.connectionText, fontSize = 13.sp, color = CreamFaint)
        if (state.cameraItems.isEmpty() && state.ingestProgress == null) {
            EmptyCameraCard(state, controller)
            return@Column
        }
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
                    .background(if (enabled) Accent else DividerCream, RoundedCornerShape(999.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp)
                    .clickable(enabled = enabled) { controller.startIngest() },
            ) {
                Text(
                    if (state.engineReady) "导出 $n 个" else "未连接相机",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) CardBg else CreamFaint,
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
    }
}

/** 相机空态：三步引导 + 重新检测（不再是一屏空白加两个箭头）。 */
@Composable
private fun EmptyCameraCard(state: ProbeUiState, controller: ProbeController) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp)
            .background(CardBg, RoundedCornerShape(18.dp))
            .border(1.dp, DividerCream, RoundedCornerShape(18.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "把 Z30 接上手机",
            fontFamily = FontFamily.Serif,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold,
            color = InkTeal,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "插上 OTG 线后，这里会按拍摄日期列出卡内照片，勾选即可导出。",
            fontSize = 12.sp,
            color = CreamFaint,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("1 插线", "2 允许访问", "3 勾选导出").forEach { s ->
                Text(
                    s,
                    fontSize = 10.sp,
                    color = AccentDeep,
                    modifier = Modifier
                        .background(PillActive, RoundedCornerShape(999.dp))
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        PillButton(
            label = if (state.pendingPermission) "重新授权" else "重新检测",
            filled = true,
        ) {
            if (state.pendingPermission) controller.retryPermission() else controller.emitCameraUiRefresh()
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
    Column(modifier = Modifier.fillMaxWidth().background(CardBg)) {
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
    onOpenSettings: () -> Unit = {},
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
    val skillLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri: android.net.Uri? -> uri?.let { controller.importSkillCard(it) } }
    val albumLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri: android.net.Uri? -> uri?.let { controller.importFromAlbum(it) } }

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
                    .background(Accent, RoundedCornerShape(999.dp))
                    .clickable {
                        importLauncher.launch(arrayOf("application/json", "text/*"))
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text("卡片", fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold, color = CardBg)
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .background(PillActive)
                    .clickable { skillLauncher.launch(arrayOf("text/*", "text/markdown")) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text("SKILL", fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold, color = AccentDeep)
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .background(PillActive)
                    .clickable {
                        albumLauncher.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text("⤵ 相册", fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold, color = AccentDeep)
            }
            Spacer(Modifier.width(12.dp))
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
            containerColor = CardBg,
        ) {
            // 口径与 startCardRun 一致：图库勾选里的非 JPEG（NEF 等）会由入队层自动滤掉，计数先对齐展示
            val selJpeg = state.passSelectedNames.count { isJpegName(it) }
            val selTotal = state.passSelectedNames.size
            CardConfirmSheet(
                card = card,
                selectedCount = selJpeg,
                totalJpeg = state.galleryItems.count { isJpegName(it.name) },
                hintCount = if (selTotal > selJpeg) selTotal - selJpeg else 0,
                cloudEnabled = state.cloudEnabled,
                onConfirm = { selectedOnly ->
                    controller.startCardRun(card.cardId, selectedOnly = selectedOnly)
                    confirmCard = null
                    onRunStarted()
                },
                onOpenSettings = {
                    confirmCard = null
                    onOpenSettings()
                },
            )
        }
    }
}

/** 确认弹层：滤镜包摘要 + 步链 + 两个范围 CTA（已选 JPEG 为 0 时禁用；云开关关 = 全部禁用并亮闸门）。 */
@Composable
private fun CardConfirmSheet(
    card: CardUi,
    selectedCount: Int,
    totalJpeg: Int,
    hintCount: Int = 0,
    cloudEnabled: Boolean = true,
    onConfirm: (selectedOnly: Boolean) -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    Column(modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 20.dp)) {
        Text("${card.name}${if (card.imported) "（导入）" else ""}", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = InkTeal)
        Spacer(Modifier.height(4.dp))
        Text("v${card.version} · ${card.stepsCount} 步 · ${card.steps.joinToString(" → ")}", fontSize = 13.sp, color = SageText)
        Spacer(Modifier.height(4.dp))
        Text("对所选 Master 逐张串行执行；可中途返回，后台继续。", fontSize = 12.sp, color = CreamFaint)
        Spacer(Modifier.height(16.dp))
        if (!cloudEnabled) {
            // 闸门前置：云开关关 = 两个 CTA 一并禁用（老实告诉用户为什么，别让点击无声吞掉）
            Text(
                "⚠ 云处理总开关是关的：先到「设置」打开开关并确认 LLM key 已填。",
                fontSize = 13.sp, color = WarnBrown, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Spacer(Modifier.height(14.dp))
            PillButton(
                label = "去设置 Tab 开云处理",
                filled = true,
            ) { onOpenSettings() }
            Spacer(Modifier.height(6.dp))
            Text(
                "打开后回到本页重新点开这张配方即可执行。",
                fontSize = 11.sp, color = CreamFaint,
            )
            return@Column
        }
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
            .padding(vertical = 8.dp)
            .background(CardBg, RoundedCornerShape(14.dp))
            .border(1.dp, DividerCream, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(10.dp),
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
                fontSize = 11.sp, color = CreamFaint, modifier = Modifier.padding(top = 3.dp))
            Text("导出",
                fontSize = 11.sp, color = Accent, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable { onExport() }
                    .padding(top = 6.dp))
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

/** 设置分节白卡。 */
@Composable
private fun SettingsPanel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBg, RoundedCornerShape(16.dp))
            .border(1.dp, DividerCream, RoundedCornerShape(16.dp))
            .padding(16.dp),
        content = content,
    )
}

@OptIn(ExperimentalLayoutApi::class)
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
    var showKey by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val versionName = remember(ctx) {
        @Suppress("DEPRECATION")
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
    }

    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.padding(top = 14.dp),
        ) {
            Text(
                "设置",
                fontFamily = FontFamily.Serif,
                fontSize = 27.sp,
                fontWeight = FontWeight.SemiBold,
                color = InkTeal,
            )
            Spacer(Modifier.weight(1f))
            Text("v$versionName", fontSize = 10.sp, color = CreamFaint)
        }
        Text(
            "自用构建 · 云端与备份",
            fontSize = 11.5.sp, color = CreamFaint,
            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
        )

        SettingsPanel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("云处理总开关", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = InkTeal)
                    Text(
                        if (state.cloudEnabled) "开启中：联网模型仅用于你配置的 API 域" else "关闭中：所有云端请求挂起（回网也不跑）",
                        fontSize = 11.5.sp,
                        color = if (state.cloudEnabled) Accent else CreamFaint,
                    )
                }
                Switch(
                    checked = state.cloudEnabled,
                    onCheckedChange = { controller.setCloudEnabled(it) },
                    colors = androidx.compose.material3.SwitchDefaults.colors(
                        checkedThumbColor = CardBg,
                        checkedTrackColor = Accent,
                        uncheckedThumbColor = CreamFaint,
                        uncheckedTrackColor = DividerCream,
                        uncheckedBorderColor = Color.Transparent,
                    ),
                )
            }

            Spacer(Modifier.height(16.dp))
            Text("平台模板", fontSize = 11.sp, color = Accent)
            Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            com.lumina.llm.LlmTemplates.presets.forEach { t ->
                val active = templateName == t.name
                Text(
                    t.name,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (active) CardBg else AccentDeep,
                    modifier = Modifier
                        .background(if (active) Accent else CardBg, RoundedCornerShape(999.dp))
                        .border(1.dp, if (active) Accent else DividerCream, RoundedCornerShape(999.dp))
                        .clickable {
                            templateName = t.name
                            baseUrl = t.baseUrl
                            model = t.model
                        }
                        .padding(horizontal = 11.dp, vertical = 6.dp),
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
            visualTransformation = if (showKey) androidx.compose.ui.text.input.VisualTransformation.None
                else androidx.compose.ui.text.input.PasswordVisualTransformation(),
            trailingIcon = {
                Text(
                    if (showKey) "隐藏" else "显示",
                    fontSize = 11.sp, color = Accent, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable { showKey = !showKey }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            },
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
                .background(Accent, RoundedCornerShape(999.dp))
                .padding(horizontal = 24.dp, vertical = 11.dp)
                .clickable {
                    if (baseUrl.isBlank() || model.isBlank()) {
                        saved = "base_url 与 model 不能为空"
                    } else {
                        controller.saveLlmConfig(baseUrl, apiKey, model, templateName, state.cloudEnabled)
                        saved = "已保存 ✓"
                    }
                },
        ) {
            Text("保存配置", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = CardBg)
        }
        if (saved.isNotBlank()) {
            Text(saved, fontSize = 12.sp, color = InkTeal, modifier = Modifier.padding(top = 8.dp))
        }

        Spacer(Modifier.height(16.dp))
        // 连接测试：真实 ping 一次 chat.completions（文本 only，无图）
        Box(
            modifier = Modifier
                .background(
                    if (state.cloudEnabled) PillActive else DividerCream,
                    RoundedCornerShape(999.dp),
                )
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
        }

        Spacer(Modifier.height(20.dp))
        // ── WebDAV Master 备份（M3-04，ADR-0003） ──
        val wd = state.webdav
        Text("WebDAV 备份（Master 终点）", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = InkTeal)
        Spacer(Modifier.height(6.dp))
        SettingsPanel {
        // 本地记录的 baseURL/用户名回显；密码永不回显（只显示"已存"）
        var davUrl by remember(cfg) { mutableStateOf(wd?.baseUrl ?: "") }
        var davUser by remember(cfg) { mutableStateOf(wd?.username ?: "") }
        var davPass by remember(cfg) { mutableStateOf("") }
        OutlinedTextField(value = davUrl, onValueChange = { davUrl = it },
            label = { Text("WebDAV 地址（含目录根，如 https://…/dav/）") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = davUser, onValueChange = { davUser = it },
            label = { Text("账号") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = davPass, onValueChange = { davPass = it },
            label = { Text(if (wd != null) "密码（已保存，重填即覆盖）" else "密码") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PillButton(
                label = "保存 WebDAV",
                filled = true,
            ) { controller.saveWebDavConfig(davUrl, davUser, davPass) }
            Spacer(Modifier.width(12.dp))
            PillButton(
                label = "测试连接",
                filled = false,
            ) { if (wd == null && davPass.isBlank())
                    controller.saveWebDavConfig(davUrl, davUser, davPass)
                else if (wd != null && davPass.isBlank()) controller.testWebDavConnection()
                else { controller.saveWebDavConfig(davUrl, davUser, davPass); controller.testWebDavConnection() } }
        }
        if (wd != null && davPass.isBlank()) {
            Text(
                "已配置：${wd.baseUrl}（${wd.username}）",
                fontSize = 11.sp, color = CreamFaint, modifier = Modifier.padding(top = 6.dp),
            )
        }
        state.backup?.let { bk ->
            Spacer(Modifier.height(10.dp))
            when (bk.phase) {
                BackupPhase.RUNNING -> {
                    Text("备份中：${bk.done + bk.fail}/${bk.total}" +
                        (bk.currentItem?.let { " —— $it" } ?: ""), fontSize = 12.sp, color = SageText)
                    LinearProgressIndicator(
                        progress = { if (bk.total > 0) (bk.done + bk.fail).toFloat() / bk.total else 0f },
                        modifier = Modifier.fillMaxWidth(),
                        color = InkTeal, trackColor = DividerCream,
                    )
                }
                BackupPhase.VERIFIED -> {
                    Text(
                        "✓ ${bk.summary}　已核验可清出 ${bk.deletableNames.size} 项" +
                            "（eq. 远端存在且字节一致的 Master）",
                        fontSize = 12.sp, color = InkTeal, fontWeight = FontWeight.SemiBold,
                    )
                    if (bk.deletableNames.size > 0) {
                        Text(
                            "样本：${bk.deletableNames.take(3).joinToString(" · ")}" +
                                (if (bk.deletableNames.size > 3) " …" else ""),
                            fontSize = 11.sp, color = CreamFaint,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PillButton(label = "都留着，不删", filled = false) { controller.dismissWebDavCleanup() }
                        Spacer(Modifier.width(12.dp))
                        PillButton(label = "确认清出 ${bk.deletableNames.size} 张", filled = true) {
                            controller.confirmWebDavCleanup()
                        }
                    }
                    Text(
                        "清出 = 把已核验（上云比对通过）的 Master 从手机删除；NEF 体积大，先行清能腾很多空间。",
                        fontSize = 11.sp, color = CreamFaint, modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "备份语义：全部 Master（JPEG+NEF）→ <base>/Lumina/Master/<日期>/；远端同名同大小即跳过（幂等）。",
            fontSize = 11.sp, color = CreamFaint,
        )
        }
        Spacer(Modifier.height(20.dp))
        // ── 图像生成端点（M4-01，合并进平台模板：选模板/保存 LLM 时一键并配，无独立表单） ──
        Text(
            "生成式卡出图（并入平台模板）",
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = InkTeal,
        )
        Spacer(Modifier.height(6.dp))
        val ig = state.imagegen
        if (ig != null) {
            Text(
                "已并配：${ig.model} · ${ig.size} · ${ig.baseUrl.substringBeforeLast('/')}",
                fontSize = 12.sp, color = Accent,
            )
            if (!com.lumina.imagegen.ImageGenClient.isEditEndpoint(ig.baseUrl)) {
                Text(
                    "当前是纯文生图端点，不会上传参考图；要“以原照片为参考”请把生图地址换成支持 /images/edits 的服务。",
                    fontSize = 10.5.sp, lineHeight = 15.sp, color = CreamFaint,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        } else {
            Text(
                "选择【智谱 GLM】模板并保存后，生图端点（glm-image · 3:5）自动一并配好（key 同平台两用）。",
                fontSize = 11.sp, color = CreamFaint,
            )
        }
        Spacer(Modifier.height(6.dp))
        PillButton(label = "测试生图一次", filled = false) { controller.testImageGenConfig() }
        Spacer(Modifier.height(10.dp))
        var watermarkOff by remember { mutableStateOf(!com.lumina.imagegen.ImageGenPrefs.watermarkEnabled(ctx)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("关闭 AI 生成水印", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = InkTeal)
                Text(
                    "需先在智谱控制台签署去水印声明；关闭后新图不再带「AI生成」",
                    fontSize = 10.5.sp, lineHeight = 15.sp, color = CreamFaint,
                )
            }
            Switch(
                checked = watermarkOff,
                onCheckedChange = {
                    watermarkOff = it
                    com.lumina.imagegen.ImageGenPrefs.setWatermarkEnabled(ctx, !it)
                },
                colors = androidx.compose.material3.SwitchDefaults.colors(
                    checkedThumbColor = CardBg,
                    checkedTrackColor = Accent,
                    uncheckedThumbColor = CreamFaint,
                    uncheckedTrackColor = DividerCream,
                    uncheckedBorderColor = Color.Transparent,
                ),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "生成卡（scenes/pixel/gc-minimal 等）跑完标注/文案后，将按 SKILL 工作流出一张 3:5 纸刊海报存 Work 层；作者署名：Visual Skill by @Zeejay0（个人非商业）。",
            fontSize = 11.sp, color = CreamFaint,
        )
        Spacer(Modifier.height(16.dp))
        Text("明细日志（最近 200 内）", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = InkTeal)
        if (state.ledgerRecent.isEmpty()) {
            Text("还没有云端调用", fontSize = 12.sp, color = CreamFaint)
        } else {
            state.ledgerRecent.forEach { line ->
                Text(line, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = SageText)
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("关于", fontSize = 11.sp, color = Accent)
        Spacer(Modifier.height(6.dp))
        SettingsPanel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("版本", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = InkTeal)
                Spacer(Modifier.weight(1f))
                Text("v$versionName", fontSize = 11.sp, color = CreamFaint)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "联网仅指向你配置的模型 API 域；无自有服务器；无定位 / 相机 / 麦克风权限。",
                fontSize = 10.5.sp, lineHeight = 16.sp, color = CreamFaint,
            )
        }
        Spacer(Modifier.height(28.dp))
    }
}
