package com.yllite.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * YL-Lite 真实抢福袋版 APK 壳(v3.0.0)。
 * - Web 控制台运行在 WebView 内(本地资产, 完全离线)
 * - JS 桥(YLNative): 无障碍开关检测 / 打开抖音 / 启停真机监听(直接作用于真实抖音界面)
 */
public class MainActivity extends Activity {

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true); // targetSdk 30+ 默认 false, 本地资产页面需要
        webView.addJavascriptInterface(new Bridge(), "YLNative");
        webView.setWebViewClient(new WebViewClient());
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    class Bridge {

        @JavascriptInterface
        public boolean isServiceEnabled() {
            String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return enabled != null && enabled.contains("com.yllite.app");
        }

        @JavascriptInterface
        public void openAccessibilitySettings() {
            runOnUiThread(() -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        }

        /** 拉起已安装的抖音(优先标准版, 其次极速版)。 */
        @JavascriptInterface
        public void openDouyin() {
            runOnUiThread(() -> {
                String[] pkgs = {BotCore.PKG_DOUYIN, BotCore.PKG_DOUYIN_LITE};
                for (String p : pkgs) {
                    Intent i = getPackageManager().getLaunchIntentForPackage(p);
                    if (i != null) {
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                        BotCore.get().log("[bot] 已打开: " + p);
                        return;
                    }
                }
                BotCore.get().log("[bot] 未找到已安装的抖音/抖音极速版");
            });
        }

        @JavascriptInterface
        public void startBot(String configJson) {
            BotCore bot = BotCore.get();
            BotCore.Config c = new BotCore.Config();
            try {
                JSONObject j = new JSONObject(configJson);
                c.targetPkg = j.optString("targetPkg", "any");
                c.clickMode = j.optString("clickMode", "random");
                c.fixedSeconds = j.optInt("fixedSeconds", 5);
                c.randomMin = j.optInt("randomMin", 3);
                c.randomMax = j.optInt("randomMax", 8);
                c.sameRoomGrabLimit = j.optInt("sameRoomGrabLimit", 3);
                c.grabNormal = j.optBoolean("grabNormal", true);
                c.grabSuper = j.optBoolean("grabSuper", true);
                c.grabHb = j.optBoolean("grabHb", true);
                c.structDetect = j.optBoolean("structDetect", true);
                c.openIntervalSec = Math.max(10, Math.min(600, j.optInt("openIntervalSec", 60)));
                c.ocrEnabled = j.optBoolean("ocr", true);
                c.speed = j.optDouble("speed", 1.0);
                JSONObject y = j.optJSONObject("yanghao");
                if (y != null) { c.likeEnabled = y.optBoolean("like", true); c.followEnabled = y.optBoolean("follow", false); }
            } catch (Exception ignored) { }
            bot.configure(c);
            bot.resetStats();
            bot.running = true;
            bot.log("[bot] 真机模式启动: 目标=" + describeTarget(c.targetPkg)
                    + " 模式=" + c.clickMode + " 随机=" + c.randomMin + "-" + c.randomMax
                    + "s 同房上限=" + c.sameRoomGrabLimit + " 速度=" + c.speed + "x");
        }

        @JavascriptInterface
        public void stopBot() {
            BotCore bot = BotCore.get();
            bot.running = false;
            bot.log("[bot] 真机模式已停止");
        }

        /** 运行中实时调整点击速度倍率。 */
        @JavascriptInterface
        public void setSpeed(double v) {
            double s = Math.max(0.5, Math.min(8.0, v));
            BotCore.get().cfg.speed = s;
            BotCore.get().log("[bot] 点击速度 -> " + s + "x");
        }

        @JavascriptInterface
        public String botState() {
            BotCore bot = BotCore.get();
            try {
                JSONObject o = new JSONObject();
                o.put("running", bot.running);
                o.put("serviceEnabled", isServiceEnabled());
                o.put("targetPkg", bot.cfg.targetPkg);
                o.put("fgPkg", YlAccessibilityService.fgPkg == null ? "" : YlAccessibilityService.fgPkg);
                o.put("room", YlAccessibilityService.currentRoom == null ? "" : YlAccessibilityService.currentRoom);
                o.put("loginWall", YlAccessibilityService.loginWallTs > 0);
                o.put("grabs", bot.grabs);
                o.put("popups", bot.popups);
                o.put("engaged", bot.engaged);
                o.put("escaped", bot.escaped);
                o.put("skipped", bot.skipped);
                JSONArray logs = new JSONArray();
                String[] lines = bot.logsText().split("\n");
                for (int i = Math.max(0, lines.length - 40); i < lines.length; i++) logs.put(lines[i]);
                o.put("logs", logs);
                return o.toString();
            } catch (Exception e) {
                return "{\"running\":false,\"error\":\"" + e.getMessage() + "\"}";
            }
        }
    }

    private static String describeTarget(String t) {
        if ("dy".equals(t)) return "抖音";
        if ("lite".equals(t)) return "抖音极速版";
        return "抖音+极速版";
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
