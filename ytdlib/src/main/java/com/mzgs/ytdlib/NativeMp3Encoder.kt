package com.mzgs.ytdlib

import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer

internal class NativeMp3Encoder private constructor(
    private var handle: Long,
) : Closeable {

    fun encode(pcmData: ByteArray, length: Int) {
        require(length >= 0) { "length must be non-negative" }
        require(length <= pcmData.size) { "length exceeds PCM buffer size" }
        if (length == 0) {
            return
        }
        nativeEncode(handle, pcmData, length)
    }

    fun encode(pcmData: ByteBuffer, length: Int) {
        require(length >= 0) { "length must be non-negative" }
        require(pcmData.isDirect) { "pcmData must be a direct ByteBuffer" }
        require(length <= pcmData.remaining()) { "length exceeds PCM buffer size" }
        if (length == 0) {
            return
        }
        nativeEncodeDirect(handle, pcmData, length)
    }

    fun finish() {
        nativeFinish(handle)
    }

    override fun close() {
        if (handle == 0L) {
            return
        }
        nativeClose(handle)
        handle = 0L
    }

    companion object {
        init {
            System.loadLibrary(NATIVE_LIBRARY_NAME)
        }

        fun create(
            outputFile: File,
            sampleRate: Int,
            channelCount: Int,
            bitrateKbps: Int,
            lameQuality: Int,
        ): NativeMp3Encoder {
            val handle = nativeCreate(
                outputFile.absolutePath,
                sampleRate,
                channelCount,
                bitrateKbps,
                lameQuality,
            )
            return NativeMp3Encoder(handle)
        }

        @JvmStatic
        private external fun nativeCreate(
            outputPath: String,
            sampleRate: Int,
            channelCount: Int,
            bitrateKbps: Int,
            lameQuality: Int,
        ): Long

        @JvmStatic
        private external fun nativeEncode(
            handle: Long,
            pcmData: ByteArray,
            length: Int,
        )

        @JvmStatic
        private external fun nativeEncodeDirect(
            handle: Long,
            pcmData: ByteBuffer,
            length: Int,
        )

        @JvmStatic
        private external fun nativeFinish(handle: Long)

        @JvmStatic
        private external fun nativeClose(handle: Long)

        private const val NATIVE_LIBRARY_NAME = "ytdmp3"
    }
}
