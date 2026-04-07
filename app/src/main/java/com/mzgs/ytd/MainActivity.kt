package com.mzgs.ytd

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mzgs.ytdlib.YtDlp
import com.mzgs.ytdlib.YtDlpException
import com.mzgs.ytd.ui.theme.YtdTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "yt-dlp JSON tester",
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

        Button(
            onClick = {
                val trimmedUrl = url.trim()
                if (trimmedUrl.isBlank()) {
                    output = "Please enter a URL first"
                    return@Button
                }

                isRunning = true
                output = "Running yt-dlp --json..."

                coroutineScope.launch {
                    output = runYtDlpJson(applicationContext, trimmedUrl)
                    isRunning = false
                }
            },
            enabled = !isRunning,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.heightIn(max = 18.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text("Run yt-dlp --json")
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
