package com.kooo.evcam.recording;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;
import com.kooo.evcam.StorageHelper;
import com.kooo.evcam.share.DriveExport;
import com.kooo.evcam.share.UsbExport;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * After a save-moment, upload finished protected files to Drive in the background.
 * Straighten runs when that setting is on (same rule as the share button).
 * Skips upload when Drive is not signed in.
 */
public final class SaveMomentUpload {

    private static final String TAG = "SaveMomentUpload";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);
    private static final Set<String> PENDING = new LinkedHashSet<>();

    private SaveMomentUpload() {
    }

    public static void enqueueFinished(Context context, Collection<String> stamps) {
        if (context == null || stamps == null || stamps.isEmpty()) {
            return;
        }
        Context app = context.getApplicationContext();
        AppConfig config = new AppConfig(app);
        if (!config.hasDriveClient() || !config.hasDriveRefreshToken()) {
            AppLog.d(TAG, "没登录 Drive，跳过上传");
            return;
        }
        synchronized (PENDING) {
            PENDING.addAll(stamps);
        }
        kick(app);
    }

    private static void kick(Context app) {
        if (!BUSY.compareAndSet(false, true)) {
            return;
        }
        new Thread(() -> {
            try {
                drain(app);
            } finally {
                BUSY.set(false);
                boolean more;
                synchronized (PENDING) {
                    more = !PENDING.isEmpty();
                }
                if (more) {
                    MAIN.postDelayed(() -> kick(app), 2000L);
                }
            }
        }, "save-moment-upload").start();
    }

    private static void drain(Context app) {
        List<String> batch;
        synchronized (PENDING) {
            batch = new ArrayList<>(PENDING);
            PENDING.clear();
        }
        File videoDir = StorageHelper.getVideoDir(app);
        File[] all = videoDir == null ? null : videoDir.listFiles();
        List<File> files = new ArrayList<>();
        Set<String> unfinished = new LinkedHashSet<>();
        for (String stamp : batch) {
            boolean sawPlayable = false;
            boolean sawAny = false;
            if (all != null) {
                for (File file : all) {
                    if (!file.isFile() || !stamp.equals(SavedClips.stampOf(file.getName()))) {
                        continue;
                    }
                    sawAny = true;
                    if (UsbExport.playable(file)) {
                        files.add(file);
                        sawPlayable = true;
                    }
                }
            }
            // Still writing, or moov not flushed yet: try again later
            if (sawAny && !sawPlayable) {
                unfinished.add(stamp);
            }
        }
        if (!unfinished.isEmpty()) {
            synchronized (PENDING) {
                PENDING.addAll(unfinished);
            }
        }
        if (files.isEmpty()) {
            AppLog.d(TAG, "还没有可传的成品文件: " + batch);
            return;
        }
        int sent = DriveExport.uploadQuiet(app, files);
        AppLog.i(TAG, "保存瞬间上传完成 " + sent + "/" + files.size());
        if (sent > 0) {
            final int count = sent;
            MAIN.post(() -> Toast.makeText(app,
                    app.getString(R.string.save_moment_uploaded, count),
                    Toast.LENGTH_SHORT).show());
        }
    }
}
