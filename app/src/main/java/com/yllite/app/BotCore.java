package com.yllite.app;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * 真实抖音抢福袋的共享状态机(v3.0.0)。
 * 目标界面: 真实抖音 / 抖音极速版(com.ss.android.ugc.aweme / .lite)的无障碍节点。
 * 真实节点的 text/contentDescription 随版本变化, 因此对关键文案用"包含"匹配 + 排除词,
 * 不再依赖本应用自带的模拟直播间。
 */
public final class BotCore {

    public static final String PKG_DOUYIN = "com.ss.android.ugc.aweme";
    public static final String PKG_DOUYIN_LITE = "com.ss.android.ugc.aweme.lite";

    public static class Config {
        public String targetPkg = "any";      // any: 抖音+极速版 | dy: 仅抖音 | lite: 仅极速版
        public int sameRoomGrabLimit = 10;    // 同一主播直播间累计参与上限(仅统计真实参与动作)
        public boolean grabNormal = true;     // 普通福袋
        public boolean grabSuper = true;      // 超级福袋
        public boolean grabHb = true;         // 直播红包
        public boolean structDetect = true;   // 无文本福袋入口的结构识别兜底
        public int openIntervalSec = 60;      // 同一房间两次自动点开福袋面板的最小间隔(秒), 防频繁查看触发风控
        public boolean ocrEnabled = true;     // H5/自绘弹窗的 OCR 识别与点击(API 30+)
        public boolean likeEnabled = true;
        public boolean followEnabled = false;
        public volatile double speed = 1.0; // 点击人类化延迟倍率(运行中可实时调整)
    }

    private static final BotCore INSTANCE = new BotCore();
    public static BotCore get() { return INSTANCE; }

