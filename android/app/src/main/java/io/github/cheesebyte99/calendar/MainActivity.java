package io.github.cheesebyte99.calendar;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.webkit.WebViewAssetLoader;

import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 우리들의 캘린더 — 저장소의 calendar/index.html 을 앱 안에 넣어 WebView 로 띄운다.
 *
 * 페이지는 https://cheesebyte99.github.io/our-calendar/ 주소로 열리지만,
 * 이 경로의 요청은 WebViewAssetLoader 가 앱 안의 파일로 바로 돌려준다(인터넷 없이도 화면이 열림).
 * 그래서 웹판과 출처(origin)가 같고, 앱에서 만든 초대 링크를 친구가 브라우저로 열어도 같은 방에 들어간다.
 * 일정 동기화는 웹판과 똑같이 Firebase Firestore(rooms/{방ID}/events)로 실시간 공유된다.
 */
public class MainActivity extends Activity {

    static final String HOST = "cheesebyte99.github.io";
    static final String BASE_PATH = "/our-calendar/";
    static final String BASE_URL = "https://" + HOST + BASE_PATH;

    private static final int REQ_NOTIFICATIONS = 7;
    private static final Pattern ROOM_IN_TEXT = Pattern.compile("[?&]room=([A-Za-z0-9-]{1,32})");

