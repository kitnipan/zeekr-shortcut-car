package com.kooo.evcam.input;

import android.os.Bundle;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;

/** Press the external button, then pick the action it should run. */
public class ShortcutCaptureActivity extends AppCompatActivity {

    private Shortcut captured;
    private TextView prompt;
    private View actions;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_shortcut_capture);
        prompt = findViewById(R.id.shortcut_prompt);
        actions = findViewById(R.id.shortcut_actions);
        findViewById(R.id.shortcut_capture_close).setOnClickListener(v -> finish());
        bind(R.id.shortcut_action_record, ShortcutAction.RECORD);
        bind(R.id.shortcut_action_mirror, ShortcutAction.MIRROR);
        bind(R.id.shortcut_action_app, ShortcutAction.APP);
        bind(R.id.shortcut_action_dim, ShortcutAction.DIM);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ShortcutCapture.setActive(true);
    }

    @Override
    protected void onPause() {
        ShortcutCapture.setActive(false);
        super.onPause();
    }

    private void bind(int id, ShortcutAction action) {
        findViewById(id).setOnClickListener(v -> save(action));
    }

    private void save(ShortcutAction action) {
        if (captured == null) {
            return;
        }
        AppConfig config = new AppConfig(this);
        java.util.List<Shortcut> items = ShortcutBook.put(
                ShortcutBook.parse(config.getButtonShortcuts()),
                new Shortcut(captured.keyCode, captured.scanCode, captured.deviceName, action.key));
        config.setButtonShortcuts(ShortcutBook.write(items));
        if (AccessibilityGate.selfEnable(this) || config.isShortcutCatchEverywhere()) {
            finish();
            return;
        }
        com.kooo.evcam.ui.CamDialogs.show(new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                this, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.shortcut_access_title)
                .setMessage(getString(R.string.shortcut_access_msg, AccessibilityGate.grantCommand(this)))
                .setPositiveButton(R.string.shortcut_shizuku, (dialog, which) ->
                        ShizukuGrant.run(this, this::finish))
                .setNegativeButton(R.string.shortcut_access_ok, (dialog, which) -> finish())
                .setNeutralButton(R.string.shortcut_access_open, (dialog, which) -> {
                    try {
                        startActivity(new android.content.Intent(
                                android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    } catch (Exception e) {
                        com.kooo.evcam.AppLog.w("ShortcutCapture", "打不开无障碍设置页: " + e);
                    }
                    finish();
                })
                .setOnCancelListener(dialog -> finish()));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            return super.dispatchKeyEvent(event);
        }
        if (captured != null && event.getKeyCode() == captured.keyCode
                && (captured.keyCode != 0 || event.getScanCode() == captured.scanCode)) {
            return true;
        }
        if (captured != null || event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() > 0) {
            return super.dispatchKeyEvent(event);
        }
        InputDevice device = event.getDevice();
        if (device != null && device.isVirtual()) {
            return super.dispatchKeyEvent(event);
        }
        String name = device == null || device.getName() == null ? "" : device.getName();
        captured = new Shortcut(event.getKeyCode(), event.getScanCode(), name, "");
        prompt.setText(getString(R.string.shortcut_picked, buttonName(captured)));
        actions.setVisibility(View.VISIBLE);
        return true;
    }

    private String buttonName(Shortcut item) {
        if (item.keyCode != 0) {
            String name = KeyEvent.keyCodeToString(item.keyCode);
            String prefix = "KEYCODE_";
            if (name.startsWith(prefix)) {
                name = name.substring(prefix.length());
            }
            return name;
        }
        return getString(R.string.shortcut_scan, item.scanCode);
    }
}
