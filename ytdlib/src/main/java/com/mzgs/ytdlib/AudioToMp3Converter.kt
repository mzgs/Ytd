package com.mzgs.ytdlib

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer

internal fun convertAudioFileToMp3(
    sourceFile: File,
    mp3File: File,
    bitrateKbps: Int,
    lameQuality: Int,
    progressCallback: ((Double) -> Unit)? = null,
): AudioConversionResult {
    val mimeType = runCatching { sourceFile.findAudioMimeType() }
        .getOrElse { throwable ->
            return AudioConversionResult(
                exitCode = -1,
                output = throwable.stackTraceToString(),
            )
        }
        ?: return AudioConversionResult(
            exitCode = -1,
            output = "No audio track found in ${sourceFile.name}",
        )

    val failures = mutableListOf<String>()
    val decoderNames = findDecoderNames(mimeType)
    var reportedProgress = 0.0

    for (decoderName in decoderNames) {
        mp3File.delete()
        val result = convertAudioFileToMp3WithDecoder(
            sourceFile = sourceFile,
            mp3File = mp3File,
            bitrateKbps = bitrateKbps,
            lameQuality = lameQuality,
            decoderName = decoderName,
            progressCallback = { progress ->
                if (progress > reportedProgress) {
                    reportedProgress = progress
                    progressCallback?.invoke(progress)
                }
            },
        )
        if (result.exitCode == 0) {
            return result
        }

        failures += "Decoder ${decoderName ?: "system default"} failed:\n${result.output}"
    }

    return AudioConversionResult(
        exitCode = -1,
        output = failures.joinToString(separator = "\n\n"),
    )
}

