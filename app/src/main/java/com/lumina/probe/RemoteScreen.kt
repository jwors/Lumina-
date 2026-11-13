package com.lumina.probe

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.remote.CameraParams

/**
 * M5 遥控页（全屏覆盖层，不新增底部 Tab）：
 * 上半屏连接/参数，下半屏大快门——戴手套也能按。
 */
@Composable
internal fun RemoteScreen(
    ui: RemoteUi?,
    controller: ProbeController,
    onClose: () -> Unit,
) {
    val state = ui ?: RemoteUi()
    Column(Modifier.fillMaxSize().background(FilmBackground).statusBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "‹ 返回",
                fontSize = 15.sp,
                color = InkTeal,
                modifier = Modifier
                    .clickable {
                        controller.closeRemote()
                        onClose()
                    }
                    .padding(6.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                "遥控",
                fontFamily = FontFamily.Serif,
                fontSize = 23.sp,
                fontWeight = FontWeight.SemiBold,
                color = InkTeal,
            )
            Spacer(Modifier.weight(1f))
            Text("BLE", fontSize = 11.sp, color = CreamFaint, modifier = Modifier.padding(6.dp))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            StatusCard(state)
            Spacer(Modifier.height(14.dp))
            when (state.stage) {
                RemoteStage.READY -> ParamsSection(state)
                RemoteStage.PAIRING, RemoteStage.WIFI, RemoteStage.CONNECTING -> BusySection(state, controller)
                else -> ConnectSection(state, controller)
            }
            state.warning?.let {
                Spacer(Modifier.height(10.dp))
                Text("提示：$it", fontSize = 12.sp, color = WarnBrown, lineHeight = 17.sp)
            }
            if (state.log.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text("协议日志", fontSize = 10.sp, color = CreamFaint)
                state.log.takeLast(4).forEach {
                    Text(it, fontSize = 10.sp, color = CreamFaint, lineHeight = 14.sp)
                }
            }
            Spacer(Modifier.height(20.dp))
        }
        if (state.stage == RemoteStage.READY) ShutterBar(state, controller)
    }
}

@Composable
private fun StatusCard(state: RemoteUi) {
    val ready = state.stage == RemoteStage.READY
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (ready) PillActive else CardBg, RoundedCornerShape(16.dp))
            .border(1.dp, DividerCream, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(if (ready) Accent else CreamFaint, CircleShape),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                state.status.ifBlank { "未连接" },
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (ready) AccentDeep else InkTeal,
                lineHeight = 18.sp,
            )
            when {
                ready && state.shotCount > 0 -> Text(
                    "本次已拍 ${state.shotCount} 张",
                    fontSize = 11.sp,
                    color = SageText,
                )
                state.model != null -> Text(state.model, fontSize = 11.sp, color = SageText)
            }
        }
    }
}

@Composable
private fun ConnectSection(state: RemoteUi, controller: ProbeController) {
    val scanning = state.stage == RemoteStage.SCANNING
    PillButton(
        label = if (scanning) "扫描中…" else "扫描相机",
        filled = true,
        enabled = !scanning,
    ) { controller.remoteScan() }

    if (state.devices.isNotEmpty()) {
        Spacer(Modifier.height(14.dp))
        Text("附近的相机", fontSize = 11.sp, color = SageText)
        Spacer(Modifier.height(6.dp))
        state.devices.forEach { device ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .background(CardBg, RoundedCornerShape(12.dp))
                    .border(1.dp, DividerCream, RoundedCornerShape(12.dp))
                    .clickable { controller.remoteConnect(device.address) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(device.name, fontSize = 14.sp, color = InkTeal, fontWeight = FontWeight.SemiBold)
                    Text("信号 ${device.rssi} dBm", fontSize = 10.sp, color = CreamFaint)
                }
                Text("连接 ›", fontSize = 13.sp, color = AccentDeep, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    Spacer(Modifier.height(18.dp))
    ManualWifiSection(controller)
}

@Composable
private fun BusySection(state: RemoteUi, controller: ProbeController) {
    Spacer(Modifier.height(10.dp))
    if (state.stage == RemoteStage.WIFI) {
        Text("相机热点已开，但 Android 12 需要你手动入网。", fontSize = 12.5.sp, color = SageText, lineHeight = 18.sp)
        Spacer(Modifier.height(10.dp))
        PillButton("打开系统 Wi-Fi", filled = false) {
            controller.remoteOpenWifiPanel()
        }
        Spacer(Modifier.height(8.dp))
        PillButton("已连好，继续", filled = true) {
            controller.remoteContinueWifi()
        }
    } else {
        Text(
            when (state.stage) {
                RemoteStage.PAIRING -> "正在和相机蓝牙配对：首次会弹系统配对确认，点「配对」。"
                else -> "正在连相机（Wi-Fi 加入 + PTP 握手），大约 5–15 秒。"
            },
            fontSize = 12.5.sp,
            color = SageText,
            lineHeight = 18.sp,
        )
    }
}

@Composable
private fun ParamsSection(state: RemoteUi) {
    Text("镜头参数", fontSize = 11.sp, color = SageText)
    Spacer(Modifier.height(8.dp))
    val rows = (state.params ?: CameraParams()).rows()
    rows.chunked(2).forEach { pair ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            pair.forEach { (label, value) ->
                ParamCell(label, value, Modifier.weight(1f))
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
    }
    if (state.params?.isEmpty != false) {
        Text(
            "相机还没回报参数：确认相机在拍摄模式（不是回放/菜单），或稍等两秒。",
            fontSize = 11.sp,
            color = CreamFaint,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun ParamCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(CardBg, RoundedCornerShape(14.dp))
            .border(1.dp, DividerCream, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(label, fontSize = 10.sp, color = CreamFaint)
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            fontFamily = FontFamily.Serif,
            fontSize = 21.sp,
            fontWeight = FontWeight.SemiBold,
            color = InkTeal,
        )
    }
}

@Composable
private fun ShutterBar(state: RemoteUi, controller: ProbeController) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBg)
            .navigationBarsPadding()
            .padding(top = 10.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val busy = state.status == "拍摄中…"
        Box(
            modifier = Modifier
                .size(84.dp)
                .border(3.dp, if (busy) CreamFaint else Accent, CircleShape)
                .padding(8.dp)
                .background(if (busy) CreamFaint else Accent, CircleShape)
                .clickable(enabled = !busy) { controller.remoteCapture() },
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (busy) "拍摄中…" else "按一下拍照",
            fontSize = 11.sp,
            color = if (busy) CreamFaint else AccentDeep,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ManualWifiSection(controller: ProbeController) {
    var open by remember { mutableStateOf(false) }
    var ssid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Text(
        if (open) "手动 Wi-Fi（收起）" else "手动 Wi-Fi（BLE 拿不到密码时）",
        fontSize = 11.sp,
        color = CreamFaint,
        modifier = Modifier
            .clickable { open = !open }
            .padding(vertical = 6.dp),
    )
    if (!open) return
    Text(
        "相机菜单 → 连接至智能设备 → 显示 SSID/密码，填一次即可记住。",
        fontSize = 11.sp,
        color = CreamFaint,
        lineHeight = 16.sp,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = ssid,
        onValueChange = { ssid = it },
        label = { Text("SSID") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("密码") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    PillButton("连接这个热点", filled = false, enabled = ssid.isNotBlank()) {
        controller.remoteManualConnect(ssid.trim(), password)
    }
}
