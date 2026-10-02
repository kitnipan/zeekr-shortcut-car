package com.kooo.evcam.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class ShortcutBookTest {

    @Test
    public void roundTripKeepsTheDeviceName() {
        Shortcut item = new Shortcut(96, 12, "AB Shutter, 3", "toggle_recording");
        String raw = ShortcutBook.write(Collections.singletonList(item));
        Shortcut back = ShortcutBook.parse(raw).get(0);
        assertEquals(96, back.keyCode);
        assertEquals(12, back.scanCode);
        assertEquals("AB Shutter, 3", back.deviceName);
        assertEquals("toggle_recording", back.action);
    }

    @Test
    public void sameButtonReplacesTheAction() {
        Shortcut first = new Shortcut(96, 0, "Pad", "toggle_recording");
        Shortcut second = new Shortcut(96, 0, "Pad", "toggle_dim");
        java.util.List<Shortcut> items = ShortcutBook.put(Collections.singletonList(first), second);
        assertEquals(1, items.size());
        assertEquals("toggle_dim", items.get(0).action);
    }

    @Test
    public void anotherDeviceKeepsItsOwnRow() {
        Shortcut pad = new Shortcut(96, 0, "Pad", "open_app");
        Shortcut shutter = new Shortcut(96, 0, "Shutter", "toggle_mirror");
        java.util.List<Shortcut> items = ShortcutBook.put(Collections.singletonList(pad), shutter);
        assertEquals(2, items.size());
    }

    @Test
    public void matchUsesKeyCodeAndDevice() {
        Shortcut pad = new Shortcut(96, 0, "Pad", "open_app");
        assertEquals("open_app", ShortcutBook.match(Collections.singletonList(pad), 96, 0, "Pad").action);
        assertNull(ShortcutBook.match(Collections.singletonList(pad), 96, 0, "Other"));
    }

    @Test
    public void unknownKeyMatchesTheScanCode() {
        Shortcut item = new Shortcut(0, 88, "Button", "toggle_dim");
        assertEquals("toggle_dim", ShortcutBook.match(Arrays.asList(item), 0, 88, "Button").action);
        assertNull(ShortcutBook.match(Arrays.asList(item), 0, 1, "Button"));
    }

    @Test
    public void brokenLinesAreSkipped() {
        assertEquals(0, ShortcutBook.parse("nope\n1,2,not_an_action,Pad").size());
    }
}
