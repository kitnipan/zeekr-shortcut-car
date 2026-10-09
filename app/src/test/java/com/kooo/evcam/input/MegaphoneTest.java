package com.kooo.evcam.input;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.media.AudioDeviceInfo;

import org.junit.Test;

public class MegaphoneTest {

    @Test
    public void outerNotifyIsTheBusAddressNotADeviceId() {
        assertTrue(Megaphone.isOuterNotify(AudioDeviceInfo.TYPE_BUS, "BUS12_OUTER_NOTIFY"));
        assertFalse(Megaphone.isOuterNotify(AudioDeviceInfo.TYPE_BUS, "BUS11_OUTER_SPEAKER_PLAYBACK"));
        assertFalse(Megaphone.isOuterNotify(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "BUS12_OUTER_NOTIFY"));
        assertFalse(Megaphone.isOuterNotify(AudioDeviceInfo.TYPE_BUS, null));
    }
}
