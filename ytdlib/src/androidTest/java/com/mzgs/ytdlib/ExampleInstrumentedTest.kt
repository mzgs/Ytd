package com.mzgs.ytdlib

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

import org.junit.Assert.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.mzgs.ytdlib.test", appContext.packageName)
    }

    @Test
    fun bundledQuickJsIsAvailableToYtDlp() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val executor = Executors.newSingleThreadExecutor()

        try {
            val diagnostics = executor.submit<JSONObject> {
                YtDlp.getDiagnostics(appContext)
            }.get(60, TimeUnit.SECONDS)

            assertTrue(diagnostics.optBoolean("yt_dlp_ejs_import_ok"))
            assertTrue(diagnostics.optBoolean("quickjs_exists"))
            assertTrue(diagnostics.optBoolean("quickjs_executable"))
            assertTrue(diagnostics.getJSONObject("quickjs_runtime").optBoolean("supported"))
        } finally {
            executor.shutdownNow()
        }
    }
}
