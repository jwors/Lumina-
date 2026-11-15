package com.lumina.ui.feedback

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * 语义化触感（对照 iOS HIG 的三类反馈）：
 * - Tick：选择/步进，最轻；
 * - Tap：普通按钮点击；
 * - Confirm：主操作/提交完成；
 * - Reject：删除、失败等负向动作；
 * - ToggleOn/Off：开关与勾选的状态变化。
 *
 * 规则：
 * 1. 只在"点击成立"时触发（不在按下时触发），避免列表滚动起手误振；
 * 2. 每次动作最多一次，不做连续振动；
 * 3. 遵守 [LuminaUiPrefs.hapticsEnabled]，关闭后完全静音；
 * 4. 使用系统 `performHapticFeedback`，尊重系统触感总开关。
 */
enum class LuminaHaptic {
    None,
    Tick,
    Tap,
    Confirm,
    Reject,
    ToggleOn,
    ToggleOff,
    ;

    fun perform(view: View) {
        if (this == None || !LuminaUiPrefs.hapticsEnabled) return
        val constant = when (this) {
            None -> return
            Tick -> HapticFeedbackConstants.CLOCK_TICK
            Tap -> HapticFeedbackConstants.KEYBOARD_TAP
            Confirm -> if (Build.VERSION.SDK_INT >= 30) {
                HapticFeedbackConstants.CONFIRM
            } else {
                HapticFeedbackConstants.VIRTUAL_KEY
            }
            Reject -> if (Build.VERSION.SDK_INT >= 30) {
                HapticFeedbackConstants.REJECT
            } else {
                HapticFeedbackConstants.VIRTUAL_KEY
            }
            ToggleOn -> when {
                Build.VERSION.SDK_INT >= 34 -> HapticFeedbackConstants.TOGGLE_ON
                Build.VERSION.SDK_INT >= 30 -> HapticFeedbackConstants.CONFIRM
                else -> HapticFeedbackConstants.KEYBOARD_TAP
            }
            ToggleOff -> when {
                Build.VERSION.SDK_INT >= 34 -> HapticFeedbackConstants.TOGGLE_OFF
                Build.VERSION.SDK_INT >= 30 -> HapticFeedbackConstants.CONFIRM
                else -> HapticFeedbackConstants.KEYBOARD_TAP
            }
        }
        @Suppress("DEPRECATION")
        view.performHapticFeedback(constant)
    }
}
