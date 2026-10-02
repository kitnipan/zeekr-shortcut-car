package com.kooo.evcam.input;

/** One external button bound to one action. */
public final class Shortcut {

    public final int keyCode;
    public final int scanCode;
    public final String deviceName;
    public final String action;

    public Shortcut(int keyCode, int scanCode, String deviceName, String action) {
        this.keyCode = keyCode;
        this.scanCode = scanCode;
        this.deviceName = deviceName == null ? "" : deviceName;
        this.action = action == null ? "" : action;
    }
}
