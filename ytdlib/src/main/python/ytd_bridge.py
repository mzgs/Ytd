import json
import os
import re
import sys
import traceback
import urllib.parse


def _prepare_curl_cffi_for_ytdlp():
    try:
        import curl_cffi
    except Exception:
        return

    version = getattr(curl_cffi, "__version__", "")
    try:
        version_parts = tuple(map(int, re.split(r"[^\d]+", version)[:3]))
    except ValueError:
        return
    if (0, 15) <= version_parts < (0, 16):
        curl_cffi._ytd_android_original_version = version
        curl_cffi.__version__ = "0.14.0"


_prepare_curl_cffi_for_ytdlp()

from yt_dlp import YoutubeDL
from yt_dlp.networking.impersonate import ImpersonateTarget
from yt_dlp.version import __version__


_quickjs_path = None

_DEFAULT_DOWNLOAD_RETRIES = 0
_DEFAULT_FRAGMENT_RETRIES = 2

_PLAYER_API_LOG_PATTERN = re.compile(
    r"Downloading (?P<client>[a-z0-9_ ]+) player API JSON",
    re.IGNORECASE,
)
_YOUTUBE_URL_CLIENT_NAMES = {
    "WEB_EMBEDDED_PLAYER": "web_embedded",
    "WEB_REMIX": "web_music",
    "WEB_CREATOR": "web_creator",
    "TVHTML5": "tv",
    "TVHTML5_SIMPLY": "tv_simply",
}


def configure_js_runtime(path):
    global _quickjs_path
    resolved_path = os.path.abspath(str(path)) if path else None
    _quickjs_path = resolved_path if resolved_path and os.path.isfile(resolved_path) else None
    return _quickjs_path is not None


def _default_js_runtimes():
    if not _quickjs_path:
        return None
    return {"quickjs": {"path": _quickjs_path}}


class _CollectorLogger:
    def __init__(self):
        self.entries = []

    def debug(self, message):
        self.entries.append({"level": "debug", "message": str(message)})

    def warning(self, message):
        self.entries.append({"level": "warning", "message": str(message)})

    def error(self, message):
        self.entries.append({"level": "error", "message": str(message)})


def _safe_float(value):
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _safe_int(value):
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _progress_fraction(progress):
    downloaded = _safe_float(progress.get("downloaded_bytes"))
    total = _safe_float(progress.get("total_bytes"))
    total_estimate = _safe_float(progress.get("total_bytes_estimate"))
    denominator = total or total_estimate

    if downloaded is not None and denominator and denominator > 0:
        return max(0.0, min(downloaded / denominator, 1.0))

    percent_text = str(progress.get("_percent_str") or "").strip()
    if percent_text.endswith("%"):
        percent_value = _safe_float(percent_text[:-1])
        if percent_value is not None:
            return max(0.0, min(percent_value / 100.0, 1.0))

    if progress.get("status") == "finished":
        return 1.0

    return None


def _progress_payload(progress):
    return {
        "status": str(progress.get("status") or "unknown"),
        "downloaded_bytes": _safe_int(progress.get("downloaded_bytes")),
        "total_bytes": _safe_int(progress.get("total_bytes")),
        "total_bytes_estimate": _safe_int(progress.get("total_bytes_estimate")),
        "progress_fraction": _progress_fraction(progress),
        "speed_bytes_per_second": _safe_float(progress.get("speed")),
        "eta_seconds": _safe_float(progress.get("eta")),
        "filename": progress.get("filename") or progress.get("info_dict", {}).get("_filename"),
    }


def _notify_progress(progress_callback, logger, progress):
    if progress_callback is None:
        return

    try:
        progress_callback.onProgress(json.dumps(_progress_payload(progress)))
    except Exception as exc:
        logger.warning(f"Progress callback failed: {exc}")


def get_version():
    return __version__


