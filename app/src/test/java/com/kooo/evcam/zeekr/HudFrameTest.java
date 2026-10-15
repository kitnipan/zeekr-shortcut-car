package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HudFrameTest {

    private static final int WIDTH = 2560;
    private static final int HEIGHT = 536;

    @Test
    public void leftSignalSitsOnTheLeftHalf() {
        int[] frame = HudFrame.place(WIDTH, HEIGHT, true);
        assertTrue(frame[0] >= 0);
        assertTrue(frame[0] + frame[2] <= WIDTH / 2);
    }

    @Test
    public void rightSignalSitsOnTheRightHalf() {
        int[] frame = HudFrame.place(WIDTH, HEIGHT, false);
        assertTrue(frame[0] >= WIDTH / 2);
        assertTrue(frame[0] + frame[2] <= WIDTH);
    }

    @Test
    public void frameStaysInsideAndIsNarrowerThanSixteenByNine() {
        int[] frame = HudFrame.place(WIDTH, HEIGHT, true);
        assertTrue(frame[1] >= 0);
        assertTrue(frame[1] + frame[3] <= HEIGHT);
        assertTrue(frame[2] < frame[3] * 16 / 9);
    }

    @Test
    public void measuredHudSitsOnTheOuterEdgeAndIsNarrow() {
        int[] left = HudFrame.place(1440, 480, true);
        int[] right = HudFrame.place(1440, 480, false);
        assertEquals(8, left[0]);
        assertEquals(1440 - 8, right[0] + right[2]);
        assertEquals(left[3], left[2]);
        assertTrue(left[0] + left[2] < 720);
        assertTrue(right[0] > 720);
    }
}
