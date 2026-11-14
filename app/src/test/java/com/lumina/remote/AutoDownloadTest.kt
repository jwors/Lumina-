package com.lumina.remote

import com.lumina.ptp.ObjectFormat
import com.lumina.ptp.ObjectInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** M6-04：拍后回传的 JPEG 选择（跳过 NEF/目录，取最新 handle）。 */
class AutoDownloadTest {

    private fun info(name: String, format: Int = ObjectFormat.EXIF_JPEG, folder: Boolean = false) =
        ObjectInfo(
            storageId = 1,
            format = format,
            sizeBytes = 1_000,
            parentObject = 0,
            associationType = if (folder) 0x0001 else 0,
            fileName = name,
            captureDateRaw = "",
            modificationDateRaw = "",
        )

    @Test
    fun `picks the newest jpeg by handle`() {
        val pick = AutoDownload.pickNewestJpeg(
            listOf(
                ObjectCandidate(10, info("DSC_0010.JPG")),
                ObjectCandidate(12, info("DSC_0012.JPG")),
                ObjectCandidate(11, info("DSC_0011.JPG")),
            ),
        )
        assertEquals(12L, pick?.handle)
    }

    @Test
    fun `ignores non jpeg and folders`() {
        val pick = AutoDownload.pickNewestJpeg(
            listOf(
                ObjectCandidate(20, info("DSC_0020.NEF", format = 0x0000)),
                ObjectCandidate(21, info("100NZ30", folder = true)),
                ObjectCandidate(19, info("DSC_0019.JPG")),
            ),
        )
        assertEquals(19L, pick?.handle)
    }

    @Test
    fun `returns null when there is no new jpeg`() {
        assertNull(AutoDownload.pickNewestJpeg(emptyList()))
        assertNull(AutoDownload.pickNewestJpeg(listOf(ObjectCandidate(1, info("A.NEF", format = 0x0000)))))
    }
}
