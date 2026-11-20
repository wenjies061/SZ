package com.yllite.app;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 抖音观看记录的标题、主播名和头像直播状态可能由页面自行绘制。 */
final class LiveWatchHistory {
    private final Rect screen;
    private final Rect list;
    private final List<Rect> rows;

    private LiveWatchHistory(Rect screen, Rect list, List<Rect> rows) {
        this.screen = screen;
        this.list = list;
        this.rows = rows;
    }

    static LiveWatchHistory find(AccessibilityNodeInfo root) {
        Rect screen = new Rect();
        root.getBoundsInScreen(screen);
        if (screen.isEmpty()) return null;
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        for (int visited = 0; visited < 55 && !queue.isEmpty(); visited++) {
            AccessibilityNodeInfo node = queue.remove();
            CharSequence cls = node.getClassName();
            if (cls != null && cls.toString().endsWith(".RecyclerView")) {
                Rect list = new Rect();
                node.getBoundsInScreen(list);
                if (list.top <= screen.top + screen.height() * .18f && list.width() >= screen.width() * .90f
                        && list.height() >= screen.height() * .45f) {
                    List<Rect> rows = new ArrayList<>();
                    for (int i = 0; i < Math.min(node.getChildCount(), 20); i++) {
                        AccessibilityNodeInfo child = node.getChild(i);
                        if (child == null || !child.isVisibleToUser()) continue;
                        Rect row = new Rect();
                        child.getBoundsInScreen(row);
                        if (row.width() >= list.width() * .90f && row.height() >= list.width() * .16f
                                && row.height() <= list.width() * .30f && row.top >= list.top
                                && row.bottom <= list.bottom) rows.add(row);
                    }
                    if (!rows.isEmpty()) {
                        rows.sort(Comparator.comparingInt(row -> row.top));
                        return new LiveWatchHistory(screen, list, rows);
                    }
                }
            }
            for (int i = 0; i < node.getChildCount() && queue.size() < 55; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        return null;
    }

    boolean hasTitle(List<OcrHelper.Item> items) {
        for (OcrHelper.Item item : items) {
            Rect box = item.box;
            if (box != null && box.centerY() >= screen.top && box.centerY() < list.top
                    && Math.abs(box.centerX() - screen.centerX()) < screen.width() * .18f
                    && normalize(item.text).equals("观看记录")) return true;
        }
        return false;
    }

    Candidate choose(Bitmap image, List<OcrHelper.Item> items, String previousHost) {
        if (previousHost.isEmpty()) {
            Rect first = rows.get(0);
            boolean atTop = false;
            for (OcrHelper.Item item : items) {
                Rect box = item.box;
                String label = normalize(item.text).replace('內', '内').replace('過', '过');
                if (box != null && box.centerY() >= list.top && box.centerY() < first.top
                        && box.left < screen.left + screen.width() * .25f
                        && (label.equals("一周内看过") || label.equals("1周内看过") || label.equals("周内看过"))) {
                    atTop = true;
                    break;
                }
            }
            return atTop ? liveCandidate(image, items, first, "") : null;
        }
        Candidate match = null;
        for (Rect row : rows) {
            Candidate candidate = liveCandidate(image, items, row, previousHost);
            if (candidate == null) continue;
            if (match != null) return null;
            match = candidate;
        }
        return match;
    }

    private Candidate liveCandidate(Bitmap image, List<OcrHelper.Item> items, Rect row, String previousHost) {
        boolean hostMatches = previousHost.isEmpty();
        boolean liveText = false;
        float height = row.height();
        int avatarX = row.left + Math.round(height * .545f);
        int avatarY = row.centerY();
        for (OcrHelper.Item item : items) {
            Rect box = item.box;
            if (box == null) continue;
            String label = normalize(item.text);
            float y = (box.centerY() - row.top) / height;
            if (y >= .07f && y <= .37f && Math.abs(box.centerX() - avatarX) <= height * .23f
                    && (label.equals("直播") || label.equals("直播中"))) liveText = true;
            if (y < .18f || y > .53f || box.height() >= height * .27f
                    || box.left < row.left + row.width() * .19f || box.left > row.left + row.width() * .40f) continue;
            if (!hostMatches && matchesHost(previousHost, label)) hostMatches = true;
        }
        if (!hostMatches || !hasRedRing(image, avatarX, avatarY, height)
                || (!liveText && !hasBadge(image, avatarX, avatarY, height))) return null;
        return new Candidate(avatarX, avatarY);
    }

    static boolean matchesHost(String previous, String observed) {
        String expected = normalize(previous);
        // 真机上 ML Kit 偶尔将直播间后缀识别为「直摇间」，主播名前缀仍需匹配。
        String actual = normalize(observed).replaceFirst("直摇间$", "直播间");
        if (expected.isEmpty() || actual.isEmpty()) return false;
        if (expected.equals(actual)) return true;
        String prefix = actual.replaceFirst("(?:…+|\\.{2,})$", "");
        return prefix.length() >= 6 && !prefix.equals(actual) && expected.startsWith(prefix);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("[\\s　]+", "");
    }

    private static boolean hasRedRing(Bitmap image, int x, int y, float height) {
        if (x - height * .41f < 0 || y - height * .41f < 0
                || x + height * .41f >= image.getWidth() || y + height * .41f >= image.getHeight()) return false;
        int red = 0;
        for (int i = 0; i < 16; i++) {
            double angle = Math.PI * i / 8;
            for (int band = 0; band < 5; band++) {
                double radius = height * (.365 + .01 * band);
                int px = x + (int) Math.round(radius * Math.cos(angle));
                int py = y + (int) Math.round(radius * Math.sin(angle));
                if (badgeRed(image.getPixel(px, py))) { red++; break; }
            }
        }
        return red >= 13;
    }

    private static boolean hasBadge(Bitmap image, int x, int y, float height) {
        int bands = 0;
        for (int row = 0; row < 4; row++) {
            int py = Math.round(y - height * (.245f + .02f * row));
            int red = 0;
            int white = 0;
            for (int col = -8; col <= 8; col++) {
                int px = Math.round(x + col * height * .019f);
                int color = image.getPixel(px, py);
                if (badgeRed(color)) red++;
                if (Color.red(color) > 225 && Color.green(color) > 225 && Color.blue(color) > 225) white++;
            }
            if (red >= 6 && white >= 1) bands++;
        }
        return bands >= 2;
    }

    private static boolean badgeRed(int color) {
        int r = Color.red(color);
        return r >= 185 && r - Color.green(color) >= 75 && r - Color.blue(color) >= 28;
    }

    static final class Candidate {
        final int x;
        final int y;

        Candidate(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }
}
