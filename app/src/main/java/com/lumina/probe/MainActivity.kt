package com.lumina.probe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.ptp.CameraObject
import com.lumina.ptp.DateGroup
import java.time.LocalDate

/**
 * M0 探针屏（工单 05 集成版）：
 * 插线 → 授权 → 按拍摄日期分组的卡内照片清单 + 顶部统计 + 底部会话日志尾巴。
 * 视觉延续 F·Grain 胶片奶绿（奶油底 / 鼠标绿点缀 / 衬线编辑气质）。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        var state by mutableStateOf(ProbeUiState("未连接 · 请插入相机"))
        val controller = ProbeController(this) { next -> state = next }
        setContent {
            Surface(color = FilmBackground) {
                Box(modifier = Modifier.fillMaxSize()) {
                    ProbeScreen(state, controller)
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

@Composable
fun ProbeScreen(state: ProbeUiState, controller: ProbeController) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Lumina", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = InkTeal)
            Spacer(Modifier.height(0.dp).weight(1f))
            Text("M0 probe", fontSize = 10.sp, color = CreamFaint)
        }
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
        state.summary?.let { summary ->
            Text(
                "${summary.totalCount} 个对象 · ${humanBytes(summary.totalBytes)}",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = InkTeal,
                modifier = Modifier.padding(top = 20.dp),
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                summary.byDate.forEach { group ->
                    item(key = "day-${group.day}") { GroupHeader(group) }
                    items(group.objects, key = { "obj-${it.handle}" }) { obj ->
                        CameraRow(obj)
                    }
                }
            }
        } ?: Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(16.dp))
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

@Composable
private fun GroupHeader(group: DateGroup) {
    val label = group.day
        ?.let { LocalDate.ofEpochDay(it) }
        ?.let { "$it" }
        ?: "无拍摄日期"
    Text(
        label,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = SageText,
        modifier = Modifier
            .padding(top = 16.dp, bottom = 4.dp)
            .fillMaxWidth(),
    )
}

@Composable
private fun CameraRow(obj: CameraObject) {
    Column(modifier = Modifier.fillMaxWidth().background(CreamFaint.copy(alpha = 0.08f))) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
