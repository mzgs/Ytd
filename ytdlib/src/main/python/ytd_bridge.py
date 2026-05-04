import json
import re
import sys
import traceback


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
from yt_dlp.version import __version__


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
        with YoutubeDL({"quiet": True, "no_warnings": True}) as yt_dlp:
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
    except Exception as exc:
        diagnostics["diagnostics_error"] = "".join(
            traceback.format_exception_only(exc.__class__, exc)
        ).strip()

    return json.dumps(diagnostics)


def run(request_json, progress_callback=None):
    request = json.loads(request_json)
    download = bool(request.get("download", False))
    options = dict(request.get("options", {}))
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
        response = {
            "ok": True,
            "download": download,
            "result": sanitized,
            "logs": logger.entries,
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