private fun convertAudioFileToMp3WithDecoder(
    sourceFile: File,
    mp3File: File,
    bitrateKbps: Int,
    lameQuality: Int,
    decoderName: String?,
    progressCallback: ((Double) -> Unit)?,
): AudioConversionResult {
    var extractor: MediaExtractor? = null
    var codec: MediaCodec? = null
    var writer: Pcm16LeMp3Writer? = null

    return try {
        extractor = MediaExtractor().apply {
            setDataSource(sourceFile.absolutePath)
        }

        val audioTrackIndex = extractor.findAudioTrackIndex()
            ?: return AudioConversionResult(
                exitCode = -1,
                output = "No audio track found in ${sourceFile.name}",
            )

        extractor.selectTrack(audioTrackIndex)
        val sourceFormat = extractor.getTrackFormat(audioTrackIndex)
        // MediaCodec implementations are allowed to choose float PCM unless the caller
        // requests an output encoding. The native LAME bridge consumes signed PCM16.
        sourceFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        val sourceDurationUs = sourceFormat.getLongOrNull(MediaFormat.KEY_DURATION)
            ?.takeIf { it > 0L }
        val mimeType = sourceFormat.getString(MediaFormat.KEY_MIME)
            ?: return AudioConversionResult(
                exitCode = -1,
                output = "Audio track MIME type could not be resolved for ${sourceFile.name}",
            )

        codec = (decoderName?.let(MediaCodec::createByCodecName)
            ?: MediaCodec.createDecoderByType(mimeType)).apply {
            configure(sourceFormat, null, null, 0)
            start()
        }

        val bufferInfo = MediaCodec.BufferInfo()
        var inputStreamEnded = false
        var outputStreamEnded = false
        var lastReportedProgress = -1.0

        while (!outputStreamEnded) {
            if (!inputStreamEnded) {
                val inputBufferIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                        ?: throw IllegalStateException("Decoder input buffer was null")
                    inputBuffer.clear()
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            0,
                            0L,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        inputStreamEnded = true
                    } else {
                        check(sampleSize <= inputBuffer.capacity()) {
                            "Compressed audio sample ($sampleSize bytes) exceeds decoder input " +
                                "capacity (${inputBuffer.capacity()} bytes)"
                        }
                        codec.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            sampleSize,
                            extractor.sampleTime,
                            extractor.sampleFlags.toCodecInputFlags(),
                        )
                        extractor.advance()
                    }
                }
            }

            when (val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    writer = writer ?: Pcm16LeMp3Writer.fromMediaFormat(
                        mediaFormat = codec.outputFormat,
                        mp3File = mp3File,
                        bitrateKbps = bitrateKbps,
                        lameQuality = lameQuality,
                    )
                }

                else -> {
                    if (outputBufferIndex >= 0) {
                        if (bufferInfo.size > 0) {
                            val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                                ?: throw IllegalStateException("Decoder output buffer was null")
                            val activeWriter = writer ?: Pcm16LeMp3Writer.fromMediaFormat(
                                mediaFormat = codec.outputFormat,
                                mp3File = mp3File,
                                bitrateKbps = bitrateKbps,
                                lameQuality = lameQuality,
                            ).also { writer = it }

                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            activeWriter.write(outputBuffer)

                            val durationUs = sourceDurationUs
                            if (durationUs != null && bufferInfo.presentationTimeUs >= 0L) {
                                val progressFraction = (bufferInfo.presentationTimeUs.toDouble() / durationUs.toDouble())
                                    .coerceIn(0.0, 1.0)

                                if (progressFraction >= lastReportedProgress + CONVERSION_PROGRESS_MIN_STEP) {
                                    progressCallback?.invoke(progressFraction)
                                    lastReportedProgress = progressFraction
                                }
                            }
                        }

                        codec.releaseOutputBuffer(outputBufferIndex, false)

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputStreamEnded = true
                        }
                    }
                }
            }
        }

        val activeWriter = writer
            ?: return AudioConversionResult(
                exitCode = -1,
                output = "Decoder did not produce PCM audio for ${sourceFile.name}",
            )

        activeWriter.finish()
        if (lastReportedProgress < 1.0) {
            progressCallback?.invoke(1.0)
        }

        if (!mp3File.exists() || mp3File.length() == 0L) {
            AudioConversionResult(
                exitCode = -1,
                output = "MP3 conversion produced an empty file for ${sourceFile.name}",
            )
        } else {
            AudioConversionResult(exitCode = 0, output = "")
        }
    } catch (throwable: Throwable) {
        mp3File.delete()
        AudioConversionResult(
            exitCode = -1,
            output = throwable.stackTraceToString(),
        )
    } finally {
        writer?.close()
        codec?.stopSafely()
        codec?.release()
        extractor?.release()
    }
}

private fun File.findAudioMimeType(): String? {
    val extractor = MediaExtractor()
    return try {
        extractor.setDataSource(absolutePath)
        extractor.findAudioTrackIndex()
            ?.let(extractor::getTrackFormat)
            ?.getString(MediaFormat.KEY_MIME)
    } finally {
        extractor.release()
    }
}

private fun findDecoderNames(mimeType: String): List<String?> {
    val decoderNames = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .asSequence()
            .filterNot { it.isEncoder }
            .filter { codecInfo ->
                codecInfo.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
            }
            .map { it.name }
            .distinct()
            .sortedByDescending(::isLikelySoftwareCodec)
            .toList()
    }.getOrDefault(emptyList())

    // The system-default path is retained as a final fallback in case a device does
    // not expose its preferred decoder through REGULAR_CODECS.
    return decoderNames + null
}

private fun isLikelySoftwareCodec(codecName: String): Boolean {
    return codecName.startsWith("c2.android.", ignoreCase = true) ||
        codecName.startsWith("OMX.google.", ignoreCase = true)
}

