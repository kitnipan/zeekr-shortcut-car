package com.kooo.evcam.input;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.List;

/** A dropped sticker gets one connect. A second connect on the same drop closes the first. */
public class StickerRecoverTest {

    @Test
    public void liveDropSchedulesOneConnect() {
        List<Integer> actions = StickerRecover.onDisconnect(false, true, 8, false, false);
        assertEquals(1, actions.size());
        assertEquals(StickerRecover.CONNECT, (int) actions.get(0));
    }

    @Test
    public void dropWaitsWhileScanningOrASiblingIsUp() {
        assertEquals(0, StickerRecover.onDisconnect(false, true, 8, true, false).size());
        assertEquals(0, StickerRecover.onDisconnect(false, true, 8, false, true).size());
    }

    @Test
    public void intentionalCloseAndPreLinkNoiseDoNotReconnect() {
        assertEquals(0, StickerRecover.onDisconnect(true, true, 8, false, false).size());
        assertEquals(0, StickerRecover.onDisconnect(false, false, 0, false, false).size());
    }

    @Test
    public void connectIsAllowedOnlyWhenThisStickerIsDownAndTheRadioIsFree() {
        assertEquals(StickerRecover.CONNECT, StickerRecover.allowConnect(false, false, false));
        assertEquals(StickerRecover.WAIT, StickerRecover.allowConnect(true, false, false));
        assertEquals(StickerRecover.WAIT, StickerRecover.allowConnect(false, true, false));
        assertEquals(StickerRecover.WAIT, StickerRecover.allowConnect(false, false, true));
    }
}
