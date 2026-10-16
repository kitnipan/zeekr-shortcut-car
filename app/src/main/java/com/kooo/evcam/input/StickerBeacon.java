package com.kooo.evcam.input;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
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
 * <p>After each press the button broadcasts about once a second for ~15 s, non-connectable,
 * then goes quiet. Its data carries a press counter (cnt) that moves once per press and the
 * press kind (y: 00 single, 02 double). So a changed cnt is a press. Connecting was never needed:
 * the GATT link hung up 15 s after each press and lost the press that woke it.</p>
 *
 * <p>This radio cannot scan and connect at the same time, so {@link StickerHub} decides
 * when the scan may run (no sticker screen scanning, no setup connection, Bluetooth on).
 * Main thread only.</p>
 */
final class StickerBeacon {

    /**
     * No advert from any button for this long while scanning: restart the scan. A button is
     * quiet between presses (it broadcasts for about 15 s after each one), so this is long.
     */
    private static final long SILENT_MS = 5L * 60 * 1000;
    private static final long CHECK_MS = 10_000L;
    /** Android refuses more than five scan starts in 30 s; stay well under. */
    private static final long MIN_RESTART_MS = 8_000L;
    private static final long RETRY_MS = 5_000L;
    private static final long REFRESH_MS = 20L * 60 * 1000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** A button not heard for this long shows as down. It is quiet between presses. */
    private static final long HEARD_MS = 10L * 60 * 1000;

    private static final Map<String, Integer> LAST_CNT = new HashMap<>();
    /** Last advert time per button; read from any thread for the status dots. */
    private static final Map<String, Long> HEARD = new java.util.concurrent.ConcurrentHashMap<>();

    private static Context app;
    private static BluetoothLeScanner scanner;
    private static Set<String> scanning = new HashSet<>();
    private static boolean wantOn;
    /** A controller or shortcut screen is open: any ZEEKR_button counts, saved or not. */
    private static boolean openToNew;
    /**
     * The sticker screens took the radio. Presses made there are theirs, so the next scan
     * starts from a fresh baseline instead of replaying the last one. Not after Bluetooth off:
     * the first press after the car wakes must still count.
     */
    private static boolean rebaseline;
    private static Set<String> wanted = new HashSet<>();
    private static long startedAt;
    private static long lastAdvertAt;

    private StickerBeacon() {
    }

