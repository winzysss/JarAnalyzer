package com.jaranalyzer;

import java.awt.event.InputEvent;

public final class Keymap {
    public static int ctrlDownModifier() {
        return SystemInfo.IS_MAC ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK;
    }
}
