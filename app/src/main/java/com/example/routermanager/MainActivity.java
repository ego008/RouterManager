package com.example.routermanager;

import android.os.Bundle;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.util.List;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "RouterManager";

    private WebView webView;
    private NulFilterProxy proxy;

    // 路由器参数：请根据你的实际情况修改！！！
    private static final String ROUTER_IP = "192.168.10.16";    // 路由器地址
    private static final int ROUTER_PORT = 80;
    private static final String USERNAME = "admin";
    private static final String PASSWORD = "admin";
    private static final int PROXY_PORT = 8888;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);

        // 设置 WebViewClient，捕获页面加载错误
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, int errorCode,
                                        String description, String failingUrl) {
                Log.e(TAG, "WebView error: " + errorCode + " - " + description + " URL:" + failingUrl);
                Toast.makeText(MainActivity.this, "页面加载错误: " + description, Toast.LENGTH_LONG).show();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                Log.d(TAG, "页面加载完成: " + url);
                if (url.startsWith("http://127.0.0.1")) {
                    Toast.makeText(MainActivity.this, "管理页面加载成功", Toast.LENGTH_SHORT).show();
                }
            }
        });

        // 启动代理
        proxy = new NulFilterProxy(PROXY_PORT, ROUTER_IP, ROUTER_PORT);
        proxy.start();
        Log.d(TAG, "代理已启动: 127.0.0.1:" + PROXY_PORT);
        Toast.makeText(this, "代理已启动", Toast.LENGTH_SHORT).show();

        // 登录并加载页面
        new Thread(() -> {
            boolean success = loginAndSetCookie();
            runOnUiThread(() -> {
                if (success) {
                    Log.d(TAG, "登录成功，准备加载管理页面");
                    Toast.makeText(MainActivity.this, "登录成功，正在加载页面", Toast.LENGTH_SHORT).show();
                    webView.loadUrl("http://127.0.0.1:" + PROXY_PORT + "/wizard.asp");
                } else {
                    Log.e(TAG, "登录失败，尝试加载首页");
                    Toast.makeText(MainActivity.this, "登录失败，尝试加载首页", Toast.LENGTH_LONG).show();
                    webView.loadUrl("http://127.0.0.1:" + PROXY_PORT + "/");
                }
            });
        }).start();
    }

    private boolean loginAndSetCookie() {
        OkHttpClient client = new OkHttpClient();
        RequestBody body = new FormBody.Builder()
                .add("username", USERNAME)
                .add("password", PASSWORD)
                .build();
        Request request = new Request.Builder()
                .url("http://" + ROUTER_IP + "/cgi-bin/login.cgi")
                .post(body)
                .build();
        try (Response response = client.newCall(request).execute()) {
            Log.d(TAG, "登录响应码: " + response.code());
            List<String> setCookies = response.headers("Set-Cookie");
            if (setCookies.isEmpty()) {
                Log.e(TAG, "未收到 Set-Cookie");
                return false;
            }
            CookieManager cookieManager = CookieManager.getInstance();
            cookieManager.setAcceptCookie(true);
            for (String cookie : setCookies) {
                Log.d(TAG, "Set-Cookie: " + cookie);
                cookieManager.setCookie("http://127.0.0.1", cookie);
            }
            cookieManager.flush();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "登录请求异常: " + e.getMessage());
            return false;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (proxy != null) {
            proxy.stop();
        }
    }
}