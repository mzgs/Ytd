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
    fun downloadRetryDefaultsAndCallerOverridesArePreserved() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        YtDlp.init(context)
        val python = Python.getInstance()
        val bridge = python.getModule("ytd_bridge")
        val json = python.getModule("json")

        val defaults = json.callAttr("loads", "{}")
        bridge.callAttr("_configure_download_options", defaults)
        val defaultOptions = org.json.JSONObject(json.callAttr("dumps", defaults).toString())
        assertEquals(0, defaultOptions.getInt("retries"))
        assertEquals(2, defaultOptions.getInt("fragment_retries"))

        val overrides = json.callAttr("loads", """{"retries":1,"fragment_retries":0}""")
        bridge.callAttr("_configure_download_options", overrides)
        val overrideOptions = org.json.JSONObject(json.callAttr("dumps", overrides).toString())
        assertEquals(1, overrideOptions.getInt("retries"))
        assertEquals(0, overrideOptions.getInt("fragment_retries"))
    }

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
