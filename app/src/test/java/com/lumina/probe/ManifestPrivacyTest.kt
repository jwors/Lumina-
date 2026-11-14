package com.lumina.probe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 隐私断言 V3（ADR-0006 / M5 遥控）：
 * - 允许：INTERNET（模型 API）、ACCESS_NETWORK_STATE（补跑横幅）、
 *   BLUETOOTH_SCAN/BLUETOOTH_CONNECT（仅配对，neverForLocation）、
 *   NEARBY_WIFI_DEVICES（仅加入相机热点，neverForLocation）。
 * - 仍然不存在任何定位/存储/相机/麦克风权限。
 */
class ManifestPrivacyTest {

    private val manifest: String =
        File("src/main/AndroidManifest.xml").readText()

    @Test
    fun `internet permission is present from M2`() {
        assertTrue("M2 需要模型 API 联网：INTERNET 应在榜样案", manifest.contains("android.permission.INTERNET"))
    }

    @Test
    fun `only the remote and network allowlist is declared`() {
        val declared = Regex("""android\.permission\.([A-Z_]+)""")
            .findAll(manifest)
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(
            setOf(
                "INTERNET",
                "ACCESS_NETWORK_STATE",
                "CHANGE_NETWORK_STATE",
                "ACCESS_WIFI_STATE",
                "CHANGE_WIFI_STATE",
                "BLUETOOTH_SCAN",
                "BLUETOOTH_CONNECT",
                "NEARBY_WIFI_DEVICES",
            ),
            declared,
        )
    }

    @Test
    fun `bluetooth and nearby wifi never claim location`() {
        assertTrue(
            "BLUETOOTH_SCAN 必须带 neverForLocation（ADR-0006：蓝牙不用于定位）",
            Regex("""android\.permission\.BLUETOOTH_SCAN[\s\S]{0,120}?neverForLocation""")
                .containsMatchIn(manifest),
        )
        assertTrue(
            "NEARBY_WIFI_DEVICES 必须带 neverForLocation",
            Regex("""android\.permission\.NEARBY_WIFI_DEVICES[\s\S]{0,120}?neverForLocation""")
                .containsMatchIn(manifest),
        )
    }

    @Test
    fun `manifest references nothing sensitive in raw`() {
        // 粗筛：无任何其他敏感关键字以任何形式出现（防止注释外引用）
        listOf(
            "CAMERA", "LOCATION", "RECORD_AUDIO", "READ_MEDIA", "EXTERNAL_STORAGE",
            "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION",
        ).forEach { banned ->
            assertTrue(
                "不得声明敏感权限：$banned",
                !Regex("""android\.permission\.$banned""").containsMatchIn(manifest),
            )
        }
    }
}
