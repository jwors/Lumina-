package com.lumina.ui.feedback

import android.animation.ValueAnimator
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 触感/动效的全局偏好。
 *
 * 为什么是单例而不是 CompositionLocal：
 * - 反馈发生在点击回调里（非 Composable 作用域），要能同步读到最新开关；
 * - 设置页需要观察值变化来重绘开关，因此字段用 Compose state。
 *
 * 默认值对齐系统：触感开、动效开；真正的"减弱动效"还叠加系统 Animator duration scale
 * （开发者选项/无障碍里把动画调到 0 时，[animationsEnabled] 也会返回 false）。
 */
object LuminaUiPrefs {
    private const val FILE = "lumina_ui_feedback"
    private const val KEY_HAPTICS = "haptics_enabled"
    private const val KEY_REDUCED_MOTION = "reduced_motion"

    private var prefs: SharedPreferences? = null

    var hapticsEnabled by mutableStateOf(true)
        private set

    var reducedMotion by mutableStateOf(false)
        private set

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        hapticsEnabled = p.getBoolean(KEY_HAPTICS, true)
        reducedMotion = p.getBoolean(KEY_REDUCED_MOTION, false)
    }

    fun updateHaptics(enabled: Boolean) {
        hapticsEnabled = enabled
        prefs?.edit()?.putBoolean(KEY_HAPTICS, enabled)?.apply()
    }

    fun updateReducedMotion(enabled: Boolean) {
        reducedMotion = enabled
        prefs?.edit()?.putBoolean(KEY_REDUCED_MOTION, enabled)?.apply()
    }

    /** 任一路径要求减弱动效时都不播动画；系统动画时长 0 也尊从。 */
    fun animationsEnabled(): Boolean =
        !reducedMotion && ValueAnimator.areAnimatorsEnabled()
}
