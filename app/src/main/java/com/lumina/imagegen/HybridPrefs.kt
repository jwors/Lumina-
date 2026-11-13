package com.lumina.imagegen

import android.content.Context
import com.lumina.work.HybridComposition

/** 生图模式偏好：hybrid=true 时 AI 只出纸面底图，照片本地合成（不做图生图）。 */
object HybridPrefs {
    private const val FILE = "lumina_prefs"
    private const val KEY_HYBRID = "imagegen.hybrid"
    private const val KEY_COMPOSITION = "imagegen.hybrid.composition"

    fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_HYBRID, false)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HYBRID, enabled).apply()
    }

    /** 默认 AUTO：整卷按页序左右交替，单张时色场在左、照片靠右（沿用旧版观感）。 */
    fun composition(ctx: Context): HybridComposition {
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_COMPOSITION, null)
        return runCatching { HybridComposition.valueOf(raw.orEmpty()) }
            .getOrDefault(HybridComposition.AUTO)
    }

    fun setComposition(ctx: Context, composition: HybridComposition) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_COMPOSITION, composition.name).apply()
    }
}
