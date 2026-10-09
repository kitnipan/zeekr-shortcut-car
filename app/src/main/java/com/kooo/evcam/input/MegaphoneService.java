package com.kooo.evcam.input;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.kooo.evcam.MainActivity;
import com.kooo.evcam.R;

/** Foreground only while the mic is open, so a held shortcut still works off the main screen. */
public class MegaphoneService extends Service {

    static final String ACTION_START = "com.kooo.evcam.action.MEGAPHONE_START";
    static final String ACTION_STOP = "com.kooo.evcam.action.MEGAPHONE_STOP";

    private static final String CHANNEL_ID = "megaphone";
    private static final int NOTIFICATION_ID = 7102;

    private static volatile boolean up;

    private final Megaphone pump = new Megaphone();

    public static void start(Context context) {
        if (context == null) {
            return;
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(context, R.string.megaphone_need_mic, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(context, MegaphoneService.class);
        intent.setAction(ACTION_START);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void stop(Context context) {
        if (context == null || !up) {
            return;
        }
        Intent intent = new Intent(context, MegaphoneService.class);
        intent.setAction(ACTION_STOP);
        try {
            context.startService(intent);
        } catch (IllegalStateException ignored) {
            // process is background and the pump is already gone
        }
    }

    /** Sticker presses have no key-up, so a second press stops. */
    public static void toggle(Context context) {
        if (up) {
            stop(context);
        } else {
            start(context);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        show();
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            halt();
            return START_NOT_STICKY;
        }
        up = true;
        pump.begin(this, this::halt);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        up = false;
        pump.end();
        super.onDestroy();
    }

    private void halt() {
        up = false;
        pump.end();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void show() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.megaphone_channel), NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            channel.setSound(null, null);
            manager.createNotificationChannel(channel);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification note = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_app)
                .setContentTitle(getString(R.string.megaphone_speaking))
                .setContentText(getString(R.string.megaphone_release))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, note);
        }
    }
}
