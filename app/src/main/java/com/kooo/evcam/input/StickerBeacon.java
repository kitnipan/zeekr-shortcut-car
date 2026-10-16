package com.kooo.evcam.input;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads presses straight from the ZEEKR_button broadcasts, with no connection at all.
 *
 * <p>The button advertises about once a second, non-connectable, and keeps doing so while it
 * "sleeps". Its data carries a press counter (cnt) that moves once per press and the press
 * kind (y: 00 single, 02 double). So a changed cnt is a press. Connecting was never needed:
 * the GATT link hung up 15 s after each press and lost the press that woke it.</p>
 *
 * <p>This radio cannot scan and connect at the same time, so {@link StickerHub} decides
 * when the scan may run (no sticker screen scanning, no setup connection, Bluetooth on).
 * Main thread only.</p>
 */
final class StickerBeacon {

    /** No advert from any button for this long while scanning: restart the scan. */
    private static final long SILENT_MS = 30_000L;
    private static final long CHECK_MS = 10_000L;
    /** Android refuses more than five scan starts in 30 s; stay well under. */
    private static final long MIN_RESTART_MS = 8_000L;
    private static final long RETRY_MS = 5_000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** A button not heard for this long shows as down. It advertises about once a second. */
    private static final long HEARD_MS = 15_000L;

    private static final Map<String, Integer> LAST_CNT = new HashMap<>();
    /** Last advert time per button; read from any thread for the status dots. */
    private static final Map<String, Long> HEARD = new java.util.concurrent.ConcurrentHashMap<>();

    private static Context app;
    private static BluetoothLeScanner scanner;
    private static Set<String> scanning = new HashSet<>();
    private static boolean wantOn;
    private static Set<String> wanted = new HashSet<>();
    private static long startedAt;
    private static long lastAdvertAt;

    private StickerBeacon() {
    }

    /**
     * Run the scan for exactly these buttons, or stop it when {@code allowed} is false.
     * Safe to call often; only a change of address set restarts the scan.
     */
    static void sync(Context context, Set<String> addresses, boolean allowed) {
        Context application = context == null ? null : context.getApplicationContext();
        Set<String> next = new HashSet<>(addresses == null ? new HashSet<>() : addresses);
        MAIN.post(() -> {
            app = application;
            wanted = next;
            wantOn = allowed && !next.isEmpty();
            apply();
        });
    }

    /** True while the scan is running (presses come from here, not from GATT). */
    static boolean isScanning() {
        return scanner != null;
    }

    /** This button was heard on air recently, so it is reachable. Any thread. */
    static boolean isHeard(String mac) {
        Long at = HEARD.get(mac);
        return at != null && SystemClock.elapsedRealtime() - at < HEARD_MS;
    }

    private static void apply() {
        if (!wantOn) {
            stop("off");
            return;
        }
        if (scanner != null && scanning.equals(wanted)) {
            return;
        }
        stop(null);
        start();
    }

    private static void start() {
        if (app == null || !canScan(app)) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (startedAt != 0 && now - startedAt < MIN_RESTART_MS) {
            MAIN.postDelayed(StickerBeacon::apply, MIN_RESTART_MS - (now - startedAt));
            return;
        }
        BluetoothManager manager = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        BluetoothLeScanner next = adapter == null || !adapter.isEnabled() ? null : adapter.getBluetoothLeScanner();
        if (next == null) {
            return;
        }
        List<ScanFilter> filters = new ArrayList<>();
        for (String mac : wanted) {
            filters.add(new ScanFilter.Builder().setDeviceAddress(mac).build());
        }
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setReportDelay(0)
                .build();
        try {
            next.startScan(filters, settings, CALLBACK);
        } catch (SecurityException | IllegalArgumentException | IllegalStateException e) {
            StickerHub.trace("beacon scan refused: " + e.getMessage());
            MAIN.postDelayed(StickerBeacon::apply, RETRY_MS);
            return;
        }
        scanner = next;
        scanning = new HashSet<>(wanted);
        startedAt = now;
        lastAdvertAt = now;
        // A fresh baseline: a press made while we were not listening is not replayed.
        LAST_CNT.clear();
        StickerHub.trace("beacon scan on " + scanning);
        MAIN.removeCallbacks(CHECK);
        MAIN.postDelayed(CHECK, CHECK_MS);
    }

