package com.lumina.work

/**
 * M3-02：内置预设 LUT（数学合成，免外部资产授权）。
 * 五个预设映射 M2 滤镜建议文本的关键词家族（标注文案出现"胶片暖/黑白/冷调/zine…"直接吃到）。
 * 每预设为 N=17 的 3D lattice；坐标 (r,g,b)→输出颜色经简单函数式变换生成。
 * 调用走显式命名参数（synth(xform = {...})）——Kotlin 编译器对三参尾随 lambda + 默认第二参曾给
 * 出诡异解析，显式写法彻底绕开（工单事实记录）。
 *
 * M3-07：补 name/category——滤镜台要把这 12 支当可列条目展示（此前只有关键词族，没有展示名）。
 * 下标即滤镜台 id（"builtin:<下标>"），**顺序不可随意重排**，否则已持久化的选择会错位。
 */
object LutPresets {

    const val DEFAULT_N = 17

    /** 预设定义（keys = 滤镜建议文案的命中关键词家族；name/category = 滤镜台展示用）。 */
    data class Preset(
        val name: String,
        val category: FilterCategory,
        val keys: List<String>,
        val lut: CubeLut,
    )

    private fun synth(xform: (Float, Float, Float) -> Triple<Float, Float, Float>, n: Int = DEFAULT_N): CubeLut {
        val data = FloatArray(n * n * n * 3)
        var o = 0
        fun clamp01(v: Float) = v.coerceIn(0f, 1f)
        for (b in 0 until n) {
            for (g in 0 until n) {
                for (r in 0 until n) {
                    val rf = r / (n - 1f)
                    val gf = g / (n - 1f)
                    val bf = b / (n - 1f)
                    val t = xform(rf, gf, bf)
                    data[o] = clamp01(t.first)
                    data[o + 1] = clamp01(t.second)
                    data[o + 2] = clamp01(t.third)
                    o += 3
                }
            }
        }
        return CubeLut(size = n, data = data)
    }

