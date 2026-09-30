package com.kooo.evcam.share;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;
import com.kooo.evcam.StorageHelper;
import com.kooo.evcam.repair.Mp4Repair;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 「存到U盘」：和 {@link PhoneShare} 发的是同一个文件，只是落点换成 U 盘根目录的 {@code exports}。
 *
 * <p>录像、照片本来就在盘上，但散在录像目录里，还可能被循环删掉。
 * 这里按原文件名再拷一份到 {@code exports}，拔下来就能拿走，不碰正在录的那些文件。</p>
 *
 * <p>先写 {@code 原名.part}，把数据刷到盘上再改名。只改名不刷盘的话，拔 U 盘时
 * 目录里有这个文件，里面却是断的。环视录像在拷之前先拉直。</p>
 */
public final class UsbExport {

    private static final String TAG = "UsbExport";

    /** U 盘根目录下的文件夹名。笔记本打开 U 盘就能看见，不必进 DCIM。 */
    public static final String DIR_NAME = "exports";

    private static final AtomicBoolean BUSY = new AtomicBoolean(false);

    private UsbExport() {
    }

    /** {@code usbRoot/exports}。 */
    public static File folder(File usbRoot) {
        return new File(usbRoot, DIR_NAME);
    }

    /**
     * 把 {@code source} 拷进 {@code destDir}，文件名不变。已在目标位置的同一个文件直接返回。
     *
     * @return 写好的那个文件
     */
    public static File copy(File source, File destDir) throws IOException {
        if (source == null || !source.isFile() || source.length() <= 0) {
            throw new IOException("empty");
        }
        if (destDir == null) {
            throw new IOException("no dest");
        }
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw new IOException("mkdir " + destDir.getAbsolutePath());
        }
        if (!destDir.isDirectory()) {
            throw new IOException("not a directory: " + destDir.getAbsolutePath());
        }

        String name = source.getName();
        File dest = new File(destDir, name);
        if (source.getCanonicalPath().equals(dest.getCanonicalPath())) {
            return dest;
        }

