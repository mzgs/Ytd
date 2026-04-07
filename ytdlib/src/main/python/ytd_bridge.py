import json
import traceback

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


def get_version():
    return __version__


def run(request_json):
    request = json.loads(request_json)
    download = bool(request.get("download", False))
    options = dict(request.get("options", {}))
    logger = _CollectorLogger()

    options.setdefault("logger", logger)
    options.setdefault("quiet", True)
    options.setdefault("no_warnings", True)
    if not download:
        options.setdefault("skip_download", True)

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
