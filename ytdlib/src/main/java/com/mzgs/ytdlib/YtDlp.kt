package com.mzgs.ytdlib

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object YtDlp {

    @Volatile
    private var isInitialized = false

    fun getVersion(context: Context): String {
        return module(context).callAttr("get_version").toString()
    }

    @Throws(YtDlpException::class)
    fun extractInfo(
        context: Context,
        url: String,
        options: Map<String, Any?> = emptyMap(),
    ): YtDlpResult {
        return run(
            context = context,
            request = YtDlpRequest(
                url = url,
                download = false,
                options = options,
            ),
        )
    }

    @Throws(YtDlpException::class)
    fun download(
        context: Context,
        url: String,
        options: Map<String, Any?> = emptyMap(),
        progressListener: YtDlpProgressListener? = null,
    ): YtDlpResult {
        return run(
            context = context,
            request = YtDlpRequest(
                url = url,
                download = true,
                options = options,
            ),
            progressListener = progressListener,
        )
    }

    @Throws(YtDlpException::class)
    fun downloadAudio(
        context: Context,
        url: String,
        options: Map<String, Any?> = emptyMap(),
        progressListener: YtDlpProgressListener? = null,
        bitrateKbps: Int = 128,
        lameQuality: Int = DEFAULT_MP3_LAME_QUALITY,
        downloadFormat: String? = null,
        deleteSourceFile: Boolean = true,
        conversionMode: AudioConversionMode = AudioConversionMode.ONLY_CONVERT_WHEN_NOT_M4A,
    ): YtDlpResult {
        val resolvedBitrateKbps = bitrateKbps.coerceIn(64, 320)
        val resolvedLameQuality = lameQuality.coerceIn(MIN_LAME_QUALITY, MAX_LAME_QUALITY)
        val resolvedDownloadFormat = downloadFormat
            ?.takeIf(String::isNotBlank)
            ?: (options[FORMAT_OPTION_KEY] as? String)?.takeIf(String::isNotBlank)
            ?: DEFAULT_MP3_DOWNLOAD_FORMAT

        val downloadOptions = options.toMutableMap().apply {
            put(FORMAT_OPTION_KEY, resolvedDownloadFormat)
        }
        val audioDownloadProgressListener = progressListener?.asAudioDownloadProgressListener(conversionMode)

        val downloadResult = download(
            context = context,
            url = url,
            options = downloadOptions,
            progressListener = audioDownloadProgressListener,
        )

        val sourceFile = downloadResult.payload
            ?.findDownloadedFilePath()
            ?.let(::File)
            ?.absoluteFile
            ?.takeIf(File::exists)
            ?: throw YtDlpException(
                pythonType = "AudioConversion",
                message = "Downloaded audio file path could not be resolved",
                traceback = null,
                logs = downloadResult.logs,
            )

        val mp3File = if (sourceFile.extension.equals(MP3_EXTENSION, ignoreCase = true)) {
            sourceFile
        } else {
            File(sourceFile.parentFile, "${sourceFile.nameWithoutExtension}.$MP3_EXTENSION")
        }

        val shouldConvertToMp3 = shouldConvertToMp3(
            sourceFile = sourceFile,
            mp3File = mp3File,
            conversionMode = conversionMode,
        )

        val outputFile = if (shouldConvertToMp3) mp3File else sourceFile
        val outputIsMp3 = outputFile.extension.equals(MP3_EXTENSION, ignoreCase = true)

        if (shouldConvertToMp3) {
            progressListener?.onProgress(
                YtDlpProgress(
                    status = "converting",
                    downloadedBytes = null,
                    totalBytes = null,
                    totalBytesEstimate = null,
                    progressFraction = AUDIO_DOWNLOAD_PROGRESS_WEIGHT,
                    speedBytesPerSecond = null,
                    etaSeconds = null,
                    filename = outputFile.absolutePath,
                ),
            )

            val commandResult = convertAudioFileToMp3(
                sourceFile = sourceFile,
                mp3File = outputFile,
                bitrateKbps = resolvedBitrateKbps,
                lameQuality = resolvedLameQuality,
                progressCallback = { conversionProgress ->
                    progressListener?.onProgress(
                        YtDlpProgress(
                            status = "converting",
                            downloadedBytes = null,
                            totalBytes = null,
                            totalBytesEstimate = null,
                            progressFraction = AUDIO_DOWNLOAD_PROGRESS_WEIGHT +
                                (conversionProgress.coerceIn(0.0, 1.0) * AUDIO_CONVERSION_PROGRESS_WEIGHT),
                            speedBytesPerSecond = null,
                            etaSeconds = null,
                            filename = outputFile.absolutePath,
                        ),
                    )
                },
            )

            if (commandResult.exitCode != 0) {
                throw YtDlpException(
                    pythonType = "AudioConversion",
                    message = "Audio conversion failed to convert ${sourceFile.name} to MP3",
                    traceback = null,
                    logs = downloadResult.logs + YtDlpLogEntry(
                        level = "error",
                        message = commandResult.output.ifBlank { "MP3 conversion failed" },
                    ),
                )
            }

            if (deleteSourceFile && sourceFile != outputFile) {
                sourceFile.delete()
            }
        }

        progressListener?.onProgress(
            YtDlpProgress(
                status = "finished",
                downloadedBytes = outputFile.length().takeIf { it > 0L },
                totalBytes = outputFile.length().takeIf { it > 0L },
                totalBytesEstimate = outputFile.length().takeIf { it > 0L },
                progressFraction = 1.0,
                speedBytesPerSecond = null,
                etaSeconds = null,
                filename = outputFile.absolutePath,
            ),
        )

        return downloadResult.withPayloadFields(
            "mp3_filepath" to outputFile.takeIf { outputIsMp3 }?.absolutePath,
            "mp3_bitrate_kbps" to resolvedBitrateKbps.takeIf { shouldConvertToMp3 },
            "mp3_lame_quality" to resolvedLameQuality.takeIf { shouldConvertToMp3 },
            "download_format" to resolvedDownloadFormat,
            "audio_filepath" to outputFile.absolutePath,
            "audio_extension" to outputFile.extension.lowercase(),
            "converted_to_mp3" to shouldConvertToMp3,
            "output_is_mp3" to outputIsMp3,
            "audio_conversion_mode" to conversionMode.name,
            "source_filepath" to sourceFile.absolutePath,
        )
    }

    @Throws(YtDlpException::class)
    fun run(
        context: Context,
        request: YtDlpRequest,
        progressListener: YtDlpProgressListener? = null,
    ): YtDlpResult {
        val responseJson = if (progressListener == null) {
            module(context)
                .callAttr("run", request.toJson().toString())
                .toString()
        } else {
            module(context)
                .callAttr(
                    "run",
                    request.toJson().toString(),
                    YtDlpProgressCallback(progressListener),
                )
                .toString()
        }
        val response = JSONObject(responseJson)
        val logs = response.optJSONArray("logs").toLogEntries()

        if (!response.optBoolean("ok")) {
            val error = response.getJSONObject("error")
            throw YtDlpException(
                pythonType = error.optString("type", "RuntimeError"),
                message = error.optString("message", "yt-dlp failed"),
                traceback = error.optString("traceback").ifBlank { null },
                logs = logs,
            )
        }

        return YtDlpResult(
            payload = response.optJSONObject("result"),
            logs = logs,
            download = response.optBoolean("download"),
        )
    }

    private fun module(context: Context) = run {
        ensureStarted(context)
        Python.getInstance().getModule(MODULE_NAME)
    }

    private fun ensureStarted(context: Context) {
        if (isInitialized) {
            return
        }

        synchronized(this) {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context.applicationContext))
            }
            isInitialized = true
        }
    }

    private fun JSONArray?.toLogEntries(): List<YtDlpLogEntry> {
        if (this == null) {
            return emptyList()
        }

        return buildList(length()) {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                add(
                    YtDlpLogEntry(
                        level = item.optString("level", "info"),
                        message = item.optString("message"),
                    ),
                )
            }
        }
    }

    private fun YtDlpProgressListener.asAudioDownloadProgressListener(
        conversionMode: AudioConversionMode,
    ): YtDlpProgressListener {
        var shouldReserveConversionProgress = when (conversionMode) {
            AudioConversionMode.ALWAYS_CONVERT_MP3 -> true
            AudioConversionMode.DO_NOT_CONVERT -> false
            AudioConversionMode.ONLY_CONVERT_WHEN_NOT_M4A -> null
        }

        return YtDlpProgressListener { progress ->
            if (shouldReserveConversionProgress == null) {
                shouldReserveConversionProgress = shouldConvertFromFilename(
                    filename = progress.filename,
                    conversionMode = conversionMode,
                )
            }

            val mappedProgress = if (shouldReserveConversionProgress != false) {
                progress.copy(
                    progressFraction = progress.progressFraction?.let {
                        it.coerceIn(0.0, 1.0) * AUDIO_DOWNLOAD_PROGRESS_WEIGHT
                    },
                )
            } else {
                progress
            }

            onProgress(mappedProgress)
        }
    }

    private fun shouldConvertToMp3(
        sourceFile: File,
        mp3File: File,
        conversionMode: AudioConversionMode,
    ): Boolean {
        return when (conversionMode) {
            AudioConversionMode.ALWAYS_CONVERT_MP3 -> sourceFile != mp3File
            AudioConversionMode.ONLY_CONVERT_WHEN_NOT_M4A -> {
                sourceFile != mp3File && !sourceFile.extension.equals(M4A_EXTENSION, ignoreCase = true)
            }
            AudioConversionMode.DO_NOT_CONVERT -> false
        }
    }

    private fun shouldConvertFromFilename(
        filename: String?,
        conversionMode: AudioConversionMode,
    ): Boolean? {
        val extension = filename
            ?.let(::File)
            ?.extension
            ?.lowercase()
            ?.takeIf(String::isNotBlank)

        return when (conversionMode) {
            AudioConversionMode.ALWAYS_CONVERT_MP3 -> extension?.let { it != MP3_EXTENSION } ?: true
            AudioConversionMode.ONLY_CONVERT_WHEN_NOT_M4A -> when (extension) {
                null -> null
                MP3_EXTENSION, M4A_EXTENSION -> false
                else -> true
            }
            AudioConversionMode.DO_NOT_CONVERT -> false
        }
    }

    private const val MODULE_NAME = "ytd_bridge"
    private const val M4A_EXTENSION = "m4a"
    private const val MP3_EXTENSION = "mp3"
    private const val FORMAT_OPTION_KEY = "format"
    private const val AUDIO_DOWNLOAD_PROGRESS_WEIGHT = 0.5
    private const val AUDIO_CONVERSION_PROGRESS_WEIGHT = 0.5
    private const val DEFAULT_MP3_DOWNLOAD_FORMAT = "bestaudio[ext=m4a][tbr<=128]/bestaudio[acodec^=mp4a][tbr<=128]/bestaudio[tbr<=128]/bestaudio/best"
    private const val DEFAULT_MP3_LAME_QUALITY = 5
    private const val MIN_LAME_QUALITY = 0
    private const val MAX_LAME_QUALITY = 9
}

