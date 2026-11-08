package com.lumina.work

/**
 * M3-02：.cube LUT 解析器（纯 Kotlin，JVM 可测——缝隙方法论延续）。
 *
 * v1 支持 LUT_3D（含可选 LUT_3D_INPUT_MIN/MAX）；LUT_1D 与异常档位显式拒收。
 * 数据序按 .cube 规范：B 最慢、G 中、R 最快（index = (b*N + g)*N + r）。
 * 结果值域收敛到 [0..1]（由 inputMin/Max 归一），渲染端不再关心 domain。
 */
class CubeLutException(val reason: String) : Exception(reason)

data class CubeLut(
    val size: Int,
    /** 长度 = size³×3，r 最快序；单通道值已归一到 [0..1] */
    val data: FloatArray,
) {
    /** 输出颜色采样（b,g,r 逻辑坐标，均 [0..N-1] 整数格）。 */
    fun at(b: Int, g: Int, r: Int): FloatArray {
        val idx = ((b * size + g) * size + r) * 3
        return floatArrayOf(data[idx], data[idx + 1], data[idx + 2])
    }

    override fun equals(other: Any?): Boolean =
        other is CubeLut && other.size == size && other.data.contentEquals(data)

    override fun hashCode(): Int = size * 31 + data.contentHashCode()
}

object CubeParser {

    private const val MAX_LATTICE = 256   // 规范上限（防炸内存）

    fun parse(text: String): CubeLut {
        var size = -1
        var inputMin = 0f
        var inputMax = 1f
        val values = ArrayList<Float>(64)

        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val upper = line.uppercase()
            when {
                upper.startsWith("TITLE") -> Unit               // 标题随手弃（v1 不存展示名）
                upper.startsWith("LUT_1D_SIZE") ->
                    throw CubeLutException("1D LUT 不支持（本管线只吃 3D 格位）")
                upper.startsWith("LUT_1D_INPUT_MIN") ->
                    throw CubeLutException("1D LUT 不支持（本管线只吃 3D 格位）")
                upper.startsWith("LUT_1D_INPUT_MAX") ->
                    throw CubeLutException("1D LUT 不支持（本管线只吃 3D 格位）")
                upper.startsWith("LUT_3D_SIZE") -> {
                    val v = upper.substringAfterLast(' ').trim().toFloatOrNull()
                        ?: throw CubeLutException("LUT_3D_SIZE 值不可解析: $line")
                    if (v < 2f || v > MAX_LATTICE) throw CubeLutException("LUT_3D_SIZE 越界（2..$MAX_LATTICE）: $line")
                    size = v.toInt()
                }
                upper.startsWith("LUT_3D_INPUT_MIN") -> {
                    val v = upper.substringAfterLast(' ').trim().toFloatOrNull()
                        ?: throw CubeLutException("INPUT_MIN 不可解析: $line")
                    inputMin = v
                }
                upper.startsWith("LUT_3D_INPUT_MAX") -> {
                    val v = upper.substringAfterLast(' ').trim().toFloatOrNull()
                        ?: throw CubeLutException("INPUT_MAX 不可解析: $line")
                    inputMax = v
                }
                upper.startsWith("NUMBER_OF_INPUT_TERMS") || upper.startsWith("LUT_3D_INPUT_COLORSPACE")
                    || upper.startsWith("NUMBER_OF_TABLE_POINTS") -> Unit   // 兼容头部，语义无关 v1
                else -> {
                    // 数据行：期望 1-4 列数值（1=单列 1V 表头变形仅限 LUT_1D，会拒；3/4 列容忍尾 alpha 列）
                    val parts = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
                    val nums = parts.map {
                        it.toFloatOrNull() ?: throw CubeLutException("数据行含非数值: $line")
                    }
                    when (nums.size) {
                        3 -> { values.add(nums[0]); values.add(nums[1]); values.add(nums[2]) }
                        4 -> { values.add(nums[0]); values.add(nums[1]); values.add(nums[2]) } // 尾列丢弃
                        else -> throw CubeLutException("数据行列数异常(${nums.size}): $line")
                    }
                }
            }
        }

        if (size < 2) throw CubeLutException("缺 LUT_3D_SIZE 头（或从未出现有效格位）")
        if (inputMin >= inputMax) throw CubeLutException("INPUT_MIN/MAX 无效域: [$inputMin..$inputMax]")
        val expected = size * size * size * 3
        if (values.size != expected) {
            throw CubeLutException("数据量不符：期望 $expected 值（$size³ 表位 ×3），实得 ${values.size}")
        }
        val span = inputMax - inputMin
        val data = FloatArray(expected)
        for (i in 0 until expected) {
            val v = values[i]
            if (v < inputMin - 1e-4f || v > inputMax + 1e-4f) {
                throw CubeLutException("值越域：index#$i 值 $v 不在 [$inputMin..$inputMax]")
            }
            data[i] = (v - inputMin) / span
        }
        return CubeLut(size = size, data = data)
    }
}