def get_diagnostics():
    diagnostics = {
        "python_version": sys.version,
        "yt_dlp_version": __version__,
        "curl_cffi_import_ok": False,
        "curl_cffi_version": None,
        "curl_cffi_effective_version": None,
        "curl_cffi_curl_version": None,
        "curl_cffi_error": None,
        "yt_dlp_ejs_import_ok": False,
        "yt_dlp_ejs_version": None,
        "yt_dlp_ejs_error": None,
        "quickjs_path": _quickjs_path,
        "quickjs_exists": bool(_quickjs_path and os.path.isfile(_quickjs_path)),
        "quickjs_executable": bool(_quickjs_path and os.access(_quickjs_path, os.X_OK)),
        "quickjs_runtime": None,
        "quickjs_error": None,
        "request_handlers": [],
        "available_impersonate_targets": [],
        "diagnostics_error": None,
    }

    try:
        import curl_cffi
        diagnostics["curl_cffi_import_ok"] = True
        diagnostics["curl_cffi_version"] = getattr(
            curl_cffi,
            "_ytd_android_original_version",
            getattr(curl_cffi, "__version__", None),
        )
        diagnostics["curl_cffi_effective_version"] = getattr(curl_cffi, "__version__", None)
        diagnostics["curl_cffi_curl_version"] = getattr(curl_cffi, "__curl_version__", None)
    except Exception as exc:
        diagnostics["curl_cffi_error"] = "".join(
            traceback.format_exception_only(exc.__class__, exc)
        ).strip()

    try:
        import yt_dlp_ejs
        diagnostics["yt_dlp_ejs_import_ok"] = True
        diagnostics["yt_dlp_ejs_version"] = getattr(yt_dlp_ejs, "version", None)
    except Exception as exc:
        diagnostics["yt_dlp_ejs_error"] = "".join(
            traceback.format_exception_only(exc.__class__, exc)
        ).strip()

    try:
        diagnostic_options = {"quiet": True, "no_warnings": True}
        js_runtimes = _default_js_runtimes()
        if js_runtimes:
            diagnostic_options["js_runtimes"] = js_runtimes

        with YoutubeDL(diagnostic_options) as yt_dlp:
            handlers = getattr(getattr(yt_dlp, "_request_director", None), "handlers", {})
            diagnostics["request_handlers"] = [
                {
                    "key": str(key),
                    "name": getattr(handler, "RH_NAME", handler.__class__.__name__),
                    "class": handler.__class__.__name__,
                }
                for key, handler in handlers.items()
            ]
            diagnostics["available_impersonate_targets"] = [
                str(target)
                for target in yt_dlp._get_available_impersonate_targets()
            ]

            quickjs = yt_dlp._js_runtimes.get("quickjs")
            if quickjs:
                try:
                    info = quickjs.info
                    if info:
                        diagnostics["quickjs_runtime"] = {
                            "name": info.name,
                            "path": info.path,
                            "version": info.version,
                            "supported": info.supported,
                        }
                except Exception as exc:
                    diagnostics["quickjs_error"] = "".join(
                        traceback.format_exception_only(exc.__class__, exc)
                    ).strip()
    except Exception as exc:
        diagnostics["diagnostics_error"] = "".join(
            traceback.format_exception_only(exc.__class__, exc)
        ).strip()

    return json.dumps(diagnostics)


def _normalize_impersonate_option(value):
    if value in (True, ""):
        return ImpersonateTarget()
    if isinstance(value, str):
        return ImpersonateTarget.from_str(value.lower())
    return value


def _normalize_options(options):
    if "impersonate" in options:
        options["impersonate"] = _normalize_impersonate_option(options["impersonate"])
    js_runtimes = _default_js_runtimes()
    if js_runtimes:
        options.setdefault("js_runtimes", js_runtimes)
    return options