enum class AudioConversionMode {
    ALWAYS_CONVERT_MP3,
    ONLY_CONVERT_WHEN_NOT_M4A,
    DO_NOT_CONVERT,
}

fun interface YtDlpProgressListener {
    fun onProgress(progress: YtDlpProgress)
}

data class YtDlpRequest(
    val url: String,
    val download: Boolean = false,
    val options: Map<String, Any?> = emptyMap(),
) {
    internal fun toJson(): JSONObject {
        return JSONObject().apply {
            put("url", url)
            put("download", download)
            put("options", options.toJsonObject())
        }
    }
}

data class YtDlpResult(
    val payload: JSONObject?,
    val logs: List<YtDlpLogEntry>,
    val download: Boolean,
)

data class YtDlpProgress(
    val status: String,
    val downloadedBytes: Long?,
    val totalBytes: Long?,
    val totalBytesEstimate: Long?,
    val progressFraction: Double?,
    val speedBytesPerSecond: Double?,
    val etaSeconds: Double?,
    val filename: String?,
) {
    companion object {
        internal fun fromJson(progressJson: String): YtDlpProgress {
            val json = JSONObject(progressJson)
            return YtDlpProgress(
                status = json.optString("status", "unknown"),
                downloadedBytes = json.optLongOrNull("downloaded_bytes"),
                totalBytes = json.optLongOrNull("total_bytes"),
                totalBytesEstimate = json.optLongOrNull("total_bytes_estimate"),
                progressFraction = json.optDoubleOrNull("progress_fraction"),
                speedBytesPerSecond = json.optDoubleOrNull("speed_bytes_per_second"),
                etaSeconds = json.optDoubleOrNull("eta_seconds"),
                filename = json.optStringOrNull("filename"),
            )
        }
    }
}

