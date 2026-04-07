package com.mzgs.ytd

import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mzgs.ytdlib.YtDlp
import com.mzgs.ytdlib.YtDlpException
import com.mzgs.ytdlib.YtDlpProgress
import com.mzgs.ytdlib.YtDlpProgressListener
import com.mzgs.ytd.ui.theme.YtdTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            YtdTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    YtDlpTesterScreen(
                        applicationContext = applicationContext,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun YtDlpTesterScreen(
    applicationContext: android.content.Context,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf("") }
    var output by rememberSaveable { mutableStateOf("yt-dlp --json output will appear here") }
    var isRunning by rememberSaveable { mutableStateOf(false) }
    var progress by remember { mutableStateOf<YtDlpProgress?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "yt-dlp tester",
            style = MaterialTheme.typography.headlineSmall,
        )

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Video URL") },
            placeholder = { Text("https://www.youtube.com/watch?v=...") },
            singleLine = true,
            enabled = !isRunning,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = {
                    val trimmedUrl = url.trim()
                    if (trimmedUrl.isBlank()) {
                        output = "Please enter a URL first"
                        return@Button
                    }

                    isRunning = true
                    progress = null
                    output = "Running yt-dlp --json..."

                    coroutineScope.launch {
                        output = runYtDlpJson(applicationContext, trimmedUrl)
                        isRunning = false
                    }
                },
                enabled = !isRunning,
                modifier = Modifier.weight(1f),
            ) {
                if (isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.heightIn(max = 18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Run --json")
                }
            }

            Button(
                onClick = {
                    val trimmedUrl = url.trim()
                    if (trimmedUrl.isBlank()) {
                        output = "Please enter a URL first"
                        return@Button
                    }

                    isRunning = true
                    progress = null
                    output = "Preparing download..."

                    val progressListener = YtDlpProgressListener { update ->
                        coroutineScope.launch {
                            progress = update
                        }
                    }

                    coroutineScope.launch {
                        output = runYtDlpDownload(
                            context = applicationContext,
                            url = trimmedUrl,
                            progressListener = progressListener,
                        )
                        isRunning = false
                    }
                },
                enabled = !isRunning,
                modifier = Modifier.weight(1f),
            ) {
                Text("Download")
            }
        }

        progress?.let { downloadProgress ->
            val progressFraction = downloadProgress.progressFraction

            Text(
                text = buildDownloadProgressText(downloadProgress),
                style = MaterialTheme.typography.bodyMedium,
            )

            if (progressFraction != null) {
                LinearProgressIndicator(
                    progress = { progressFraction.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            downloadProgress.filename?.let { filename ->
                Text(
                    text = filename,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        OutlinedTextField(
            value = output,
            onValueChange = {},
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            label = { Text("Output") },
            readOnly = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
    }
}

@Preview(showBackground = true)
@Composable
fun YtDlpTesterScreenPreview() {
    YtdTheme {
        YtDlpTesterScreen(
            applicationContext = androidx.compose.ui.platform.LocalContext.current.applicationContext,
        )
    }
}

private suspend fun runYtDlpJson(
    context: android.content.Context,
    url: String,
): String {
    return withContext(Dispatchers.IO) {
        try {
            val result = YtDlp.extractInfo(context = context, url = url)
            result.payload?.toString(2) ?: "{}"
        } catch (exception: YtDlpException) {
            buildString {
                appendLine("${exception.pythonType}: ${exception.message}")
                if (!exception.traceback.isNullOrBlank()) {
                    appendLine()
                    appendLine(exception.traceback)
                }
                if (exception.logs.isNotEmpty()) {
                    appendLine()
                    appendLine("Logs:")
                    exception.logs.forEach { log ->
                        appendLine("[${log.level}] ${log.message}")
                    }
                }
            }.trim()
        } catch (exception: Exception) {
            exception.stackTraceToString()
        }
    }
}

private suspend fun runYtDlpDownload(
    context: android.content.Context,
    url: String,
    progressListener: YtDlpProgressListener,
): String {
    return withContext(Dispatchers.IO) {
        val downloadDirectory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir

        try {
            val result = YtDlp.download(
                context = context,
                url = url,
                options = mapOf(
                    "paths" to mapOf("home" to downloadDirectory.absolutePath),
                    "outtmpl" to "%(title)s.%(ext)s",
                ),
                progressListener = progressListener,
            )

            buildString {
                appendLine("Download finished")
                appendLine("Directory: ${downloadDirectory.absolutePath}")
                appendLine()
                append(result.payload?.toString(2) ?: "{}")
            }.trim()
        } catch (exception: YtDlpException) {
            buildYtDlpError(exception)
        } catch (exception: Exception) {
            exception.stackTraceToString()
        }
    }
}

private fun buildYtDlpError(exception: YtDlpException): String {
    return buildString {
        appendLine("${exception.pythonType}: ${exception.message}")
        if (!exception.traceback.isNullOrBlank()) {
            appendLine()
            appendLine(exception.traceback)
        }
        if (exception.logs.isNotEmpty()) {
            appendLine()
            appendLine("Logs:")
            exception.logs.forEach { log ->
                appendLine("[${log.level}] ${log.message}")
            }
        }
    }.trim()
}

private fun buildDownloadProgressText(progress: YtDlpProgress): String {
    val status = progress.status.replaceFirstChar { character ->
        if (character.isLowerCase()) {
            character.titlecase(Locale.US)
        } else {
            character.toString()
        }
    }

    return buildString {
        append(status)

        progress.progressFraction?.let {
            append(" • ")
            append(String.format(Locale.US, "%.1f%%", it * 100))
        }

        progress.downloadedBytes?.let { downloadedBytes ->
            append(" • ")
            append(formatBytes(downloadedBytes))

            progress.totalBytes?.let { totalBytes ->
                append(" / ")
                append(formatBytes(totalBytes))
            } ?: progress.totalBytesEstimate?.let { totalBytesEstimate ->
                append(" / ~")
                append(formatBytes(totalBytesEstimate))
            }
        }

        progress.speedBytesPerSecond?.let {
            append(" • ")
            append(formatBytes(it.toLong()))
            append("/s")
        }

        progress.etaSeconds?.let {
            append(" • ETA ")
            append(formatEta(it.toLong()))
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) {
        return "$bytes B"
    }

    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = -1

    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex += 1
    }

    return String.format(Locale.US, "%.1f %s", value, units[unitIndex])
}

private fun formatEta(seconds: Long): String {
    val totalSeconds = seconds.coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val remainingSeconds = totalSeconds % 60

    return when {
        hours > 0 -> String.format(Locale.US, "%d:%02d:%02d", hours, minutes, remainingSeconds)
        minutes > 0 -> String.format(Locale.US, "%d:%02d", minutes, remainingSeconds)
        else -> "${remainingSeconds}s"
    }
}
