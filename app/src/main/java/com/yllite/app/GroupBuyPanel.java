package com.yllite.app;

import android.graphics.Rect;

import java.util.List;

/** 团购福袋面板的自绘标题、操作按钮和观看任务状态。 */
final class GroupBuyPanel {
    static final String ENTRY = "生活服务-直播-福袋";

    enum Stage { UNKNOWN, COMMENT, START_WATCH, WATCHING, SHARE, PARTICIPATED }

    final Stage stage;
    final Rect button;
    final long drawSeconds;
    final boolean requiresShare;
    final boolean shareAchieved;

    private GroupBuyPanel(Stage stage, Rect button, long drawSeconds, boolean requiresShare, boolean shareAchieved) {
        this.stage = stage;
        this.button = button;
        this.drawSeconds = drawSeconds;
        this.requiresShare = requiresShare;
        this.shareAchieved = shareAchieved;
    }

    static GroupBuyPanel read(List<OcrHelper.Item> items, int width, int height) {
        boolean hasTitle = false;
        Stage stage = Stage.UNKNOWN;
        Rect button = null;
        long drawSeconds = -1;
        int shareRowY = -1;
        for (OcrHelper.Item item : items) {
            Rect box = item.box;
            if (box == null || box.isEmpty() || item.text == null) continue;
            String text = compact(item.text);
            float y = (float) box.centerY() / height;
            if (y >= .75f && y <= .88f && box.centerX() < width * .26f && text.equals("分享直播间")) {
                shareRowY = box.centerY();
            }
            if (box.centerX() < width * 0.30f || box.centerX() > width * 0.70f) continue;
            if (y >= 0.44f && y <= 0.73f) {
                if (text.equals("团购福袋")) hasTitle = true;
                if (text.endsWith("后开奖")) drawSeconds = Math.max(drawSeconds, BotCore.parseCountdownSeconds(text));
            }
            if (y < 0.78f || y > 0.97f || box.height() > height * 0.045f) continue;
            Stage found = Stage.UNKNOWN;
            if (text.equals("发送评论")) found = Stage.COMMENT;
            if (text.equals("开始观看直播任务参与抽奖")) found = Stage.START_WATCH;
            if (text.startsWith("还需观看直播")) found = Stage.WATCHING;
            if (text.equals("分享直播间参与抽奖")) found = Stage.SHARE;
            if (text.equals("已参与") || text.equals("已参与抽奖") || text.equals("参与成功")
                    || text.equals("参与成功等待开奖")
                    || text.equals("已成功参与") || text.equals("任务已完成等待开奖")) found = Stage.PARTICIPATED;
            if (found.ordinal() > stage.ordinal()) {
                stage = found;
                button = box;
            }
        }
        if (!hasTitle) return null;
        boolean shareAchieved = false;
        if (shareRowY >= 0) {
            for (OcrHelper.Item item : items) {
                Rect box = item.box;
                if (box != null && item.text != null && box.centerX() > width * .75f
                        && Math.abs(box.centerY() - shareRowY) < height * .013f && compact(item.text).equals("已达成")) {
                    shareAchieved = true;
                    break;
                }
            }
        }
        return new GroupBuyPanel(stage, button, drawSeconds, shareRowY >= 0 || stage == Stage.SHARE, shareAchieved);
    }

    private static String compact(String text) {
        return text.replaceAll("[\\s　]+", "").replace('：', ':');
    }
}
