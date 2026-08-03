package com.example.routermanager;

import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.util.List;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private NulFilterProxy proxy;

    // 路由器参数：根据你的实际情况修改
    private static final String ROUTER_IP = "192.168.10.16";   // 常见路由器地址
    private static final int ROUTER_PORT = 80;
    private static final String USERNAME = "admin";          // 默认用户名
    private static final String PASSWORD = "admin";          // 默认密码
    private static final int PROXY_PORT = 8888;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        // 配置 WebView
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);                // 允许 JavaScript
        settings.setDomStorageEnabled(true);                // 允许 DOM Storage
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        webView.setWebViewClient(new WebViewClient());

        // 启动本地代理
        proxy = new NulFilterProxy(PROXY_PORT, ROUTER_IP, ROUTER_PORT);
        proxy.start();

        // 先登录获取 Cookie，再加载页面
        new Thread(() -> {
            boolean loginSuccess = loginAndSetCookie();
            runOnUiThread(() -> {
                if (loginSuccess) {
                    // 登录成功，加载管理页面（通过代理）
                    webView.loadUrl("http://127.0.0.1:" + PROXY_PORT + "/wizard.asp");
                } else {
                    // 登录失败，也可以尝试直接加载（某些路由器可能无需登录即可看到部分页面）
                    webView.loadUrl("http://127.0.0.1:" + PROXY_PORT + "/");
                }
            });
        }).start();
    }

    /**
     * 通过原生 HTTP 请求登录路由器，获取 Set-Cookie 并注入到 WebView 的 CookieManager
     * @return 登录成功返回 true
     */
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
            if (response.isSuccessful() || response.isRedirect()) {
                // 从响应头中提取所有 Set-Cookie
                List<String> cookies = response.headers("Set-Cookie");
                CookieManager cookieManager = CookieManager.getInstance();
                cookieManager.setAcceptCookie(true);
                // 注意：Cookie 要设置到代理域名 "127.0.0.1" 上，这样 WebView 通过代理访问时才会携带
                for (String cookie : cookies) {
                    cookieManager.setCookie("http://127.0.0.1", cookie);
                }
                cookieManager.flush();
                return true;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (proxy != null) {
            proxy.stop();
        }
    }
}