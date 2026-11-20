package com.yllite.app;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
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
    private String versionName = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) { }
        webView = new WebView(this);
        webView.setBackgroundColor(getColor(R.color.console_surface));
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
            if (enabled == null) return false;
            ComponentName service = new ComponentName(MainActivity.this, YlAccessibilityService.class);
            for (String component : enabled.split(":")) {
                if (service.equals(ComponentName.unflattenFromString(component))) return true;
            }
            return false;
        }

        @JavascriptInterface
        public void openAccessibilitySettings() {
            runOnUiThread(() -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        }

        /** 按控制台选择打开抖音，双端模式下优先标准版。 */
        @JavascriptInterface
        public boolean openDouyin(String target) {
            String[] packages = {BotCore.PKG_DOUYIN, BotCore.PKG_DOUYIN_LITE};
            for (String pkg : packages) {
                if (("dy".equals(target) && !BotCore.PKG_DOUYIN.equals(pkg))
                        || ("lite".equals(target) && !BotCore.PKG_DOUYIN_LITE.equals(pkg))) continue;
                Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    runOnUiThread(() -> startActivity(intent));
                    BotCore.get().log("[bot] 已打开应用: " + pkg);
                    return true;
                }
            }
            BotCore.get().log("[bot] 未找到所选抖音应用");
            return false;
        }

        @JavascriptInterface
        public void startBot(String configJson) {
            BotCore bot = BotCore.get();
            BotCore.Config c = new BotCore.Config();
            try {
                JSONObject j = new JSONObject(configJson);
                c.targetPkg = j.optString("targetPkg", "any");
                c.sameRoomGrabLimit = Math.max(1, Math.min(99, j.optInt("sameRoomGrabLimit", 10)));
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
            bot.setRunning(true);
            bot.log("[bot] 真机模式启动: 目标=" + describeTarget(c.targetPkg)
                    + " 同房上限=" + c.sameRoomGrabLimit + " 速度=" + c.speed + "x");
        }

        @JavascriptInterface
        public void stopBot() {
            BotCore bot = BotCore.get();
            bot.setRunning(false);
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
                o.put("version", versionName);
                o.put("loginWall", YlAccessibilityService.loginWallTs > 0);
                o.put("grabs", bot.grabs);
                o.put("popups", bot.popups);
                o.put("engaged", bot.engaged);
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
