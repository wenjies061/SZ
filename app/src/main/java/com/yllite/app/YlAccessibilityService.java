package com.yllite.app;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * YL-Lite 真实抖音抢福袋服务(v3.0.0)。
 * 无障碍配置不再限定包名白名单(见 res/xml/yl_accessibility_config.xml),
 * 运行时仅对 BotCore.isTargetPackage(抖音/抖音极速版)的前台窗口行动;
 * 同一直播间身份 = TYPE_WINDOW_STATE_CHANGED 上报的 Activity 类名。
 */
public class YlAccessibilityService extends AccessibilityService {

    private long lastScanTs = 0;
    private static final long SCAN_THROTTLE_MS = 120;

    /** 最近前台包名/房间(Activity 类名), 供控制台展示与同房上限判定 */
    public static volatile String fgPkg = "";
    public static volatile String currentRoom = "douyin";
    /** 当前直播间主播名(同房身份, 所有直播间共用同一个 Activity 类名, 不能用类名作房间 key) */
    public static volatile String currentHost = "";
    /** 最近一次检出抖音登录墙的时间戳(0=无) */
    public static volatile long loginWallTs = 0;

    private long lastDumpTs = 0;
    private android.content.BroadcastReceiver adbCtl;
    private final android.os.Handler watchdog = new android.os.Handler(android.os.Looper.getMainLooper());
    private long lastHeartbeatTs = 0;
    /** 扫描线程与阻塞自愈: 无障碍 IPC 可能卡住, 扫描绝不占用主线程, 卡死则重建线程 */
    private java.util.concurrent.ExecutorService scanner =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile boolean scanBusy = false;
    private volatile long scanStartTs = 0;
    private volatile long scanHangLoggedTs = 0;
    private int ticks = 0;
    private final Runnable watchTask = new Runnable() {
        @Override public void run() {
            BotCore bot = BotCore.get();
            try {
                if (ticks < 3) { ticks++; bot.log("[dbg] watchdog tick " + ticks); }
                if (bot.running || bot.dumpMode) scheduleScan();
                long nowHb = System.currentTimeMillis();
                if (nowHb - lastHeartbeatTs > 10000) {
                    lastHeartbeatTs = nowHb;
                    bot.log("[dbg] 心跳 run=" + bot.running + " dump=" + bot.dumpMode
                            + " busy=" + scanBusy + " fg=" + fgPkg);
                }
            } catch (Throwable ignored) { }
            watchdog.postDelayed(this, 900); // 事件静默时仍保持轮询(原版 Auto.js 的 while 轮询思路)
        }
    };

    /** 在扫描线程执行一次扫描和观察模式转储，阻塞超过 20 秒后重建线程。 */
    private void scheduleScan() {
        final BotCore bot = BotCore.get();
        long now = System.currentTimeMillis();
        if (!scanBusy) {
            scanBusy = true;
            scanStartTs = now;
            try {
                scanner.execute(() -> {
                    try {
                        if (bot.running) scan();
                        if (bot.dumpMode) {
                            long n = System.currentTimeMillis();
                            if (n - lastDumpTs >= 2000) { lastDumpTs = n; dumpNodes(); }
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        scanBusy = false;
                    }
                });
            } catch (Throwable t) {
                scanBusy = false;
            }
        } else if (now - scanStartTs > 20000 && now - scanHangLoggedTs > 15000) {
            scanHangLoggedTs = now;
            bot.log("[dbg] 扫描阻塞超过20s, 重建扫描线程");
            try { scanner.shutdownNow(); } catch (Throwable ignored) { }
            scanner = java.util.concurrent.Executors.newSingleThreadExecutor();
            scanBusy = false;
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        BotCore bot = BotCore.get();
        CharSequence pkgCs = event.getPackageName();
        String pkg = pkgCs == null ? "" : pkgCs.toString();
        if (!pkg.isEmpty()) fgPkg = pkg;
        if (!BotCore.isTargetPackage(pkg, bot.cfg)) return;

        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence cn = event.getClassName();
            if (cn != null && cn.length() > 0) currentRoom = cn.toString();
        }

        if (!bot.running && !bot.dumpMode) return;
        long now = System.currentTimeMillis();
        if (now - lastScanTs < SCAN_THROTTLE_MS) return;
        lastScanTs = now;
        scheduleScan();
    }

    /** 观察模式: 遍历全部目标窗口(直播间为覆盖窗口, 需 getWindows()), 记入 logcat(tag=YLDump)。 */
    private void dumpNodes() {
        BotCore bot = BotCore.get();
        StringBuilder sb = new StringBuilder();
        int lines = 0;
        android.graphics.Rect r = new android.graphics.Rect();
        for (AccessibilityNodeInfo root : targetRoots(bot)) {
            java.util.ArrayDeque<AccessibilityNodeInfo> queue = new java.util.ArrayDeque<>();
            queue.add(root);
            int visited = 0;
            while (!queue.isEmpty() && visited < 1500 && lines < 320) {
                AccessibilityNodeInfo n = queue.poll();
                if (n == null) continue;
                visited++;
                for (int i = 0; i < n.getChildCount() && visited < 1500; i++) {
                    AccessibilityNodeInfo c = n.getChild(i);
                    if (c != null) queue.add(c);
                }
                CharSequence text = n.getText();
                CharSequence desc = n.getContentDescription();
                String viewId = n.getViewIdResourceName();
                boolean empty = (text == null || text.length() == 0) && (desc == null || desc.length() == 0)
                        && (viewId == null || viewId.isEmpty());
                if (empty && !n.isClickable() && lines > 40) continue; // 前几十行保留骨架
                sb.setLength(0);
                CharSequence cls = n.getClassName();
                sb.append("C=[").append(cls == null ? "?" : cls.toString().replace("android.widget.", "w.").replace("android.view.", "v.").replace("com.ss.android.ugc.aweme.", "dy.")).append(']');
                sb.append(" T=[").append(text == null ? "" : abbreviate(text)).append(']');
                sb.append(" D=[").append(desc == null ? "" : abbreviate(desc)).append(']');
                if (viewId != null && !viewId.isEmpty())
                    sb.append(" I=[").append(viewId.substring(viewId.indexOf(":id/") + 1)).append(']');
                n.getBoundsInScreen(r);
                sb.append(" XY=[").append(r.centerX()).append(',').append(r.centerY()).append(']');
                sb.append(" B=[").append(r.left).append(',').append(r.top).append(',').append(r.right).append(',').append(r.bottom).append(']');
                if (n.isClickable()) sb.append(" CLICK");
                android.util.Log.i("YLDump", sb.toString());
                lines++;
            }
            if (visited >= 900) android.util.Log.i("YLDump", "...(截断, visited=" + visited + ")");
        }
    }

    /** 收集所有属于目标包名的窗口根节点(含覆盖窗口); 优先 active window。 */
    private java.util.List<AccessibilityNodeInfo> targetRoots(BotCore bot) {
        java.util.List<AccessibilityNodeInfo> roots = new java.util.ArrayList<>();
        boolean dbg = bot.dumpMode;
        if (dbg) android.util.Log.i("YLBot", "[dbg] getRootInActiveWindow...");
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (dbg) android.util.Log.i("YLBot", "[dbg] active=" + (active == null ? "null" : "ok"));
        if (active != null) {
            CharSequence p = active.getPackageName();
            if (p != null && BotCore.isTargetPackage(p.toString(), bot.cfg)) roots.add(active);
        }
        try {
            if (dbg) android.util.Log.i("YLBot", "[dbg] getWindows...");
            java.util.List<android.view.accessibility.AccessibilityWindowInfo> ws = getWindows();
            if (dbg) android.util.Log.i("YLBot", "[dbg] windows=" + (ws == null ? "null" : ws.size()));
            for (android.view.accessibility.AccessibilityWindowInfo w : ws) {
                AccessibilityNodeInfo r = w.getRoot() == null ? null : w.getRoot();
                if (r == null) continue;
                CharSequence p = r.getPackageName();
                if (p == null || !BotCore.isTargetPackage(p.toString(), bot.cfg)) continue;
                boolean dup = false;
                for (AccessibilityNodeInfo x : roots) if (x.equals(r)) { dup = true; break; }
                if (!dup) roots.add(r);
            }
        } catch (Exception ignored) { }
        return roots;
    }

    private static String abbreviate(CharSequence s) {
        String v = s.toString().replace('\n', ' ').replace('\r', ' ');
        return v.length() > 60 ? v.substring(0, 60) + "…" : v;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            registerAdbControl();
        }
        watchdog.removeCallbacks(watchTask);
        watchdog.postDelayed(watchTask, 500);
        BotCore.get().log("[bot] 无障碍服务已连接(监听抖音/抖音极速版)");
    }

