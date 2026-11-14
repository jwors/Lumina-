package com.lumina.work

import jp.co.cyberagent.android.gpuimage.filter.GPUImageTwoInputFilter

/**
 * M3-02：3D LUT 三线性插值 shader（GPUImage two-input 槽位：0=原图 1=图集）。
 * 图集契约见 LutAtlas：x = g*N + r，y = b；坐标自原图 rgb 演算，8 邻域三线性。
 * 设备侧专用（GL 依赖不可 JVM 测；几何/数值逻辑全部在 LutAtlas/CubeParser 层先行验证）。
 *
 * M3-07：strength = 滤镜强度（0..1），输出与原图线性混合——"强度"是滤镜的参数（见 GLOSSARY）。
 */
class Lut3DFilter(
    lut: CubeLut,
    strength: Float = STRENGTH_FULL,
) : GPUImageTwoInputFilter(FRAGMENT_SHADER) {

    companion object {
        private val FRAGMENT_SHADER = """
precision mediump float;
// GPUImage 基类顶点（GPUImageFilter.VERTEX_SHADER）输出的 varying 名就叫 textureCoordinate——
// 之前误用 vTextureCoord 导致原图采样恒 0（黑屏成片根因）。
varying highp vec2 textureCoordinate;
uniform sampler2D inputImageTexture;
uniform sampler2D inputImageTexture2;
uniform float uLutSize;
uniform float uStrength;

vec3 sampleLut(vec3 base) {
    float n = uLutSize;
    float cell = 1.0 / (n * n);
    float rowH = 1.0 / n;
    float x = (base.y * n + base.x + 0.5) * cell;
    float y = (base.z + 0.5) * rowH;
    return texture2D(inputImageTexture2, vec2(x, y)).rgb;
}

void main() {
    vec3 c = texture2D(inputImageTexture, textureCoordinate).rgb;
    float n = uLutSize;
    vec3 coord = clamp(c, 0.0, 1.0) * (n - 1.0);
    vec3 bo = floor(coord);
    vec3 fr = coord - bo;
    vec3 hi = min(bo + 1.0, vec3(n - 1.0));

    vec3 lowG = mix(sampleLut(vec3(bo.x, bo.y, bo.z)), sampleLut(vec3(hi.x, bo.y, bo.z)), fr.x);
    vec3 highG = mix(sampleLut(vec3(bo.x, hi.y, bo.z)), sampleLut(vec3(hi.x, hi.y, bo.z)), fr.x);
    vec3 lowB = mix(lowG, highG, fr.y);

    vec3 lowG2 = mix(sampleLut(vec3(bo.x, bo.y, hi.z)), sampleLut(vec3(hi.x, bo.y, hi.z)), fr.x);
    vec3 highG2 = mix(sampleLut(vec3(bo.x, hi.y, hi.z)), sampleLut(vec3(hi.x, hi.y, hi.z)), fr.x);
    vec3 highB = mix(lowG2, highG2, fr.y);

    vec3 outColor = mix(lowB, highB, fr.z);
    // 强度：0 = 原图，1 = 全强度 LUT（截图的滑杆就映射到这里）
    gl_FragColor = vec4(mix(c, outColor, uStrength), 1.0);
}
""".trimIndent()

        /** 全强度（默认，与 M3-02 行为等价）。 */
        const val STRENGTH_FULL = 1f
    }

    private val lutSize: Float
    private val strength: Float = strength.coerceIn(0f, 1f)

    init {
        lutSize = lut.size.toFloat()
        setBitmap(LutGpu.atlasBitmap(lut))
    }

    override fun onInitialized() {
        super.onInitialized()
        // GPUImage 2.1.0 无名字 uniform 便捷 API：惯例 = 自取位置（基类 getProgram() 公共）
        val loc = android.opengl.GLES20.glGetUniformLocation(getProgram(), "uLutSize")
        if (loc >= 0) setFloat(loc, lutSize)
        val locS = android.opengl.GLES20.glGetUniformLocation(getProgram(), "uStrength")
        if (locS >= 0) setFloat(locS, strength)
    }
}
