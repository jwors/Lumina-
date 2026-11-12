package com.lumina.imagegen

import android.content.Context

/**
 * 生图偏好（SharedPreferences，避免为单个开关动 Room 迁移）。
 * watermarkEnabled=true 时请求体带 watermark_enabled=true（智谱默认加「AI生成」水印）。
 */
object ImageGenPrefs {
    private const val FILE = "lumina_prefs"
    private const val KEY_WATERMARK = "imagegen.watermark_enabled"

    fun watermarkEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_WATERMARK, true)

    fun setWatermarkEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_WATERMARK, enabled).apply()
    }
}
