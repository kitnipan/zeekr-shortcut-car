package com.kooo.evcam.input;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the radio may do after a sticker link drops.
 * One connect. A second connectGatt on this head unit closes the live link.
 */
public final class StickerRecover {

    public static final int WAIT = 0;
    public static final int CONNECT = 1;

    /** After a failed attempt, wait, then connect again so a press can finish it. */
    public static final long AGAIN_MS = 500L;
    /** After an unexpected drop, connect while the sticker is still advertising. */
    public static final long RECONNECT_MS = 400L;

    private StickerRecover() {
    }

    /**
     * @param scanHold  sticker screen owns the radio
     * @param otherLive some other sticker is already up
     * @param busy      this sticker already has a gatt open, pending, or up
     */
    public static int allowConnect(boolean scanHold, boolean otherLive, boolean busy) {
        if (scanHold || otherLive || busy) {
            return WAIT;
        }
        return CONNECT;
    }

    /**
     * Follow-ups for one disconnect callback. Empty, or a single {@link #CONNECT}.
     *
     * @param intentional close() asked for this disconnect
     * @param wasUp       STATE_CONNECTED had already arrived
     * @param status      GATT status on the disconnect
     */
    public static List<Integer> onDisconnect(boolean intentional, boolean wasUp, int status,
                                              boolean scanHold, boolean otherLive) {
        if (intentional || (!wasUp && status == 0)) {
            return Collections.emptyList();
        }
        if (allowConnect(scanHold, otherLive, false) != CONNECT) {
            return Collections.emptyList();
        }
        List<Integer> actions = new ArrayList<>(1);
        actions.add(CONNECT);
        return actions;
    }
}