private fun Int.toCodecInputFlags(): Int {
    // MediaExtractor and MediaCodec flags are separate APIs. In particular,
    // SAMPLE_FLAG_ENCRYPTED has the same numeric value as BUFFER_FLAG_CODEC_CONFIG.
    return if ((this and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
        MediaCodec.BUFFER_FLAG_KEY_FRAME
    } else {
        0
    }
}

internal data class AudioConversionResult(
    val exitCode: Int,
    val output: String,
)

private class Pcm16LeMp3Writer private constructor(
    private val channelCount: Int,
    private val encoder: NativeMp3Encoder,
) : Closeable {
    private val frameSizeBytes = channelCount * PCM_16_BIT_SAMPLE_BYTES
    private var remainder = ByteArray(0)
    private var finished = false

    fun write(buffer: ByteBuffer) {
        if (!buffer.hasRemaining()) {
            return
        }

        if (remainder.isEmpty() && buffer.isDirect) {
            val writableSize = buffer.remaining() - (buffer.remaining() % frameSizeBytes)
            if (writableSize > 0) {
                val directChunk = buffer.slice().apply {
                    limit(writableSize)
                }
                encoder.encode(directChunk, writableSize)
                buffer.position(buffer.position() + writableSize)
            }

            if (!buffer.hasRemaining()) {
                return
            }

            remainder = ByteArray(buffer.remaining()).also(buffer::get)
            return
        }

        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        write(bytes)
    }

    fun finish() {
        if (finished) {
            return
        }
        if (remainder.isNotEmpty()) {
            throw IllegalStateException("Decoder returned an incomplete PCM frame")
        }
        encoder.finish()
        finished = true
    }

    override fun close() {
        encoder.close()
    }

    private fun write(chunk: ByteArray) {
        if (chunk.isEmpty()) {
            return
        }

        val combined = if (remainder.isEmpty()) {
            chunk
        } else {
            ByteArray(remainder.size + chunk.size).also { bytes ->
                remainder.copyInto(bytes, destinationOffset = 0)
                chunk.copyInto(bytes, destinationOffset = remainder.size)
            }
        }

        val writableSize = combined.size - (combined.size % frameSizeBytes)
        if (writableSize > 0) {
            encoder.encode(combined, writableSize)
        }

        remainder = if (writableSize < combined.size) {
            combined.copyOfRange(writableSize, combined.size)
        } else {
            ByteArray(0)
        }
    }

    companion object {
        fun fromMediaFormat(
            mediaFormat: MediaFormat,
            mp3File: File,
            bitrateKbps: Int,
            lameQuality: Int,
        ): Pcm16LeMp3Writer {
            val sampleRate = mediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = mediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val pcmEncoding = if (mediaFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                mediaFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }

            require(channelCount in 1..2) {
                "Unsupported PCM channel count: $channelCount"
            }
            require(pcmEncoding == AudioFormat.ENCODING_PCM_16BIT) {
                "Unsupported PCM encoding: $pcmEncoding"
            }

            return Pcm16LeMp3Writer(
                channelCount = channelCount,
                encoder = NativeMp3Encoder.create(
                    outputFile = mp3File,
                    sampleRate = sampleRate,
                    channelCount = channelCount,
                    bitrateKbps = bitrateKbps,
                    lameQuality = lameQuality,
                ),
            )
        }
    }
}

private fun MediaExtractor.findAudioTrackIndex(): Int? {
    for (trackIndex in 0 until trackCount) {
        val mimeType = getTrackFormat(trackIndex).getString(MediaFormat.KEY_MIME) ?: continue
        if (mimeType.startsWith("audio/")) {
            return trackIndex
        }
    }
    return null
}

private fun MediaCodec.stopSafely() {
    runCatching { stop() }
}

private fun MediaFormat.getLongOrNull(name: String): Long? {
    if (!containsKey(name)) {
        return null
    }

    return runCatching { getLong(name) }.getOrNull()
}

private const val CODEC_TIMEOUT_US = 10_000L
private const val CONVERSION_PROGRESS_MIN_STEP = 0.01
private const val PCM_16_BIT_SAMPLE_BYTES = 2
