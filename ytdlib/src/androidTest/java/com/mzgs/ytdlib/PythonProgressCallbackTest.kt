package com.mzgs.ytdlib

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PythonProgressCallbackTest {
    @Test
    fun pythonProgressHookReachesKotlinListener() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        YtDlp.init(context)
        val python = Python.getInstance()
        val bridge = python.getModule("ytd_bridge")
        val logger = bridge.callAttr("_CollectorLogger")
        val progress = python.getModule("json").callAttr(
            "loads",
            """{"status":"downloading","downloaded_bytes":512,"total_bytes":1024,"filename":"test.m4a"}""",
        )
        var received: YtDlpProgress? = null
        val callback = YtDlpProgressCallback { received = it }

        // Exercise Python's name-based lookup, not a direct Kotlin method call.
        bridge.callAttr("_notify_progress", callback, logger, progress)

        assertNotNull("Python did not deliver the progress callback", received)
        val result = requireNotNull(received)
        assertEquals("downloading", result.status)
        assertEquals(512L, result.downloadedBytes)
        assertEquals(1024L, result.totalBytes)
        assertEquals(0.5, requireNotNull(result.progressFraction), 0.0)
        assertEquals("test.m4a", result.filename)
    }
}
