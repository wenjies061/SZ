package com.yllite.app;

import android.graphics.Rect;

import java.util.List;

/** 团购福袋面板的自绘标题、操作按钮和观看任务状态。 */
final class GroupBuyPanel {
    static final String ENTRY = "生活服务-直播-福袋";

    enum Stage { UNKNOWN, COMMENT, START_WATCH, WATCHING, PARTICIPATED }

    final Stage stage;
    final Rect button;
    final long drawSeconds;

    private GroupBuyPanel(Stage stage, Rect button, long drawSeconds) {
        this.stage = stage;
        this.button = button;
        this.drawSeconds = drawSeconds;
    }

    static GroupBuyPanel read(List<OcrHelper.Item> items, int width, int height) {
        boolean hasTitle = false;
        Stage stage = Stage.UNKNOWN;
        Rect button = null;
        long drawSeconds = -1;
        for (OcrHelper.Item item : items) {
            Rect box = item.box;
            if (box == null || box.isEmpty() || box.centerX() < width * 0.30f
                    || box.centerX() > width * 0.70f) continue;
            String text = item.text.replaceAll("[\\s　]+", "").replace('：', ':');
            float y = (float) box.centerY() / height;
            if (y >= 0.44f && y <= 0.73f) {
                if (text.equals("团购福袋")) hasTitle = true;
                if (text.endsWith("后开奖")) drawSeconds = Math.max(drawSeconds, BotCore.parseCountdownSeconds(text));
            }
            if (y < 0.78f || y > 0.97f || box.height() > height * 0.045f) continue;
            Stage found = Stage.UNKNOWN;
            if (text.equals("发送评论")) found = Stage.COMMENT;
            if (text.equals("开始观看直播任务参与抽奖")) found = Stage.START_WATCH;
            if (text.startsWith("还需观看直播")) found = Stage.WATCHING;
            if (text.equals("已参与") || text.equals("已参与抽奖") || text.equals("参与成功")
                    || text.equals("参与成功等待开奖")
                    || text.equals("已成功参与") || text.equals("任务已完成等待开奖")) found = Stage.PARTICIPATED;
            if (found.ordinal() > stage.ordinal()) {
                stage = found;
                button = box;
            }
        }
        return hasTitle ? new GroupBuyPanel(stage, button, drawSeconds) : null;
    }
}
