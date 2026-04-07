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

## Public API

- `YtDlp.getVersion(context)`
- `YtDlp.extractInfo(context, url, options)`
- `YtDlp.download(context, url, options, progressListener)`
- `YtDlp.run(context, request, progressListener)`

Errors are thrown as `YtDlpException`, which includes the Python exception type, traceback, and collected log messages.

## Notes

- The library currently packages only the `arm64-v8a` Python runtime.
- Some `yt-dlp` post-processing flows require external tools like `ffmpeg`, which are not bundled here.
