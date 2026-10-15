package com.kooo.evcam.zeekr;

import android.content.Context;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.view.Display;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 驾驶员挡风玻璃上的抬头显示是哪一块屏。
 *
 * <p>先认名字里带 hud 的非主屏。没有的话，极氪 7X 上社区实测是第 5 块屏
 * （WazeHUD：https://t.me/s/zeekroid?before=145）。主屏再叫 5 也不认，免得投到中控上。</p>
 */
public final class HudDisplay {

    /** 极氪 7X 抬头显示的显示编号。名字对不上时才用。 */
    public static final int ZEEKR_7X_ID = 5;

    public static final class Screen {
        public final int id;
        public final String name;
        public final int width;
        public final int height;
        public final boolean main;

        public Screen(int id, String name, int width, int height, boolean main) {
            this.id = id;
            this.name = name;
            this.width = width;
            this.height = height;
            this.main = main;
        }
    }

    private HudDisplay() {
    }

    /** 抬头显示的编号；没有就 -1。 */
    public static int pick(List<Screen> screens) {
        if (screens == null) {
            return -1;
        }
        int known = -1;
        for (Screen screen : screens) {
            if (screen == null || screen.main) {
                continue;
            }
            String name = screen.name == null ? "" : screen.name.toLowerCase(Locale.US);
            if (name.contains("hud")) {
                return screen.id;
            }
            if (screen.id == ZEEKR_7X_ID) {
                known = screen.id;
            }
        }
        return known;
    }

    public static Display find(Context context) {
        DisplayManager manager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (manager == null) {
            return null;
        }
        Display[] all = manager.getDisplays();
        List<Screen> screens = new ArrayList<>();
        for (Display display : all) {
            Point size = sizeOf(display);
            screens.add(new Screen(display.getDisplayId(), display.getName(), size.x, size.y,
                    display.getDisplayId() == Display.DEFAULT_DISPLAY));
        }
        int id = pick(screens);
        if (id < 0) {
            return null;
        }
        for (Display display : all) {
            if (display.getDisplayId() == id) {
                return display;
            }
        }
        return null;
    }

    public static Point sizeOf(Display display) {
        Point size = new Point();
        display.getRealSize(size);
        return size;
    }
}
