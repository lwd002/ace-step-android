package com.seance.acestep;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONObject;

public class MainActivity extends Activity {
    private WebView web;
    private ExecutorService pool;
    private File audioCacheDir;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER_REQ = 1001;

    private static final int MAX_ATTEMPTS = 3;
    /* The server sits behind a Tailscale tunnel that can take a few seconds to
       wake up, and the phone may be coming out of doze — 15s was too tight. */
    private static final int CONNECT_TIMEOUT_MS = 30000;
    private static final int READ_TIMEOUT_MS = 120000;
    private static final int DOWNLOAD_TIMEOUT_MS = 300000;
    /* Polling means a flaky link used to raise a wall of identical toasts. */
    private static final long TOAST_MIN_GAP_MS = 8000;
    private long lastToastAt = 0;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pool = Executors.newFixedThreadPool(4);

        /* Connection reuse stays ON. Turning it off did cure the dead-socket errors,
           but it also meant every poll opened a fresh TCP connection through the
           tunnel, and that churn competed with the multi-MB song downloads. The
           retry below covers the same failure more cheaply: a stale connection
           fails instantly, and the second attempt gets a new socket. */

        // fetched songs are cached here and served back to the page same-origin;
        // safe to wipe on every launch — the page keeps its own copies in IndexedDB
        audioCacheDir = new File(getFilesDir(), "audio");
        if (audioCacheDir.exists()) {
            File[] old = audioCacheDir.listFiles();
            if (old != null) for (File f : old) f.delete();
        } else {
            audioCacheDir.mkdirs();
        }

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler("/audio/", new WebViewAssetLoader.InternalStoragePathHandler(this, audioCacheDir))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }
        });

        /* <input type="file"> is inert in a WebView unless the host app opens the
           system picker itself — without this, tapping the upload boxes does nothing */
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), FILE_CHOOSER_REQ);
                    return true;
                } catch (Exception e) {
                    filePathCallback = null;
                    toastOnUi("无法打开文件选择器: " + e.getMessage());
                    return false;
                }
            }
        });

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.loadUrl("https://appassets.androidplatform.net/assets/ace-step-studio-mobile.html");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQ && filePathCallback != null) {
            filePathCallback.onReceiveValue(
                    WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            filePathCallback = null;
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    private void callJs(final String script) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript(script, null);
            }
        });
    }

    /**
     * Native bridge. All ACE-Step API traffic goes through here instead of fetch():
     * WebView (Chromium) increasingly blocks "secure page → plain-http LAN device"
     * requests (private-network preflights the server doesn't answer), while requests
     * made from Java are subject to no such web policies.
     */
    private class Bridge {

        /** Generic HTTP for the JSON API. Small payloads only — response returns as base64 via callback. */
        @JavascriptInterface
        public void httpRequest(final String id, final String method, final String url,
                                final String headersJson, final String bodyBase64) {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    /* A GET can be replayed freely. A POST may only be repeated while we
                       are certain the server never saw it — i.e. the failure happened
                       before any response line came back — otherwise a lost reply would
                       submit the same generation twice. */
                    final boolean idempotent = "GET".equalsIgnoreCase(method)
                            || "HEAD".equalsIgnoreCase(method);
                    Exception last = null;
                    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                        HttpURLConnection c = null;
                        boolean answered = false;
                        try {
                            c = (HttpURLConnection) new URL(url).openConnection();
                            c.setRequestMethod(method);
                            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
                            c.setReadTimeout(READ_TIMEOUT_MS);
                            JSONObject headers = new JSONObject(headersJson);
                            Iterator<String> it = headers.keys();
                            while (it.hasNext()) {
                                String k = it.next();
                                c.setRequestProperty(k, headers.getString(k));
                            }
                            if (bodyBase64 != null && bodyBase64.length() > 0) {
                                c.setDoOutput(true);
                                byte[] body = Base64.decode(bodyBase64, Base64.DEFAULT);
                                OutputStream os = c.getOutputStream();
                                os.write(body);
                                os.close();
                            }
                            int status = c.getResponseCode();
                            answered = true;
                            InputStream in = (status >= 400) ? c.getErrorStream() : c.getInputStream();
                            byte[] resp = readAll(in);
                            String b64 = Base64.encodeToString(resp, Base64.NO_WRAP);
                            callJs("window.__nativeHttpDone('" + id + "'," + status + ",'" + b64 + "','')");
                            return;                     // a retry that worked stays silent
                        } catch (Exception e) {
                            last = e;
                            if (answered && !idempotent) break;
                            if (attempt < MAX_ATTEMPTS) sleepBackoff(attempt);
                        } finally {
                            if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
                        }
                    }
                    String msg = safeMsg(last);
                    callJs("window.__nativeHttpDone('" + id + "',0,'','" + msg + "')");
                    toastThrottled("请求失败: " + msg);
                }
            });
        }

        /**
         * Audio files can be many MB — shuttling them through evaluateJavascript as
         * base64 is fragile, so they are downloaded to the app cache instead and
         * served back to the page same-origin under /audio/.
         */
        @JavascriptInterface
        public void downloadToCache(final String id, final String url) {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    String ext = "mp3";
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("\\.(mp3|wav|flac|opus|aac|ogg|m4a)\\b", java.util.regex.Pattern.CASE_INSENSITIVE)
                            .matcher(url);
                    if (m.find()) ext = m.group(1).toLowerCase();

                    /* Fetching a static file, so the whole transfer can simply be
                       replayed — including when the stream dies part-way through,
                       which is the usual way a tunnel drops a multi-MB download. */
                    Exception last = null;
                    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                        HttpURLConnection c = null;
                        File out = null;
                        InputStream in = null;
                        FileOutputStream fos = null;
                        try {
                            c = (HttpURLConnection) new URL(url).openConnection();
                            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
                            c.setReadTimeout(DOWNLOAD_TIMEOUT_MS);
                            int status = c.getResponseCode();
                            if (status < 200 || status >= 300) throw new Exception("HTTP " + status);
                            out = new File(audioCacheDir, UUID.randomUUID().toString() + "." + ext);
                            in = c.getInputStream();
                            fos = new FileOutputStream(out);
                            byte[] buf = new byte[65536];
                            int n;
                            while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
                            fos.close();
                            fos = null;
                            String virtualUrl = "https://appassets.androidplatform.net/audio/" + out.getName();
                            callJs("window.__nativeDlDone('" + id + "',true,'" + virtualUrl + "')");
                            return;
                        } catch (Exception e) {
                            last = e;
                            if (out != null) out.delete();   // never leave half a song in the cache
                            if (attempt < MAX_ATTEMPTS) sleepBackoff(attempt);
                        } finally {
                            if (fos != null) try { fos.close(); } catch (Exception ignored) {}
                            if (in != null) try { in.close(); } catch (Exception ignored) {}
                            if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
                        }
                    }
                    callJs("window.__nativeDlDone('" + id + "',false,'" + safeMsg(last) + "')");
                }
            });
        }

        /**
         * Fallback for when the page can't read a cached file through the asset
         * loader's virtual URL: push the bytes over the bridge as base64 chunks.
         */
        @JavascriptInterface
        public void streamCachedFile(final String id, final String name) {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    FileInputStream fis = null;
                    try {
                        File f = new File(audioCacheDir, new File(name).getName());
                        if (!f.exists()) throw new Exception("缓存文件不存在: " + f.getName());
                        fis = new FileInputStream(f);
                        byte[] buf = new byte[262144];
                        int n;
                        while ((n = fis.read(buf)) != -1) {
                            String b64 = Base64.encodeToString(buf, 0, n, Base64.NO_WRAP);
                            callJs("window.__nativeChunk('" + id + "','" + b64 + "')");
                        }
                        fis.close();
                        fis = null;
                        callJs("window.__nativeChunkDone('" + id + "',true,'')");
                    } catch (Exception e) {
                        if (fis != null) try { fis.close(); } catch (Exception ignored) {}
                        callJs("window.__nativeChunkDone('" + id + "',false,'" + safeMsg(e) + "')");
                    }
                }
            });
        }

        /** Writes a finished song into the system Downloads collection. */
        @JavascriptInterface
        public void saveFile(final String name, final String base64, final String mime) {
            try {
                byte[] data = Base64.decode(base64, Base64.DEFAULT);
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                cv.put(MediaStore.Downloads.MIME_TYPE, mime);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new Exception("MediaStore insert failed");
                OutputStream os = getContentResolver().openOutputStream(uri);
                os.write(data);
                os.close();
                toastOnUi("已保存到「下载」：" + name);
            } catch (Exception e) {
                toastOnUi("保存失败: " + e.getMessage());
            }
        }
    }

    /** 500ms, then 2s — long enough for a tunnel to come back, short enough to feel instant. */
    private static void sleepBackoff(int attempt) {
        try {
            Thread.sleep(500L * attempt * attempt);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /** Polling used to stack up dozens of identical failure toasts; show at most one. */
    private void toastThrottled(String msg) {
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (now - lastToastAt < TOAST_MIN_GAP_MS) return;
            lastToastAt = now;
        }
        toastOnUi(msg);
    }

    /** Messages are interpolated into a JS single-quoted string literal. */
    private static String safeMsg(Exception e) {
        if (e == null) return "未知错误";
        /* Plenty of socket failures carry no message at all, which used to surface
           to the user as the literal text "null". */
        String m = e.getMessage();
        if (m == null || m.length() == 0) m = e.getClass().getSimpleName();
        return m.replace("\\", "").replace("'", "").replace("\r", " ").replace("\n", " ");
    }

    private static byte[] readAll(InputStream in) throws Exception {
        if (in == null) return new byte[0];
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    private void toastOnUi(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
            }
        });
    }
}