    /**
     * Run the scan for exactly these buttons, or stop it when {@code allowed} is false.
     * Safe to call often; only a change of address set restarts the scan.
     */
    static void sync(Context context, Set<String> addresses, boolean allowed, boolean anyButton,
                     boolean screenHasRadio) {
        Context application = context == null ? null : context.getApplicationContext();
        Set<String> next = new HashSet<>(addresses == null ? new HashSet<>() : addresses);
        MAIN.post(() -> {
            app = application;
            wanted = next;
            openToNew = anyButton;
            if (screenHasRadio) {
                rebaseline = true;
            }
            wantOn = allowed && (!next.isEmpty() || anyButton);
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
        if (app == null) {
            return;
        }
        if (!canScan(app)) {
            retryLater("no scan permission");
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
            retryLater("scanner not ready");
            return;
        }
        // Unfiltered: an address filter matched nothing on this head unit (car log 2.11.26,
        // 30 s silent while the BLE signals screen heard both buttons). seen() keeps only ours.
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setReportDelay(0)
                .build();
        // A new callback per start: reusing one across quick stop/start cycles is a known way
        // to get SCAN_FAILED_APPLICATION_REGISTRATION_FAILED (2). Old ones are ignored.
        ScanCallback fresh = newCallback();
        try {
            next.startScan(null, settings, fresh);
        } catch (SecurityException | IllegalArgumentException | IllegalStateException e) {
            retryLater("refused: " + e.getMessage());
            return;
        }
        callback = fresh;
        scanner = next;
        scanning = new HashSet<>(wanted);
        startedAt = now;
        lastAdvertAt = now;
        if (rebaseline) {
            rebaseline = false;
            countsLoaded = true;
            LAST_CNT.clear();
        } else {
            loadCounts();
        }
        StickerHub.trace("beacon scan on " + scanning);
        MAIN.removeCallbacks(CHECK);
        MAIN.postDelayed(CHECK, CHECK_MS);
        MAIN.removeCallbacks(REFRESH);
        MAIN.postDelayed(REFRESH, REFRESH_MS);
    }

    private static void stop(String why) {
        MAIN.removeCallbacks(CHECK);
        MAIN.removeCallbacks(REFRESH);
        MAIN.removeCallbacks(RETRY);
        BluetoothLeScanner current = scanner;
        ScanCallback old = callback;
        scanner = null;
        callback = null;
        scanning = new HashSet<>();
        if (current != null && old != null) {
            try {
                current.stopScan(old);
            } catch (SecurityException | IllegalStateException ignored) {
                // Bluetooth already off
            }
        }
        // A restart keeps the dots lit; only switching the scan off clears them.
        if ("off".equals(why)) {
            for (String mac : new ArrayList<>(HEARD.keySet())) {
                HEARD.remove(mac);
                StickerHub.beaconState(mac, false);
            }
        }
        if (current != null && why != null) {
            StickerHub.trace("beacon scan " + why);
        }
    }

    /**
     * Android quietly downgrades an unfiltered scan that runs for 30 minutes. Start a fresh one
     * before that, keeping the press counters so no press is lost or replayed across it.
     */
    private static final Runnable REFRESH = new Runnable() {
        @Override
        public void run() {
            if (scanner == null) {
                return;
            }
            stop(null);
            start();
        }
    };

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

    private static ScanCallback callback;
    private static int failures;

    private static final Runnable RETRY = StickerBeacon::apply;

    /** Try again later, backing off 5, 10, 20 s, then every 60 s. Never gives up. */
    private static void retryLater(String why) {
        failures++;
        long wait = Math.min(60_000L, RETRY_MS << Math.min(4, failures - 1));
        StickerHub.trace("beacon scan " + why + ", retry in " + wait / 1000 + "s (#" + failures + ")");
        MAIN.removeCallbacks(RETRY);
        MAIN.postDelayed(RETRY, wait);
    }

    private static ScanCallback newCallback() {
        return new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                ScanCallback self = this;
                MAIN.post(() -> {
                    if (callback == self) {
                        seen(result);
                    }
                });
            }

            @Override
            public void onBatchScanResults(List<ScanResult> results) {
                ScanCallback self = this;
                MAIN.post(() -> {
                    if (callback != self) {
                        return;
                    }
                    for (ScanResult result : results) {
                        seen(result);
                    }
                });
            }

            @Override
            public void onScanFailed(int errorCode) {
                ScanCallback self = this;
                MAIN.post(() -> {
                    if (callback != self) {
                        return;
                    }
                    callback = null;
                    scanner = null;
                    scanning = new HashSet<>();
                    MAIN.removeCallbacks(CHECK);
                    MAIN.removeCallbacks(REFRESH);
                    retryLater("failed code=" + errorCode + " (" + failName(errorCode) + ")");
                });
            }
        };
    }

    static String failName(int code) {
        switch (code) {
            case 1: return "already started";
            case 2: return "app registration failed";
            case 3: return "internal error";
            case 4: return "feature unsupported";
            case 5: return "out of hardware resources";
            case 6: return "scanning too frequently";
            default: return "unknown";
        }
    }

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
        boolean saved = scanning.contains(mac);
        int[] shape = StickerAdverts.fields(result.getScanRecord().getBytes());
        // An unsaved button only while a controller / shortcut screen wants to capture one;
        // its data shape is what marks it (the name is not in every advert).
        if (!saved && !(openToNew && shape != null)) {
            return;
        }
        lastAdvertAt = SystemClock.elapsedRealtime();
        failures = 0;
        if (HEARD.put(mac, lastAdvertAt) == null) {
            StickerHub.beaconState(mac, true);
        }
        int[] f = shape;
        if (f == null) {
            return;
        }
        int cnt = f[0];
        int y = f[1];
        Integer last = LAST_CNT.put(mac, cnt);
        if (last == null || last != cnt) {
            saveCount(mac, cnt);
        }
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

    private static final String PREFS = "sticker_beacon";
    private static boolean countsLoaded;

    /**
     * The last press counter per button survives scan restarts and app restarts. Otherwise the
     * first press after a quiet spell, or after the app starts, only set a baseline and was
     * lost (car log 2.11.27). A button only broadcasts for ~15 s after a press, so at worst a
     * press made while the app was starting runs a moment late, which is what was meant.
     */
    private static void loadCounts() {
        if (countsLoaded || app == null) {
            return;
        }
        countsLoaded = true;
        Map<String, ?> all = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getAll();
        for (Map.Entry<String, ?> e : all.entrySet()) {
            if (e.getValue() instanceof Integer && !LAST_CNT.containsKey(e.getKey())) {
                LAST_CNT.put(e.getKey(), (Integer) e.getValue());
            }
        }
    }

    private static void saveCount(String mac, int cnt) {
        if (app != null) {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(mac, cnt).apply();
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
