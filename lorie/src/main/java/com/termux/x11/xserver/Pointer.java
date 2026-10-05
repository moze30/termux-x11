package com.termux.x11.xserver;

/**
 * 从 winlator 移植时精简：termux-x11 侧不需要完整的指针状态机，
 * 只保留虚拟控件用到的 Button 枚举与序号映射。
 */
public class Pointer {
    public enum Button {
        BUTTON_LEFT, BUTTON_MIDDLE, BUTTON_RIGHT,
        BUTTON_SCROLL_UP, BUTTON_SCROLL_DOWN,
        BUTTON_SCROLL_CLICK_LEFT, BUTTON_SCROLL_CLICK_RIGHT;

        public byte code() {
            return (byte)(ordinal() + 1);
        }

        public int flag() {
            return 1 << (code() + MAX_BUTTONS);
        }
    }

    public static final byte MAX_BUTTONS = 7;

    public static Button fromCode(int btnCode) {
        Button[] values = Button.values();
        int idx = btnCode - 1;
        return idx >= 0 && idx < values.length ? values[idx] : null;
    }
}
