package com.kooo.evcam.share;

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
}