    private WebView webView;
    private FrameLayout root;
    /** 배경 사진 고르기(<input type=file>) 결과를 웹에 돌려줄 통로 */
    private ValueCallback<Uri[]> fileCallback;
    private static final int REQ_FILE = 8;
    private static final String UI_PREFS = "ui";
    private WebViewAssetLoader assetLoader;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // WebView 는 자기 padding 을 무시하고 화면 끝까지 그리므로, 바깥 틀에 여백을 준다.
        root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.app_bg));
        webView = new WebView(this);
        webView.setBackgroundColor(getColor(R.color.app_bg));
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        applyInsets(root);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // localStorage: 이름·마지막 방 기억
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setUserAgentString(s.getUserAgentString() + " OurCalendarApp/1");

        assetLoader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler(BASE_PATH, new CalendarPathHandler(this))
                .build();

        webView.addJavascriptInterface(new Bridge(), "AndroidApp");
        webView.setWebChromeClient(new CalendarChrome()); // confirm()/prompt() 대화상자, 사진 고르기
        // 지난번 테마의 막대 색으로 바로 시작(깜빡임 줄이기)
        SharedPreferences ui = getSharedPreferences(UI_PREFS, MODE_PRIVATE);
        if (ui.contains("barColor")) applySystemBars(ui.getInt("barColor", 0), ui.getBoolean("barLight", true));
        webView.setWebViewClient(new CalendarClient());

        Push.createChannel(this);
        Push.listener = token -> runOnUiThread(() -> sendTokenToPage(token));
        Push.refreshToken(this);

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        }
        if (webView.getUrl() == null) {
            webView.loadUrl(urlForIntent(getIntent()));
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String room = roomFromIntent(intent);
        if (room != null) {
            webView.loadUrl(urlFor(room, dateFromIntent(intent)));
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE || fileCallback == null) return;
        fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
        fileCallback = null;
    }

    /** 테마에 맞춰 상태 막대·내비게이션 막대 자리의 색과 아이콘 밝기를 바꾼다 */
    private void applySystemBars(int color, boolean light) {
        root.setBackgroundColor(color);
        webView.setBackgroundColor(color);
        WindowInsetsControllerCompat c = new WindowInsetsControllerCompat(getWindow(), root);
        c.setAppearanceLightStatusBars(light);
        c.setAppearanceLightNavigationBars(light);
        if (Build.VERSION.SDK_INT < 35) {
            getWindow().setStatusBarColor(color);
            getWindow().setNavigationBarColor(color);
        }
    }

    private final class CalendarChrome extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = callback;
            try {
                Intent pick = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
                startActivityForResult(Intent.createChooser(pick, "사진 고르기"), REQ_FILE);
                return true;
            } catch (ActivityNotFoundException e) {
                fileCallback = null;
                return false;
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_NOTIFICATIONS) return;
        boolean granted = Push.hasPermission(this);
        webView.evaluateJavascript("window.__onPushPermission && window.__onPushPermission('"
                + (granted ? "granted" : "denied") + "')", null);
        if (granted) {
            String t = Push.savedToken(this);
            if (!t.isEmpty()) sendTokenToPage(t); else Push.refreshToken(this);
        }
    }

    private void sendTokenToPage(String token) {
        if (webView == null || token == null || token.isEmpty() || !Push.hasPermission(this)) return;
        webView.evaluateJavascript("window.__onPushToken && window.__onPushToken('"
                + token.replace("\\", "").replace("'", "") + "')", null);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onPause() {
        webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (Push.listener != null) Push.listener = null;
        webView.destroy();
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        // 열린 창(일정 편집·방 설정)이 있으면 먼저 닫고, 없으면 앱을 나간다.
        webView.evaluateJavascript(
                "(function(){var m=document.querySelector('.modal:not([hidden])');"
                        + "if(!m)return false;"
                        + "document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape'}));"
                        + "return true;})()",
                value -> {
                    if (!"true".equals(value)) {
                        MainActivity.super.onBackPressed();
                    }
                });
    }

    /** 상태바·내비게이션 바·키보드 영역만큼 WebView 를 감싼 틀을 안쪽으로 밀어 넣는다(Android 15 전체 화면 대응). */
    private static void applyInsets(View view) {
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout()
                    | WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    private String urlForIntent(Intent intent) {
        String room = roomFromIntent(intent);
        return room != null ? urlFor(room, dateFromIntent(intent)) : BASE_URL;
    }

    private static String urlFor(String room, String date) {
        String url = BASE_URL + "?room=" + room;
        if (date != null && date.matches("\\d{4}-\\d{2}-\\d{2}")) url += "&date=" + date;
        return url;
    }

    /** 알림에 실려 온 날짜(그 날 상세 창을 연다) */
    static String dateFromIntent(Intent intent) {
        if (intent == null) return null;
        String d = intent.getStringExtra("date");
        if (d == null && intent.getData() != null) d = intent.getData().getQueryParameter("date");
        return d;
    }

    /** 초대 링크(VIEW) 또는 공유받은 글(SEND)에서 방 ID 를 꺼낸다. */
    static String roomFromIntent(Intent intent) {
        if (intent == null) return null;
        String fromPush = intent.getStringExtra("room"); // 알림을 눌러 연 경우
        if (fromPush != null && fromPush.matches("[a-z0-9-]{1,32}")) return fromPush;
        String text = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            text = intent.getData().toString();
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            text = intent.getStringExtra(Intent.EXTRA_TEXT);
        }
        if (text == null) return null;
        Matcher m = ROOM_IN_TEXT.matcher(text);
        if (m.find()) return m.group(1).toLowerCase();
        String bare = text.trim().toLowerCase();
        return bare.matches("[a-z0-9-]{1,32}") ? bare : null;
    }

    /** /our-calendar/ 아래 요청을 앱 안의 www/ 파일로 돌려준다. */
    private static final class CalendarPathHandler implements WebViewAssetLoader.PathHandler {
        private final Context context;

        CalendarPathHandler(Context context) {
            this.context = context.getApplicationContext();
        }

        @Override
        public WebResourceResponse handle(@NonNull String path) {
            if (path.isEmpty() || path.equals("index.html")) {
                try {
                    InputStream in = context.getAssets().open("www/index.html");
                    return new WebResourceResponse("text/html", "utf-8", in);
                } catch (IOException e) {
                    return null;
                }
            }
            return null; // 그 밖의 경로는 실제 인터넷에서 받는다
        }
    }

    private final class CalendarClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            return assetLoader.shouldInterceptRequest(request.getUrl());
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (HOST.equals(uri.getHost()) && uri.getPath() != null
                    && uri.getPath().startsWith(BASE_PATH.substring(0, BASE_PATH.length() - 1))) {
                return false; // 캘린더 안의 이동은 앱에서
            }
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri)); // 그 밖의 링크는 브라우저로
            } catch (ActivityNotFoundException ignored) {
                // 열 앱이 없으면 무시
            }
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            view.evaluateJavascript(ENHANCE_JS, null);
        }
    }

    /**
     * 웹판(calendar/index.html)은 그대로 두고, 앱에서만 다음을 덧붙인다.
     * - 「🔗 링크 복사」 → 「📤 친구 초대」: 카카오톡 등으로 바로 보내는 안드로이드 공유 창
     * - 방 설정의 「복사」도 공유 창으로
     * - 방 ID 칸에 초대 링크를 통째로 붙여 넣어도 방 ID 만 골라 이동
     */
    private static final String ENHANCE_JS =
            "(function(){if(window.__ourCalApp)return;window.__ourCalApp=1;"
            + "function link(){var u=new URL(location.href);u.hash='';return u.toString();}"
            + "function roomName(){var r=new URL(location.href).searchParams.get('room');return r||'';}"
            + "function invite(){AndroidApp.share('📅 우리들의 캘린더에 초대할게요!\\n'"
            + "+'방: '+roomName()+'\\n'+link()+'\\n\\n앱이 있으면 방 설정에 이 링크를 붙여 넣어도 돼요.');}"
            + "var b=document.getElementById('copyLinkBtn');"
            + "if(b){b.textContent='📤 친구 초대';b.addEventListener('click',function(e){"
            + "e.stopImmediatePropagation();e.preventDefault();invite();},true);}"
            + "var c=document.getElementById('roomCopyBtn');"
            + "if(c){c.textContent='공유';c.addEventListener('click',function(e){"
            + "e.stopImmediatePropagation();e.preventDefault();invite();},true);}"
            + "var i=document.getElementById('roomInput');"
            + "function fix(){if(!i)return;var m=/[?&]room=([A-Za-z0-9-]{1,32})/.exec(i.value);"
            + "if(m)i.value=m[1];}"
            + "if(i){i.removeAttribute('maxlength');i.placeholder='방 ID 또는 초대 링크';"
            + "i.addEventListener('keydown',function(e){if(e.key==='Enter')fix();},true);"
            + "i.addEventListener('paste',function(){setTimeout(fix,0);});}"
            + "var j=document.getElementById('roomJoinBtn');"
            + "if(j)j.addEventListener('click',fix,true);"
            + "})();";

    /** 페이지에서 window.AndroidApp 으로 부르는 기능. */
    private final class Bridge {
        /** 이 기기의 FCM 토큰(아직 없거나 알림을 못 쓰면 빈 문자열) */
        @JavascriptInterface
        public String getPushToken() {
            if (!Push.enabled()) return "";
            String t = Push.savedToken(MainActivity.this);
            if (t.isEmpty()) Push.refreshToken(MainActivity.this);
            return t;
        }

        /** 'granted' | 'default' | 'unavailable' */
        @JavascriptInterface
        public String pushPermission() {
            if (!Push.enabled()) return "unavailable";
            return Push.hasPermission(MainActivity.this) ? "granted" : "default";
        }

        @JavascriptInterface
        public void requestPushPermission() {
            runOnUiThread(() -> {
                if (Push.hasPermission(MainActivity.this)) {
                    onRequestPermissionsResult(REQ_NOTIFICATIONS, new String[0], new int[0]);
                } else if (Build.VERSION.SDK_INT >= 33) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
                } else {
                    // Android 12 이하: 앱 알림이 꺼져 있으면 설정 화면으로
                    try {
                        startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName()));
                    } catch (ActivityNotFoundException ignored) {
                        // 무시
                    }
                    onRequestPermissionsResult(REQ_NOTIFICATIONS, new String[0], new int[0]);
                }
            });
        }

        @JavascriptInterface
        public void share(String text) {
            runOnUiThread(() -> {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(send, getString(R.string.share_title)));
            });
        }

        /** 테마 배경색(#rrggbb)과 밝은 배경인지 — 상태 막대 색을 맞춘다 */
        @JavascriptInterface
        public void setSystemBars(String hex, boolean light) {
            final int color;
            try {
                color = Color.parseColor(hex);
            } catch (IllegalArgumentException e) {
                return;
            }
            runOnUiThread(() -> {
                applySystemBars(color, light);
                getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit()
                        .putInt("barColor", color).putBoolean("barLight", light).apply();
            });
        }

        @JavascriptInterface
        public void copy(String text) {
            runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("calendar", text));
                    Toast.makeText(MainActivity.this, R.string.copied, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }
}