data class YtDlpLogEntry(
    val level: String,
    val message: String,
)

class YtDlpException(
    val pythonType: String,
    override val message: String,
    val traceback: String?,
    val logs: List<YtDlpLogEntry>,
) : RuntimeException(message)

class YtDlpProgressCallback(
    private val listener: YtDlpProgressListener,
) {
    fun onProgress(progressJson: String) {
        listener.onProgress(YtDlpProgress.fromJson(progressJson))
    }
}

private fun Map<String, Any?>.toJsonObject(): JSONObject {
    return JSONObject().apply {
        for ((key, value) in this@toJsonObject) {
            put(key, value.toJsonValue())
        }
    }
}

private fun Any?.toJsonValue(): Any {
    return when (this) {
        null -> JSONObject.NULL
        is JSONArray -> this
        is JSONObject -> this
        is Boolean, is Int, is Long, is Double, is String -> this
        is Float -> toDouble()
        is Number -> toDouble()
        is Map<*, *> -> {
            val map = this
            JSONObject().apply {
                for ((key, value) in map) {
                    put(key.toString(), value.toJsonValue())
                }
            }
        }
        is Iterable<*> -> {
            val items = this
            JSONArray().apply {
                for (item in items) {
                    put(item.toJsonValue())
                }
            }
        }
        is Array<*> -> {
            val items = this
            JSONArray().apply {
                for (item in items) {
                    put(item.toJsonValue())
                }
            }
        }
        else -> toString()
    }
}

