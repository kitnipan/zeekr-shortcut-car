package com.kooo.evcam.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 拉直一次只放一帧。放得比画得快时，结束符发不出去，进度会停在 99%。 */
public class SurroundDefishTest {

    @Test
    public void holdsNextFrameUntilPreviousIsDrawn() {
        assertTrue(SurroundDefish.canReleaseFrame(0, 0));
        assertFalse(SurroundDefish.canReleaseFrame(0, 1));
        assertTrue(SurroundDefish.canReleaseFrame(1, 1));
    }

    @Test
    public void signalsEncoderOnlyAfterDrawnCatchesReleased() {
        assertFalse(SurroundDefish.canSignalEncoder(true, false, 3, 4));
        assertTrue(SurroundDefish.canSignalEncoder(true, false, 4, 4));
        assertFalse(SurroundDefish.canSignalEncoder(false, false, 4, 4));
        assertFalse(SurroundDefish.canSignalEncoder(true, true, 4, 4));
    }

    @Test
    public void forcesEndWhenLastFrameNeverArrives() {
        assertFalse(SurroundDefish.forceSignalEncoder(
                true, false, 3, 4, SurroundDefish.FRAME_STALL_MS - 1));
        assertTrue(SurroundDefish.forceSignalEncoder(
                true, false, 3, 4, SurroundDefish.FRAME_STALL_MS));
        assertFalse(SurroundDefish.forceSignalEncoder(
                true, false, 4, 4, SurroundDefish.FRAME_STALL_MS));
    }

    @Test
    public void eachChannelIsWidescreen() {
        int[] plain = SurroundDefish.exportFrame(2560, 2560);
        assertEquals(2560, plain[0]);
        assertEquals(1440, plain[2]);
        assertEquals(1440, plain[1]);
        assertEquals(0, plain[3]);
        assertEquals(16f / 9f, (plain[0] / 2f) / (plain[2] / 2f), 0.001f);
    }

    @Test
    public void keepsDrivingBarFromTheRecording() {
        int[] frame = SurroundDefish.exportFrame(2560, 2660);
        assertEquals(2560, frame[0]);
        assertEquals(1440, frame[2]);
        assertEquals(1540, frame[1]);
        assertEquals(100, frame[3]);
    }

    @Test
    public void stripKeepsItsOwnShape() {
        int[] frame = SurroundDefish.exportFrame(1280, 5140);
        assertEquals(1280, frame[0]);
        assertEquals(5140, frame[1]);
        assertEquals(5140, frame[2]);
    }

    @Test
    public void readsClipStartFromFileName() {
        long ms = SurroundDefish.clipStartMillis("20250101_120000_surround.mp4");
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.setTimeInMillis(ms);
        assertEquals(2025, calendar.get(java.util.Calendar.YEAR));
        assertEquals(java.util.Calendar.JANUARY, calendar.get(java.util.Calendar.MONTH));
        assertEquals(1, calendar.get(java.util.Calendar.DAY_OF_MONTH));
        assertEquals(12, calendar.get(java.util.Calendar.HOUR_OF_DAY));
        assertEquals(0, calendar.get(java.util.Calendar.MINUTE));
        assertEquals(-1L, SurroundDefish.clipStartMillis("clip.mp4"));
    }
}
