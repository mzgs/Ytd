# ytd_bridge.py calls this method by name through Chaquopy. R8 cannot see
# that call: retain the callback entry point without keeping the whole library.
-keep class com.mzgs.ytdlib.YtDlpProgressCallback {
    public void onProgress(java.lang.String);
}
