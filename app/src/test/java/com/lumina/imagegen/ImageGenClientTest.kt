package com.lumina.imagegen

import okhttp3.MultipartBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ImageGenClientTest {

    private fun client(
        baseUrl: String,
        model: String = "glm-image",
        mode: ReferenceMode = ReferenceMode.AUTO,
    ) = ImageGenClient(
        baseUrl = baseUrl,
        apiKey = "test-key",
        model = model,
        size = "1056x1760",
        referenceMode = mode,
    )

    @Test
    fun detectsEditsEndpoint() {
        assertTrue(ImageGenProtocol.isEditEndpoint("https://api.openai.com/v1/images/edits"))
        assertTrue(ImageGenProtocol.isEditEndpoint("https://host/v1/images/edits/"))
        assertFalse(ImageGenProtocol.isEditEndpoint("https://open.bigmodel.cn/api/paas/v4/images/generations"))
        assertFalse(ImageGenProtocol.isEditEndpoint("https://open.bigmodel.cn/api/paas/v4"))
    }

    @Test
    fun endpointNormalizationNeverStacksSuffixes() {
        assertEquals(
            "https://host/v1/images/generations",
            ImageGenProtocol.endpoint("https://host/v1/images/edits", edit = false),
        )
        assertEquals(
            "https://host/v1/images/edits",
            ImageGenProtocol.endpoint("https://host/v1/images/generations", edit = true),
        )
        assertEquals(
            "https://host/v1/images/edits",
            ImageGenProtocol.endpoint("https://host/v1", edit = true),
        )
    }

    @Test
    fun autoOnlyUsesExplicitEditEndpoint() {
        assertTrue(ImageGenProtocol.shouldUseEdit("https://host/v1/images/edits", ReferenceMode.AUTO, 1))
        assertFalse(ImageGenProtocol.shouldUseEdit("https://host/v1/images/generations", ReferenceMode.AUTO, 1))
        assertFalse(ImageGenProtocol.shouldUseEdit("https://host/v1/images/generations", ReferenceMode.OFF, 1))
        assertTrue(ImageGenProtocol.shouldUseEdit("https://host/v1/images/generations", ReferenceMode.FORCE, 1))
        assertFalse(ImageGenProtocol.shouldUseEdit("https://host/v1/images/edits", ReferenceMode.FORCE, 0))
    }

    @Test
    fun textModeRewritesEditsUrlBackToGenerations() {
        val req = client("https://host/v1/images/edits").buildRequest("paper", emptyList())
        assertEquals("https://host/v1/images/generations", req.url.toString())
    }

    @Test
    fun forcedReferenceUsesMultipartWithSingleImageField() {
        val req = client("https://host/v1/images/generations", mode = ReferenceMode.FORCE)
            .buildRequest("paper", listOf(ByteArray(12)))
        assertEquals("https://host/v1/images/edits", req.url.toString())
        val body = req.body as MultipartBody
        assertEquals(listOf("model", "prompt", "size", "image"), body.parts.map { it.formName() })
    }

    @Test
    fun multipleReferencesUseOpenAiImageArrayField() {
        val req = client("https://host/v1/images/edits", mode = ReferenceMode.AUTO)
            .buildRequest("paper", listOf(ByteArray(12), ByteArray(13)))
        val body = req.body as MultipartBody
        assertEquals(2, body.parts.count { it.formName() == "image[]" })
        assertTrue(body.parts.none { it.formName() == "image" })
    }

    @Test
    fun parsesUrlAndBase64Responses() {
        val url = ImageGenResponse.parse(JSONObject().put("data", org.json.JSONArray().put(
            JSONObject().put("url", "https://cdn.example/a.jpg"),
        )).toString())
        assertTrue(url is ImageReply.Url)
        assertEquals("https://cdn.example/a.jpg", (url as ImageReply.Url).url)

        val b64 = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        val inline = ImageGenResponse.parse(JSONObject().put("data", org.json.JSONArray().put(
            JSONObject().put("b64_json", "data:image/jpeg;base64,$b64"),
        )).toString())
        assertNotNull(inline)
        assertTrue(inline is ImageReply.Bytes)
        assertEquals(3, (inline as ImageReply.Bytes).bytes.size)
    }

    @Test
    fun unknownShapeParsesToNull() {
        assertEquals(null, ImageGenResponse.parse("""{"data":[]}"""))
        assertEquals(null, ImageGenResponse.parse("not json"))
    }

    private fun MultipartBody.Part.formName(): String? =
        headers?.get("Content-Disposition")
            ?.substringAfter("name=\"", "")
            ?.substringBefore("\"")
}
