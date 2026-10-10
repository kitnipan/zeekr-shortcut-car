package com.kooo.evcam.recording;

import com.kooo.evcam.storage.LockWindow;

/**
 * Where the 10 seconds before a flash and the 10 seconds after sit inside one
 * segment file. Times are microseconds from the start of that file.
 */
public final class InstantSpan {

    public final long startUs;
    public final long endUs;

    InstantSpan(long startUs, long endUs) {
        this.startUs = startUs;
        this.endUs = endUs;
    }

    /**
     * Overlap of {@code [windowStartMs, windowEndMs]} with a file that begins at
     * {@code fileStartMs}. Null when the file starts at or after the window ends,
     * or the window ends at or before the file starts.
     */
    public static InstantSpan inFile(long fileStartMs, long windowStartMs, long windowEndMs) {
        if (fileStartMs < 0 || windowEndMs <= fileStartMs || windowEndMs <= windowStartMs) {
            return null;
        }
        long startMs = Math.max(0L, windowStartMs - fileStartMs);
        long endMs = windowEndMs - fileStartMs;
        if (endMs <= startMs) {
            return null;
        }
        return new InstantSpan(startMs * 1000L, endMs * 1000L);
    }

    /**
     * Video samples a flash excerpt may contain. A cut whose timestamps never
     * reach the end of the window stops here instead of copying the whole segment.
     */
    public static int videoSampleBudget(int frameRate) {
        int fps = frameRate > 0 ? frameRate : 20;
        long ms = LockWindow.BEFORE_MS + LockWindow.AFTER_MS + 2_000L;
        return (int) ((ms * fps) / 1000L);
    }
}
