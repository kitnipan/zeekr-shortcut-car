package com.kooo.evcam.recording;

/**
 * Where the 10 seconds before a press and the 10 seconds after sit inside one
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
}