    /** adb 调试控制: am broadcast 启停/观察模式(仅注册为运行时 receiver, 不常驻) */
    private void registerAdbControl() {
        if (adbCtl != null) return;
        adbCtl = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context ctx, android.content.Intent i) {
                BotCore bot = BotCore.get();
                String a = i.getAction() == null ? "" : i.getAction();
                switch (a) {
                    case "com.yllite.app.START":
                        bot.running = true;
                        bot.log("[bot] adb: 启动真机模式");
                        break;
                    case "com.yllite.app.STOP":
                        bot.running = false;
                        bot.log("[bot] adb: 停止真机模式");
                        break;
                    case "com.yllite.app.DUMP_ON":
                        bot.dumpMode = true;
                        bot.log("[bot] adb: 观察模式开启");
                        break;
                    case "com.yllite.app.DUMP_OFF":
                        bot.dumpMode = false;
                        bot.log("[bot] adb: 观察模式关闭");
                        break;
                }
            }
        };
        android.content.IntentFilter f = new android.content.IntentFilter();
        f.addAction("com.yllite.app.START");
        f.addAction("com.yllite.app.STOP");
        f.addAction("com.yllite.app.DUMP_ON");
        f.addAction("com.yllite.app.DUMP_OFF");
        String shellPermission = "android.permission.DUMP";
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(adbCtl, f, shellPermission, null, android.content.Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(adbCtl, f, shellPermission, null);
        }
    }

    private void scan() {
        BotCore bot = BotCore.get();

        String room0 = (currentHost != null && !currentHost.isEmpty()) ? currentHost : currentRoom;
        if (room0 == null || room0.isEmpty()) room0 = "douyin";
        final String room = room0;
        // 商品逃逸仅限直播间上下文(视频流里也有商品卡 tv_title, back() 会退出应用)
        boolean inLiveContext = currentRoom != null
                && currentRoom.toLowerCase(java.util.Locale.ROOT).contains("live");

        for (AccessibilityNodeInfo root : targetRoots(bot)) {
            scanRoot(bot, root, room, inLiveContext);
        }
    }

    private void scanRoot(BotCore bot, AccessibilityNodeInfo root, String room, boolean inLiveContext) {
        if (groupPanelPending(System.currentTimeMillis())) {
            maybeOcrH5Dialog(room, false, 0);
            return;
        }
        boolean popupSeen = false;
        boolean anchorSeen = false;
        boolean fudaiSeen = false;
        boolean h5Backdrop = false;
        boolean fudaiPanelSeen = false;
        java.util.List<android.graphics.Rect> blockedRects = new java.util.ArrayList<>();
        AccessibilityNodeInfo sendNode = null;
        AccessibilityNodeInfo closeNode = null;
        AccessibilityNodeInfo groupEntry = null;
        AccessibilityNodeInfo regularEntry = null;
        String regularLabel = null;
        android.graphics.Rect screenR = new android.graphics.Rect();
        root.getBoundsInScreen(screenR);
        final int scrW = Math.max(1, screenR.width());
        final int scrH = Math.max(1, screenR.height());
        java.util.ArrayDeque<AccessibilityNodeInfo> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        int visited = 0;

        while (!queue.isEmpty() && visited < 1500) {
            AccessibilityNodeInfo n = queue.poll();
            if (n == null) continue;
            visited++;
            if (visited % 100 == 0 && bot.dumpMode) {
                android.util.Log.i("YLBot", "[dbg] scan visited=" + visited);
            }
            for (int i = 0; i < n.getChildCount() && visited < 1500; i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) queue.add(c);
            }

            CharSequence text = n.getText();
            CharSequence desc = n.getContentDescription();
            String viewId = n.getViewIdResourceName();
            boolean isButton = n.getClassName() != null && n.getClassName().toString().contains("Button");

            // 主播名 => 直播间身份(同房上限按主播名计, 换房自动归零)
            if (viewId != null && viewId.endsWith(":id/user_name")
                    && text != null && text.length() > 0) {
                currentHost = text.toString().trim();
            }

            if (GroupBuyPanel.ENTRY.contentEquals(desc == null ? "" : desc) && n.isVisibleToUser()) {
                groupEntry = n;
                fudaiSeen = true;
                continue;
            }

            // H5/自绘弹窗探测: 全屏可点击背景层 desc="关闭"(弹窗内容无障碍不可见)
            if (desc != null && "关闭".contentEquals(desc) && n.isClickable()) {
                android.graphics.Rect hb = new android.graphics.Rect();
                n.getBoundsInScreen(hb);
                if (hb.width() >= scrW * 0.85f && hb.height() >= scrH * 0.85f) h5Backdrop = true;
            }

            // 商品页逃逸已停用: tv_title 商品卡在正常直播间(电商直播)也存在, 同轮扫描先遇到它就误判
            // => back() 会直接退出直播间(表现为"反复跳出直播间")。商品页误入改为用户手动返回, 这里只记录。
            if (inLiveContext && viewId != null && viewId.endsWith(":id/tv_title") && !isButton && !anchorSeen) {
                if (bot.canAct("escape", 15000)) {
                    bot.log("[room] 疑似商品卡片(tv_title, 无锚点) —— 仅记录, 不 back()");
                }
                continue;
            }

            CharSequence label = (text != null && text.length() > 0) ? text : desc;
            BotCore.Kind kind = BotCore.classify(label);

            // 普通福袋面板标记(原生节点): 标题"福袋"/参与条件/发送评论/去发表评论
            if (label != null) {
                String lv = label.toString();
                if (lv.equals("福袋")) {
                    Rect bounds = new Rect();
                    n.getBoundsInScreen(bounds);
                    if (!bounds.isEmpty() && bounds.centerY() > screenR.top + scrH * 0.30f) fudaiPanelSeen = true;
                } else if (lv.contains("参与条件") || lv.contains("发送评论") || lv.contains("去发表评论")) {
                    fudaiPanelSeen = true;
                }
                // 房间左上角出现非目标图标(红包/券): 把其矩形加入屏蔽表, 结构识别只避开这些区域
                // (同排其他空白图标仍可能是福袋, 不能整排禁用)
                if (lv.equals("红包") || lv.contains("电商优惠券") || lv.contains("双11消费券")
                        || lv.contains("优惠券")) {
                    android.graphics.Rect br = new android.graphics.Rect();
                    n.getBoundsInScreen(br);
                    if (br.width() > 0 && br.height() > 0) {
                        br.inset(-4, -4);
                        blockedRects.add(br);
                    }
                }
                // 发送按钮: 需在面板上下文/自动发送窗口内才处理(避免误发普通评论)
                // 只取屏幕下半区且边界有效的节点(上半区的同名节点是多窗口重影)
                if (lv.equals("发送") && sendNode == null) {
                    android.graphics.Rect sr = new android.graphics.Rect();
                    n.getBoundsInScreen(sr);
                    if (sr.width() > 0 && sr.height() > 0 && sr.centerY() > scrH * 0.55f) sendNode = n;
                }
            }

            switch (kind) {
                case IN_ROOM:
                    anchorSeen = true;
                    loginWallTs = 0;
                    if (bot.canAct("inroom", 10000)) bot.log("[room] 在抖音直播间内(" + label + ")");
                    break;
                case LOGIN_WALL:
                    loginWallTs = System.currentTimeMillis();
                    if (bot.canAct("loginwall", 20000)) {
                        bot.log("[room] 检测到抖音登录墙 —— 未登录无法抢福袋, 请先登录抖音");
                    }
                    break;
                case FUDAI: {
                    fudaiSeen = true;
                    String v = label.toString();
                    boolean isSuper = v.contains("超级");
                    boolean isHb = v.contains("红包");
                    if ((isSuper && !bot.cfg.grabSuper) || (isHb && !bot.cfg.grabHb)
                            || (!isSuper && !isHb && !bot.cfg.grabNormal)) break;
                    if (regularEntry == null && n.isVisibleToUser()) {
                        Rect bounds = new Rect();
                        n.getBoundsInScreen(bounds);
                        if (!bounds.isEmpty() && bounds.centerY() < screenR.top + scrH * 0.42f) {
                            regularEntry = n;
                            regularLabel = v;
                        }
                    }
                    break;
                }
                case GRAB: {
                    // 强特征按钮(抢福袋/一键发表评论)可在福袋弹窗遮挡评论框时生效; 弱特征仍需锚点
                    String gl = label.toString();
                    boolean strong = gl.contains("福袋") || gl.contains("红包")
                            || gl.contains("一键") || gl.contains("评论");
                    if (!anchorSeen && !strong) break;
                    // 先剔除重影节点(负宽高), 避免其抢先消耗点击冷却, 导致真按钮永远点不到
                    android.graphics.Rect gRect = new android.graphics.Rect();
                    n.getBoundsInScreen(gRect);
                    if (gRect.width() <= 0 || gRect.height() <= 0) break;
                    fudaiSeen = true;
                    int done = bot.grabCountOf(room);
                    if (done >= bot.cfg.sameRoomGrabLimit) {
                        if (bot.canAct("limitlog", 5000)) {
                            bot.skipped++;
                            bot.log("[room] 达到同房上限(" + bot.cfg.sameRoomGrabLimit + "), 跳过[" + label + "]");
                        }
                        break;
                    }
                    if (!bot.canAct("grabclick:" + room, 3000)) break;
                    final AccessibilityNodeInfo target = n;
                    final String what = label.toString();
                    // 普通福袋: 点"发表评论"后会出现"发送"按钮, 打开 30 秒自动发送窗口
                    if (what.contains("发表评论")) awaitSendUntilTs = System.currentTimeMillis() + 30000L;
                    bot.scheduleClick(what, bot.humanTapDelayMs(), () -> click(target, () -> {
                        bot.addGrab(room);
                        bot.grabs++;
                        bot.log("[room] >> 已点击[" + what + "] (真实无障碍点击)");
                    }));
                    break;
                }
                case CLAIM: {
                    if (!bot.canAct("claim:" + label, 6000)) break;
                    bot.popups++;
                    final AccessibilityNodeInfo target = n;
                    final String what = label.toString();
                    bot.scheduleClick(what, bot.humanTapDelayMs(),
                            () -> click(target, () -> bot.log("[room] 领取弹窗: 点击[" + what + "]")));
                    break;
                }
                case DISMISS: {
                    if (!bot.canAct("dismiss:" + label, 5000)) break;
                    final AccessibilityNodeInfo target = n;
                    final String what = label.toString();
                    click(target, () -> bot.log("[room] 引导弹窗: 点击[" + what + "]"));
                    break;
                }
                case RESULT:
                    if (label != null && label.toString().contains("已参与") && closePanelAfterTs == 0) {
                        // 面板内首次出现"已参与" => 4 秒后收起面板(只设置一次, 否则每轮刷新永远不到时间)
                        closePanelAfterTs = System.currentTimeMillis() + 4000;
                    }
                    if (bot.canAct("result", 6000)) bot.log("[room] 福袋结果: " + label);
                    break;
                case POPUP_MARK:
                    popupSeen = true;
                    break;
                case CLOSE:
                    closeNode = n;
                    break;
                case ENGAGE: {
                    if (!anchorSeen) break; // 互动仅限直播间内, 视频流中的"点赞/关注"不触发
                    String what = label.toString();
                    // 精确匹配按钮文案: 评论区事件("xxx分享了直播")等长文本不算互动按钮
                    boolean isLike = what.equals("点赞") || what.equals("小心心");
                    boolean isFollow = what.equals("关注");
                    if (!isLike && !isFollow && !what.equals("收藏") && !what.equals("分享")) break;
                    boolean enabled = isLike ? bot.cfg.likeEnabled : isFollow ? bot.cfg.followEnabled : true;
                    if (!enabled) break;
                    if (!bot.canAct("engage:" + what, 6000)) break;
                    bot.engaged++;
                    final AccessibilityNodeInfo target = n;
                    click(target, () -> bot.log("[room] 互动: " + what));
                    break;
                }
                default:
                    break;
            }
        }

        if (anchorSeen && !fudaiPanelSeen) {
            if (groupEntry != null && bot.cfg.grabNormal && bot.cfg.ocrEnabled
                    && android.os.Build.VERSION.SDK_INT >= 30) {
                openGroupBuyPanel(bot, groupEntry, currentHost.isEmpty() ? room : currentHost);
            } else if (regularEntry != null && bot.grabCountOf(room) < bot.cfg.sameRoomGrabLimit
                    && bot.canAct("panelopen:" + room, Math.max(10, bot.cfg.openIntervalSec) * 1000L)) {
                final AccessibilityNodeInfo target = regularEntry;
                final String what = regularLabel;
                bot.scheduleClick(what, bot.humanTapDelayMs(), () -> click(target, () -> {
                    panelOpenedByBot = true;
                    panelOpenSinceTs = System.currentTimeMillis();
                    bot.log("[room] 已点开福袋入口[" + what + "]");
                }));
            }
        }

        if (groupPanelPending(System.currentTimeMillis())) return;

        // 普通福袋: 点过"去发表评论"后出现"发送"按钮, 自动发送完成任务(仅福袋上下文或自动发送窗口内)
        long nowSend = System.currentTimeMillis();
        boolean sendWindow = nowSend < awaitSendUntilTs;
        if (sendNode != null && (fudaiPanelSeen || sendWindow) && bot.canAct("send", 4000)) {
            final AccessibilityNodeInfo target = sendNode;
            awaitSendUntilTs = 0;
            bot.log("[room] 普通福袋 -> 点击[发送]完成任务评论");
            click(target, () -> {
                bot.addGrab(room);
                bot.grabs++;
                closePanelAfterTs = System.currentTimeMillis() + 3000; // 参与完成, 3 秒后收起面板
                bot.log("[room] >> 已发送评论(参与完成)");
            });
        }

        // 福利弹窗关闭 —— 已停用点击(2026-11-19): 抖音 41.1.0 上该路径会反复命中"关闭"元素,
        // 疑似导致直播间被退出。弹窗处理统一交给 OCR(非目标面板识别 + 安全 back), 这里只记录。
        if (!fudaiSeen && popupSeen && closeNode != null && bot.canAct("popuplog", 15000)) {
            bot.log("[room] 检测到关闭按钮(仅记录, 不点击)");
        }

        // 结构识别兜底: 房间内没有任何带文本的福袋入口时才启用(有文字入口时绝不用 ——
        // 左上角无标签图标可能是人气/会员红包, 误点会打开非目标面板)
        // 注: 不依赖 currentRoom(服务可能在进房后才启用, 收不到窗口切换事件), 锚点存在即视为直播间
        if (anchorSeen && !fudaiSeen && bot.cfg.structDetect
                && bot.canAct("structscan", 12000)) {
            AccessibilityNodeInfo sf = findStructFudai(root, blockedRects);
            if (sf != null) {
                int done = bot.grabCountOf(room);
                if (done < bot.cfg.sameRoomGrabLimit
                        && bot.canAct("panelopen:" + room, Math.max(10, bot.cfg.openIntervalSec) * 1000L)) {
                    final String pos;
                    android.graphics.Rect rr = new android.graphics.Rect();
                    sf.getBoundsInScreen(rr);
                    pos = rr.centerX() + "," + rr.centerY();
                    bot.log("[room] 结构识别到无文本福袋入口(" + pos + "), 计划手势点击");
                    bot.scheduleClick("福袋图标", bot.humanTapDelayMs(), () -> {
                        try {
                            bot.log("[dbg] 福袋图标点击任务执行");
                            // 点击时刻重新抓取窗口根节点(旧节点在延时后已失效)
                            for (AccessibilityNodeInfo r2 : targetRoots(bot)) {
                                AccessibilityNodeInfo fresh = findStructFudai(r2, blockedRects);
                                if (fresh == null) continue;
                                android.graphics.Rect fr = new android.graphics.Rect();
                                fresh.getBoundsInScreen(fr);
                                if (gestureTap(fr.centerX(), fr.centerY())) {
                                    panelOpenedByBot = true;
                                    panelOpenSinceTs = System.currentTimeMillis();
                                    lastTappedIconKey = iconKey(fr.centerX(), fr.centerY());
                                    lastStructKey = lastTappedIconKey;
                                    lastStructTapTs = System.currentTimeMillis();
                                    lastStructSawPanel = false;
                                    bot.log("[room] >> 手势点击福袋图标(" + fr.centerX() + "," + fr.centerY() + ")");
                                } else {
                                    bot.log("[room] 手势点击失败");
                                }
                                return;
                            }
                            bot.log("[room] 点击时福袋图标已消失");
                        } catch (Throwable t) {
                            bot.log("[room] 结构点击异常: " + t.getClass().getSimpleName());
                        }
                    });
                }
            }
        }

        // ---- 面板管理: 仅当有"面板内容证据"(OCR 关键词/原生面板标记)时才视为弹窗 ----
        // 注意: 本版抖音直播间本身就带常驻全屏 desc=关闭 层(id/bg+), 仅凭它判定弹窗会在房间里
        // 空转 OCR 并误按 back() 退出直播间 —— 这是"反复退出直播间"的另一真凶。
        if (h5Backdrop) lastBackdropSeenTs = System.currentTimeMillis();
        long nowPc = System.currentTimeMillis();
        if (fudaiPanelSeen) lastPanelSeenTs = nowPc;
        boolean panelSignal = fudaiPanelSeen
                || (lastPanelKeywordTs > 0 && nowPc - lastPanelKeywordTs < 15000)
                || (lastPanelSeenTs > 0 && nowPc - lastPanelSeenTs < 3000);
        boolean backdropRecent = h5Backdrop || (lastBackdropSeenTs > 0 && nowPc - lastBackdropSeenTs < 5000);
        if (panelSignal && panelFirstSeenTs == 0) panelFirstSeenTs = nowPc;
        // 结构点击试错: 点击后 15 秒内出现面板 => 该图标有效; 否则排除并换下一个候选
        if (panelSignal && lastStructTapTs > 0 && nowPc - lastStructTapTs < 15000) {
            lastStructSawPanel = true;
        }
        if (!panelSignal && lastStructTapTs > 0 && nowPc - lastStructTapTs > 15000) {
            if (!lastStructSawPanel && lastStructKey != null) {
                rejectedIconKeys.add(lastStructKey);
                bot.log("[room] 图标无面板响应, 排除并换下一个候选(" + lastStructKey + ")");
            }
            lastStructTapTs = 0;
        }
        // OCR 统一探测: 面板信号存在时快速识别(1.6s); 否则运行期间低频(8s)做弹窗/横幅探测
        // (不依赖背景层/锚点 —— 本版抖音的券弹窗可以没有背景层, 评论框也可能仍然可见)
        if (panelSignal) {
            maybeOcrH5Dialog(room, false, 0);
        } else {
            maybeOcrH5Dialog(room, anchorSeen, 8000);
        }
        if (panelSignal && bot.canAct("panclose", 8000)) {
            boolean afterPart = closePanelAfterTs > 0 && nowPc >= closePanelAfterTs;
            boolean idle = panelOpenedByBot && ocrIdlePasses >= 5;
            boolean tooLong = panelFirstSeenTs > 0 && nowPc - panelFirstSeenTs > 90000;
            // 二次确认面板仍新鲜(3 秒内): 面板已关时绝不 back, 防止误退直播间
            boolean fresh = fudaiPanelSeen || (lastPanelKeywordTs > 0 && nowPc - lastPanelKeywordTs < 3000);
            if (fresh && (afterPart || idle || tooLong)) {
                lastPanelCloseTs = nowPc;
                ocrIdlePasses = 0;
                panelOpenedByBot = false;
                panelOpenSinceTs = 0;
                panelFirstSeenTs = 0;
                closePanelAfterTs = 0;
                lastPanelKeywordTs = 0;
                bot.log("[room] 福袋面板" + (afterPart ? "参与完成" : idle ? "无可操作内容" : "停留过久") + ", back() 收起");
                performGlobalAction(GLOBAL_ACTION_BACK);
            }
        }
        if (!panelSignal) {
            panelOpenedByBot = false;
            panelOpenSinceTs = 0;
            panelFirstSeenTs = 0;
            closePanelAfterTs = 0;
            ocrIdlePasses = 0;
        }
    }

    private volatile long groupEntryPendingUntilTs;
    private volatile long lastGroupPanelSeenTs;
    private String groupRoundRoom = "";
    private long groupRoundDeadlineTs;
    private GroupBuyPanel.Stage groupAction = GroupBuyPanel.Stage.UNKNOWN;
    private GroupBuyPanel.Stage groupLastStage = GroupBuyPanel.Stage.UNKNOWN;
    private boolean groupConfirmed;

    private boolean groupPanelPending(long now) {
        return now < groupEntryPendingUntilTs || (lastGroupPanelSeenTs > 0 && now - lastGroupPanelSeenTs < 4500);
    }

    private void openGroupBuyPanel(BotCore bot, AccessibilityNodeInfo node, String room) {
        if (!node.refresh() || !node.isVisibleToUser()) return;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        CharSequence desc = node.getContentDescription();
        if (desc == null || !GroupBuyPanel.ENTRY.contentEquals(desc) || bounds.isEmpty()
                || !isTargetActive(bot, node.getWindowId())
                || bot.grabCountOf(room) >= bot.cfg.sameRoomGrabLimit
                || !bot.canAct("panelopen:" + room, Math.max(10, bot.cfg.openIntervalSec) * 1000L)) return;
        // 这一入口的父容器 ACTION_CLICK 会返回 true，但只有触摸事件会打开面板。
        groupEntryPendingUntilTs = System.currentTimeMillis() + 6500L;
        if (gestureTap(bounds.centerX(), bounds.centerY())) {
            bot.log("[room] 已触摸团购福袋入口，等待面板确认");
            maybeOcrH5Dialog(room, false, 0);
        } else {
            groupEntryPendingUntilTs = 0;
        }
    }

    private boolean isTargetActive(BotCore bot, int windowId) {
        if (!bot.running) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || (windowId >= 0 && root.getWindowId() != windowId)) return false;
        CharSequence pkg = root.getPackageName();
        return pkg != null && BotCore.isTargetPackage(pkg.toString(), bot.cfg);
    }

    private void handleGroupBuyPanel(BotCore bot, GroupBuyPanel panel, String room, long now) {
        lastGroupPanelSeenTs = now;
        groupEntryPendingUntilTs = 0;
        if (!bot.cfg.grabNormal) return;

        long deadline = panel.drawSeconds > 0 ? now + panel.drawSeconds * 1000 : 0;
        boolean sameRound = room.equals(groupRoundRoom) && now < groupRoundDeadlineTs + 6000
                && (deadline == 0 || Math.abs(deadline - groupRoundDeadlineTs) < 15000);
        if (!sameRound && deadline > 0) {
            groupRoundRoom = room;
            groupRoundDeadlineTs = deadline;
            groupAction = GroupBuyPanel.Stage.UNKNOWN;
            groupLastStage = GroupBuyPanel.Stage.UNKNOWN;
            groupConfirmed = false;
        }
        if (panel.stage != GroupBuyPanel.Stage.UNKNOWN && panel.stage != groupLastStage) {
            groupLastStage = panel.stage;
            switch (panel.stage) {
                case COMMENT: bot.log("[ocr] 团购福袋面板已确认，待发送任务评论"); break;
                case START_WATCH: bot.log("[ocr] 团购福袋评论任务完成，待开始观看直播任务"); break;
                case WATCHING: bot.log("[ocr] 团购福袋观看任务进行中，请留在当前直播间"); break;
                case PARTICIPATED: bot.log("[ocr] 团购福袋已参与抽奖，等待开奖"); break;
                default: break;
            }
        }
        if (panel.stage == GroupBuyPanel.Stage.PARTICIPATED) {
            if (room.equals(groupRoundRoom)) {
                if (!groupConfirmed && groupAction != GroupBuyPanel.Stage.UNKNOWN) bot.recordConfirmedGrab(room);
                groupConfirmed = true;
            }
            return;
        }
        if ((panel.stage != GroupBuyPanel.Stage.COMMENT && panel.stage != GroupBuyPanel.Stage.START_WATCH)
                || groupConfirmed || groupRoundDeadlineTs <= now || !room.equals(groupRoundRoom)
                || panel.stage.ordinal() <= groupAction.ordinal()
                || bot.grabCountOf(room) >= bot.cfg.sameRoomGrabLimit || !isTargetActive(bot, -1)) return;
        if (gestureTap(panel.button.centerX(), panel.button.centerY())) {
            groupAction = panel.stage;
            bot.log("[ocr] 团购福袋已触摸按钮，等待任务状态更新");
        }
    }

    /** 执行真实点击: 优先 ACTION_CLICK, 失败则手势坐标点击(自绘 View 无点击事件时仍可命中)。 */
    private void click(AccessibilityNodeInfo node, Runnable onSuccess) {
        AccessibilityNodeInfo cur = node;
        for (int i = 0; i < 5 && cur != null; i++) {
            if (cur.isClickable() && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                if (onSuccess != null) onSuccess.run();
                return;
            }
            cur = cur.getParent();
        }
        android.graphics.Rect r = new android.graphics.Rect();
        node.getBoundsInScreen(r);
        if (r.width() <= 0 || r.height() <= 0) {
            BotCore.get().log("[bot] 节点边界无效(重影节点), 跳过");
            return;
        }
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int x = r.centerX(), y = r.centerY();
        if (x < 1 || y < 1 || x >= dm.widthPixels || y >= dm.heightPixels) {
            BotCore.get().log("[bot] 节点中心在屏幕外(" + x + "," + y + "), 跳过");
            return;
        }
        if (gestureTap(x, y)) {
            if (onSuccess != null) onSuccess.run();
            return;
        }
        BotCore.get().log("[bot] 节点不可点击且手势失败, 放弃");
    }

    /** 无障碍手势单击(ACTION_TAP), 用于点击无标签自绘控件。 */
    private boolean gestureTap(float x, float y) {
        try {
            android.graphics.Path p = new android.graphics.Path();
            p.moveTo(x, y);
            android.accessibilityservice.GestureDescription.Builder b =
                    new android.accessibilityservice.GestureDescription.Builder();
            b.addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(p, 0, 40));
            return dispatchGesture(b.build(), null, null);
        } catch (Exception e) {
            return false;
        }
    }

    /** 结构识别: 直播间左上区域内的无文本可点击节点(福袋图标为纯绘制 View, 无文本无 desc)。
     *  注意: 同区域还有「电商优惠券」按钮(desc=电商优惠券) 与「人气红包」图标 —— 券靠"无标签"条件排除,
     *  人气红包靠面板 OCR 标记排除(靠坐标 key 记忆, 下次换下一个图标)。 */
    private AccessibilityNodeInfo findStructFudai(AccessibilityNodeInfo root, java.util.List<android.graphics.Rect> blockedRects) {
        android.graphics.Rect r = new android.graphics.Rect();
        root.getBoundsInScreen(r);
        int w = Math.max(1, r.width());
        int h = Math.max(1, r.height());
        int maxX = (int) (w * 0.36f); // 图标栏(避开顶部头像行)
        int minY = (int) (h * 0.12f);
        int maxY = (int) (h * 0.28f);
        if (!currentHost.equals(rejectedHost)) { rejectedIconKeys.clear(); rejectedHost = currentHost; }
        java.util.ArrayDeque<AccessibilityNodeInfo> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        AccessibilityNodeInfo fallback = null;
        int visited = 0;
        while (!queue.isEmpty() && visited < 1500) {
            AccessibilityNodeInfo n = queue.poll();
            if (n == null) continue;
            visited++;
            for (int i = 0; i < n.getChildCount() && visited < 1500; i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) queue.add(c);
            }
            if (!n.isClickable()) continue;
            CharSequence t = n.getText();
            CharSequence d = n.getContentDescription();
            if ((t != null && t.length() > 0) || (d != null && d.length() > 0)) continue;
            n.getBoundsInScreen(r);
            if (r.centerX() > maxX || r.centerY() < minY || r.centerY() > maxY) continue;
            if (r.width() < 20 || r.height() < 20) continue;
            // 跳过与"红包/消费券"按钮重叠的候选(那是非目标图标)
            boolean blocked = false;
            for (android.graphics.Rect br : blockedRects) {
                if (br.contains(r.centerX(), r.centerY())) { blocked = true; break; }
            }
            if (blocked) continue;
            String key = iconKey(r.centerX(), r.centerY());
            if (rejectedIconKeys.contains(key)) continue; // 已排除(人气红包等), 换下一个候选
            CharSequence cls = n.getClassName();
            String clsName = cls == null ? "" : cls.toString();
            // 券图标是 Button/容器 FrameLayout, 福袋图标是纯绘制的 android.view.View:
            // 优先返回 View 类; 其余类仅在没有 View 候选时兜底
            if (clsName.endsWith(".View")) return n;
            if (fallback == null && !clsName.endsWith("Button") && !clsName.endsWith("TextView")
                    && !clsName.endsWith("ImageView")) fallback = n;
        }
        return fallback;
    }

    private static String iconKey(int x, int y) {
        // 坐标量化到 16px 网格, 容忍轻微漂移
        return (x / 16) + "," + (y / 16);
    }

    private long lastOcrTs = 0;
    private volatile boolean ocrBusy = false;
    /** OCR 连续无动作计数: 用于收起已开奖/无任务的陈旧福袋面板 */
    private volatile int ocrIdlePasses = 0;
    private volatile long lastPanelCloseTs = 0;
    /** 面板是否由机器人打开(仅自动收起自己打开的面板) */
    private volatile boolean panelOpenedByBot = false;
    private volatile long panelOpenSinceTs = 0;
    private volatile long panelFirstSeenTs = 0;
    private volatile long lastBackdropSeenTs = 0;
    /** 最近一次 OCR 在面板中读到福袋关键词的时间(面板存在的内容证据) */
    private volatile long lastPanelKeywordTs = 0;
    /** 结构点击闭环: 记录最近点击的图标/时间/是否成功打开面板(用于排除无效图标) */
    private volatile String lastStructKey = null;
    private volatile long lastStructTapTs = 0;
    private volatile boolean lastStructSawPanel = false;
    /** 最近一次结构点击的图标(坐标 key)与已排除的非目标图标(人气红包等), 按房间隔离 */
    private volatile String lastTappedIconKey = null;
    private final java.util.Set<String> rejectedIconKeys = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private volatile String rejectedHost = "";
    /** 点过"发表评论"后的自动发送窗口(截止时间戳): 普通福袋需要再点"发送"才算参与 */
    private volatile long awaitSendUntilTs = 0;
    /** 原生福袋面板最近可见时间 / 参与完成后计划收起的时间 */
    private volatile long lastPanelSeenTs = 0;
    private volatile long closePanelAfterTs = 0;

    /** H5 弹窗 OCR: 截图 -> 识别文字 -> 命中任务/抢/领取按钮则手势点击, 开奖类文案记入日志。 */
    private void maybeOcrH5Dialog(final String room, final boolean inRoom, final long minIntervalMs) {
        final BotCore bot = BotCore.get();
        if (android.os.Build.VERSION.SDK_INT < 30 || !bot.cfg.ocrEnabled || ocrBusy) return;
        long now = System.currentTimeMillis();
        if (now - lastOcrTs < Math.max(1600, minIntervalMs)) return;
        
        lastOcrTs = now;
        ocrBusy = true;
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, getMainExecutor(),
                    new TakeScreenshotCallback() {
                        @Override public void onSuccess(ScreenshotResult result) {
                            android.hardware.HardwareBuffer buf = null;
                            android.graphics.Bitmap wrapped = null;
                            android.graphics.Bitmap soft = null;
                            try {
                                buf = result.getHardwareBuffer();
                                wrapped = android.graphics.Bitmap.wrapHardwareBuffer(buf, result.getColorSpace());
                                if (wrapped != null) soft = wrapped.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
                            } catch (Throwable ignored) {
                            } finally {
                                if (buf != null) try { buf.close(); } catch (Throwable ignored) { }
                                if (wrapped != null) try { wrapped.recycle(); } catch (Throwable ignored) { }
                            }
                            if (soft == null) { ocrBusy = false; return; }
                            final android.graphics.Bitmap img = soft;
                            handleOcr(img, room, inRoom, () -> {
                                try { img.recycle(); } catch (Throwable ignored) { }
                                ocrBusy = false;
                            });
                        }

                        @Override public void onFailure(int errorCode) {
                            ocrBusy = false;
                            if (BotCore.get().canAct("ocrfail", 10000)) {
                                BotCore.get().log("[ocr] 截图失败 code=" + errorCode);
                            }
                        }
                    });
        } catch (Throwable t) {
            ocrBusy = false;
        }
    }

    private void handleOcr(final android.graphics.Bitmap bmp, final String room, final boolean inRoom,
                           final Runnable done) {
        final BotCore bot = BotCore.get();
        OcrHelper.recognize(bmp, getMainExecutor(), items -> {
            try {
                if (!bot.running || items.isEmpty()) return;
                int bw0 = bmp.getWidth(), bh0 = bmp.getHeight();
                long now = System.currentTimeMillis();
                GroupBuyPanel group = GroupBuyPanel.read(items, bw0, bh0);
                if (group != null) {
                    if (isTargetActive(bot, -1)) {
                        handleGroupBuyPanel(bot, group, currentHost.isEmpty() ? room : currentHost, now);
                    }
                    return;
                }
                if (groupPanelPending(now)) return;
                StringBuilder dbg = new StringBuilder();
                String tapText = null;
                android.graphics.Rect tapBox = null;
                String resultText = null;
                for (OcrHelper.Item it : items) {
                    String t = it.text == null ? "" : it.text.trim();
                    if (t.isEmpty()) continue;
                    if (dbg.length() < 200) {
                        dbg.append(t.length() > 16 ? t.substring(0, 16) : t).append("|");
                    }
                    if (tapText == null && isOcrTapText(t)) { tapText = t; tapBox = it.box; }
                    if (resultText == null && it.box != null && it.box.centerY() > bh0 * 0.55f
                            && BotCore.isOcrDrawResult(t)) resultText = t;
                }
                if (bot.canAct("ocrlog", 5000)) bot.log("[ocr] 弹窗文字: " + dbg);
                // 面板关键词 => 记录"面板存在"的内容证据(驱动面板生命周期与 back 保护)
                boolean panelKw = false;
                for (OcrHelper.Item it : items) {
                    String kw = it.text == null ? "" : it.text.trim();
                    if (kw.contains("福袋奖品") || kw.contains("超级福袋") || kw.contains("一键发表评论")
                            || kw.contains("参与条件") || kw.contains("去发表评论")
                            || kw.contains("发送评论") || kw.contains("福袋任务") || kw.contains("后开奖")) {
                        lastPanelKeywordTs = System.currentTimeMillis();
                        panelKw = true;
                        break;
                    }
                }
                // 红包类面板(非目标福袋): 排除当前图标, 快速收起面板, 换下一个图标再试
                // 只认"居中且在面板区域"的文本 —— 聊天区(靠左)可能也有"送你会员红包"等字样
                boolean redPacket = false;
                int bw = bmp.getWidth(), bh = bmp.getHeight();
                for (OcrHelper.Item it : items) {
                    String rt = it.text == null ? "" : it.text.trim();
                    if (rt.isEmpty() || it.box == null) continue;
                    boolean centered = it.box.centerX() > bw * 0.25f && it.box.centerX() < bw * 0.75f
                            && it.box.top > bh * 0.30f;
                    if (!centered) continue;
                    if (rt.contains("人气红包") || rt.contains("会员红包") || rt.contains("粉丝团") || rt.contains("需1钻")
                            || rt.contains("红包已过期") || rt.contains("钻减")
                            || rt.contains("消费券") || rt.contains("优惠券") || rt.contains("领券")) {
                        redPacket = true;
                        break;
                    }
                }
                if (redPacket) {
                    String lk = lastTappedIconKey;
                    if (lk != null) {
                        if (!currentHost.equals(rejectedHost)) {
                            rejectedIconKeys.clear();
                            rejectedHost = currentHost;
                        }
                        rejectedIconKeys.add(lk);
                    }
                    panelOpenedByBot = true; // 强制纳入自动收起(人气红包不管谁打开都收起)
                    lastPanelKeywordTs = System.currentTimeMillis(); // 提供面板存在证据, 允许安全 back 收起
                    ocrIdlePasses = 99;
                    if (bot.canAct("redpacketlog", 10000)) {
                        bot.log("[ocr] 非目标面板(红包/消费券) -> 排除该图标并收起面板");
                    }
                    return;
                }
                if (tapText != null && tapBox != null) {
                    String label = tapText.length() > 12 ? tapText.substring(0, 12) : tapText;
                    if (bot.canAct("ocrtap:" + label, 6000)) {
                        ocrIdlePasses = 0;
                        int x = tapBox.centerX();
                        int y = tapBox.centerY();
                        if (gestureTap(x, y)) {
                            bot.addGrab(room);
                            bot.grabs++;
                            bot.log("[ocr] 命中[" + tapText + "] -> 手势点击(" + x + "," + y + ")");
                        } else {
                            bot.log("[ocr] 手势点击失败: " + tapText);
                        }
                    }
                } else {
                    ocrIdlePasses++;
                }
                // 房间横幅/角标福袋入口(仅在房间内且本轮没有面板动作时; 与面板处理共用同一次 OCR)
                if (inRoom && tapText == null) {
                    String hit = null;
                    android.graphics.Rect hitBox = null;
                    String badge = null;
                    android.graphics.Rect badgeBox = null;
                    android.graphics.Rect waitBox = null;
                    for (OcrHelper.Item it : items) {
                        String t = it.text == null ? "" : it.text.trim();
                        if (t.isEmpty() || it.box == null) continue;
                        if (waitBox == null && t.contains("待参与")) waitBox = it.box;
                        if (t.contains("福袋") && !t.contains("红包") && !t.contains("券")
                                && !t.contains("规则") && !t.contains("记录")
                                && it.box.top < bh0 * 0.55f && hit == null) {
                            hit = t;
                            hitBox = it.box;
                        }
                        if (t.contains("价值") && t.contains("钻") && badge == null
                                && it.box.top < bh0 * 0.60f) {
                            badge = t;
                            badgeBox = it.box;
                        }
                    }
                    if (waitBox != null) {
                        // 福袋图标下方绘制"待参与"状态文字: 点该文字正上方(图标热区)
                        int x = waitBox.centerX();
                        int y = Math.max(1, waitBox.top - 60);
                        if (bot.canAct("roomwaiting", 20000) && gestureTap(x, y)) {
                            panelOpenedByBot = true;
                            panelOpenSinceTs = System.currentTimeMillis();
                            bot.log("[ocr] 命中[待参与] -> 手势点击福袋图标(" + x + "," + y + ")");
                        }
                    } else if (hit != null) {
                        // 横幅文字中心通常不是热区: 点文字左侧(图标所在的横幅起点)
                        int x = Math.max(1, hitBox.left + 24);
                        int y = hitBox.centerY();
                        if (bot.canAct("roomfudaitap", 30000) && gestureTap(x, y)) {
                            panelOpenedByBot = true;
                            panelOpenSinceTs = System.currentTimeMillis();
                            bot.log("[ocr] 房间福袋横幅[" + hit + "] -> 手势点击(" + x + "," + y + ")");
                        }
                    } else if (badge != null) {
                        // 兜底: "价值X钻"角标通常绘制在福袋图标附近
                        int x = badgeBox.centerX();
                        int y = Math.max(1, badgeBox.top - 20);
                        if (bot.canAct("roombadgetap", 30000) && gestureTap(x, y)) {
                            panelOpenedByBot = true;
                            panelOpenSinceTs = System.currentTimeMillis();
                            bot.log("[ocr] 房间福袋角标[" + badge + "] -> 手势点击(" + x + "," + y + ")");
                        }
                    }
                }
                if (panelKw && resultText != null && bot.canAct("ocrresult:" + resultText, 8000)) {
                    bot.log("[ocr] 开奖/结果文案: " + resultText);
                }
                // 福袋面板里读到"已参与/中奖"等结果 => 参与已完成, 计划 4 秒后收起面板
                if (panelKw && resultText != null && closePanelAfterTs == 0) {
                    closePanelAfterTs = System.currentTimeMillis() + 4000;
                    panelOpenedByBot = true;
                    lastPanelKeywordTs = System.currentTimeMillis();
                }
            } finally {
                if (done != null) done.run();
            }
        });
    }

    /** OCR 命中的可点击动作文案(H5 弹窗按钮)。 */
    private static boolean isOcrTapText(String t) {
        if (t.length() > 20) return false;
        return t.contains("一键发表评论") || t.contains("发表评论")
                || t.contains("抢福袋") || t.contains("立即抢") || t.contains("去抢") || t.contains("开抢")
                || t.contains("开心收下") || t.contains("我知道了") || t.contains("收下祝福")
                || t.contains("立即领取") || t.contains("马上领取") || t.contains("点击领取");
    }

    @Override
    public void onInterrupt() { BotCore.get().log("[bot] 无障碍服务被中断"); }

    @Override
    public void onDestroy() {
        super.onDestroy();
        watchdog.removeCallbacks(watchTask);
        if (adbCtl != null) { try { unregisterReceiver(adbCtl); } catch (Exception ignored) { } adbCtl = null; }
    }
}
