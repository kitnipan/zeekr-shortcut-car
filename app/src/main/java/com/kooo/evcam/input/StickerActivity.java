package com.kooo.evcam.input;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Experimental screen: find a smart sticker the head unit will not pair, connect it,
 * and save a press as a shortcut.
 */
public class StickerActivity extends AppCompatActivity implements StickerHub.Watch {

    private static final int ASK = 47;
    private static final long SCAN_MS = 10_000L;
    private static final int LOG_LIMIT = 30;
    private static final int OTHER_LIMIT = 12;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable scanDone = this::stopScan;
    private final Map<String, Seen> seen = new LinkedHashMap<>();
    private final ArrayDeque<String> logLines = new ArrayDeque<>();
    private final Map<String, Boolean> connected = new LinkedHashMap<>();

    private AppConfig config;
    private LinearLayout devices;
    private TextView empty;
    private TextView status;
    private TextView live;
    private TextView log;
    private View actions;
    private TextView scanButton;
    private BluetoothLeScanner scanner;
    private boolean scanning;
    private String pendingAddress;
    private StickerFrame pending;

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            runOnUiThread(() -> addResult(result));
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            runOnUiThread(() -> {
                for (ScanResult result : results) {
                    addResult(result);
                }
            });
        }

        @Override
        public void onScanFailed(int errorCode) {
            runOnUiThread(() -> {
                scanning = false;
                scanButton.setText(R.string.sticker_scan);
                status.setText(getString(R.string.sticker_fail, errorCode));
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sticker);
        config = new AppConfig(this);
        devices = findViewById(R.id.sticker_devices);
        empty = findViewById(R.id.sticker_empty);
        status = findViewById(R.id.sticker_status);
        live = findViewById(R.id.sticker_live);
        log = findViewById(R.id.sticker_log);
        actions = findViewById(R.id.sticker_actions);
        scanButton = findViewById(R.id.sticker_scan);
        findViewById(R.id.sticker_close).setOnClickListener(v -> finish());
        scanButton.setOnClickListener(v -> {
            if (scanning) {
                stopScan();
            } else if (ensurePermission()) {
                startScan();
            }
        });
        SwitchCompat use = findViewById(R.id.sticker_use);
        use.setChecked(config.isStickerEnabled());
        use.setOnCheckedChangeListener((button, on) -> {
            config.setStickerEnabled(on);
            StickerHub.sync(this);
        });
        bind(R.id.sticker_action_record, ShortcutAction.RECORD);
        bind(R.id.sticker_action_mirror, ShortcutAction.MIRROR);
        bind(R.id.sticker_action_both_mirrors, ShortcutAction.BOTH_MIRRORS);
        bind(R.id.sticker_action_app, ShortcutAction.APP);
        bind(R.id.sticker_action_dim, ShortcutAction.DIM);
        bind(R.id.sticker_action_save, ShortcutAction.SAVE);
        bind(R.id.sticker_action_lock_save, ShortcutAction.LOCK_SAVE);
        bind(R.id.sticker_action_hold_speak, ShortcutAction.HOLD_SPEAK);
    }

    @Override
    protected void onResume() {
        super.onResume();
        StickerHub.setCapture(true);
        StickerHub.addWatch(this);
        StickerHub.sync(this);
        render();
    }

    @Override
    protected void onPause() {
        stopScan();
        StickerHub.setCapture(false);
        StickerHub.removeWatch(this);
        StickerHub.sync(this);
        super.onPause();
    }

    private void bind(int id, ShortcutAction action) {
        findViewById(id).setOnClickListener(v -> save(action));
    }

    private void save(ShortcutAction action) {
        if (pending == null || pendingAddress == null) {
            return;
        }
        Shortcut next = new Shortcut(pending.control, 0, StickerFrame.deviceName(pendingAddress),
                action.key, pending.press.key);
        config.setButtonShortcuts(ShortcutBook.write(
                ShortcutBook.put(ShortcutBook.parse(config.getButtonShortcuts()), next)));
        config.setStickerEnabled(true);
        SwitchCompat use = findViewById(R.id.sticker_use);
        if (!use.isChecked()) {
            use.setChecked(true);
        } else {
            StickerHub.sync(this);
        }
        int label = StickerFrame.controlLabel(pending.control);
        String control = label == 0 ? String.valueOf(pending.control) : getString(label);
        status.setText(getString(R.string.sticker_saved_action,
                control + " " + getString(pending.press.labelRes),
                getString(action.labelRes)));
    }

    private boolean ensurePermission() {
        List<String> missing = new ArrayList<>();
        for (String permission : needed()) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        if (missing.isEmpty()) {
            return true;
        }
        requestPermissions(missing.toArray(new String[0]), ASK);
        return false;
    }

    private static String[] needed() {
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            };
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != ASK) {
            return;
        }
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                status.setText(R.string.sticker_need_permission);
                return;
            }
        }
        startScan();
    }

    private void startScan() {
        BluetoothAdapter adapter = adapter();
        if (adapter == null || !adapter.isEnabled()) {
            status.setText(R.string.sticker_bluetooth_off);
            return;
        }
        BluetoothLeScanner next = adapter.getBluetoothLeScanner();
        if (next == null) {
            status.setText(R.string.sticker_bluetooth_off);
            return;
        }
        stopScan();
        scanner = next;
        scanning = true;
        scanButton.setText(R.string.sticker_scanning);
        status.setText(R.string.sticker_scanning);
        try {
            scanner.startScan(null, new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build(), scanCallback);
        } catch (SecurityException e) {
            scanning = false;
            scanButton.setText(R.string.sticker_scan);
            status.setText(R.string.sticker_need_permission);
            return;
        }
        handler.postDelayed(scanDone, SCAN_MS);
    }

    private void stopScan() {
        handler.removeCallbacks(scanDone);
        if (scanner != null && scanning) {
            try {
                scanner.stopScan(scanCallback);
            } catch (SecurityException ignored) {
                // scan permission gone
            }
        }
        scanning = false;
        if (scanButton != null) {
            scanButton.setText(R.string.sticker_scan);
        }
    }

    private void addResult(ScanResult result) {
        if (result == null || result.getDevice() == null) {
            return;
        }
        BluetoothDevice device = result.getDevice();
        String address;
        String name;
        try {
            address = StickerDevices.normalize(device.getAddress());
            name = device.getName();
        } catch (SecurityException e) {
            status.setText(R.string.sticker_need_permission);
            return;
        }
        if (address.isEmpty()) {
            return;
        }
        boolean service = advertises(result.getScanRecord());
        Seen row = seen.get(address);
        boolean fresh = row == null;
        if (row == null) {
            row = new Seen();
            row.address = address;
            row.rank = StickerMatch.RANK_OTHER;
            seen.put(address, row);
        }
        int previousRank = row.rank;
        int nextRank = Math.min(previousRank, StickerMatch.rank(name, service));
        boolean nameChanged = name != null && !name.isEmpty() && !name.equals(row.name);
        boolean rssiChanged = row.rssi == null || Math.abs(row.rssi - result.getRssi()) >= 8;
        if (name != null && !name.isEmpty()) {
            row.name = name;
        }
        row.rank = nextRank;
        row.rssi = result.getRssi();
        if (fresh || nameChanged || rssiChanged || nextRank != previousRank) {
            render();
        }
    }

    private static boolean advertises(ScanRecord record) {
        if (record == null) {
            return false;
        }
        List<ParcelUuid> uuids = record.getServiceUuids();
        if (uuids != null) {
            for (ParcelUuid id : uuids) {
                if (id != null && StickerMatch.isService(id.getUuid())) {
                    return true;
                }
            }
        }
        Map<ParcelUuid, byte[]> data = record.getServiceData();
        if (data != null) {
            for (ParcelUuid id : data.keySet()) {
                if (id != null && StickerMatch.isService(id.getUuid())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void render() {
        devices.removeAllViews();
        List<String> saved = StickerDevices.parse(config.getStickerDevices());
        boolean candidate = false;
        for (Seen row : seen.values()) {
            if (row.rank < StickerMatch.RANK_OTHER) {
                candidate = true;
            }
        }
        int shown = 0;
        for (String address : saved) {
            Seen row = seen.get(address);
            addRow(address, row == null ? null : row.name, row == null ? null : row.rssi, true);
            shown++;
        }
        for (Seen row : seen.values()) {
            if (saved.contains(row.address) || row.rank >= StickerMatch.RANK_OTHER) {
                continue;
            }
            addRow(row.address, row.name, row.rssi, false);
            shown++;
        }
        if (!candidate) {
            int others = 0;
            boolean header = false;
            for (Seen row : seen.values()) {
                if (saved.contains(row.address) || row.rank < StickerMatch.RANK_OTHER) {
                    continue;
                }
                if (row.name == null || row.name.isEmpty()) {
                    continue;
                }
                if (!header) {
                    TextView title = new TextView(this);
                    title.setText(R.string.sticker_other);
                    title.setTextAppearance(R.style.TextAppearance_Cam_Caption);
                    title.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_tertiary));
                    title.setPadding(dp(16), dp(12), dp(16), dp(4));
                    devices.addView(title);
                    header = true;
                }
                addRow(row.address, row.name, row.rssi, false);
                shown++;
                others++;
                if (others >= OTHER_LIMIT) {
                    break;
                }
            }
        }
        empty.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
    }

    private void addRow(String address, String name, Integer rssi, boolean saved) {
        View line = LayoutInflater.from(this).inflate(R.layout.item_sticker, devices, false);
        TextView title = line.findViewById(R.id.sticker_name);
        TextView meta = line.findViewById(R.id.sticker_meta);
        TextView action = line.findViewById(R.id.sticker_action);
        title.setText(name == null || name.isEmpty() ? getString(R.string.sticker_unnamed) : name);
        String detail = rssi == null ? address : getString(R.string.sticker_device_meta, address, rssi);
        if (Boolean.TRUE.equals(connected.get(address))) {
            detail = detail + "  " + getString(R.string.sticker_connected);
        }
        meta.setText(detail);
        action.setText(saved ? R.string.sticker_forget : R.string.sticker_connect);
        action.setOnClickListener(v -> {
            if (saved) {
                forget(address);
            } else if (ensurePermission()) {
                stopScan();
                status.setText(R.string.sticker_connecting);
                StickerHub.probe(this, address);
            }
        });
        devices.addView(line);
    }

    private void forget(String address) {
        String deviceName = StickerFrame.deviceName(address);
        List<Shortcut> kept = new ArrayList<>();
        for (Shortcut item : ShortcutBook.parse(config.getButtonShortcuts())) {
            if (item != null && !deviceName.equals(item.deviceName)) {
                kept.add(item);
            }
        }
        config.setButtonShortcuts(ShortcutBook.write(kept));
        config.setStickerDevices(StickerDevices.write(
                StickerDevices.remove(StickerDevices.parse(config.getStickerDevices()), address)));
        connected.remove(address);
        StickerHub.disconnect(address);
        render();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private BluetoothAdapter adapter() {
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        return manager == null ? null : manager.getAdapter();
    }

    private void append(String line) {
        if (line == null || line.isEmpty()) {
            return;
        }
        logLines.addLast(line);
        while (logLines.size() > LOG_LIMIT) {
            logLines.removeFirst();
        }
        StringBuilder out = new StringBuilder();
        for (String item : logLines) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(item);
        }
        log.setText(out.toString());
    }

    @Override
    public void onState(String address, boolean up) {
        connected.put(StickerDevices.normalize(address), up);
        render();
    }

    @Override
    public void onPacket(String address, byte[] raw) {
        append(StickerFrame.hex(raw));
        StickerFrame frame = StickerFrame.parse(raw);
        if (frame == null) {
            return;
        }
        pendingAddress = StickerDevices.normalize(address);
        pending = frame;
        int label = StickerFrame.controlLabel(frame.control);
        String control = label == 0 ? String.valueOf(frame.control) : getString(label);
        live.setVisibility(View.VISIBLE);
        live.setText(getString(R.string.sticker_press_line, control, getString(frame.press.labelRes)));
        actions.setVisibility(View.VISIBLE);
    }

    @Override
    public void onReady(String address) {
        String mac = StickerDevices.normalize(address);
        config.setStickerDevices(StickerDevices.write(
                StickerDevices.put(StickerDevices.parse(config.getStickerDevices()), mac)));
        connected.put(mac, true);
        status.setText(R.string.sticker_waiting);
        render();
    }

    @Override
    public void onFail(String address, int reason, int code) {
        if (reason == StickerHub.FAIL_BLUETOOTH) {
            status.setText(R.string.sticker_bluetooth_off);
        } else if (reason == StickerHub.FAIL_TIMEOUT) {
            status.setText(R.string.sticker_timeout);
        } else if (reason == StickerHub.FAIL_NO_SERVICE) {
            status.setText(R.string.sticker_no_service);
        } else {
            status.setText(getString(R.string.sticker_fail, code));
        }
    }

    @Override
    public void onPhase(String address, int phase) {
        if (phase == StickerHub.PHASE_PAIRING) {
            status.setText(R.string.sticker_pairing);
        } else if (phase == StickerHub.PHASE_CONNECTING) {
            status.setText(R.string.sticker_connecting);
        } else if (phase == StickerHub.PHASE_DISCOVERING) {
            status.setText(R.string.sticker_discovering);
        }
    }

    @Override
    public void onLog(String line) {
        append(line);
    }

    private static final class Seen {
        String address;
        String name;
        Integer rssi;
        int rank;
    }
}
