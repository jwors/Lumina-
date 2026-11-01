package com.lumina.probe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * M0 spec 的隐私断言自动化版（Spec 轴审查 finding a2）：
 * 探针 App 无任何联网能力，AndroidManifest 不得声明 INTERNET 权限。
 */
class ManifestPrivacyTest {

    @Test
    fun `manifest declares no internet permission`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("android.permission.INTERNET"))
        assertFalse(manifest.contains("<uses-permission"))
    }
}
