package com.kooo.evcam.remote;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Car side of 7xDash remote watch.
 * Dials {@code /ws/car}, shows a QR from the code the hub sends, and pushes a JPEG
 * only while a phone has an active watch.
 */
public final class CarLink {

    public static final String HTTP = "https://7x-dash.vercel.app";
    public static final String WS = "wss://7xdash-production.up.railway.app";

    public interface Ui {
        void onLink(Snapshot snap);
    }

    public static final class Snapshot {
        public final String state;
        public final String code;
        public final String url;
        public final String email;
        public final boolean watching;
        public final String source;

        Snapshot(String state, String code, String url, String email, boolean watching, String source) {
            this.state = state;
            this.code = code;
            this.url = url;
            this.email = email;
            this.watching = watching;
            this.source = source;
        }
    }

    private final Context context;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService jpegExec = Executors.newSingleThreadExecutor();
    private final AtomicBoolean watching = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean reconnectPosted = new AtomicBoolean(false);
    private final AtomicInteger generation = new AtomicInteger(0);

    private WebSocket socket;
    private long lastPushMs;
    private String email = "";
    private String source = "drive";
    private String lastCode = "";
    private Ui ui;

    private final Runnable reconnect = new Runnable() {
        @Override
        public void run() {
            reconnectPosted.set(false);
            if (running.get()) {
                dial();
            }
        }
    };

    public CarLink(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setUi(Ui ui) {
        this.ui = ui;
    }

    public void start() {
        running.set(true);
        dial();
    }

    public void stop() {
        running.set(false);
        generation.incrementAndGet();
        handler.removeCallbacks(reconnect);
        reconnectPosted.set(false);
        watching.set(false);
        WebSocket ws = socket;
        socket = null;
        if (ws != null) {
            ws.close(1000, "stop");
        }
        jpegExec.shutdown();
    }

    public boolean isWatching() {
        return watching.get();
    }

    public void pushPreview(Bitmap frame) {
        if (!watching.get() || frame == null) {
            if (frame != null) {
                frame.recycle();
            }
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastPushMs < 350) {
            frame.recycle();
            return;
        }
        lastPushMs = now;
        if (jpegExec.isShutdown()) {
            frame.recycle();
            return;
        }
        jpegExec.execute(() -> {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
                if (!frame.compress(Bitmap.CompressFormat.JPEG, 55, out)) {
                    return;
                }
                WebSocket ws = socket;
                if (ws != null && watching.get()) {
                    ws.send(ByteString.of(out.toByteArray()));
                }
            } finally {
                frame.recycle();
            }
        });
    }

    private void dial() {
        int gen = generation.incrementAndGet();
        WebSocket old = socket;
        if (old != null) {
            old.cancel();
        }
        publish("CONNECTING", "", email);
        Request req = new Request.Builder().url(WS + "/ws/car").build();
        socket = client.newWebSocket(req, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                if (gen != generation.get()) {
                    return;
                }
                try {
                    JSONObject hello = new JSONObject();
                    hello.put("t", "hello");
                    hello.put("device", deviceId());
                    webSocket.send(hello.toString());
                } catch (Exception ignored) {
                }
                publish("ONLINE", "", email);
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                if (gen != generation.get()) {
                    return;
                }
                try {
                    JSONObject j = new JSONObject(text);
                    String type = j.optString("t");
                    if ("hello".equals(type)) {
                        email = j.optString("email");
                        watching.set(j.optBoolean("watch", false));
                        source = norm(j.optString("source", source));
                        if (watching.get()) {
                            lastPushMs = 0L;
                        }
                        publish("ONLINE", j.optString("code"), email);
                    } else if ("linked".equals(type)) {
                        email = j.optString("email", email);
                        publish("LINKED", "", email);
                    } else if ("unlinked".equals(type)) {
                        email = "";
                        watching.set(false);
                        publish("ONLINE", j.optString("code"), "");
                    } else if ("watch".equals(type)) {
                        source = norm(j.optString("source", "drive"));
                        watching.set(true);
                        lastPushMs = 0L;
                        publish("ONLINE", "", email);
                    } else if ("idle".equals(type)) {
                        watching.set(false);
                        publish("ONLINE", "", email);
                    }
                } catch (Exception ignored) {
                }
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                if (gen != generation.get()) {
                    return;
                }
                watching.set(false);
                publish("OFFLINE " + code, "", email);
                scheduleReconnect();
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                if (gen != generation.get()) {
                    return;
                }
                watching.set(false);
                String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                publish("FAIL " + msg, "", email);
                scheduleReconnect();
            }
        });
    }

    private void publish(String state, String code, String mail) {
        if (code != null && !code.trim().isEmpty()) {
            lastCode = code.trim().toUpperCase();
        }
        String url = lastCode.isEmpty() ? "" : HTTP + "/link?c=" + lastCode;
        Snapshot snap = new Snapshot(state, lastCode, url, mail == null ? "" : mail, watching.get(), source);
        Ui target = ui;
        if (target == null) {
            return;
        }
        handler.post(() -> {
            if (ui != null) {
                ui.onLink(snap);
            }
        });
    }

    private void scheduleReconnect() {
        if (!running.get()) {
            return;
        }
        if (!reconnectPosted.compareAndSet(false, true)) {
            return;
        }
        handler.postDelayed(reconnect, 3000);
    }

    private String deviceId() {
        String aid = "";
        try {
            aid = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Exception ignored) {
        }
        String model = (Build.MANUFACTURER + "_" + Build.MODEL).replace(' ', '_');
        if (aid == null || aid.isEmpty()) {
            return model;
        }
        return model + "_" + aid;
    }

    private static String norm(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase();
        if ("drive".equals(s) || "driver".equals(s) || "backseat".equals(s) || s.startsWith("ch")) {
            return s;
        }
        return "drive";
    }
}
