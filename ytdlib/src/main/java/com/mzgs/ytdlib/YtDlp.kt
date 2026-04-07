package com.mzgs.ytdlib

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import org.json.JSONObject

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
    ): YtDlpResult {
        return run(
            context = context,
            request = YtDlpRequest(
                url = url,
                download = true,
                options = options,
            ),
        )
    }

    @Throws(YtDlpException::class)
    fun run(
        context: Context,
        request: YtDlpRequest,
    ): YtDlpResult {
        val responseJson = module(context)
            .callAttr("run", request.toJson().toString())
            .toString()
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

    private const val MODULE_NAME = "ytd_bridge"
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
