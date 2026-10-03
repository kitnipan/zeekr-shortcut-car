package com.kooo.evcam.remote;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RemoteLiveTest {

    @Test
    public void watchSourceMapsToSlot() {
        assertEquals(RemoteLive.DRIVE, RemoteLive.slotFor("drive"));
        assertEquals(RemoteLive.DRIVE, RemoteLive.slotFor(null));
        assertEquals(RemoteLive.DRIVE, RemoteLive.slotFor("nope"));
        assertEquals(RemoteLive.CH1, RemoteLive.slotFor("ch1"));
        assertEquals(RemoteLive.CH4, RemoteLive.slotFor("CH4"));
        assertEquals(RemoteLive.DRIVER, RemoteLive.slotFor("driver"));
        assertEquals(RemoteLive.BACKSEAT, RemoteLive.slotFor("backseat"));
    }

    @Test
    public void jpegForReturnsPutBytes() {
        RemoteLive live = new RemoteLive();
        byte[] jpeg = new byte[]{1, 2, 3};
        live.put(RemoteLive.CH2, jpeg, null);
        assertEquals(jpeg, live.jpegFor("ch2"));
        assertEquals(jpeg, live.jpegFor("CH2"));
    }
}
