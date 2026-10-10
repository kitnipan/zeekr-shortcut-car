package com.kooo.evcam.input;

import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.MainActivity;
import com.kooo.evcam.R;
import com.kooo.evcam.overlay.OverlayCoordinator;
import com.kooo.evcam.service.RecordingFloatingService;

/** Runs a saved shortcut. Recording goes through the floating service, which already knows foreground and background. */
public final class ShortcutPerformer {

    private ShortcutPerformer() {
    }

    public static void perform(Context context, String actionKey) {
        ShortcutAction action = ShortcutAction.fromKey(actionKey);
        if (context == null || action == null) {
            return;
        }
        switch (action) {
            case RECORD:
                Intent toggle = new Intent(context, RecordingFloatingService.class);
                toggle.setAction(RecordingFloatingService.ACTION_TOGGLE_RECORDING);
                context.startService(toggle);
                return;
            case MIRROR:
                AppConfig config = new AppConfig(context);
                if (!OverlayCoordinator.setRearViewEnabled(context, !config.isRearViewEnabled())) {
                    toast(context);
                }
                return;
            case BOTH_MIRRORS:
                AppConfig both = new AppConfig(context);
                if (!OverlayCoordinator.setBothMirrorsEnabled(context, !both.isBothMirrorsEnabled())) {
                    toast(context);
                }
                return;
            case APP:
                Intent open = new Intent(context, MainActivity.class);
                open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                context.startActivity(open);
                return;
            case DIM:
                AppConfig dim = new AppConfig(context);
                if (!OverlayCoordinator.setDimOverlayEnabled(context, !dim.isDimOverlayEnabled())) {
                    toast(context);
                }
                return;
            case SAVE:
                com.kooo.evcam.recording.SaveMoment.perform(context);
                return;
            case LOCK_SAVE:
                com.kooo.evcam.storage.AutoLock.get().lockFromShortcut(context);
                com.kooo.evcam.recording.InstantCapture.perform(context);
                return;
            case HOLD_SPEAK:
                MegaphoneService.toggle(context);
                return;
            case CABIN_PASSENGER:
                AppConfig cabin = new AppConfig(context);
                if (!OverlayCoordinator.setCabinPassengerEnabled(context, !cabin.isCabinPassengerEnabled())) {
                    toast(context);
                }
                return;
            default:
                return;
        }
    }

    private static void toast(Context context) {
        Toast.makeText(context, R.string.msg_need_overlay, Toast.LENGTH_SHORT).show();
    }
}
