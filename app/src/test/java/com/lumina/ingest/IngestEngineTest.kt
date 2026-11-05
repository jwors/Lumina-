package com.lumina.ingest

import com.lumina.master.InMemoryInventoryStore
import com.lumina.master.MasterStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 工单 02 验收：IngestEngine 缝隙（Fake 引擎 + MasterStore 真文件加链接）。
 * 协议事实： enqueue 去重 / drain 顺序回调 / 失败不阻塞 / 同名跳过 / 失败保留待重试。
 */
class IngestEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val store by lazy { MasterStore(tmp.root, InMemoryInventoryStore()) }

    private val capture = 1793404800000L // 2026-10-31T00:00Z

    private fun item(name: String, handle: Long = 100, size: Long = 1024) =
        IngestItem(handle = handle, name = name, sizeBytes = size, captureMs = capture, modifiedMs = null)

    /** 驱动一次 drain，收下全部回调（供顺序断言）。 */
    private class Recorder : IngestCallback {
        val events = mutableListOf<String>()
        override fun onFileStart(item: IngestItem) { events += "start:${item.name}" }
        override fun onFileSuccess(item: IngestItem, relPath: String) { events += "ok:${item.name}" }
        override fun onFileSkipped(item: IngestItem, relPath: String) { events += "skip:${item.name}" }
        override fun onFileFail(item: IngestItem, reason: String) { events += "fail:${item.name}($reason)" }
        override fun onBatchDone(success: Int, skipped: Int, fail: Int) { events += "done($success,$skipped,$fail)" }
    }

    @Test
    fun `success batch copies files to master layout and reports order`() {
        val engine = FakeIngestEngine(store)
        engine.enqueue(item("DSC_A.JPG", handle = 200))
        engine.enqueue(item("DSC_B.NEF", handle = 201))
        val rec = Recorder()
        engine.drain(rec)
        assertEquals(
            listOf("start:DSC_A.JPG", "ok:DSC_A.JPG", "start:DSC_B.NEF", "ok:DSC_B.NEF", "done(2,0,0)"),
            rec.events,
        )
        assertEquals(2, store.snapshot().size)
        assertEquals(2, engine.written().size)
        assertTrue(File(tmp.root, "Master/2026-10-31/DSC_A.JPG").exists())
    }

    @Test
    fun `scripted failure does not block the rest and stays pending`() {
        val engine = FakeIngestEngine(
            store,
            outcomes = mapOf("DSC_A.JPG" to FakeIngestEngine.Outcome.Fail("camera refused")),
        )
        engine.enqueue(item("DSC_A.JPG"))
        engine.enqueue(item("DSC_B.JPG"))
        val rec = Recorder()
        engine.drain(rec)
        assertEquals(
            listOf("start:DSC_A.JPG", "fail:DSC_A.JPG(camera refused)", "start:DSC_B.JPG", "ok:DSC_B.JPG", "done(1,0,1)"),
            rec.events,
        )
        assertEquals(listOf("DSC_A.JPG"), engine.pendingFails().map { it.name })
        // 失败文件不在库
        assertEquals(listOf("Master/2026-10-31/DSC_B.JPG"), store.snapshot().map { it.relPath })
    }

    @Test
    fun `enqueue is deduplicated by name`() {
        val engine = FakeIngestEngine(store)
        engine.enqueue(item("DSC_A.JPG", handle = 200))
        engine.enqueue(item("DSC_A.JPG", handle = 999)) // 第二句柄同名 = 无副作用
        assertEquals(1, engine.queueSize())
    }

    @Test
    fun `dismiss removes only the named item`() {
        val engine = FakeIngestEngine(store)
        engine.enqueue(item("A.JPG"))
        engine.enqueue(item("B.JPG"))
        engine.dismiss(item("A.JPG"))
        assertEquals(1, engine.queueSize())
        val rec = Recorder()
        engine.drain(rec)
        assertEquals(1, engine.written().size)
        assertEquals(listOf("start:B.JPG", "ok:B.JPG", "done(1,0,0)"), rec.events)
    }

    @Test
    fun `empty queue drains immediately with zeros`() {
        val engine = FakeIngestEngine(store)
        val rec = Recorder()
        engine.drain(rec)
        assertEquals(listOf("done(0,0,0)"), rec.events)
    }

    @Test
    fun `already-on-disk file is adopted without rewriting content`() {
        // 预置：文件已在（例如上一轮复制后 App 没登记）
        val pre = File(tmp.root, "Master/2026-10-31/DSC_A.JPG")
        pre.parentFile?.mkdirs()
        pre.writeBytes(byteArrayOf(0x7F, 0x50))
        val engine = FakeIngestEngine(store)
        engine.enqueue(item("DSC_A.JPG"))
        val rec = Recorder()
        engine.drain(rec)
        // 内容未被伪字节覆盖
        assertEquals(0x7F, pre.readBytes()[0].toInt())
        assertEquals(true, rec.events.contains("ok:DSC_A.JPG") || rec.events.contains("skip:DSC_A.JPG"))
        assertEquals(1, store.snapshot().size)
    }

    @Test
    fun `skip outcome reports skip count without store writes`() {
        val engine = FakeIngestEngine(
            store,
            outcomes = mapOf("A.JPG" to FakeIngestEngine.Outcome.Skip),
        )
        engine.enqueue(item("A.JPG"))
        val rec = Recorder()
        engine.drain(rec)
        assertEquals(listOf("start:A.JPG", "skip:A.JPG", "done(0,1,0)"), rec.events)
        assertEquals(0, store.snapshot().size)
        assertEquals(0, engine.written().size)
    }
}
