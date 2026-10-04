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
import com.kooo.evcam.share.SurroundDefish;
import com.kooo.evcam.share.UsbExport;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * After a save-moment, copy finished files to the USB moments folder when that
 * option is on, and upload them to Drive when signed in. Straighten runs when
 * that setting is on (same rule as the share button).
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
        boolean drive = config.hasDriveClient() && config.hasDriveRefreshToken();
        if (!drive && !config.isSaveMomentUsb()) {
            AppLog.d(TAG, "没登录 Drive，也没开存到 U 盘，跳过");
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
        AppConfig config = new AppConfig(app);
        if (config.isSaveMomentUsb()) {
            int copied = copyToMoments(app, files);
            if (copied > 0) {
                final int count = copied;
                MAIN.post(() -> Toast.makeText(app,
                        app.getString(R.string.save_moment_usb, count),
                        Toast.LENGTH_SHORT).show());
            }
        }
        if (config.hasDriveClient() && config.hasDriveRefreshToken()) {
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

    /** Finished clips into {@code <usb>/moments}. Straighten when that setting is on. */
    private static int copyToMoments(Context app, List<File> files) {
        File root = StorageHelper.getExternalSdCardRoot(app);
        if (root == null) {
            AppLog.w(TAG, "要存到 U 盘，但没有 U 盘");
            MAIN.post(() -> Toast.makeText(app, R.string.save_moment_no_usb, Toast.LENGTH_SHORT).show());
            return 0;
        }
        File destDir = UsbExport.moments(root);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        int copied = 0;
        for (File file : files) {
            File temp = null;
            try {
                File payload = file;
                if (SurroundDefish.wanted(app, file)) {
                    File cache = new File(app.getCacheDir(), "moments");
                    if (!cache.exists() && !cache.mkdirs()) {
                        throw new java.io.IOException("mkdir " + cache.getAbsolutePath());
                    }
                    temp = new File(cache, file.getName());
                    SurroundDefish.write(app, file, temp, cancelled, null);
                    if (!UsbExport.playable(temp)) {
                        throw new java.io.IOException("unfinished " + file.getName());
                    }
                    payload = temp;
                }
                UsbExport.copy(payload, destDir);
                copied++;
            } catch (Exception e) {
                AppLog.e(TAG, "拷到 moments 失败: " + file.getAbsolutePath(), e);
            } finally {
                if (temp != null && temp.exists() && !temp.delete()) {
                    AppLog.w(TAG, "删不掉临时文件: " + temp.getAbsolutePath());
                }
            }
        }
        AppLog.i(TAG, "保存瞬间拷到 U 盘 moments " + copied + "/" + files.size());
        return copied;
    }
}