def _configure_download_options(options):
    # YoutubeDL's Python API doesn't inherit the CLI parser defaults. Without these,
    # a transient CDN disconnect (for example curl error 56) aborts immediately
    # instead of reopening the request and resuming the partially downloaded file.
    options.setdefault("retries", _DEFAULT_DOWNLOAD_RETRIES)
    options.setdefault("fragment_retries", _DEFAULT_FRAGMENT_RETRIES)

    extractor_args = options.get("extractor_args")
    if extractor_args is None:
        extractor_args = {}
        options["extractor_args"] = extractor_args

    if not isinstance(extractor_args, dict):
        return

    youtube_args = extractor_args.get("youtube")
    if youtube_args is None:
        youtube_args = {}
        extractor_args["youtube"] = youtube_args

    if not isinstance(youtube_args, dict):
        return

    youtube_args.setdefault("player_client", ["default", "-web"])


def _attempted_player_clients(logger):
    clients = []
    for entry in logger.entries:
        match = _PLAYER_API_LOG_PATTERN.search(entry.get("message", ""))
        if not match:
            continue
        client = match.group("client").strip().lower().replace(" ", "_")
        if client not in clients:
            clients.append(client)
    return clients


def _player_client_from_url(url):
    if not isinstance(url, str) or not url:
        return None
    try:
        raw_client = urllib.parse.parse_qs(
            urllib.parse.urlsplit(url).query
        ).get("c", [None])[0]
    except (TypeError, ValueError):
        return None
    if not raw_client:
        return None
    normalized = str(raw_client).upper()
    return _YOUTUBE_URL_CLIENT_NAMES.get(normalized, normalized.lower())


def _selected_format_candidates(info):
    requested_formats = info.get("requested_formats")
    if isinstance(requested_formats, list) and requested_formats:
        return [item for item in requested_formats if isinstance(item, dict)]

    requested_downloads = info.get("requested_downloads")
    if isinstance(requested_downloads, list) and requested_downloads:
        candidates = [item for item in requested_downloads if isinstance(item, dict)]
        if any(item.get("url") for item in candidates):
            return candidates

    return [info]


def _selected_player_metadata(info):
    selected_formats = []
    selected_clients = []
    for selected_format in _selected_format_candidates(info):
        client = (
            selected_format.get("__yt_dlp_client")
            or _player_client_from_url(selected_format.get("url"))
        )
        if not client:
            continue
        client = str(client)
        if client not in selected_clients:
            selected_clients.append(client)

        vcodec = selected_format.get("vcodec")
        acodec = selected_format.get("acodec")
        media_type = (
            "audio" if vcodec == "none"
            else "video" if acodec == "none"
            else "video_audio"
        )
        selected_formats.append({
            "format_id": selected_format.get("format_id"),
            "media_type": media_type,
            "player_client": client,
        })
    return selected_clients, selected_formats


def run(request_json, progress_callback=None):
    request = json.loads(request_json)
    download = bool(request.get("download", False))
    options = _normalize_options(dict(request.get("options", {})))
    if download:
        _configure_download_options(options)
    logger = _CollectorLogger()

    options.setdefault("logger", logger)
    options.setdefault("quiet", True)
    options.setdefault("no_warnings", True)
    if not download:
        options.setdefault("skip_download", True)
    elif progress_callback is not None:
        hooks = list(options.get("progress_hooks") or [])
        hooks.append(lambda progress: _notify_progress(progress_callback, logger, progress))
        options["progress_hooks"] = hooks

    try:
        with YoutubeDL(options) as yt_dlp:
            info = yt_dlp.extract_info(request["url"], download=download)
            sanitized = yt_dlp.sanitize_info(info)
        selected_clients, selected_formats = _selected_player_metadata(info)
        response = {
            "ok": True,
            "download": download,
            "result": sanitized,
            "logs": logger.entries,
            "player_clients_attempted": _attempted_player_clients(logger),
            "selected_player_clients": selected_clients,
            "selected_formats": selected_formats,
        }
    except Exception as exc:
        response = {
            "ok": False,
            "download": download,
            "logs": logger.entries,
            "error": {
                "type": exc.__class__.__name__,
                "message": str(exc),
                "traceback": traceback.format_exc(),
            },
        }

    return json.dumps(response)
