package com.termux.x11.xserverbridge;

import android.graphics.Point;

import com.termux.x11.LorieView;
import com.termux.x11.input.InputStub;
import com.termux.x11.xserver.Pointer;

/**
 * 面向 termux-x11（Lorie）X 服务器的桥接实现。
 *
 * <p>全部注入走 {@link LorieView} 的 JNI 通道，不依赖 winlator 的 XServer/WinHandler。
 *
 * <p>按键换算说明：XKeycode 枚举值 = Linux keycode + 8（X 服务器 keycode）。
 * LorieView.sendKeyEvent(scanCode, keyCode, down) 的 native 侧会取 scanCode 再加 8，
 * 因此这里传 {@code keycode - 8}，与原 winlator 实现保持一致。
 */
public class TX11XServerBridge implements IXServerBridge {
    private final LorieView lorieView;

    /** 相对移动模式下本地维护的光标位置（供触摸板/UI 参考）。 */
    private int pointerX = 0;
    private int pointerY = 0;
    private boolean stretchFullscreen = false;

    private final boolean[] buttonPressed = new boolean[Pointer.MAX_BUTTONS + 1];

    public TX11XServerBridge(LorieView lorieView) {
        this.lorieView = lorieView;
    }

    public LorieView getLorieView() {
        return lorieView;
    }

    public void setStretchFullscreen(boolean stretch) {
        this.stretchFullscreen = stretch;
    }

    @Override
    public int getScreenWidth() {
        Point p = lorieView.getScreenSize();
        return p != null ? p.x : 0;
    }

    @Override
    public int getScreenHeight() {
        Point p = lorieView.getScreenSize();
        return p != null ? p.y : 0;
    }

    @Override
    public boolean isStretchFullscreen() {
        return stretchFullscreen;
    }

    @Override
    public int getPointerX() {
        return pointerX;
    }

    @Override
    public int getPointerY() {
        return pointerY;
    }

    @Override
    public void injectPointerMove(int x, int y) {
        pointerX = x;
        pointerY = y;
        // 绝对定位：whichButton=0 表示只移动不改按键状态；relative=false 走绝对坐标
        lorieView.sendMouseEvent(x, y, InputStub.BUTTON_UNDEFINED, false, false);
    }

    @Override
    public void injectPointerMoveDelta(int dx, int dy) {
        // 相对移动：dx/dy 作为增量，relative=true
        pointerX += dx;
        pointerY += dy;
        lorieView.sendMouseEvent(dx, dy, InputStub.BUTTON_UNDEFINED, false, true);
    }

    @Override
    public void injectPointerButtonPress(int btnCode) {
        if (btnCode <= 0 || btnCode > Pointer.MAX_BUTTONS)
            return;
        buttonPressed[btnCode] = true;

        if (btnCode == Pointer.Button.BUTTON_SCROLL_UP.code()) {
            lorieView.sendMouseWheelEvent(0, 1);
        } else if (btnCode == Pointer.Button.BUTTON_SCROLL_DOWN.code()) {
            lorieView.sendMouseWheelEvent(0, -1);
        } else {
            // 坐标 (0,0) + relative=true 表示“在当前位置按下”
            lorieView.sendMouseEvent(0, 0, btnCode, true, true);
        }
    }

    @Override
    public void injectPointerButtonRelease(int btnCode) {
        if (btnCode <= 0 || btnCode > Pointer.MAX_BUTTONS)
            return;
        buttonPressed[btnCode] = false;

        if (btnCode == Pointer.Button.BUTTON_SCROLL_UP.code() ||
            btnCode == Pointer.Button.BUTTON_SCROLL_DOWN.code())
            return;

        lorieView.sendMouseEvent(0, 0, btnCode, false, true);
    }

    @Override
    public boolean isPointerButtonPressed(int btnCode) {
        return btnCode > 0 && btnCode <= Pointer.MAX_BUTTONS && buttonPressed[btnCode];
    }

    @Override
    public void injectKeyPress(byte keycode, int keysym) {
        lorieView.sendKeyEvent(keycode - 8, 0, true);
    }

    @Override
    public void injectKeyRelease(byte keycode) {
        lorieView.sendKeyEvent(keycode - 8, 0, false);
    }
}
