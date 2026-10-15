package com.kooo.evcam.zeekr;

/**
 * 抬头显示上那一小块侧视的位置。左灯贴左沿、右灯贴右沿。
 *
 * <p>原厂在车标周围已经有一块底，盖不掉。画面收窄，并靠到外沿，躲开那块底。</p>
 */
public final class HudFrame {

    /** 高度仍是屏高的 45%。宽度按这个比例收，比 16:9 窄。 */
    private static final int WIDTH_NUM = 1;
    private static final int WIDTH_DEN = 1;
    private static final int EDGE = 8;

    private HudFrame() {
    }

    /** {@code [x, y, width, height]}，单位是这块屏的像素。 */
    public static int[] place(int displayW, int displayH, boolean left) {
        if (displayW < 2 || displayH < 2) {
            return new int[]{0, 0, Math.max(displayW, 1), Math.max(displayH, 1)};
        }
        int height = Math.max(2, displayH * 45 / 100);
        if (height > displayH) {
            height = displayH;
        }
        int width = Math.max(2, height * WIDTH_NUM / WIDTH_DEN);
        int maxWidth = Math.max(2, displayW / 3);
        if (width > maxWidth) {
            width = maxWidth;
        }
        int x = left ? EDGE : displayW - EDGE - width;
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
