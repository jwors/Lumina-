package com.lumina.probe

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.ptp.CameraProp
import com.lumina.remote.CameraParams

/**
 * 相机页里的「无线相机」卡：Wi-Fi 连接状态 + 镜头参数 + 快门。
 *
 * 定稿姿态（ADR-0006）：Z30 不会因第三方 BLE 命令自动开热点，所以主路径是
 * 相机上手动开 Wi-Fi → 手机连热点 → 这里点「连接相机」；蓝牙只用于首次配对
 * 读取 SSID/密码（收在「连接设置」里，不再作为主入口）。
 */
@Composable
internal fun WirelessCameraCard(
    ui: RemoteUi?,
    controller: ProbeController,
    onConnect: () -> Unit,
) {
    val state = ui ?: RemoteUi()
    var showSteps by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBg, RoundedCornerShape(16.dp))
            .border(1.dp, DividerCream, RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(if (state.stage == RemoteStage.READY) Accent else CreamFaint, CircleShape),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("无线相机", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = InkTeal)
                Text(
                    state.status.ifBlank { "未连接" },
                    fontSize = 11.sp,
                    color = SageText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.stage == RemoteStage.READY) {
                Text(
                    "断开",
                    fontSize = 12.sp,
                    color = WarnBrown,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable { controller.remoteDisconnect() }
                        .padding(6.dp),
                )
            } else {
                Text(
                    if (showSteps) "收起" else "连接设置",
                    fontSize = 12.sp,
                    color = AccentDeep,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable { showSteps = !showSteps }
                        .padding(6.dp),
                )
            }
        }

        when (state.stage) {
            RemoteStage.READY -> ReadyBody(state, controller)
            RemoteStage.PAIRING, RemoteStage.CONNECTING -> {
                Spacer(Modifier.height(8.dp))
                Text(state.status, fontSize = 12.sp, color = SageText, lineHeight = 17.sp)
                state.error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, fontSize = 11.sp, color = WarnBrown)
                }
            }
            RemoteStage.SCANNING -> {
                Spacer(Modifier.height(8.dp))
                Text("正在找附近的尼康相机…", fontSize = 12.sp, color = SageText)
                DeviceRows(state, controller)
            }
            else -> {
                Spacer(Modifier.height(8.dp))
                if (state.savedSsid != null) {
                    Text(
                        "手机 Wi-Fi 连上「${state.savedSsid}」后，点下面按钮。",
                        fontSize = 12.sp,
                        color = SageText,
                        lineHeight = 17.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    PillButton("连接相机", filled = true) { onConnect() }
                } else {
                    Text(
                        "先在相机上打开 Wi-Fi（网络 → 连接至智能设备）。首次点扫描，App 会通过蓝牙记住热点密码。",
                        fontSize = 12.sp,
                        color = SageText,
                        lineHeight = 17.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    PillButton("扫描相机（首次配对）", filled = true) { onConnect() }
                }
                state.error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, fontSize = 11.sp, color = WarnBrown, lineHeight = 16.sp)
                }
                if (showSteps) ConnectionSettings(state, controller)
                if (state.devices.isNotEmpty()) DeviceRows(state, controller)
            }
        }
    }
}

@Composable
private fun ReadyBody(state: RemoteUi, controller: ProbeController) {
    Spacer(Modifier.height(10.dp))
    ParamsRow(state.params) { code -> controller.remoteCycleParam(code) }
    Text(
        "点光圈 / 快门 / ISO 调档",
        fontSize = 9.sp,
        color = CreamFaint,
        modifier = Modifier.padding(top = 4.dp),
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                if (state.shotCount > 0) "本次已拍 ${state.shotCount} 张" else "参数每 2 秒刷新",
                fontSize = 11.sp,
                color = CreamFaint,
            )
            state.warning?.let {
                Text(it, fontSize = 10.sp, color = WarnBrown, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (state.params?.isEmpty != false) {
                Text("相机未回报参数：确认在拍摄模式", fontSize = 10.sp, color = CreamFaint)
            }
        }
        Spacer(Modifier.width(12.dp))
        ShutterButton(busy = state.status == "拍摄中…") { controller.remoteCapture() }
    }
    LiveViewSection(state, controller)
    IntervalSection(state, controller)
}

