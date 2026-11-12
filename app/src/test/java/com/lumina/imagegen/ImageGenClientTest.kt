package com.lumina.imagegen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenClientTest {

    @Test
    fun detectsEditsEndpoint() {
        assertTrue(ImageGenClient.isEditEndpoint("https://api.openai.com/v1/images/edits"))
        assertTrue(ImageGenClient.isEditEndpoint("https://host/v1/images/edits/"))
        assertFalse(ImageGenClient.isEditEndpoint("https://open.bigmodel.cn/api/paas/v4/images/generations"))
        assertFalse(ImageGenClient.isEditEndpoint("https://open.bigmodel.cn/api/paas/v4"))
    }
}