        File part = new File(destDir, name + ".part");
        try {
            transfer(source, part);
            if (part.length() != source.length()) {
                throw new IOException("short copy");
            }
            return publish(part, dest);
        } catch (IOException e) {
            if (part.exists() && !part.delete()) {
                AppLog.w(TAG, "删不掉半成品: " + part.getAbsolutePath());
            }
            throw e;
        }
    }

    /** 刷到介质上，再改成正式名字。改名之后再刷一次目录。 */
    static File publish(File part, File dest) throws IOException {
        sync(part);
        if (dest.exists() && !dest.delete()) {
            throw new IOException("replace " + dest.getAbsolutePath());
        }
        if (!part.renameTo(dest)) {
            throw new IOException("rename " + part.getAbsolutePath());
        }
        sync(dest);
        return dest;
    }

    private static void transfer(File source, File part) throws IOException {
        FileInputStream in = new FileInputStream(source);
        FileOutputStream out = new FileOutputStream(part);
        try {
            FileChannel from = in.getChannel();
            FileChannel to = out.getChannel();
            long pos = 0;
            long size = from.size();
            while (pos < size) {
                long n = from.transferTo(pos, size - pos, to);
                if (n <= 0) {
                    break;
                }
                pos += n;
            }
            to.force(true);
            out.getFD().sync();
        } finally {
            out.close();
            in.close();
        }
    }

    private static void sync(File file) throws IOException {
        FileInputStream in = new FileInputStream(file);
        try {
            in.getFD().sync();
        } finally {
            in.close();
        }
    }

    /** 录像还没封口（没有 moov）就不是一个能播的文件，拷走也是坏的。 */
    public static boolean playable(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        String name = file.getName().toLowerCase(Locale.US);
        if (!name.endsWith(".mp4")) {
            return true;
        }
        return Mp4Repair.scan(file).status == Mp4Repair.Status.HEALTHY;
    }

    /**
     * 把这个文件拷到当前 U 盘的 {@code exports}。没有文件、没有 U 盘，都在这里说清楚。
     *
     * <p>拷贝在后台做。一段录像往往是几百 MB，放主线程上车机立刻卡死。</p>
     */
    public static void save(Activity activity, File file) {
        save(activity, file == null ? null : java.util.Collections.singletonList(file));
    }

    /**
     * 这一刻的每一路都写进 exports。环视先拉直，座舱和其他相机原样拷。
     * 还没封口的录像跳过，不把坏文件留在 U 盘上。
     */
    public static void save(Activity activity, List<File> files) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        if (files == null || files.isEmpty()) {
            toast(activity, activity.getString(R.string.share_phone_no_file));
            return;
        }
        if (BUSY.get()) {
            toast(activity, activity.getString(R.string.usb_export_busy));
            return;
        }
        File root = StorageHelper.getExternalSdCardRoot(activity);
        if (root == null) {
            com.kooo.evcam.ui.CamDialogs.show(new MaterialAlertDialogBuilder(
                    activity, R.style.Theme_Cam_MaterialAlertDialog)
                    .setTitle(R.string.usb_export_no_stick_title)
                    .setMessage(R.string.usb_export_no_stick_msg)
                    .setPositiveButton(R.string.action_got_it, null));
            return;
        }

        BUSY.set(true);
        boolean defish = false;
        for (File file : files) {
            if (file != null && SurroundDefish.isSurroundVideo(file.getName())) {
                defish = true;
                break;
            }
        }
        toast(activity, activity.getString(defish
                ? R.string.usb_export_defish : R.string.usb_export_copying));
        Context app = activity.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        File dir = folder(root);
        new Thread(() -> {
            int saved = 0;
            int unfinished = 0;
            String folder = dir.getAbsolutePath();
            String failure = null;
            for (File file : files) {
                if (file == null || !file.isFile() || file.length() <= 0) {
                    continue;
                }
                try {
                    if (!playable(file)) {
                        unfinished++;
                        AppLog.w(TAG, "还没封口，跳过 " + file.getName());
                        continue;
                    }
                    File out;
                    if (SurroundDefish.isSurroundVideo(file.getName())) {
                        if (!dir.exists() && !dir.mkdirs()) {
                            throw new IOException("mkdir " + dir.getAbsolutePath());
                        }
                        File part = new File(dir, file.getName() + ".part");
                        SurroundDefish.write(app, file, part);
                        if (!playable(part)) {
                            if (part.exists() && !part.delete()) {
                                AppLog.w(TAG, "删不掉没封口的半成品: " + part.getAbsolutePath());
                            }
                            throw new IOException("unfinished " + file.getName());
                        }
                        out = publish(part, new File(dir, file.getName()));
                    } else {
                        out = copy(file, dir);
                    }
                    saved++;
                    folder = out.getParent() == null ? out.getAbsolutePath() : out.getParent();
                    AppLog.i(TAG, "已写入 " + out.getAbsolutePath() + "（" + out.length() + " 字节）");
                } catch (Exception e) {
                    AppLog.e(TAG, "写入 U 盘失败: " + file.getAbsolutePath(), e);
                    failure = String.valueOf(e.getMessage());
                }
            }
            final int savedCount = saved;
            final int unfinishedCount = unfinished;
            final String savedFolder = folder;
            final String error = failure;
            main.post(() -> {
                if (savedCount > 0) {
                    toast(app, app.getString(R.string.usb_export_saved_count, savedCount, savedFolder));
                } else if (error != null) {
                    toast(app, app.getString(R.string.usb_export_failed, error));
                } else if (unfinishedCount == 0) {
                    toast(app, app.getString(R.string.share_phone_no_file));
                }
                if (unfinishedCount > 0) {
                    toast(app, app.getString(R.string.usb_export_unfinished, unfinishedCount));
                }
                BUSY.set(false);
            });
        }, "usb-export").start();
    }

    private static void toast(Context context, String text) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show();
    }
}
