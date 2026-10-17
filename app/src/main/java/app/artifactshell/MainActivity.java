package app.artifactshell;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebMessage;
import android.webkit.WebMessagePort;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.security.SecureRandom;

/**
 * A full-screen window onto one Claude artifact.
 *
 * Security model (see SECURITY.md):
 * - Only https://claude.ai pages are ever shown as the page itself. Every other site, including
 *   Google sign-in, opens in the phone's real browser, so no look-alike login page can appear
 *   inside the app and no Google password is ever typed into it.
 * - No JavaScript bridge is exposed to web pages. Downloads use a one-off private message channel
 *   that only the claude.ai page can receive.
 * - No file, content, location, camera or microphone access; no deep links; HTTPS only; Safe Browsing on.
 * - The Claude sign-in cookie stays in this app's private storage and is excluded from backups.
 */
public class MainActivity extends Activity {

    private static final int FILE_REQUEST = 1001;
    private static final Uri CLAUDE_ORIGIN = Uri.parse("https://claude.ai");

    private WebView web;
    private ProgressBar bar;
    private ValueCallback<Uri[]> fileCallback;
    private final SecureRandom random = new SecureRandom();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        int color = parseColor(BuildConfig.THEME_COLOR);
        getWindow().setStatusBarColor(color);
        if (isLight(color)) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        FrameLayout root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setVisibility(View.GONE);
        root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4), Gravity.TOP));
        setContentView(root);

        configure();
        if (state != null) web.restoreState(state);
        String now = web.getUrl();
        if (now == null || !isClaude(Uri.parse(now))) web.loadUrl(BuildConfig.START_URL);
    }

    private void configure() {
        WebView.setWebContentsDebuggingEnabled(false);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        // Lock down everything a web page doesn't need.
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setGeolocationEnabled(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSafeBrowsingEnabled(true);
        s.setSaveFormData(false);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        // The artifact runs in a frame on Claude's second domain, which needs its own cookies.
        cookies.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        web.setDownloadListener((url, userAgent, disposition, mime, length) -> download(url, disposition, mime));
    }

    /* ---------------- navigation ---------------- */

    /** The only pages allowed to fill the app window. */
    static boolean isClaude(Uri uri) {
        if (uri == null || !"https".equals(uri.getScheme())) return false;
        if (uri.getPort() != -1 && uri.getPort() != 443) return false;
        if (uri.getUserInfo() != null) return false;
        String host = uri.getHost();
        if (host == null) return false;
        host = host.toLowerCase(java.util.Locale.ROOT);
        return host.equals("claude.ai") || host.endsWith(".claude.ai");
    }

    /** Sends a link to the phone's browser (web links) or mail/phone apps. Nothing else is launched. */
    private void openOutside(Uri uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http") && !scheme.equals("mailto") && !scheme.equals("tel")) return;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            i.setSelector(null);
            i.setComponent(null);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            toast("No app can open this link.");
        }
    }

    private class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme();
            if (!request.isForMainFrame()) {
                // Frames inside the Claude page (the artifact itself) load normally, web only.
                return !(scheme.equals("https") || scheme.equals("about") || scheme.equals("blob") || scheme.equals("data"));
            }
            if (isClaude(uri)) return false;
            openOutside(uri);
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            // Second line of defence: anything that reached the main window without passing the
            // check above (e.g. a redirect) is stopped and sent to the browser.
            if (url == null || url.startsWith("about:") || url.startsWith("data:text/html")) return;
            Uri uri = Uri.parse(url);
            if (!isClaude(uri)) {
                view.stopLoading();
                openOutside(uri);
                if (view.canGoBack()) view.goBack();
                else view.loadUrl(BuildConfig.START_URL);
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (!request.isForMainFrame()) return;
            String html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
                    + "<body style='font-family:sans-serif;padding:32px;color:#222'>"
                    + "<h2>Can't reach Claude</h2><p>Check your internet connection and try again.</p>"
                    + "<p><a href='" + BuildConfig.START_URL + "' style='display:inline-block;padding:12px 20px;"
                    + "background:" + BuildConfig.THEME_COLOR + ";color:#fff;border-radius:8px;text-decoration:none'>Try again</a></p>"
                    + "</body></html>";
            view.loadDataWithBaseURL("about:blank", html, "text/html", "utf-8", null);
        }
    }

    private class Chrome extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int progress) {
            bar.setProgress(progress);
            bar.setVisibility(progress >= 100 ? View.GONE : View.VISIBLE);
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            request.deny(); // no camera or microphone for web pages
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
            // "Open in new window": Claude pages open here, everything else in the browser.
            // The throwaway view never runs any page; it only reveals where the link points.
            WebView probe = new WebView(MainActivity.this);
            probe.getSettings().setJavaScriptEnabled(false);
            probe.setWebViewClient(new WebViewClient() {
                private boolean done;

                private void route(Uri uri) {
                    if (done) return;
                    done = true;
                    if (isClaude(uri)) web.loadUrl(uri.toString());
                    else openOutside(uri);
                    probe.post(probe::destroy);
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                    route(r.getUrl());
                    return true;
                }

                @Override
                public void onPageStarted(WebView v, String url, Bitmap favicon) {
                    if (url != null && !url.startsWith("about:")) {
                        v.stopLoading();
                        route(Uri.parse(url));
                    }
                }
            });
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(probe);
            resultMsg.sendToTarget();
            return true;
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = callback;
            try {
                Intent intent = params.createIntent();
                if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(intent, FILE_REQUEST);
                return true;
            } catch (ActivityNotFoundException e) {
                fileCallback = null;
                toast("No app can pick files.");
                return false;
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != FILE_REQUEST || fileCallback == null) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                result = new Uri[n];
                for (int i = 0; i < n; i++) result[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
    }

    /* ---------------- downloads ---------------- */

    private void download(String url, String disposition, String mime) {
        String name = safeName(URLUtil.guessFileName(url, disposition, mime));
        String current = web.getUrl();
        if (current == null || !isClaude(Uri.parse(current))) return;

        if (url.startsWith("data:")) {
            saveDataUrl(url, name);
            return;
        }
        if (url.startsWith("blob:https://claude.ai/") || url.startsWith("blob:https://www.claude.ai/")) {
            fetchBlob(url, name);
            return;
        }
        Uri uri = Uri.parse(url);
        if ("https".equals(uri.getScheme())) openOutside(uri); // the browser handles normal downloads
        else toast("This download only works on the website.");
    }

    /**
     * A file the Claude page built in memory. We open a private, one-off channel that only the
     * claude.ai page can receive, ask it for the file, and accept exactly one answer.
     */
    private void fetchBlob(String blobUrl, String name) {
        byte[] nonceBytes = new byte[16];
        random.nextBytes(nonceBytes);
        String nonce = Base64.encodeToString(nonceBytes, Base64.NO_WRAP | Base64.URL_SAFE | Base64.NO_PADDING);

        WebMessagePort[] ports = web.createWebMessageChannel();
        ports[0].setWebMessageCallback(new WebMessagePort.WebMessageCallback() {
            private boolean used;

            @Override
            public void onMessage(WebMessagePort port, WebMessage message) {
                if (used) return;
                used = true;
                String data = message.getData();
                port.close();
                if (data == null || data.equals("error")) toast("This download only works on the website.");
                else saveDataUrl(data, name);
            }
        });

        String js = "(function(){var u=" + quote(blobUrl) + ",k=" + quote(nonce) + ";"
                + "function h(e){if(e.data!==k||!e.ports||!e.ports[0])return;window.removeEventListener('message',h);var p=e.ports[0];"
                + "fetch(u).then(function(r){return r.blob();}).then(function(b){var f=new FileReader();"
                + "f.onloadend=function(){p.postMessage(String(f.result));};f.readAsDataURL(b);})"
                + ".catch(function(){p.postMessage('error');});}"
                + "window.addEventListener('message',h);})();";
        web.evaluateJavascript(js, ignored ->
                web.postWebMessage(new WebMessage(nonce, new WebMessagePort[]{ports[1]}), CLAUDE_ORIGIN));
    }

    private void saveDataUrl(String dataUrl, String name) {
        try {
            int comma = dataUrl.indexOf(',');
            if (!dataUrl.startsWith("data:") || comma < 0) throw new IllegalArgumentException();
            String meta = dataUrl.substring(5, comma);
            String body = dataUrl.substring(comma + 1);
            if (!meta.endsWith(";base64")) throw new IllegalArgumentException();
            byte[] bytes = Base64.decode(body, Base64.DEFAULT);
            String mime = meta.substring(0, meta.length() - ";base64".length());
            if (mime.isEmpty() || mime.length() > 100) mime = "application/octet-stream";
            writeToDownloads(name, mime, bytes);
            toast("Saved to Downloads: " + name);
        } catch (Exception e) {
            toast("Couldn't save the file.");
        }
    }

    private void writeToDownloads(String name, String mime, byte[] bytes) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IllegalStateException();
            try (OutputStream out = cr.openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException();
                out.write(bytes);
            }
        } else {
            File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) throw new IllegalStateException();
            try (OutputStream out = new FileOutputStream(new File(dir, name))) {
                out.write(bytes);
            }
        }
    }

    /* ---------------- lifecycle ---------------- */

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onPause() {
        web.onPause();
        CookieManager.getInstance().flush(); // stay signed in
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }

    /* ---------------- helpers ---------------- */

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static int parseColor(String hex) {
        try {
            return Color.parseColor(hex);
        } catch (Exception e) {
            return Color.BLACK;
        }
    }

    private static boolean isLight(int c) {
        double l = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c);
        return l > 186;
    }

    static String safeName(String name) {
        String n = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").replaceAll("^\\.+", "").trim();
        if (n.length() > 120) n = n.substring(n.length() - 120);
        return n.isEmpty() ? "download" : n;
    }

    private static String quote(String s) {
        StringBuilder b = new StringBuilder("'");
        for (char c : s.toCharArray()) {
            if (c == '\\' || c == '\'') b.append('\\').append(c);
            else if (c < 0x20 || c == '<' || c == '>' || c == 0x2028 || c == 0x2029) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('\'').toString();
    }
}
