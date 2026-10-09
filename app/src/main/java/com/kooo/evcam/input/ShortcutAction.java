package com.kooo.evcam.input;

import com.kooo.evcam.R;

/** What a saved button does. The key is what gets stored. */
public enum ShortcutAction {

    RECORD("toggle_recording", R.string.shortcut_action_record),
    MIRROR("toggle_mirror", R.string.shortcut_action_mirror),
    BOTH_MIRRORS("toggle_both_mirrors", R.string.shortcut_action_both_mirrors),
    APP("open_app", R.string.shortcut_action_app),
    DIM("toggle_dim", R.string.shortcut_action_dim),
    SAVE("save_moment", R.string.shortcut_action_save),
    LOCK_SAVE("lock_and_save", R.string.shortcut_action_lock_save),
    HOLD_SPEAK("hold_to_speak", R.string.shortcut_action_hold_speak);

    public final String key;
    public final int labelRes;

    ShortcutAction(String key, int labelRes) {
        this.key = key;
        this.labelRes = labelRes;
    }

    public static ShortcutAction fromKey(String key) {
        if (key == null) {
            return null;
        }
        for (ShortcutAction action : values()) {
            if (action.key.equals(key)) {
                return action;
            }
        }
        return null;
    }
}
