package com.mzgs.ytdlib

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Mp3OutputTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun bufferedOutputFailureIsReportedAtFinish() {
        for (direct in listOf(false, true)) {
            val pipe = ParcelFileDescriptor.createPipe()
            pipe[0].use { reader ->
                pipe[1].use { writer ->
                    NativeMp3Encoder.create(File("/proc/self/fd/${writer.fd}"), 44100, 1, 128, 2).use { encoder ->
                        // Writes to a pipe without a reader fail when the buffered output is flushed.
                        reader.close()
                        val pcm = ByteArray(44100 * 2)
                        if (direct) {
                            val buffer = ByteBuffer.allocateDirect(pcm.size).apply { put(pcm); flip() }
                            encoder.encode(buffer, pcm.size)
                        } else {
                            encoder.encode(pcm, pcm.size)
                        }
                        val error = outputError { encoder.finish() }
                        assertTrue(error.message.orEmpty().contains("Failed to flush MP3 output file"))
                        assertTrue(error.message.orEmpty().contains("[errno 32]"))
                        assertTrue(error.message.orEmpty().contains("Broken pipe"))
                    }
                }
            }
        }
    }

    @Test
    fun validOutputFinishesSuccessfully() {
        val output = File.createTempFile("encoder-", ".mp3", context.cacheDir)
        try {
            NativeMp3Encoder.create(output, 44100, 1, 128, 2).use { encoder ->
                encoder.encode(ByteArray(44100 * 2), 44100 * 2)
                encoder.finish()
                encoder.finish()
            }
            assertTrue(output.length() > 0)
        } finally {
            output.delete()
        }
    }

    @Test
    fun conversionStopsOnOutputFailureAndPreservesSource() {
        val source = File.createTempFile("audio-", ".m4a", context.cacheDir)
        val output = File(File(source.parentFile, "missing-${System.nanoTime()}"), "audio.mp3")
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("tone.m4a").use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            val result = convertAudioFileToMp3(source, output, 128, 2)
            assertEquals(-1, result.exitCode)
            assertTrue(result.cause is Mp3OutputException)
            assertTrue(result.output.contains("[errno 2]"))
            assertTrue(result.output.contains("No such file or directory"))
            assertFalse("Output failure retried as a decoder failure", result.output.contains("Decoder "))
            assertTrue(source.exists())
            assertFalse(output.exists())
        } finally {
            source.delete()
        }
    }

    private fun outputError(block: () -> Unit): Mp3OutputException {
        try {
            block()
        } catch (error: Mp3OutputException) {
            return error
        }
        throw AssertionError("Expected an MP3 output failure")
    }
}
