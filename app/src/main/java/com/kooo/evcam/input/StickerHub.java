package com.kooo.evcam.input;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.UserExit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps a GATT link to saved smart stickers and turns a press into a shortcut.
 *
 * <p>The head unit firmware that pairs this sticker is not on every car. This
 * talks to it directly. The link stays up only while {@link AppConfig#isStickerEnabled()}
 * is on, or while the experimental screen is open.</p>
 */
public final class StickerHub {

    public static final int FAIL_BLUETOOTH = 1;
    public static final int FAIL_TIMEOUT = 2;
    public static final int FAIL_NO_SERVICE = 3;
    public static final int FAIL_GATT = 4;

    private static final String TAG = "StickerHub";
    private static final long CONNECT_TIMEOUT_MS = 8000L;
    private static final long RECONNECT_MS = 2000L;

    /** Open while the experimental screen is visible. Presses are shown there instead of run. */
    public interface Watch {
        void onState(String address, boolean connected);

        void onPacket(String address, byte[] raw);

        void onReady(String address);

        void onFail(String address, int reason, int status);

        void onLog(String line);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** GATT from the moment connectGatt returns, including while still connecting. */
    private static final Map<String, BluetoothGatt> OPEN = new HashMap<>();
    private static final Set<String> PENDING = new HashSet<>();
    /** Closed on purpose. The disconnect callback must not reconnect. */
    private static final Set<String> CLOSING = new HashSet<>();
    private static Context app;
    private static Watch watch;

    private StickerHub() {
    }

    public static void setWatch(Watch next) {
        watch = next;
    }

    /** Connect saved stickers, or drop them when the experiment is off and the screen is closed. */
    public static void sync(Context context) {
        if (context == null) {
            return;
        }
        Context application = context.getApplicationContext();
        MAIN.post(() -> syncOnMain(application));
    }

    /** User tapped Connect. Tries once, without waiting in the background. */
    public static void probe(Context context, String address) {
        if (context == null) {
            return;
        }
        Context application = context.getApplicationContext();
        String mac = StickerDevices.normalize(address);
        MAIN.post(() -> {
            app = application;
            close(mac);
            connect(application, mac, false);
        });
    }

    public static void disconnect(String address) {
        String mac = StickerDevices.normalize(address);
        MAIN.post(() -> close(mac));
    }

    private static void syncOnMain(Context application) {
        app = application;
        if (UserExit.isExited(application)) {
            closeAll();
            return;
        }
        boolean hold = new AppConfig(application).isStickerEnabled() || watch != null;
        if (!hold) {
            closeAll();
            return;
        }
        List<String> wanted = StickerDevices.parse(new AppConfig(application).getStickerDevices());
        Set<String> keep = new HashSet<>(wanted);
        for (String address : new ArrayList<>(OPEN.keySet())) {
            if (!keep.contains(address) && !PENDING.contains(address)) {
                close(address);
            }
        }
        if (!canConnect(application)) {
            return;
        }
        for (String address : wanted) {
            if (!OPEN.containsKey(address) && !PENDING.contains(address)) {
                connect(application, address, true);
            }
        }
    }

    private static void connect(Context application, String address, boolean auto) {
        if (address.isEmpty() || OPEN.containsKey(address) || PENDING.contains(address)) {
            return;
        }
        if (!canConnect(application)) {
            fail(address, FAIL_BLUETOOTH, 0);
            return;
        }
        BluetoothAdapter adapter = adapter(application);
        if (adapter == null || !adapter.isEnabled()) {
            fail(address, FAIL_BLUETOOTH, 0);
            return;
        }
        BluetoothDevice device;
        try {
            device = adapter.getRemoteDevice(address);
        } catch (IllegalArgumentException e) {
            AppLog.w(TAG, "地址无效: " + address);
            return;
        }
        CLOSING.remove(address);
        PENDING.add(address);
        state(address, false);
        BluetoothGattCallback callback = callback(address);
        BluetoothGatt gatt;
        try {
            gatt = device.connectGatt(application, auto, callback, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) {
            PENDING.remove(address);
            fail(address, FAIL_BLUETOOTH, 0);
            return;
        }
        if (gatt == null) {
            PENDING.remove(address);
            fail(address, FAIL_GATT, -1);
            return;
        }
        OPEN.put(address, gatt);
        if (!auto) {
            MAIN.postDelayed(() -> {
                if (PENDING.contains(address)) {
                    close(address);
                    fail(address, FAIL_TIMEOUT, 0);
                }
            }, CONNECT_TIMEOUT_MS);
        }
        AppLog.d(TAG, "连接智能贴 " + address + " auto=" + auto);
    }

    private static BluetoothGattCallback callback(String address) {
        return new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                MAIN.post(() -> {
                    if (newState == BluetoothGatt.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                        if (CLOSING.contains(address)) {
                            drop(address, gatt);
                            return;
                        }
                        OPEN.put(address, gatt);
                        state(address, true);
                        try {
                            gatt.discoverServices();
                        } catch (SecurityException e) {
                            close(address);
                            fail(address, FAIL_BLUETOOTH, 0);
                        }
                        return;
                    }
                    if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                        boolean intentional = CLOSING.remove(address);
                        drop(address, gatt);
                        if (!intentional) {
                            scheduleReconnect(address);
                        }
                        if (status != BluetoothGatt.GATT_SUCCESS && status != 0) {
                            fail(address, FAIL_GATT, status);
                        }
                    }
                });
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                MAIN.post(() -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        fail(address, FAIL_GATT, status);
                        return;
                    }
                    BluetoothGattCharacteristic notify = findNotify(gatt);
                    if (notify == null) {
                        logServices(gatt);
                        close(address);
                        fail(address, FAIL_NO_SERVICE, 0);
                        return;
                    }
                    ready(address);
                    PENDING.remove(address);
                    subscribe(gatt, notify);
                });
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
                byte[] value = characteristic.getValue();
                MAIN.post(() -> packet(address, value));
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic,
                                                byte[] value) {
                MAIN.post(() -> packet(address, value));
            }
        };
    }

    private static BluetoothGattCharacteristic findNotify(BluetoothGatt gatt) {
        BluetoothGattService service = gatt.getService(StickerMatch.SERVICE);
        if (service == null && gatt.getServices() != null) {
            for (BluetoothGattService candidate : gatt.getServices()) {
                UUID uuid = candidate.getUuid();
                if (uuid != null && uuid.toString().toLowerCase(java.util.Locale.US).contains("ffd0")) {
                    service = candidate;
                    break;
                }
            }
        }
        if (service == null) {
            return null;
        }
        BluetoothGattCharacteristic exact = service.getCharacteristic(StickerMatch.NOTIFY);
        if (exact != null) {
            return exact;
        }
        for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
            if (StickerMatch.isNotify(characteristic.getUuid())) {
                return characteristic;
            }
        }
        return null;
    }

    private static void subscribe(BluetoothGatt gatt, BluetoothGattCharacteristic notify) {
        try {
            gatt.setCharacteristicNotification(notify, true);
            BluetoothGattDescriptor cccd = notify.getDescriptor(StickerMatch.CCCD);
            if (cccd == null) {
                log("notify without cccd");
                return;
            }
            boolean indicate = (notify.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                    && (notify.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0;
            byte[] value = indicate
                    ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
            if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeDescriptor(cccd, value);
            } else {
                cccd.setValue(value);
                gatt.writeDescriptor(cccd);
            }
        } catch (SecurityException e) {
            log("notify denied");
        }
    }

    private static String lastPacket = "";
    private static long lastPacketAt;

    private static void packet(String address, byte[] raw) {
        String key = address + " " + StickerFrame.hex(raw);
        long now = android.os.SystemClock.elapsedRealtime();
        if (key.equals(lastPacket) && now - lastPacketAt < 80L) {
            return;
        }
        lastPacket = key;
        lastPacketAt = now;
        AppLog.d(TAG, key);
        Watch current = watch;
        if (current != null) {
            current.onPacket(address, raw);
            return;
        }
        if (app == null || !new AppConfig(app).isStickerEnabled()) {
            return;
        }
        StickerFrame frame = StickerFrame.parse(raw);
        if (frame == null) {
            return;
        }
        List<Shortcut> items = ShortcutBook.parse(new AppConfig(app).getButtonShortcuts());
        Shortcut hit = ShortcutBook.match(items, frame.control, 0,
                StickerFrame.deviceName(address), frame.press);
        if (hit != null) {
            ShortcutPerformer.perform(app, hit.action);
        }
    }

    private static void scheduleReconnect(String address) {
        MAIN.postDelayed(() -> {
            if (app == null || OPEN.containsKey(address) || PENDING.contains(address)) {
                return;
            }
            if (!shouldHold(address)) {
                return;
            }
            connect(app, address, true);
        }, RECONNECT_MS);
    }

    private static boolean shouldHold(String address) {
        if (app == null || UserExit.isExited(app)) {
            return false;
        }
        if (!new AppConfig(app).isStickerEnabled() && watch == null) {
            return false;
        }
        return StickerDevices.parse(new AppConfig(app).getStickerDevices()).contains(address);
    }

    private static void drop(String address, BluetoothGatt gatt) {
        BluetoothGatt current = OPEN.get(address);
        if (current != null && gatt != null && current != gatt) {
            try {
                gatt.close();
            } catch (SecurityException ignored) {
                // stale callback
            }
            return;
        }
        PENDING.remove(address);
        OPEN.remove(address);
        if (current != null) {
            try {
                current.close();
            } catch (SecurityException ignored) {
                // already gone
            }
        }
        state(address, false);
    }

    private static void close(String address) {
        CLOSING.add(address);
        PENDING.remove(address);
        BluetoothGatt gatt = OPEN.remove(address);
        state(address, false);
        if (gatt == null) {
            CLOSING.remove(address);
            return;
        }
        try {
            gatt.disconnect();
            gatt.close();
        } catch (SecurityException ignored) {
            // permission dropped
        }
    }

    private static void closeAll() {
        for (String address : new ArrayList<>(OPEN.keySet())) {
            close(address);
        }
        PENDING.clear();
    }

    private static void logServices(BluetoothGatt gatt) {
        List<BluetoothGattService> services = gatt.getServices();
        if (services == null) {
            return;
        }
        for (BluetoothGattService service : services) {
            log(service.getUuid().toString());
            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                log("  " + characteristic.getUuid() + " props=" + characteristic.getProperties());
            }
        }
    }

    private static boolean canConnect(Context context) {
        if (Build.VERSION.SDK_INT < 31) {
            return true;
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static BluetoothAdapter adapter(Context context) {
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        return manager == null ? null : manager.getAdapter();
    }

    private static void state(String address, boolean connected) {
        Watch current = watch;
        if (current != null) {
            current.onState(address, connected);
        }
    }

    private static void ready(String address) {
        Watch current = watch;
        if (current != null) {
            current.onReady(address);
        }
    }

    private static void fail(String address, int reason, int status) {
        AppLog.w(TAG, "智能贴失败 " + address + " reason=" + reason + " status=" + status);
        Watch current = watch;
        if (current != null) {
            current.onFail(address, reason, status);
        }
    }

    private static void log(String line) {
        AppLog.d(TAG, line);
        Watch current = watch;
        if (current != null) {
            current.onLog(line);
        }
    }
}