private fun JSONObject.optLongOrNull(name: String): Long? {
    if (!has(name) || isNull(name)) {
        return null
    }
    return optLong(name)
}

private fun JSONObject.optDoubleOrNull(name: String): Double? {
    if (!has(name) || isNull(name)) {
        return null
    }
    return optDouble(name)
}

private fun JSONObject.optStringOrNull(name: String): String? {
    if (!has(name) || isNull(name)) {
        return null
    }
    return optString(name).ifBlank { null }
}

private fun JSONObject.findDownloadedFilePath(): String? {
    optStringOrNull("filepath")?.let { return it }
    optStringOrNull("_filename")?.let { return it }

    optJSONArray("requested_downloads")?.let { downloads ->
        for (index in 0 until downloads.length()) {
            downloads.optJSONObject(index)?.findDownloadedFilePath()?.let { return it }
        }
    }

    optJSONArray("entries")?.let { entries ->
        for (index in 0 until entries.length()) {
            entries.optJSONObject(index)?.findDownloadedFilePath()?.let { return it }
        }
    }

    return null
}

private fun YtDlpResult.withPayloadFields(vararg fields: Pair<String, Any?>): YtDlpResult {
    val payloadCopy = JSONObject(payload?.toString() ?: "{}")
    fields.forEach { (key, value) ->
        payloadCopy.put(key, value.toJsonValue())
    }
    return copy(payload = payloadCopy)
}