/** M6-03：实时取景开关 + 画面 + 点按对焦。 */
@Composable
private fun LiveViewSection(state: RemoteUi, controller: ProbeController) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("实时取景", fontSize = 11.sp, color = AccentDeep, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text(
            if (state.liveViewOn) "关闭" else "开启",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (state.liveViewOn) WarnBrown else CreamFaint,
            modifier = Modifier
                .clickable { controller.remoteToggleLiveView() }
                .padding(6.dp),
        )
    }
    if (!state.liveViewOn) return
    val bmp = state.liveView
    if (bmp == null) {
        Text("取景启动中…", fontSize = 10.sp, color = CreamFaint)
        return
    }
    Image(
        bitmap = bmp.asImageBitmap(),
        contentDescription = "实时取景",
        contentScale = ContentScale.FillBounds,
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp)
            .clip(RoundedCornerShape(8.dp))
            .pointerInput(bmp) {
                detectTapGestures { offset ->
                    if (size.width > 0 && size.height > 0) {
                        controller.remoteFocusAt(offset.x / size.width, offset.y / size.height)
                    }
                }
            },
    )
    Text(
        "点画面移动对焦点",
        fontSize = 9.sp,
        color = CreamFaint,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** M6-02：定时/间隔拍摄的折叠面板 + 运行进度。 */
@Composable
private fun IntervalSection(state: RemoteUi, controller: ProbeController) {
    val interval = state.interval
    if (interval != null && (interval.running || interval.message != null)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (interval.running) "间隔拍摄 ${interval.done}/${interval.count}"
                    else interval.message ?: "间隔结束",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (interval.running) AccentDeep else CreamFaint,
                )
                if (interval.running) {
                    Text(
                        "下一张 ${interval.nextInSeconds ?: 0}s",
                        fontSize = 10.sp,
                        color = CreamFaint,
                    )
                }
            }
            Text(
                if (interval.running) "停止" else "清除",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (interval.running) WarnBrown else CreamFaint,
                modifier = Modifier
                    .clickable {
                        if (interval.running) controller.remoteStopInterval()
                        else controller.remoteClearInterval()
                    }
                    .padding(6.dp),
            )
        }
        return
    }

    var open by remember { mutableStateOf(false) }
    var delay by remember { mutableStateOf("2") }
    var every by remember { mutableStateOf("5") }
    var count by remember { mutableStateOf("3") }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clickable { open = !open },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("定时 / 间隔拍摄", fontSize = 11.sp, color = AccentDeep, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text(if (open) "收起" else "设置", fontSize = 11.sp, color = CreamFaint)
    }
    if (!open) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        NumberField(delay, "延时s", Modifier.weight(1f)) { delay = it }
        NumberField(every, "间隔s", Modifier.weight(1f)) { every = it }
        NumberField(count, "张数", Modifier.weight(1f)) { count = it }
    }
    Spacer(Modifier.height(6.dp))
    PillButton("开始间隔拍摄", filled = true) {
        controller.remoteStartInterval(
            delay.toIntOrNull() ?: 2,
            every.toIntOrNull() ?: 5,
            count.toIntOrNull() ?: 3,
        )
    }
}

@Composable
private fun NumberField(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 3 && it.all(Char::isDigit)) onChange(it) },
        label = { Text(label, fontSize = 9.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun ParamsRow(params: CameraParams?, onCycle: (Int) -> Unit) {
    val rows = (params ?: CameraParams()).rows()
    val codes = listOf(
        CameraProp.FOCAL_LENGTH,
        CameraProp.F_NUMBER,
        CameraProp.EXPOSURE_TIME,
        CameraProp.EXPOSURE_INDEX,
        CameraProp.BATTERY_LEVEL,
    )
    val adjustable = setOf(CameraProp.F_NUMBER, CameraProp.EXPOSURE_TIME, CameraProp.EXPOSURE_INDEX)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEachIndexed { index, (label, value) ->
            val code = codes.getOrNull(index)
            val isAdjustable = code != null && code in adjustable
            Column(
                modifier = Modifier
                    .weight(1f)
                    .background(Lightbox, RoundedCornerShape(8.dp))
                    .clickable(enabled = isAdjustable) { code?.let(onCycle) }
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    label,
                    fontSize = 9.sp,
                    color = if (isAdjustable) AccentDeep else CreamFaint,
                )
                Text(
                    value,
                    fontFamily = FontFamily.Serif,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = InkTeal,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ShutterButton(busy: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(62.dp)
            .border(3.dp, if (busy) CreamFaint else Accent, CircleShape)
            .padding(6.dp)
            .background(if (busy) CreamFaint else Accent, CircleShape)
            .clickable(enabled = !busy) { onClick() },
    )
}

@Composable
private fun ConnectionSettings(state: RemoteUi, controller: ProbeController) {
    Spacer(Modifier.height(10.dp))
    Text("连接步骤", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = InkTeal)
    Spacer(Modifier.height(4.dp))
    Text("1. 相机：网络 → 连接至智能设备 → 打开 Wi-Fi", fontSize = 11.sp, color = SageText, lineHeight = 16.sp)
    Text(
        "2. 手机 Wi-Fi 连接「${state.savedSsid ?: "NIKON_Z_30_…"}」",
        fontSize = 11.sp,
        color = SageText,
        lineHeight = 16.sp,
    )
    Text("3. 回这里点「连接相机」", fontSize = 11.sp, color = SageText, lineHeight = 16.sp)
    Spacer(Modifier.height(8.dp))
    PillButton("重新扫描（蓝牙读取密码）", filled = false) { controller.remoteScan() }
    ManualWifiSection(controller)
}

@Composable
private fun DeviceRows(state: RemoteUi, controller: ProbeController) {
    Spacer(Modifier.height(8.dp))
    state.devices.forEach { device ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp)
                .background(Lightbox, RoundedCornerShape(10.dp))
                .clickable { controller.remoteConnect(device.address) }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(device.name, fontSize = 13.sp, color = InkTeal, fontWeight = FontWeight.SemiBold)
                Text("信号 ${device.rssi} dBm", fontSize = 10.sp, color = CreamFaint)
            }
            Text("配对 ›", fontSize = 12.sp, color = AccentDeep, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ManualWifiSection(controller: ProbeController) {
    var open by remember { mutableStateOf(false) }
    var ssid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Text(
        if (open) "手动 Wi-Fi（收起）" else "手动 Wi-Fi（蓝牙拿不到密码时）",
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
