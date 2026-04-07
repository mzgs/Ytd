# ytdlib

Android library wrapper around `yt-dlp` using Chaquopy.

## What it does

- Bundles `yt-dlp` into the `ytdlib` Android library.
- Starts the embedded Python runtime automatically.
- Exposes a small Kotlin API for info extraction and downloads.
- Returns the `yt-dlp` result as `JSONObject` because the response schema varies by site.

## Gradle setup

`ytdlib/build.gradle.kts` now applies the Chaquopy plugin and installs `yt-dlp` with:

```kotlin
chaquopy {
    defaultConfig {
        pip {
            install("yt-dlp")
        }
    }
}
```

If Gradle can't find a compatible Python interpreter during build on your machine, set `buildPython(...)` inside the same `chaquopy.defaultConfig` block.

## Usage

Call the library from a background thread or coroutine, not from the main thread.

```kotlin
val result = YtDlp.extractInfo(
    context = applicationContext,
    url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
    options = mapOf(
        "format" to "bv*+ba/b",
    ),
)

val title = result.payload?.optString("title")
```

Download example:

```kotlin
val result = YtDlp.download(
    context = applicationContext,
    url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
    options = mapOf(
        "paths" to mapOf("home" to filesDir.absolutePath),
        "outtmpl" to "%(title)s.%(ext)s",
        "format" to "mp4/best",
    ),
)
```

Download with progress:

```kotlin
val result = YtDlp.download(
    context = applicationContext,
    url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
    options = mapOf(
        "paths" to mapOf("home" to filesDir.absolutePath),
    ),
    progressListener = YtDlpProgressListener { progress ->
        Log.d(
            "ytdlib",
            "${progress.status} ${progress.progressFraction?.times(100)}%",
        )
    },
)
```

Audio download example:

```kotlin
val result = YtDlp.downloadAudio(
    context = applicationContext,
    url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
    options = mapOf(
        "paths" to mapOf("home" to filesDir.absolutePath),
        "outtmpl" to "%(title)s.%(ext)s",
    ),
    bitrateKbps = 320,
    lameQuality = 5,
    downloadFormat = "bestaudio[ext=m4a]/bestaudio[acodec^=mp4a]/bestaudio[abr<=128]/bestaudio/best",
    conversionMode = AudioConversionMode.ONLY_CONVERT_WHEN_NOT_M4A,
)

val audioPath = result.payload?.optString("audio_filepath")
val mp3Path = result.payload?.optString("mp3_filepath")
```

When `downloadAudio` has to convert to MP3, `progressListener` uses the first 50% for the `yt-dlp` download phase and the second 50% for on-device conversion progress. If conversion is skipped, it reports the normal `yt-dlp` download progress.

`downloadFormat` uses the normal `yt-dlp` format selector syntax. The default is `bestaudio[ext=m4a]/bestaudio[acodec^=mp4a]/bestaudio[abr<=128]/bestaudio/best`. Examples:

- `bestaudio[ext=m4a]/bestaudio[acodec^=mp4a]/bestaudio[abr<=128]/bestaudio/best` to prefer Android-friendly `m4a/AAC` first.
- `bestaudio/best` for highest available audio quality.
- `bestaudio[abr<=128]/bestaudio/best` to prefer smaller audio downloads.
- `worstaudio/worst` for the fastest, lowest-quality source.

`lameQuality` controls encoder effort from `0` to `9`:

- `0` = best quality, slowest
- `5` = balanced
- `9` = fastest, lowest encoder quality

The default `lameQuality` is `5`, which balances encode speed and output quality for on-device MP3 conversion.

`conversionMode` controls whether downloaded audio is re-encoded. The default is `ONLY_CONVERT_WHEN_NOT_M4A`.

- `ALWAYS_CONVERT_MP3` always produces MP3.
- `ONLY_CONVERT_WHEN_NOT_M4A` keeps the downloaded file when it is already `.m4a`, and only converts non-`.m4a` audio sources to MP3.
- `DO_NOT_CONVERT` always keeps the downloaded audio file as-is.

`audio_filepath` always points at the final output file, `audio_extension` tells you the final extension, `converted_to_mp3` tells you whether MP3 re-encoding actually happened, and `mp3_filepath` is only set when the final output file is `.mp3`.

## Public API

- `YtDlp.getVersion(context)`
- `YtDlp.extractInfo(context, url, options)`
- `YtDlp.download(context, url, options, progressListener)`
- `YtDlp.downloadAudio(context, url, options, progressListener, bitrateKbps, lameQuality, downloadFormat, deleteSourceFile, conversionMode)`
- `YtDlp.run(context, request, progressListener)`

Errors are thrown as `YtDlpException`, which includes the Python exception type, traceback, and collected log messages.

## Notes

- The library currently packages only the `arm64-v8a` Python runtime.
- The library now converts downloads to MP3 on Android using `MediaCodec` decoding plus a small JNI wrapper around static `lame`.
- This avoids bundling large FFmpeg shared libraries and keeps the APK compatible with Android 15 16 KB page-size requirements.
- `downloadAudio` may still do a full decode + MP3 re-encode on the device, so forced MP3 output is naturally slower than `download()` and adds post-download CPU time.
- The encoder batches PCM before passing it into `lame`, which reduces JNI and file-write overhead during conversion.
- For the quickest MP3 output, increase `lameQuality` toward `9` and lower `bitrateKbps` if acceptable.
- `downloadAudio` now defaults to Android-friendly `m4a/AAC` downloads first and skips conversion when the result is already `.m4a`.
