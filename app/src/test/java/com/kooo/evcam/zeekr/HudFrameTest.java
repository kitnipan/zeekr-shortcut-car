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
    public void frameStaysInsideAndIsSixteenByNine() {
        int[] frame = HudFrame.place(WIDTH, HEIGHT, true);
        assertTrue(frame[1] >= 0);
        assertTrue(frame[1] + frame[3] <= HEIGHT);
        assertEquals(16f / 9f, (float) frame[2] / frame[3], 0.02f);
    }
}
