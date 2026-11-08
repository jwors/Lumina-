package com.lumina.probe

import java.io.File
import java.util.regex.Pattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0005（M2）：隐私断言 V2——
 * 允许唯一 INTERNET 权限；**任何其他危险权限都是失败**（定位/媒体/相机/麦克风/存储…）。
 * 用户素材走应用私有目录 + SAF，系统相册读权限永远不需要。
 */
class ManifestPrivacyTest {

    private val manifest: String =
        File("src/main/AndroidManifest.xml").readText()

    @Test
    fun `internet permission is present from M2`() {
        assertTrue("M2 需要模型 API 联网：INTERNET 应在榜样案", manifest.contains("android.permission.INTERNET"))
    }

    @Test
    fun `no other permissions beyond internet`() {
        val declared = Regex("""android\.permission\.([A-Z_]+)""")
            .findAll(manifest)
            .map { it.groupValues[1] }
            .toSet()
        assertEquals("清单必须只有 INTERNET 一个权限", setOf("INTERNET"), declared)
    }

    @Test
    fun `manifest references nothing in raw`() {
        // 粗筛：无任何其他敏感关键字以任何形式出现（防止注释外引用）
        listOf("CAMERA", "LOCATION", "RECORD_AUDIO", "READ_MEDIA", "EXTERNAL_STORAGE").forEach { banned ->
            assertTrue(
                "不得声明敏感权限：$banned",
                !Regex("""android\.permission\.$banned""").containsMatchIn(manifest),
            )
        }
    }
}
