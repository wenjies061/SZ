package com.yllite.app;

final class RoomRecovery {
    private static final long RECENT_ROOM_MS = 120_000;
    private static final long STARTUP_HISTORY_MS = 30_000;
    private long session = -1;
    private long startedAt;
    private boolean sawLive;
    private long lastLiveAt;
    private int liveWindow = -1;
    private String livePackage = "";
    private String liveHost = "";
    private String pendingHost = "";
    private boolean pendingFirstEntry;
    private int attempts;
    private long lastAttemptAt;

    synchronized void begin(long id, long now) {
        if (id == session) return;
        session = id;
        startedAt = now;
        sawLive = false;
        lastLiveAt = 0;
        liveWindow = -1;
        livePackage = "";
        liveHost = "";
        pendingHost = "";
        pendingFirstEntry = false;
        attempts = 0;
        lastAttemptAt = 0;
    }

    synchronized boolean sawLive(long id, String pkg, int window, String host, long now) {
        if (id != session) return false;
        if (window != liveWindow || !pkg.equals(livePackage)) liveHost = "";
        liveWindow = window;
        livePackage = pkg;
        if (!host.isEmpty()) liveHost = host;
        sawLive = true;
        lastLiveAt = now;
        attempts = 0;
        if (host.isEmpty()) return false;
        boolean confirmed = pendingFirstEntry || LiveWatchHistory.matchesHost(host, pendingHost);
        pendingHost = "";
        pendingFirstEntry = false;
        return confirmed;
    }

    /** null 表示本轮不操作，空字符串表示启动后仅允许尝试观看记录第一条。 */
    synchronized String wantedHost(long id, String pkg, long now) {
        if (id != session || (lastAttemptAt != 0 && now - lastAttemptAt < 5000)) return null;
        if (!sawLive) return attempts == 0 && now - startedAt <= STARTUP_HISTORY_MS ? "" : null;
        if (attempts >= 2 || !pkg.equals(livePackage) || liveHost.isEmpty()
                || now - lastLiveAt > RECENT_ROOM_MS) return null;
        return liveHost;
    }

    synchronized boolean reserve(long id, String pkg, String requested, long now) {
        if (!requested.equals(wantedHost(id, pkg, now))) return false;
        attempts++;
        lastAttemptAt = now;
        pendingHost = requested;
        pendingFirstEntry = requested.isEmpty();
        return true;
    }
}
