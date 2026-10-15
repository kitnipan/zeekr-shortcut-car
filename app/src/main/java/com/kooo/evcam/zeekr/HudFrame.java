package com.kooo.evcam.zeekr;

/**
 * 抬头显示上侧视的位置。左灯靠左、右灯靠右。一次只显示一边，所以可以占满这条屏的高度。
 * 高度百分比 100 时顶满上下；调低就竖向居中，给原厂图形留边。
 */
public final class HudFrame {

    private HudFrame() {
    }

    /** 高度 100%。{@code [x, y, width, height]}。 */
    public static int[] place(int displayW, int displayH, boolean left) {
        return place(displayW, displayH, left, 100);
    }

    /** {@code [x, y, width, height]}，单位是这块屏的像素。{@code heightPercent} 是屏高的百分比。 */
    public static int[] place(int displayW, int displayH, boolean left, int heightPercent) {
        if (displayW < 2 || displayH < 2) {
            return new int[]{0, 0, Math.max(displayW, 1), Math.max(displayH, 1)};
        }
        int percent = Math.max(1, Math.min(100, heightPercent));
        int height = Math.max(2, displayH * percent / 100);
        int width = Math.max(2, height * 16 / 9);
        if (width > displayW) {
            width = displayW;
            height = Math.max(2, width * 9 / 16);
        }
        if (height > displayH) {
            height = displayH;
        }
        int margin = percent >= 100 ? 0 : Math.max(8, displayW / 40);
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
