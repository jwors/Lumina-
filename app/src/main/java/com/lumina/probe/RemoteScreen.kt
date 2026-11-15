package com.lumina.probe

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.lumina.ui.feedback.LuminaHaptic
import com.lumina.ui.feedback.luminaClickable

/**
 * 文字动作（断开/开/关/设置）：至少 48dp 触控区 + 14sp，别再让用户点细字。
 */
@Composable
private fun TextAction(
    label: String,
    color: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .luminaClickable(pressScale = 0.9f, haptic = LuminaHaptic.Tick) { onClick() }
            .padding(horizontal = 10.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

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
            .heightIn(max = 560.dp)
            .verticalScroll(rememberScrollState())
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
                Text("无线相机", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = InkTeal)
                Text(
                    state.status.ifBlank { "未连接" },
                    fontSize = 12.sp,
                    color = SageText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.stage == RemoteStage.READY) {
                TextAction("断开", WarnBrown) { controller.remoteDisconnect() }
            } else {
                TextAction(if (showSteps) "收起" else "连接设置", AccentDeep) {
                    showSteps = !showSteps
                }
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
        fontSize = 10.sp,
        color = CreamFaint,
        modifier = Modifier.padding(top = 6.dp),
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                if (state.shotCount > 0) "本次已拍 ${state.shotCount} 张" else "参数每 2 秒刷新",
                fontSize = 12.sp,
                color = CreamFaint,
            )
            state.warning?.let {
                Text(it, fontSize = 11.sp, color = WarnBrown, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (state.params?.isEmpty != false) {
                Text("相机未回报参数：确认在拍摄模式", fontSize = 11.sp, color = CreamFaint)
            }
        }
        Spacer(Modifier.width(12.dp))
        ShutterButton(busy = state.status == "拍摄中…") { controller.remoteCapture() }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("拍后回传 JPEG", fontSize = 13.sp, color = AccentDeep, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        TextAction(if (state.autoDownload) "开" else "关", if (state.autoDownload) Accent else CreamFaint) {
            controller.remoteToggleAutoDownload()
        }
    }
    LiveViewSection(state, controller)
    IntervalSection(state, controller)
    BracketSection(state, controller)
}

/** M6-05：曝光/对焦包围（v1 固定预设，结束自动回位）。 */
@Composable
private fun BracketSection(state: RemoteUi, controller: ProbeController) {
    val bracket = state.bracket
    if (bracket != null && (bracket.running || bracket.message != null)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${bracket.mode}包围 ${bracket.done}/${bracket.count}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (bracket.running) AccentDeep else CreamFaint,
                )
                bracket.message?.let { Text(it, fontSize = 11.sp, color = CreamFaint) }
            }
            TextAction(
                if (bracket.running) "停止" else "清除",
                if (bracket.running) WarnBrown else CreamFaint,
            ) {
                if (bracket.running) controller.remoteStopBracket()
                else controller.remoteClearBracket()
            }
        }
        return
    }

    var open by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .luminaClickable(pressScale = 1f, haptic = LuminaHaptic.Tick) { open = !open },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("包围拍摄", fontSize = 13.sp, color = AccentDeep, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text(if (open) "收起" else "设置", fontSize = 13.sp, color = CreamFaint)
    }
    if (!open) return
    Text("曝光包围：-2 / -1 / 0 EV", fontSize = 11.sp, color = CreamFaint)
    Spacer(Modifier.height(4.dp))
    PillButton("开始曝光包围", filled = false) {
        controller.remoteRunExposureBracket(-2.0, 1.0, 3)
    }
    Spacer(Modifier.height(6.dp))
    Text("对焦包围：3 张 × 2 步（近→远）", fontSize = 11.sp, color = CreamFaint)
    Spacer(Modifier.height(4.dp))
    PillButton("开始对焦包围", filled = false) {
        controller.remoteRunFocusBracket(2, 3, 1)
    }
    Text(
        "结束自动回 EV 0 / 焦点回位",
        fontSize = 10.sp,
        color = CreamFaint,
        modifier = Modifier.padding(top = 4.dp),
    )
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
        Text("实时取景", fontSize = 13.sp, color = AccentDeep, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        TextAction(
            if (state.liveViewOn) "关闭" else "开启",
            if (state.liveViewOn) WarnBrown else CreamFaint,
        ) {
            controller.remoteToggleLiveView()
        }
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
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio((bmp.width.toFloat() / bmp.height.toFloat()).coerceIn(0.5f, 3f))
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
        fontSize = 10.sp,
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
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (interval.running) AccentDeep else CreamFaint,
                )
                if (interval.running) {
                    Text(
                        "下一张 ${interval.nextInSeconds ?: 0}s",
                        fontSize = 11.sp,
                        color = CreamFaint,
                    )
                }
            }
            TextAction(
                if (interval.running) "停止" else "清除",
                if (interval.running) WarnBrown else CreamFaint,
            ) {
                if (interval.running) controller.remoteStopInterval()
                else controller.remoteClearInterval()
            }
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
            .defaultMinSize(minHeight = 48.dp)
            .luminaClickable(pressScale = 1f, haptic = LuminaHaptic.Tick) { open = !open },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("定时 / 间隔拍摄", fontSize = 13.sp, color = AccentDeep, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text(if (open) "收起" else "设置", fontSize = 13.sp, color = CreamFaint)
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
        label = { Text(label, fontSize = 11.sp) },
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
                    .luminaClickable(enabled = isAdjustable, haptic = LuminaHaptic.Tick) { code?.let(onCycle) }
                    .background(Lightbox, RoundedCornerShape(8.dp))
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    label,
                    fontSize = 11.sp,
                    color = if (isAdjustable) AccentDeep else CreamFaint,
                )
                Text(
                    value,
                    fontFamily = FontFamily.Serif,
                    fontSize = 15.sp,
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
            .luminaClickable(
                enabled = !busy,
                pressScale = 0.9f,
                haptic = LuminaHaptic.Confirm,
                magnetic = true,
                shape = CircleShape,
                rippleColor = CardBg,
            ) { onClick() }
            .border(3.dp, if (busy) CreamFaint else Accent, CircleShape)
            .padding(6.dp)
            .background(if (busy) CreamFaint else Accent, CircleShape),
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
                .luminaClickable(pressScale = 0.985f, haptic = LuminaHaptic.Tap) {
                    controller.remoteConnect(device.address)
                }
                .background(Lightbox, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(device.name, fontSize = 13.sp, color = InkTeal, fontWeight = FontWeight.SemiBold)
                Text("信号 ${device.rssi} dBm", fontSize = 10.sp, color = CreamFaint)
            }
            Text("配对 ›", fontSize = 14.sp, color = AccentDeep, fontWeight = FontWeight.SemiBold)
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
        fontSize = 13.sp,
        color = CreamFaint,
        modifier = Modifier
            .luminaClickable(haptic = LuminaHaptic.Tick) { open = !open }
            .defaultMinSize(minHeight = 48.dp)
            .padding(vertical = 12.dp),
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
