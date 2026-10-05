package com.termux.x11.xserverbridge;

/**
 * 虚拟控件与 X 服务器之间的抽象桥接层。
 *
 * <p>移植自 winlator-glibc 的 xserverbridge 设计：控件层只依赖本接口，
 * 因此同一套控件代码可以对接不同的 X 后端。在 termux-x11 中由
 * {@link TX11XServerBridge} 实现，全部走 LorieView 的注入通道。
 */
public interface IXServerBridge {
    int getScreenWidth();

    int getScreenHeight();

    boolean isStretchFullscreen();

    int getPointerX();

    int getPointerY();

    void injectPointerMove(int x, int y);

    void injectPointerMoveDelta(int dx, int dy);

    void injectPointerButtonPress(int btnCode);

    void injectPointerButtonRelease(int btnCode);

    boolean isPointerButtonPressed(int btnCode);

    void injectKeyPress(byte keycode, int keysym);

    void injectKeyRelease(byte keycode);

    /** 手柄状态的输出通道。termux-x11 暂无虚拟手柄设备，默认不做事。 */
    default void sendGamepadState() {}
}
