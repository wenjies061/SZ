package com.yllite.app;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

/** 团购福袋任务打开的抖音私信分享面板。 */
final class DouyinShareSheet {
    private static final String SELECTED = "已选中,";
    private final AccessibilityNodeInfo recipient;
    private final AccessibilityNodeInfo send;
    private final int matches;
    private final int selectedCount;
    private final boolean recipientSelected;
    private final boolean emptyDraft;

    private DouyinShareSheet(AccessibilityNodeInfo recipient, AccessibilityNodeInfo send, int matches,
                            int selectedCount, boolean recipientSelected, boolean emptyDraft) {
        this.recipient = recipient;
        this.send = send;
        this.matches = matches;
        this.selectedCount = selectedCount;
        this.recipientSelected = recipientSelected;
        this.emptyDraft = emptyDraft;
    }

    static DouyinShareSheet read(AccessibilityNodeInfo root, String pkg, String name) {
        String id = pkg + ":id/";
        AccessibilityNodeInfo header = uniqueVisible(root, id + "mbl");
        AccessibilityNodeInfo row = uniqueVisible(root, id + "v6m");
        if (header == null || row == null || !textOf(header).equals("分享给")) return null;

        AccessibilityNodeInfo recipient = null;
        int matches = 0;
        int selected = 0;
        boolean recipientSelected = false;
        for (AccessibilityNodeInfo card : row.findAccessibilityNodeInfosByViewId(id + "b_1")) {
            CharSequence desc = card.getContentDescription();
            if (desc == null) continue;
            String state = desc.toString().trim();
            boolean checked = state.startsWith(SELECTED) || state.startsWith("已选中，");
            if (checked) selected++;
            String cardName = checked ? state.substring(SELECTED.length()) : state.replaceFirst("[,，]$", "");
            AccessibilityNodeInfo label = uniqueVisible(card, id + "sey");
            if (card.isVisibleToUser() && card.isEnabled() && label != null
                    && name.equals(normalizeRecipient(textOf(label)))
                    && name.equals(normalizeRecipient(cardName))) {
                matches++;
                recipient = uniqueVisible(card, id + "amd");
                recipientSelected = checked;
            }
        }
        AccessibilityNodeInfo send = uniqueVisible(root, id + "l-c");
        AccessibilityNodeInfo draft = uniqueVisible(root, id + "swc");
        boolean emptyDraft = draft != null && !draft.isFocused()
                && (textOf(draft).isEmpty() || textOf(draft).equals("分享此刻的想法"));
        return new DouyinShareSheet(recipient, send, matches, selected, recipientSelected, emptyDraft);
    }

    boolean hasUniqueRecipient() { return matches == 1; }

    boolean hasSelection() { return selectedCount != 0 || send != null; }

    AccessibilityNodeInfo selectionAvatar() {
        if (!hasUniqueRecipient() || hasSelection() || recipient == null || !recipient.refresh()
                || !recipient.isVisibleToUser() || !recipient.isEnabled() || !recipient.isClickable()) return null;
        return boundsOf(recipient) != null ? recipient : null;
    }

    AccessibilityNodeInfo verifiedSend() {
        if (!hasUniqueRecipient() || selectedCount != 1 || !recipientSelected || !emptyDraft
                || send == null || !send.refresh() || !send.isVisibleToUser() || !send.isEnabled()
                || !send.isClickable() || !textOf(send).equals("发送")) return null;
        return boundsOf(send) != null ? send : null;
    }

    private static AccessibilityNodeInfo uniqueVisible(AccessibilityNodeInfo root, String id) {
        AccessibilityNodeInfo found = null;
        for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByViewId(id)) {
            if (!node.isVisibleToUser() || boundsOf(node) == null) continue;
            if (found != null) return null;
            found = node;
        }
        return found;
    }

    private static Rect boundsOf(AccessibilityNodeInfo node) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        return bounds.isEmpty() ? null : bounds;
    }

    private static String textOf(AccessibilityNodeInfo node) {
        CharSequence text = node.getText();
        return text == null ? "" : text.toString().trim();
    }

    static String normalizeRecipient(String text) {
        String value = text == null ? "" : text.trim();
        int end = value.length();
        while (end > 0) {
            int suffix = value.codePointBefore(end);
            if (Character.getType(suffix) != Character.PRIVATE_USE) break;
            end -= Character.charCount(suffix);
        }
        return value.substring(0, end).trim();
    }
}
