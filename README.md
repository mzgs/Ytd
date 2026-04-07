# Ytd

Android `yt-dlp` wrapper with a Kotlin API for metadata extraction, video downloads, and MP3 conversion.

[![JitPack](https://jitpack.io/v/mzgs/Ytd.svg)](https://jitpack.io/#mzgs/Ytd)  

## Requirements

- Android `minSdk 24`
- Java 11 / Kotlin JVM target 11
- `arm64-v8a` devices only

## Install from JitPack

Publish a Git tag, then add JitPack to your repositories:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

Add the library dependency:

```kotlin
dependencies {
    implementation("com.github.mzgs.Ytd:ytdlib:latest.release")
}
```

Replace `latest.release` with a Git tag if you want a pinned version.

JitPack builds only the `ytdlib` module through [`jitpack.yml`](jitpack.yml).

## Basic usage

### Optional warm-up on app start

Call `init` once if you want to pay the Python startup cost before the first real request.

```kotlin
import com.mzgs.ytdlib.YtDlp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun warmYtDlp(context: Context) {
    withContext(Dispatchers.IO) {
        YtDlp.init(context)
    }
}
```

### Get the bundled yt-dlp version

```kotlin
val version = YtDlp.getVersion(context)
Log.d("Ytd", "yt-dlp version: $version")
```

### Extract video metadata

```kotlin
import com.mzgs.ytdlib.YtDlp
import com.mzgs.ytdlib.YtDlpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun loadVideoInfo(context: Context, url: String): JSONObject? {
    return withContext(Dispatchers.IO) {
        try {
            YtDlp.extractInfo(
                context = context,
                url = url,
                options = mapOf(
                    "playlistend" to 1,
                ),
            ).payload
        } catch (error: YtDlpException) {
            Log.e("Ytd", "${error.pythonType}: ${error.message}")
            error.logs.forEach { log -> Log.d("Ytd", "[${log.level}] ${log.message}") }
            null
        }
    }
}
```

Read fields from the returned JSON:

```kotlin
val info = loadVideoInfo(context, "https://www.youtube.com/watch?v=...")
val title = info?.optString("title")
val durationSeconds = info?.optLong("duration")
val uploader = info?.optString("uploader")
```

### Download a video

```kotlin
import android.os.Environment
import com.mzgs.ytdlib.YtDlp
import com.mzgs.ytdlib.YtDlpProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun downloadVideo(context: Context, url: String) {
    withContext(Dispatchers.IO) {
        val outputDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir

        val progressListener = YtDlpProgressListener { progress ->
            Log.d(
                "Ytd",
                "status=${progress.status}, progress=${progress.progressFraction}, file=${progress.filename}",
            )
        }

        val result = YtDlp.download(
            context = context,
            url = url,
            options = mapOf(
                "paths" to mapOf("home" to outputDir.absolutePath),
                "outtmpl" to "%(title)s.%(ext)s",
            ),
            progressListener = progressListener,
        )

        Log.d("Ytd", "Saved to: ${result.payload?.optString("filepath")}")
    }
}
```

### Download audio and convert to MP3

```kotlin
import android.os.Environment
import com.mzgs.ytdlib.AudioConversionMode
import com.mzgs.ytdlib.YtDlp
import com.mzgs.ytdlib.YtDlpProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun downloadMp3(context: Context, url: String) {
    withContext(Dispatchers.IO) {
        val outputDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: context.filesDir

        val result = YtDlp.downloadAudio(
            context = context,
            url = url,
            options = mapOf(
                "paths" to mapOf("home" to outputDir.absolutePath),
                "outtmpl" to "%(title)s.%(ext)s",
            ),
            progressListener = YtDlpProgressListener { progress ->
                Log.d("Ytd", "audio progress=${progress.progressFraction}")
            },
            bitrateKbps = 128,
            lameQuality = 5,
            conversionMode = AudioConversionMode.ALWAYS_CONVERT_MP3,
        )

        Log.d("Ytd", "Audio file: ${result.payload?.optString("audio_filepath")}")
        Log.d("Ytd", "Converted: ${result.payload?.optBoolean("converted_to_mp3")}")
    }
}
```

## Advanced usage

### Build and run a custom request

Use `run` when you want to construct the request explicitly.

```kotlin
import com.mzgs.ytdlib.YtDlp
import com.mzgs.ytdlib.YtDlpRequest

val result = YtDlp.run(
    context = context,
    request = YtDlpRequest(
        url = "https://www.youtube.com/watch?v=...",
        download = false,
        options = mapOf(
            "skip_download" to true,
            "dump_single_json" to true,
        ),
    ),
)
```

### Handle errors

`YtDlpException` exposes the Python exception type, traceback, and captured logs.

```kotlin
try {
    YtDlp.extractInfo(context, "https://invalid.example")
} catch (error: YtDlpException) {
    Log.e("Ytd", "${error.pythonType}: ${error.message}")
    Log.e("Ytd", error.traceback ?: "no traceback")
    error.logs.forEach { log ->
        Log.d("Ytd", "[${log.level}] ${log.message}")
    }
}
```
