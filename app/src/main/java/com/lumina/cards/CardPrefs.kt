package com.lumina.cards

import android.content.Context

/**
 * 配方库的轻量本地状态（M2-07）：内置卡删除墓碑。
 *
 * 内置卡在 `loadCards` 里按名字「缺哪补哪」，所以直接删行会被下次盘点补回来。
 * 这里按名字记墓碑，播种时跳过；重装/清应用数据后才会恢复。
 */
object CardPrefs {

    private const val FILE = "lumina_prefs"
    private const val KEY_DELETED_BUILTINS = "cards.deleted_builtin_names"

    fun deletedBuiltIns(ctx: Context): Set<String> =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getStringSet(KEY_DELETED_BUILTINS, emptySet())
            ?.toSet()      // SharedPreferences 返回的是内部集合：必须拷贝，禁止原地改
            ?: emptySet()

    fun markBuiltInDeleted(ctx: Context, name: String) {
        val next = deletedBuiltIns(ctx).toMutableSet().apply { add(name) }
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_DELETED_BUILTINS, next)
            .apply()
    }
}
