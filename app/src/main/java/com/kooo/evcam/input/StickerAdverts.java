package com.kooo.evcam.input;

import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.util.SparseArray;

import com.kooo.evcam.AppConfig;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes sticker adverts seen by the BLE signals screen into the sticker log, so a press on a
 * sleeping sticker can be read back: does its advert carry the press, or only "I am here"?
 * Only saved stickers and sticker-looking names are written; a payload is written when it
 * changes, and an unchanged one at most once a second.
 */
final class StickerAdverts {

    private static final long SAME_MS = 1_000L;
    private static final Map<String, String> LAST_PAYLOAD = new HashMap<>();
    private static final Map<String, Long> LAST_AT = new HashMap<>();

    private StickerAdverts() {
    }

    static void start(Context context) {
        StickerLog.attach(context);
        synchronized (LAST_PAYLOAD) {
            LAST_PAYLOAD.clear();
            LAST_AT.clear();
        }
        StickerLog.line("advert scan start");
    }

    static void stop() {
        StickerLog.line("advert scan stop");
    }

    /** Any thread. */
    static void seen(Context context, ScanResult result) {
        if (context == null || result == null || result.getDevice() == null) {
            return;
        }
        String mac;
        String name;
        try {
            mac = StickerDevices.normalize(result.getDevice().getAddress());
            name = result.getDevice().getName();
        } catch (SecurityException e) {
            return;
        }
        ScanRecord record = result.getScanRecord();
        if (name == null && record != null) {
            name = record.getDeviceName();
        }
        boolean saved = StickerDevices.parse(new AppConfig(context).getStickerDevices()).contains(mac);
        if (!saved && !StickerMatch.nameHint(name)) {
            return;
        }
        byte[] raw = record == null ? null : record.getBytes();
        String payload = StickerFrame.hex(trim(raw));
        long now = SystemClock.elapsedRealtime();
        synchronized (LAST_PAYLOAD) {
            String last = LAST_PAYLOAD.get(mac);
            Long at = LAST_AT.get(mac);
            if (payload.equals(last) && at != null && now - at < SAME_MS) {
                return;
            }
            boolean changed = last != null && !payload.equals(last);
            LAST_PAYLOAD.put(mac, payload);
            LAST_AT.put(mac, now);
            StickerLog.line("advert " + mac + (saved ? "" : " (unsaved)")
                    + " name=" + (name == null ? "-" : name)
                    + " rssi=" + result.getRssi()
                    + " connectable=" + result.isConnectable()
                    + (changed ? " CHANGED" : "")
                    + details(record)
                    + " raw=" + payload);
        }
    }

    private static String details(ScanRecord record) {
        if (record == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        SparseArray<byte[]> makers = record.getManufacturerSpecificData();
        if (makers != null) {
            for (int i = 0; i < makers.size(); i++) {
                out.append(" mfr[").append(String.format("%04x", makers.keyAt(i))).append("]=")
                        .append(StickerFrame.hex(makers.valueAt(i)));
            }
        }
        Map<ParcelUuid, byte[]> data = record.getServiceData();
        if (data != null) {
            for (Map.Entry<ParcelUuid, byte[]> e : data.entrySet()) {
                out.append(" svcdata[").append(e.getKey()).append("]=").append(StickerFrame.hex(e.getValue()));
            }
        }
        List<ParcelUuid> uuids = record.getServiceUuids();
        if (uuids != null && !uuids.isEmpty()) {
            out.append(" uuids=").append(uuids);
        }
        return out.toString();
    }

    /** Advert buffers are padded with zeros to 62 bytes; drop the padding. */
    private static byte[] trim(byte[] raw) {
        if (raw == null) {
            return null;
        }
        int end = raw.length;
        while (end > 0 && raw[end - 1] == 0) {
            end--;
        }
        byte[] out = new byte[end];
        System.arraycopy(raw, 0, out, 0, end);
        return out;
    }
}
