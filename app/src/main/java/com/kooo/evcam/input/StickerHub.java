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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import androidx.core.content.ContextCompat;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.UserExit;

import java.util.ArrayDeque;
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
 * talks to it directly. The link stays up while {@link AppConfig#isStickerEnabled()}
 * is on, or while an experimental screen is open. Pairing a second sticker
 * pauses the other links first, because this head unit will not bond while a
 * GATT connection is already up.</p>
 */
public final class StickerHub {

    public static final int FAIL_BLUETOOTH = 1;
    public static final int FAIL_TIMEOUT = 2;
    public static final int FAIL_NO_SERVICE = 3;
    public static final int FAIL_GATT = 4;

    private static final String TAG = "StickerHub";
    private static final long CONNECT_TIMEOUT_MS = 15000L;
    private static final long RECONNECT_MS = 2000L;

    /** Open while the experimental screen is visible. Presses are shown there instead of run. */
    public interface Watch {
        void onState(String address, boolean connected);

        void onPacket(String address, byte[] raw);

        void onReady(String address);

        void onFail(String address, int reason, int status);

        void onLog(String line);

        void onPhase(String address, int phase);
    }

    public static final int PHASE_PAIRING = 1;
    public static final int PHASE_CONNECTING = 2;
    public static final int PHASE_DISCOVERING = 3;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final HandlerThread GATT_THREAD = new HandlerThread("sticker-gatt");
    private static final Handler GATT;

    static {
        GATT_THREAD.start();
        GATT = new Handler(GATT_THREAD.getLooper());
    }
    /** GATT from the moment connectGatt returns, including while still connecting. */
    private static final Map<String, BluetoothGatt> OPEN = new HashMap<>();
    private static final Set<String> PENDING = new HashSet<>();
    /** Closed on purpose. The disconnect callback must not reconnect. */
    private static final Set<String> CLOSING = new HashSet<>();
    private static final List<Watch> WATCHES = new ArrayList<>();
    private static final Set<String> BONDING = new HashSet<>();
    /** Other links dropped so this head unit can bond a new sticker. */
    private static final Set<String> PAUSED = new HashSet<>();
    private static final Map<String, Runnable> BOND_TIMEOUT = new HashMap<>();
    private static String queuedAddress;
    /** Pause delay is still running. Do not bring the other stickers back yet. */
    private static int holding;
    private static final Map<String, ArrayDeque<BluetoothGattCharacteristic>> SUBSCRIBE = new HashMap<>();
    private static final Map<String, Integer> TRIES = new HashMap<>();
    private static final long BOND_TIMEOUT_MS = 12000L;
    private static Context app;
    /** Sticker screen is open, so a press is for saving, not for running the shortcut. */
    private static boolean capture;
    private static boolean bondsRegistered;

    private StickerHub() {
    }

    public static void addWatch(Watch watch) {
        if (watch == null) {
            return;
        }
        MAIN.post(() -> {
            if (!WATCHES.contains(watch)) {
                WATCHES.add(watch);
            }
        });
    }

    public static void removeWatch(Watch watch) {
        MAIN.post(() -> WATCHES.remove(watch));
    }

    public static void setCapture(boolean on) {
        capture = on;
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
            TRIES.remove(mac);
            holding++;
            boolean paused = pauseOthers(mac);
            Runnable go = () -> {
                holding = Math.max(0, holding - 1);
                pairThenConnect(application, mac);
            };
            if (paused) {
                MAIN.postDelayed(go, 500);
            } else {
                go.run();
            }
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
        boolean hold = linkWanted(application) || !WATCHES.isEmpty();
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
            if (!OPEN.containsKey(address) && !PENDING.contains(address) && !PAUSED.contains(address)) {
                connect(application, address, true);
            }
        }
    }

    private static void pairThenConnect(Context application, String address) {
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
            afterAttempt();
            return;
        }
        int bond = BluetoothDevice.BOND_NONE;
        try {
            bond = device.getBondState();
        } catch (SecurityException e) {
            fail(address, FAIL_BLUETOOTH, 0);
            return;
        }
        if (!BONDING.isEmpty() && !BONDING.contains(address)) {
            queuedAddress = address;
            phase(address, PHASE_PAIRING);
            return;
        }
        if (bond == BluetoothDevice.BOND_BONDED) {
            connect(application, address, false);
            return;
        }
        ensureBonds(application);
        BONDING.add(address);
        phase(address, PHASE_PAIRING);
        boolean started = startBond(device);
        AppLog.d(TAG, "配对智能贴 " + address + " started=" + started);
        if (!started) {
            BONDING.remove(address);
            connect(application, address, false);
            return;
        }
        Runnable giveUp = () -> {
            if (BONDING.remove(address)) {
                AppLog.w(TAG, "配对超时，改走连接 " + address);
                connect(app, address, false);
            }
        };
        BOND_TIMEOUT.put(address, giveUp);
        MAIN.postDelayed(giveUp, BOND_TIMEOUT_MS);
    }

    private static void ensureBonds(Context context) {
        if (bondsRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(BONDS, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(BONDS, filter);
        }
        bondsRegistered = true;
    }

    private static final BroadcastReceiver BONDS = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) {
                return;
            }
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
            int previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE);
            MAIN.post(() -> onBond(device, state, previous));
        }
    };

    private static void onBond(BluetoothDevice device, int state, int previous) {
        if (device == null || app == null) {
            return;
        }
        String address;
        try {
            address = StickerDevices.normalize(device.getAddress());
        } catch (SecurityException e) {
            return;
        }
        if (!BONDING.contains(address)) {
            return;
        }
        if (state == BluetoothDevice.BOND_BONDED
                || (state == BluetoothDevice.BOND_NONE && previous == BluetoothDevice.BOND_BONDING)) {
            BONDING.remove(address);
            Runnable waiting = BOND_TIMEOUT.remove(address);
            if (waiting != null) {
                MAIN.removeCallbacks(waiting);
            }
            AppLog.d(TAG, "配对结束 " + address + " state=" + state);
            connect(app, address, false);
        }
    }

    private static void connect(Context application, String address, boolean auto) {
        if (address.isEmpty() || OPEN.containsKey(address) || PENDING.contains(address)
                || PAUSED.contains(address)) {
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
        phase(address, PHASE_CONNECTING);
        state(address, false);
        BluetoothGattCallback callback = callback(address);
        BluetoothGatt gatt;
        try {
            gatt = device.connectGatt(application, auto, callback, BluetoothDevice.TRANSPORT_LE,
                    BluetoothDevice.PHY_LE_1M, GATT);
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
                    failOrRetry(address, FAIL_TIMEOUT, 0);
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
                        phase(address, PHASE_DISCOVERING);
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
                            if (status != BluetoothGatt.GATT_SUCCESS && status != 0) {
                                failOrRetry(address, FAIL_GATT, status);
                            }
                        }
                    }
                });
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                MAIN.post(() -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        failOrRetry(address, FAIL_GATT, status);
                        return;
                    }
                    List<BluetoothGattCharacteristic> notifies = findNotifies(gatt);
                    if (notifies.isEmpty()) {
                        logServices(gatt);
                        close(address);
                        fail(address, FAIL_NO_SERVICE, 0);
                        return;
                    }
                    SUBSCRIBE.put(address, new ArrayDeque<>(notifies));
                    PENDING.remove(address);
                    ready(address);
                    writeNext(gatt, address);
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
                MAIN.post(() -> packet(address, value == null ? characteristic.getValue() : value));
            }

            @Override
            public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
                MAIN.post(() -> writeNext(gatt, address));
            }
        };
    }

    private static List<BluetoothGattCharacteristic> findNotifies(BluetoothGatt gatt) {
        List<BluetoothGattCharacteristic> preferred = new ArrayList<>();
        List<BluetoothGattCharacteristic> rest = new ArrayList<>();
        List<BluetoothGattService> services = gatt.getServices();
        if (services == null) {
            return preferred;
        }
        for (BluetoothGattService service : services) {
            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                int props = characteristic.getProperties();
                boolean canListen = (props & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                        || (props & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
                if (!canListen) {
                    continue;
                }
                if (StickerMatch.isNotify(characteristic.getUuid())) {
                    preferred.add(characteristic);
                } else {
                    rest.add(characteristic);
                }
            }
        }
        preferred.addAll(rest);
        return preferred;
    }

    private static void writeNext(BluetoothGatt gatt, String address) {
        ArrayDeque<BluetoothGattCharacteristic> queue = SUBSCRIBE.get(address);
        if (queue == null) {
            return;
        }
        while (true) {
            BluetoothGattCharacteristic next = queue.poll();
            if (next == null) {
                SUBSCRIBE.remove(address);
                return;
            }
            if (subscribe(gatt, next)) {
                return;
            }
        }
    }

    /** @return true when a descriptor write is in flight */
    private static boolean subscribe(BluetoothGatt gatt, BluetoothGattCharacteristic notify) {
        try {
            gatt.setCharacteristicNotification(notify, true);
            BluetoothGattDescriptor cccd = notify.getDescriptor(StickerMatch.CCCD);
            if (cccd == null) {
                log("notify without cccd");
                return false;
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
            return true;
        } catch (SecurityException e) {
            log("notify denied");
            return false;
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
        for (Watch watch : new ArrayList<>(WATCHES)) {
            watch.onPacket(address, raw);
        }
        if (capture || app == null || !linkWanted(app)) {
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
            if (app == null || OPEN.containsKey(address) || PENDING.contains(address)
                    || PAUSED.contains(address)) {
                return;
            }
            if (!shouldHold(address)) {
                return;
            }
            connect(app, address, false);
        }, RECONNECT_MS);
    }

    private static boolean shouldHold(String address) {
        if (app == null || UserExit.isExited(app)) {
            return false;
        }
        if (!linkWanted(app) && WATCHES.isEmpty()) {
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
        BluetoothGatt dying = current != null ? current : gatt;
        if (dying != null) {
            try {
                dying.close();
            } catch (SecurityException ignored) {
                // already gone
            }
        }
        state(address, false);
    }

    private static void close(String address) {
        cancelBond(address);
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
        PAUSED.clear();
        queuedAddress = null;
        holding = 0;
        for (String address : new ArrayList<>(OPEN.keySet())) {
            close(address);
        }
        PENDING.clear();
    }

    /** Drop every open link except the one about to bond. */
    private static boolean pauseOthers(String address) {
        boolean paused = false;
        for (String other : new ArrayList<>(OPEN.keySet())) {
            if (other.equals(address)) {
                continue;
            }
            PAUSED.add(other);
            close(other);
            paused = true;
        }
        if (paused) {
            AppLog.d(TAG, "先断开其它智能贴再配对 " + address);
        }
        return paused;
    }

    private static void resumePaused() {
        if (app == null || holding > 0 || !BONDING.isEmpty()) {
            return;
        }
        for (String address : PENDING) {
            if (!PAUSED.contains(address)) {
                return;
            }
        }
        List<String> again = new ArrayList<>(PAUSED);
        PAUSED.clear();
        for (String address : again) {
            if (!OPEN.containsKey(address) && !PENDING.contains(address)) {
                connect(app, address, false);
            }
        }
    }

    private static void afterAttempt() {
        if (queuedAddress != null && holding == 0 && BONDING.isEmpty()) {
            String next = queuedAddress;
            queuedAddress = null;
            if (app != null) {
                probe(app, next);
            }
            return;
        }
        resumePaused();
    }

    /** Public bond first. The LE-only call is the fallback when that returns false. */
    private static boolean startBond(BluetoothDevice device) {
        try {
            if (device.createBond()) {
                return true;
            }
        } catch (SecurityException e) {
            return false;
        }
        Object result = invoke(device, "createBond", new Class<?>[]{int.class},
                new Object[]{BluetoothDevice.TRANSPORT_LE});
        return result instanceof Boolean && (Boolean) result;
    }

    private static Object invoke(BluetoothDevice device, String name, Class<?>[] types, Object[] args) {
        try {
            java.lang.reflect.Method method = BluetoothDevice.class.getMethod(name, types);
            return method.invoke(device, args);
        } catch (Exception ignored) {
            // not public on this build
        }
        try {
            java.lang.reflect.Method method = BluetoothDevice.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method.invoke(device, args);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Switch on, or a sticker shortcut already saved. */
    private static boolean linkWanted(Context context) {
        AppConfig config = new AppConfig(context);
        if (config.isStickerEnabled()) {
            return true;
        }
        for (Shortcut item : ShortcutBook.parse(config.getButtonShortcuts())) {
            if (item != null && StickerFrame.isSticker(item.deviceName)) {
                return true;
            }
        }
        return false;
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
        for (Watch watch : new ArrayList<>(WATCHES)) {
            watch.onState(address, connected);
        }
    }

    private static void ready(String address) {
        TRIES.remove(address);
        for (Watch watch : new ArrayList<>(WATCHES)) {
            watch.onReady(address);
        }
        MAIN.postDelayed(StickerHub::afterAttempt, 1200);
    }

    private static void phase(String address, int which) {
        for (Watch watch : new ArrayList<>(WATCHES)) {
            watch.onPhase(address, which);
        }
    }

    private static void failOrRetry(String address, int reason, int status) {
        int tries = TRIES.containsKey(address) ? TRIES.get(address) : 0;
        if (tries < 1 && app != null && (reason == FAIL_TIMEOUT || status == 133 || status == 8)) {
            TRIES.put(address, tries + 1);
            AppLog.w(TAG, "再连一次 " + address + " status=" + status);
            phase(address, PHASE_CONNECTING);
            MAIN.postDelayed(() -> {
                if (app != null) {
                    connect(app, address, false);
                }
            }, 700);
            return;
        }
        fail(address, reason, status);
    }

    private static void cancelBond(String address) {
        BONDING.remove(address);
        Runnable waiting = BOND_TIMEOUT.remove(address);
        if (waiting != null) {
            MAIN.removeCallbacks(waiting);
        }
    }

    private static void fail(String address, int reason, int status) {
        AppLog.w(TAG, "智能贴失败 " + address + " reason=" + reason + " status=" + status);
        log("fail " + reason + " status=" + status);
        for (Watch watch : new ArrayList<>(WATCHES)) {
            watch.onFail(address, reason, status);
        }
        afterAttempt();
    }

    private static void log(String line) {
        AppLog.d(TAG, line);
        for (Watch watch : new ArrayList<>(WATCHES)) {
            watch.onLog(line);
        }
    }
}
