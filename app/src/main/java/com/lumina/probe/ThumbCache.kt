package com.lumina.probe

/**
 * M3 工单 01：缩略位图缓存。
 * 图库网格墙 3 列、一次可见 ~12-15 个方格，解码目标 256px 级；
 * 双份 decode（MainActivity 内 decodeSampled/WorkThumbDecode）以及"每进页重算"的 M1 策略
 * 收敛到这里的一处真相。
 *
 * 缝隙纪律：缓存账记与淘汰策略是纯 Kotlin（LinkedHashMap 手写字节预算 LRU），
 * 可 JVM 直测；Android Bitmap 解码入口单独 StaticDecode，不进测试面。
 */
object ThumbCache {

    private val cache = object : LinkedHashMap<String, Any>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>): Boolean {
            return totalBytes > maxBudgetBytes && size > 1
        }
    }
    private var totalBytes: Long = 0

    /** 字节预算（~48MB 量级：3 列网格 + 查看器中屏图的余量）。 */
    var maxBudgetBytes: Long = 48L * 1024 * 1024

    /** 取（命中即视为最近使用，LRU 语义靠 LinkedHashMap accessOrder=true）。 */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: String): T? {
        synchronized(cache) { return cache[key] as T? }
    }

    /** 存（超预算即触发淘汰；单条超预算也要硬收下——否则查看器大图永远进不了账）。 */
    fun put(key: String, value: Any, sizeBytes: Long) {
        synchronized(cache) {
            if (cache.containsKey(key)) totalBytes -= sizeOfCacheKey(key)
            cache[key] = value
            unitSizes[key] = sizeBytes
            totalBytes += sizeBytes
            evictIfNeeded()
        }
    }

    fun remove(key: String) {
        synchronized(cache) {
            totalBytes -= sizeOfCacheKey(key)
            unitSizes.remove(key)
            cache.remove(key)
        }
    }

    fun clear() {
        synchronized(cache) {
            cache.clear()
            unitSizes.clear()
            totalBytes = 0
        }
    }

    fun sizeEntries(): Int = synchronized(cache) { cache.size }

    private val unitSizes = mutableMapOf<String, Long>()

    private fun sizeOfCacheKey(key: String): Long = unitSizes[key] ?: 0L

    private fun evictIfNeeded() {
        while (totalBytes > maxBudgetBytes && cache.size > 1) {
            // accessOrder=true 的 LinkedHashMap：eldest = 链头
            val eldest = cache.keys.firstOrNull() ?: break
            remove(eldest)
        }
    }
}

/**Android 解码的单一入口（设备侧专用；JVM 单测不进这里）。 */
object ThumbDecode {

    /** 通用降采样解码（对齐 M1 的 bounds-then-decode 两段式；缺图/非图返回 null）。 */
    fun sampled(path: String, target: Int): android.graphics.Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = maxOf(1, sample) }
        return android.graphics.BitmapFactory.decodeFile(path, opts)
    }
}