    private val presets: List<Preset> by lazy {
        listOf(
            // 黑白胶片：亮度映射 + 轻微银盐 S 曲线
            Preset(name = "黑白胶片", category = FilterCategory.MONO,
                keys = listOf("黑白", "bw", "black", "monochrome"), lut = synth(xform = { r: Float, g: Float, b: Float ->
                val l = 0.299f * r + 0.587f * g + 0.114f * b
                val s = l * l * (3f - 2f * l)
                val v = 0.15f * l + 0.85f * s
                Triple(v, v, v)
            })),
            // 胶片暖调：压蓝提红 + 小阶黑位抬升（对齐 M2 ColorMatrix 暖预设的观感延伸）
            Preset(name = "胶片暖", category = FilterCategory.FILM,
                keys = listOf("暖", "胶片暖", "film warm", "warm"), lut = synth(xform = { r: Float, g: Float, b: Float ->
                Triple(
                    (r * 1.08f + 0.03f),
                    (g * 1.02f + 0.015f),
                    (b * 0.88f + 0.03f),
                )
            })),
            // 冷调山雾：青调阴影 + 高光白灰（山雾 zine 家族底色）
            Preset(name = "冷调山雾", category = FilterCategory.LANDSCAPE,
                keys = listOf("冷调", "山雾", "冷", "mist", "cold"), lut = synth(xform = { r: Float, g: Float, b: Float ->
                val l = 0.3f * r + 0.59f * g + 0.11f * b
                Triple(
                    (l * 0.35f + r * 0.65f * 0.96f),
                    (l * 0.3f + g * 0.7f * 1.02f + 0.01f),
                    (l * 0.32f + b * 0.68f * 1.06f + 0.035f),
                )
            })),
            // 极简 zine：去饱和 + 双肩 S 对比（印刷对比感）
            Preset(name = "极简 zine", category = FilterCategory.CLASSIC,
                keys = listOf("zine", "极简", "海报"), lut = synth(xform = { r: Float, g: Float, b: Float ->
                val l = 0.299f * r + 0.587f * g + 0.114f * b
                val sat = 0.72f
                val rr = l + (r - l) * sat
                val gg = l + (g - l) * sat
                val bb = l + (b - l) * sat
                Triple(
                    rr * rr * (3f - 2f * rr),
                    gg * gg * (3f - 2f * gg),
                    bb * bb * (3f - 2f * bb),
                )
            })),
            // 秋雨暮色：橄榄压红 + 细灰花（徒步雨阴天）
            Preset(name = "秋雨暮色", category = FilterCategory.LANDSCAPE,
                keys = listOf("秋雨", "暮色", "阴天", "olive"), lut = synth(xform = { r: Float, g: Float, b: Float ->
                val l = 0.31f * r + 0.55f * g + 0.14f * b
                Triple(
                    (l * 0.42f + r * 0.58f * 0.98f),
                    (l * 0.3f + g * 0.7f * 1.05f + 0.02f),
                    (l * 0.34f + b * 0.66f * 0.94f),
                )
            })),
            // ── 以下 7 支 = 2026-11-09 扩容：公认胶片 stock 家族 ──
            // 波塔柔彩（Portra 400）：低反差暖粉调，黑位微抬，肤色奶油感
            Preset(name = "波塔柔彩", category = FilterCategory.FILM,
                keys = listOf("波塔", "portra", "柔彩", "人像胶片"), lut = synth(xform = { r: Float, g: Float, b: Float ->
                val l = 0.30f * r + 0.60f * g + 0.10f * b
                val sat = 0.88f
                val rr = 0.92f * (l + (r - l) * sat) + 0.025f
                val gg = 0.92f * (l + (g - l) * sat) + 0.02f
                val bb = 0.92f * (l + (b - l) * sat) * 0.985f + 0.035f
                Triple(rr, gg, bb)
            })),
            // 浓彩风光（Fuji Velvia）：高饱和高反差，风光浓艳
            Preset(name = "浓彩风光", category = FilterCategory.LANDSCAPE,
                keys = listOf("浓彩", "velvia", "浓艳", "风光胶片"), lut = synth(xform = { r: Float, g: Float, b: Float ->
                val l = 0.31f * r + 0.55f * g + 0.14f * b
                fun punch(c: Float): Float {
                    val s = l + (c - l) * 1.4f
                    return (s - 0.5f) * 1.18f + 0.5f
                }
                Triple(punch(r), punch(g), punch(b))
            })),
            // 经典褪色（Classic Chrome）：低饱和 + 阴影灰阶抬升 + 微冷高光
            Preset(name = "经典褪色", category = FilterCategory.CLASSIC,
                keys = listOf("褪色", "经典", "chrome", "faded"), lut = synth(xform = { r: Float, g: Float, b: Float ->
                val l = 0.299f * r + 0.587f * g + 0.114f * b
                val sat = 0.76f
                val rr = l + (r - l) * sat
                val gg = l + (g - l) * sat
                val bb = l + (b - l) * sat
                Triple(
                    rr * 0.94f + 0.055f,
                    gg * 0.94f + 0.055f,
                    bb * 0.93f + 0.065f,
                )
            })),
            // 银盐硬黑（Ilford HP5）：高反差黑白，狂压中间调
            Preset(name = "银盐硬黑", category = FilterCategory.MONO,
                keys = listOf("银盐", "hp5", "硬黑", "纪实黑白"), lut = synth(xform = { r: Float, g: Float, b: Float ->
                val l = 0.299f * r + 0.587f * g + 0.114f * b
                val v = ((l - 0.5f) * 1.28f + 0.5f).coerceIn(0f, 1f)
                Triple(v, v, v)
            })),
            // 柯达金黄（Kodak Gold 200）：暖金主调，黄昏草地向
            Preset(name = "柯达金黄", category = FilterCategory.FILM,
                keys = listOf("金黄", "kodak", "gold", "柯达"), lut = synth(xform = { r: Float, g: Float, b: Float ->
                val l = 0.30f * r + 0.58f * g + 0.12f * b
                val rr = l + (r * 1.10f + 0.035f - l) * 1.05f
                val gg = l + (g * 1.04f + 0.02f - l) * 1.05f
                val bb = l + (b * 0.80f + 0.015f - l) * 1.05f
                Triple(rr, gg, bb)
            })),
            // 青橙电影（CineStill 800T 系）：阴影喂青、高光喂橙，t 电影感
            Preset(name = "青橙电影", category = FilterCategory.FILM,
                keys = listOf("青橙", "电影", "cinestill", "夜色"), lut = synth(xform = { r: Float, g: Float, b: Float ->
                val l = 0.299f * r + 0.587f * g + 0.114f * b
                val shadow = (1f - l) * (1f - l)
                Triple(
                    r * 1.06f + 0.10f * l - 0.02f * shadow,
                    g + 0.015f,
                    b * 1.02f + 0.09f * shadow + 0.01f,
                )
            })),
            // 拍立得（Polaroid 600）：褪色 + 蓝青阴影 + 暖中调
            Preset(name = "拍立得", category = FilterCategory.FILM,
                keys = listOf("拍立得", "polaroid", "立得"), lut = synth(xform = { r: Float, g: Float, b: Float ->
                val l = 0.30f * r + 0.59f * g + 0.11f * b
                val sat = 0.82f
                val rr = 0.90f * (l + (r - l) * sat) + 0.055f + 0.02f * l
                val gg = 0.90f * (l + (g - l) * sat) + 0.045f
                val bb = 0.90f * (l + (b - l) * sat) + (0.06f - 0.01f * l)
                Triple(rr, gg, bb)
            })),
        )
    }

    val all: List<Preset> get() = presets

    /** 预设 → 下标（滤镜台 id 用的就是它）；不在内置集合里返回 -1。 */
    fun indexOf(preset: Preset): Int = presets.indexOfFirst { it === preset }

    /** 滤镜建议文本 → 命中预设（WorkRenderer GL 路线的选 LUT 入口）。 */
    fun pickFor(suggestText: String): Preset? {
        val t = suggestText.lowercase()
        return presets.firstOrNull { p -> p.keys.any { t.contains(it.lowercase()) } }
    }

    /** 卡名兜底绑定（2026-11-09 修复："滤镜建议"步 LLM 未产出文本时，卡名 → 家族的级联仍然出效果）。
     *  scenes-gathered-zine → 秋雨暮色；gc-minimal-zine-poster → 极简 zine；pixel-style-poster-skill → 浓彩风光。 */
    fun presetForCard(cardName: String): Preset? {
        val t = cardName.lowercase()
        val family = when {
            t.contains("scenes") || t.contains("聚束") -> "秋雨"
            t.contains("pixel") || t.contains("像素") -> "浓彩"
            t.contains("zine") || t.contains("海报") || t.contains("极简") -> "极简 zine"
            else -> null
        } ?: return null
        return presets.firstOrNull { p -> p.keys.any { k -> k.contains(family) } }
    }
}
