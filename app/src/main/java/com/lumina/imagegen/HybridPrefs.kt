package com.lumina.imagegen

import android.content.Context

/** 生图模式偏好：hybrid=true 时 AI 只出纸面底图，照片本地合成（不做图生图）。 */
object HybridPrefs {
    private const val FILE = "lumina_prefs"
    private const val KEY_HYBRID = "imagegen.hybrid"

    fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_HYBRID, false)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HYBRID, enabled).apply()
    }
}
