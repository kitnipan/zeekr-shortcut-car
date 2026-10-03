package com.kooo.evcam.input;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

/**
 * 借 Shizuku（以 shell 身份跑）执行一次 {@code pm grant ... WRITE_SECURE_SETTINGS}，
 * 之后 {@link AccessibilityGate} 就能自己把无障碍服务打开。
 */
public final class ShizukuGrant {
    private static final String TAG = "ShizukuGrant";
    private static final int REQUEST_CODE = 7311;

    private ShizukuGrant() {
    }

    /** 跑完（不论成败）在主线程回调；toast 已经提示过用户。 */
    public static void run(Activity activity, Runnable done) {
        if (AccessibilityGate.canWriteSecure(activity)) {
            finish(activity, AccessibilityGate.selfEnable(activity), null, done);
            return;
        }
        if (!running()) {
            Toast.makeText(activity, R.string.shizuku_not_running, Toast.LENGTH_LONG).show();
            done.run();
            return;
        }
        if (Shizuku.isPreV11()) {
            Toast.makeText(activity, R.string.shizuku_too_old, Toast.LENGTH_LONG).show();
            done.run();
            return;
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            grant(activity, done);
            return;
        }
        Shizuku.addRequestPermissionResultListener(new Shizuku.OnRequestPermissionResultListener() {
            @Override
            public void onRequestPermissionResult(int requestCode, int grantResult) {
                if (requestCode != REQUEST_CODE) {
                    return;
                }
                Shizuku.removeRequestPermissionResultListener(this);
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    grant(activity, done);
                } else {
                    Toast.makeText(activity, R.string.shizuku_denied, Toast.LENGTH_LONG).show();
                    done.run();
                }
            }
        });
        Shizuku.requestPermission(REQUEST_CODE);
    }

    private static boolean running() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    private static void grant(Activity activity, Runnable done) {
        String pkg = activity.getPackageName();
        new Thread(() -> {
            String error = null;
            try {
                // Shizuku 13 把 newProcess 收成了私有，反射调用
                Method newProcess = Shizuku.class.getDeclaredMethod(
                        "newProcess", String[].class, String[].class, String.class);
                newProcess.setAccessible(true);
                Process process = (Process) newProcess.invoke(null, new String[]{
                        "pm", "grant", pkg, android.Manifest.permission.WRITE_SECURE_SETTINGS}, null, null);
                String output = read(process);
                int code = process.waitFor();
                if (code != 0) {
                    error = output.isEmpty() ? "exit " + code : output;
                }
            } catch (Throwable t) {
                error = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            String failure = error;
            new Handler(Looper.getMainLooper()).post(() -> {
                boolean ok = failure == null && AccessibilityGate.selfEnable(activity);
                finish(activity, ok, failure, done);
            });
        }, "shizuku-grant").start();
    }

    private static String read(Process process) throws java.io.IOException {
        StringBuilder out = new StringBuilder();
        try (BufferedReader err = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
            String line;
            while ((line = err.readLine()) != null) {
                out.append(line).append('\n');
            }
        }
        return out.toString().trim();
    }

    private static void finish(Activity activity, boolean ok, String error, Runnable done) {
        if (ok) {
            AppLog.i(TAG, "已通过 Shizuku 授权并打开无障碍服务");
            Toast.makeText(activity, R.string.shizuku_done, Toast.LENGTH_LONG).show();
        } else {
            AppLog.w(TAG, "Shizuku 授权失败: " + error);
            Toast.makeText(activity, activity.getString(R.string.shizuku_failed,
                    error == null ? "" : error), Toast.LENGTH_LONG).show();
        }
        done.run();
    }
}