    /** 独立定时线程: 延时点击不依赖主线程消息队列(主线程被无障碍调用占用时也能按时执行) */
    private final java.util.concurrent.ScheduledExecutorService sched =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "YLBotSched");
                t.setDaemon(true);
                return t;
            });
    private final Random rnd = new Random();

    public volatile boolean running = false;
    private volatile long runSession = 0;
    public volatile boolean dumpMode = false; // 观察模式: 只向 logcat(YLDump) 记录节点文本, 不点击
    public volatile Config cfg = new Config();

    public volatile int grabs = 0;
    public volatile int popups = 0, engaged = 0, skipped = 0;
    public final Map<String, Integer> grabCount = new HashMap<>();
    private final ArrayDeque<String> logs = new ArrayDeque<>();
    private final Map<String, Long> cooldown = new HashMap<>();

    private BotCore() {}

    public synchronized void configure(Config c) { this.cfg = c; }

    synchronized void setRunning(boolean enabled) {
        if (running != enabled) runSession++;
        running = enabled;
    }

    long runSession() { return runSession; }

    public synchronized boolean canAct(String key, long cooldownMs) {
        long now = System.currentTimeMillis();
        Long last = cooldown.get(key);
        if (last != null && now - last < cooldownMs) return false;
        cooldown.put(key, now);
        return true;
    }

    public synchronized void log(String line) {
        logs.addLast("[" + new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date()) + "] " + line);
        while (logs.size() > 300) logs.removeFirst();
        android.util.Log.i("YLBot", line);
    }

    public synchronized String logsText() {
        StringBuilder sb = new StringBuilder();
        for (String s : logs) sb.append(s).append('\n');
        return sb.toString();
    }

    public synchronized int grabCountOf(String room) {
        Integer n = grabCount.get(room);
        return n == null ? 0 : n;
    }
    public synchronized void addGrab(String room) { grabCount.put(room, grabCountOf(room) + 1); }

    public synchronized void recordConfirmedGrab(String room) {
        addGrab(room);
        grabs++;
    }

    /** 启动时重置计数(统计+同房上限), 由控制台"开始"触发。 */
    public synchronized void resetStats() {
        grabCount.clear();
        cooldown.clear();
        grabs = 0; popups = 0; engaged = 0; skipped = 0;
    }

    /** 目标包名过滤: any 匹配抖音/极速版及其衍生包名(前缀 com.ss.android.ugc.aweme)。 */
    public static boolean isTargetPackage(String pkg, Config c) {
        if (pkg == null || pkg.isEmpty()) return false;
        if ("dy".equals(c.targetPkg)) return PKG_DOUYIN.equals(pkg);
        if ("lite".equals(c.targetPkg)) return PKG_DOUYIN_LITE.equals(pkg);
        return pkg.startsWith(PKG_DOUYIN); // any
    }

    /** 在独立定时线程执行延时点击。 */
    public void scheduleClick(String what, long delayMs, Runnable action) {
        final long d = Math.max(200, delayMs);
        sched.schedule(() -> {
            if (!running) return;
            try {
                action.run();
            } catch (Throwable t) {
                android.util.Log.i("YLBot", "[dbg] 延时任务异常(" + what + "): " + t);
            }
        }, d, java.util.concurrent.TimeUnit.MILLISECONDS);
        log("[bot] 计划 " + (delayMs / 1000.0) + "s 后点击 " + what);
    }

    /** 抢按钮的人类化短延迟(0.3~0.9s), 避免倒计时结束瞬间机械响应。 */
    public long humanTapDelayMs() {
        long ms = 300 + rnd.nextInt(600);
        return (long) (ms / Math.max(0.5, cfg.speed));
    }

    // ---- 分类(对真实节点用"包含"匹配 + 排除词) ----

    public enum Kind { IN_ROOM, FUDAI, GRAB, CLAIM, DISMISS, RESULT, LOGIN_WALL, POPUP_MARK, CLOSE, ENGAGE, NOISE }

    static boolean isOcrDrawResult(String text) {
        String value = text.replaceAll("[\\s　]+", "");
        return value.equals("已参与") || value.startsWith("已参与抽奖") || value.equals("已加入")
                || value.startsWith("参与成功") || value.startsWith("已成功参与")
                || value.startsWith("任务已完成") || value.equals("已开奖")
                || value.startsWith("恭喜中奖") || value.startsWith("恭喜你中奖")
                || value.startsWith("很遗憾") || value.equals("未中奖");
    }

    public static Kind classify(CharSequence s) {
        if (s == null) return Kind.NOISE;
        String v = s.toString().trim();
        if (v.isEmpty()) return Kind.NOISE;

        // 登录墙: 未登录时点福袋只会跳登录页, 识别后不再反复尝试
        if (v.contains("验证并登录") || v.contains("登录发现更多精彩")) return Kind.LOGIN_WALL;

        // 抢按钮必须先于福袋入口判断("抢福袋"同时含两词); 长文本(视频文案/商品标题)不参与, 防误报
        // 注意: 人气红包的裸"抢/抢，按钮"不是目标(需花钻), 不在此匹配; 只认含"福袋/红包"文案的按钮
        if (v.length() <= 20 && v.contains("抢") && (v.contains("福袋") || v.contains("红包"))) return Kind.GRAB;
        if (v.length() <= 12 && (v.equals("立即抢") || v.equals("马上抢") || v.equals("开抢") || v.equals("开")
                || v.equals("点击领取") || v.equals("立即领取") || v.equals("去领取"))) return Kind.GRAB;

        // 任务型福袋的参与动作: "去发表评论/一键发表评论" (Douyin 预置任务评论), 点完需再点"发送"
        if (v.length() <= 20 && (v.contains("一键发表评论") || v.contains("发表评论")
                || (v.contains("一键") && v.contains("评论")))) return Kind.GRAB;

        // 领取类弹窗按钮(福利到账后主动点掉, 与"关闭"分开)
        if (v.contains("开心收下") || v.equals("我知道了") || v.contains("收下祝福")
                || v.contains("开心领取") || v.contains("马上领取")) return Kind.CLAIM;

        // 系统/抖音引导类弹窗(如"添加「关注的直播」到手机桌面"), 点弱化按钮关掉
        if (v.equals("不感兴趣") || v.equals("以后再说") || v.equals("暂不开启")) return Kind.DISMISS;

        // 结果标记(只记录, 不点击)
        if (v.contains("已参与") || v.contains("参与成功") || v.contains("已加入")) return Kind.RESULT;

        // 福袋/红包入口(排除规则/记录类文本, 避免误点; 长文本=视频文案/商品标题不算)
        // 评论("昵称：内容")用全角冒号+***排除; 半角冒号不能排除 —— 入口文案含倒计时"福袋，18:57"
        // 裸"红包"(人气红包入口)不是目标福袋(需花钻), 排除; 只认"直播红包/新年福袋"等完整福袋类入口
        if (v.length() <= 20 && (v.contains("福袋") || v.contains("红包")) && !v.contains("规则")
                && !v.contains("记录") && !v.contains("明细") && !v.contains("中奖")
                && !v.contains("说明") && !v.contains("：") && !v.contains("***")
                && !v.equals("红包") && !v.startsWith("红包，")) return Kind.FUDAI;

        // 弹窗标记(金额/优惠券类; 不含"查看规则"——福袋弹窗自身也有规则入口, 不可作为关闭依据)
        // 短文本限定: 商品标题("0.01元购买"等长文案)不算弹窗标记
        if (v.length() <= 16 && !v.contains("购买") && (v.contains("开心收下") || v.contains("￥")
                || v.contains("¥") || v.contains("0.01") || v.contains("无门槛")
                || v.contains("已存入") || v.contains("优惠券"))) return Kind.POPUP_MARK;

        if (v.equals("关闭")) return Kind.CLOSE;

        if (v.contains("点赞") || v.contains("关注") || v.contains("收藏") || v.contains("分享")) return Kind.ENGAGE;

        if (v.contains("说点什么") || v.contains("聊一聊") || v.contains("在线观众")) return Kind.IN_ROOM;

        return Kind.NOISE;
    }

    /** 解析福袋倒计时文案为剩余秒数; 支持 "4分1秒"/"18:57"/"59秒"; 解析失败返回 -1。 */
    public static long parseCountdownSeconds(String v) {
        if (v == null) return -1;
        String s = v.trim();
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(\\d+)\\s*分\\s*(\\d+)\\s*秒").matcher(s);
            if (m.find()) return Long.parseLong(m.group(1)) * 60 + Long.parseLong(m.group(2));
            m = java.util.regex.Pattern.compile("(\\d+)\\s*分").matcher(s);
            if (m.find()) return Long.parseLong(m.group(1)) * 60;
            m = java.util.regex.Pattern.compile("(\\d+)\\s*秒").matcher(s);
            if (m.find()) return Long.parseLong(m.group(1));
            m = java.util.regex.Pattern.compile("(\\d+):(\\d{2})").matcher(s);
            if (m.find()) return Long.parseLong(m.group(1)) * 60 + Long.parseLong(m.group(2));
        } catch (Exception ignored) { }
        return -1;
    }

}