    private static void stop(String why) {
        MAIN.removeCallbacks(CHECK);
        BluetoothLeScanner current = scanner;
        scanner = null;
        scanning = new HashSet<>();
        if (current == null) {
            return;
        }
        try {
            current.stopScan(CALLBACK);
        } catch (SecurityException | IllegalStateException ignored) {
            // Bluetooth already off
        }
        for (String mac : new ArrayList<>(HEARD.keySet())) {
            HEARD.remove(mac);
            StickerHub.beaconState(mac, false);
        }
        if (why != null) {
            StickerHub.trace("beacon scan " + why);
        }
    }

    /** The car's scanner can go quiet without telling us; restart if nothing arrives. */
    private static final Runnable CHECK = new Runnable() {
        @Override
        public void run() {
            if (scanner == null) {
                return;
            }
            long now = SystemClock.elapsedRealtime();
            for (Map.Entry<String, Long> e : new ArrayList<>(HEARD.entrySet())) {
                if (now - e.getValue() >= HEARD_MS) {
                    HEARD.remove(e.getKey());
                    StickerHub.trace("beacon " + e.getKey() + " not heard for " + HEARD_MS / 1000 + "s");
                    StickerHub.beaconState(e.getKey(), false);
                }
            }
            if (now - lastAdvertAt > SILENT_MS) {
                StickerHub.trace("beacon scan silent " + SILENT_MS / 1000 + "s, restarting");
                stop(null);
                start();
                return;
            }
            MAIN.postDelayed(this, CHECK_MS);
        }
    };

    private static final ScanCallback CALLBACK = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            MAIN.post(() -> seen(result));
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            MAIN.post(() -> {
                for (ScanResult result : results) {
                    seen(result);
                }
            });
        }

        @Override
        public void onScanFailed(int errorCode) {
            MAIN.post(() -> {
                StickerHub.trace("beacon scan failed code=" + errorCode);
                scanner = null;
                scanning = new HashSet<>();
                MAIN.postDelayed(StickerBeacon::apply, RETRY_MS);
            });
        }
    };

    private static void seen(ScanResult result) {
        if (scanner == null || result == null || result.getDevice() == null || result.getScanRecord() == null) {
            return;
        }
        String mac;
        try {
            mac = StickerDevices.normalize(result.getDevice().getAddress());
        } catch (SecurityException e) {
            return;
        }
        if (!scanning.contains(mac)) {
            return;
        }
        lastAdvertAt = SystemClock.elapsedRealtime();
        if (HEARD.put(mac, lastAdvertAt) == null) {
            StickerHub.beaconState(mac, true);
        }
        int[] f = StickerAdverts.fields(result.getScanRecord().getBytes());
        if (f == null) {
            return;
        }
        int cnt = f[0];
        int y = f[1];
        Integer last = LAST_CNT.put(mac, cnt);
        if (!StickerAdverts.isNewPress(last, cnt)) {
            if (last == null) {
                StickerHub.trace(String.format("beacon %s baseline cnt=%02X rssi=%d", mac, cnt, result.getRssi()));
            }
            return;
        }
        int missed = ((cnt - last) & 0xFF) - 1;
        byte[] frame = StickerAdverts.frameFor(y);
        StickerHub.trace(String.format("beacon press %s cnt=%02X y=%02X %s rssi=%d%s", mac, cnt, y,
                y == StickerAdverts.Y_DOUBLE ? "double" : y == StickerAdverts.Y_SINGLE ? "single" : "unknown",
                result.getRssi(), missed > 0 ? " missed=" + missed : ""));
        if (frame != null) {
            StickerHub.beaconPress(mac, frame);
        }
    }

    private static boolean canScan(Context context) {
        if (Build.VERSION.SDK_INT >= 31) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }
}
