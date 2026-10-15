package com.kooo.evcam.zeekr;

/**
 * 抬头显示上那一小块侧视的位置。左灯靠左、右灯靠右，16:9，竖向居中，
 * 四周留着原厂的车速和车道线。
 */
public final class HudFrame {

    private HudFrame() {
    }

    /** {@code [x, y, width, height]}，单位是这块屏的像素。 */
    public static int[] place(int displayW, int displayH, boolean left) {
        if (displayW < 2 || displayH < 2) {
            return new int[]{0, 0, Math.max(displayW, 1), Math.max(displayH, 1)};
        }
        int height = Math.max(2, displayH * 45 / 100);
        int width = height * 16 / 9;
        int maxWidth = Math.max(2, displayW / 2);
        if (width > maxWidth) {
            width = maxWidth;
            height = Math.max(2, width * 9 / 16);
        }
        if (height > displayH) {
            height = displayH;
            width = Math.min(maxWidth, Math.max(2, height * 16 / 9));
        }
        int margin = Math.max(8, displayW / 40);
        int x = left ? margin : displayW - margin - width;
        if (x < 0) {
            x = 0;
        }
        if (x + width > displayW) {
            x = Math.max(0, displayW - width);
        }
        int y = Math.max(0, (displayH - height) / 2);
        return new int[]{x, y, width, height};
    }
}
