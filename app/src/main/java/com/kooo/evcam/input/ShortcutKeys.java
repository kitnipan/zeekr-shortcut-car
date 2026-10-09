package com.kooo.evcam.input;

import android.content.Context;
import android.view.KeyEvent;

import com.kooo.evcam.AppConfig;

import java.util.List;

/**
 * Shared entry for shortcut key events: classifies tap / long / double, then runs the action.
 * KeyCatcher, the accessibility service, and MainActivity all call {@link #dispatch}.
 */
public final class ShortcutKeys {

    private static Context appContext;
    private static PressClassifier classifier;

    private ShortcutKeys() {
    }

    public static synchronized boolean dispatch(Context context, KeyEvent event) {
        if (context == null || event == null || ShortcutCapture.isActive()
                || event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            return false;
        }
        appContext = context.getApplicationContext();
        if (classifier == null) {
            classifier = new PressClassifier((keyCode, scanCode, deviceName, kind) -> {
                Context ctx = appContext;
                if (ctx == null) {
                    return;
                }
                Shortcut hit = ShortcutBook.match(
                        ShortcutBook.parse(new AppConfig(ctx).getButtonShortcuts()),
                        keyCode, scanCode, deviceName, kind);
                if (hit != null) {
                    ShortcutPerformer.perform(ctx, hit.action);
                }
            });
        }
        if (holdToSpeak(appContext, event)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    MegaphoneService.start(appContext);
                }
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                MegaphoneService.stop(appContext);
                return true;
            }
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            if (!bound(appContext, event)) {
                return false;
            }
        }
        return classifier.onKeyEvent(event);
    }

    private static boolean holdToSpeak(Context context, KeyEvent event) {
        String device = event.getDevice() == null || event.getDevice().getName() == null
                ? "" : event.getDevice().getName();
        List<Shortcut> items = ShortcutBook.parse(new AppConfig(context).getButtonShortcuts());
        return ShortcutBook.holdToSpeak(items, event.getKeyCode(), event.getScanCode(), device);
    }

    /** True when any press kind is saved for this button. */
    private static boolean bound(Context context, KeyEvent event) {
        String device = event.getDevice() == null || event.getDevice().getName() == null
                ? "" : event.getDevice().getName();
        List<Shortcut> items = ShortcutBook.parse(new AppConfig(context).getButtonShortcuts());
        for (PressKind kind : PressKind.values()) {
            if (ShortcutBook.match(items, event.getKeyCode(), event.getScanCode(), device, kind) != null) {
                return true;
            }
        }
        return false;
    }
}
