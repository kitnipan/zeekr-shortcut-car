package com.kooo.evcam.recording;

import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.R;
import com.kooo.evcam.ui.CamDialogs;
import com.kooo.evcam.zeekr.RecordingTimeline;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Plays clips in the instant captures folder, and deletes one or all of them. */
public class InstantCapturesActivity extends AppCompatActivity {

    private LinearLayout rows;
    private TextView empty;
    private View deleteAll;
    private VideoView player;
    private File playing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_instant_captures);
        rows = findViewById(R.id.instant_rows);
        empty = findViewById(R.id.instant_empty);
        deleteAll = findViewById(R.id.instant_delete_all);
        player = findViewById(R.id.instant_player);
        findViewById(R.id.instant_close).setOnClickListener(v -> finish());
        deleteAll.setOnClickListener(v -> confirmDeleteAll());
        player.setOnErrorListener((mp, what, extra) -> {
            Toast.makeText(this, R.string.instant_captures_play_failed, Toast.LENGTH_SHORT).show();
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        show();
    }

    @Override
    protected void onPause() {
        player.stopPlayback();
        super.onPause();
    }

    private void show() {
        File dir = InstantClips.folder(this);
        List<File> clips = InstantClips.list(dir);
        rows.removeAllViews();
        boolean none = clips.isEmpty();
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        empty.setText(dir == null ? R.string.instant_captures_no_usb : R.string.instant_captures_empty);
        deleteAll.setEnabled(!none);
        if (playing != null && (dir == null || !InstantClips.owned(dir, playing))) {
            player.stopPlayback();
            player.setVisibility(View.GONE);
            playing = null;
        }
        for (File clip : clips) {
            rows.addView(row(dir, clip));
        }
    }

    private View row(File dir, File clip) {
        View line = LayoutInflater.from(this).inflate(R.layout.item_instant_clip, rows, false);
        TextView label = line.findViewById(R.id.instant_label);
        label.setText(labelOf(clip));
        label.setOnClickListener(v -> play(clip));
        line.findViewById(R.id.instant_delete).setOnClickListener(v -> confirmDelete(dir, clip));
        return line;
    }

    private void play(File clip) {
        playing = clip;
        player.setVisibility(View.VISIBLE);
        player.setVideoURI(Uri.fromFile(clip));
        player.start();
    }

    private void confirmDelete(File dir, File clip) {
        CamDialogs.showDestructive(new MaterialAlertDialogBuilder(this, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.instant_captures_delete_title)
                .setMessage(labelOf(clip))
                .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                    if (clip.equals(playing)) {
                        player.stopPlayback();
                        player.setVisibility(View.GONE);
                        playing = null;
                    }
                    InstantClips.delete(dir, clip);
                    show();
                })
                .setNegativeButton(R.string.action_cancel, null));
    }

    private void confirmDeleteAll() {
        File dir = InstantClips.folder(this);
        int count = InstantClips.list(dir).size();
        if (count == 0) {
            return;
        }
        CamDialogs.showDestructive(new MaterialAlertDialogBuilder(this, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.instant_captures_delete_all_title)
                .setMessage(getString(R.string.instant_captures_delete_all_msg, count))
                .setPositiveButton(R.string.instant_captures_delete_all, (dialog, which) -> {
                    player.stopPlayback();
                    player.setVisibility(View.GONE);
                    playing = null;
                    InstantClips.deleteAll(dir);
                    show();
                })
                .setNegativeButton(R.string.action_cancel, null));
    }

    private static String labelOf(File clip) {
        long start = RecordingTimeline.parseStartEpochMs(clip.getName());
        if (start < 0) {
            return clip.getName();
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(start));
    }
}
