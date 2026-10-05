package com.termux.x11;

import static android.Manifest.permission.WRITE_SECURE_SETTINGS;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Build.VERSION.SDK_INT;
import static android.view.KeyEvent.*;
import static android.view.WindowManager.LayoutParams.*;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AppOpsManager;
import android.app.PictureInPictureParams;
import android.content.ClipData;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Rational;
import android.util.TypedValue;
import android.view.Display;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.PointerIcon;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.math.MathUtils;
import androidx.core.view.ViewCompat;
import androidx.viewpager.widget.ViewPager;

import com.termux.x11.extrakeys.ExtraKeysInfo;
import com.termux.x11.inputcontrols.ControlsProfile;
import com.termux.x11.inputcontrols.InputControlsManager;
import com.termux.x11.widget.InputControlsView;
import com.termux.x11.widget.TouchpadView;
import com.termux.x11.xserverbridge.IXServerBridge;
import com.termux.x11.xserverbridge.TX11XServerBridge;
import com.termux.x11.core.PreloaderDialog;
import com.termux.x11.input.InputEventSender;
import com.termux.x11.input.InputStub;
import com.termux.x11.input.TouchInputHandler;
import com.termux.x11.utils.ImeHeightProvider;
import com.termux.x11.utils.KeyInterceptor;
import com.termux.x11.utils.TermuxX11ExtraKeys;
import com.termux.x11.utils.X11ToolbarViewPager;

import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;

@Keep @SuppressLint("ApplySharedPref")
@SuppressWarnings({"deprecation", "unused"})
public class MainActivity extends AppCompatActivity {
    public static final String ACTION_STOP = "com.termux.x11.ACTION_STOP";
    public static final String ACTION_CUSTOM = "com.termux.x11.ACTION_CUSTOM";
    public static final byte OPEN_FILE_REQUEST_CODE = 2;
    public PreloaderDialog preloaderDialog;

    public static Handler handler = new Handler();
    private final Runnable connectRetry = this::tryConnect;
    FrameLayout frm;
    TouchInputHandler mInputHandler;
    protected ICmdEntryInterface service = null;
    public TermuxX11ExtraKeys mExtraKeys;
    private DisplayManager displayManager;
    private boolean showIMEWhileExternalConnected = true;
    private boolean externalKeyboardConnected = false;
    private View.OnKeyListener mLorieKeyListener;
    private boolean filterOutWinKey = false;
    boolean useTermuxEKBarBehaviour = false;
    private boolean isInPictureInPictureMode = false;

    /* ===== 虚拟控件（移植自 winlator-glibc）===== */
    private InputControlsManager mInputControlsManager;
    private InputControlsView mInputControlsView;
    private TouchpadView mTouchpadView;
    private TX11XServerBridge mXServerBridge;

    /* ===== 侧边栏（实时设置）===== */
    private View mSidePanel;
    private boolean mSidePanelShown = false;
    private boolean mSidePanelSyncing = false;
    private long mLastSidePanelToggle = 0L;
    private Spinner mControlsProfileSpinner;
    private float mGlobalCursorSpeed = 1.0f;
    /** The display the system letterboxed us on instead of rotating, {@code null} until it does. */
    private Rect orientationDeniedAt = null;
    private String screenIdleTimeoutArmedMode = null; // numeric screenIdleTimeout mode the pending idle check reflects, or null if none pending
    private final Runnable screenIdleTimeoutCheck = this::checkScreenIdleTimeout;
    /** Aspect ratios outside of the range the device is configured with are rejected by the system. */
    private static final float MIN_PIP_ASPECT_RATIO = getSystemDimenFloat("config_pictureInPictureMinAspectRatio", 1.f / 2.39f);
    private static final float MAX_PIP_ASPECT_RATIO = getSystemDimenFloat("config_pictureInPictureMaxAspectRatio", 2.39f);

    public Prefs prefs;
    LorieApp app;

    private boolean oldFullscreen = false, oldHideCutout = false;
    private OrientationEventListener orientationListener;

    ViewTreeObserver.OnPreDrawListener mOnPredrawListener = new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            if (!getLorieView().connected())
                return false;

            finishStartupDraw();
            return true;
        }
    };

    private void finishStartupDraw() {
        View content = findViewById(android.R.id.content);
        content.getViewTreeObserver().removeOnPreDrawListener(mOnPredrawListener);
        content.invalidate();
    }

    @SuppressLint("StaticFieldLeak")
    private static MainActivity instance;

    public MainActivity() {
        instance = this;
    }

    public static MainActivity getInstance() {
        return instance;
    }

    /** Unwraps the {@link MainActivity} a view's {@link Context} was inflated with, if any. */
    public static MainActivity findActivity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof MainActivity)
                return (MainActivity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    @Override
    @SuppressLint({"AppCompatMethod", "ObsoleteSdkInt", "ClickableViewAccessibility", "WrongConstant", "UnspecifiedRegisterReceiverFlag"})
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        app = (LorieApp) getApplication();
        prefs = app.getPrefs(this);
        int modeValue = Integer.parseInt(prefs.touchMode.get()) - 1;
        if (modeValue > 2)
            prefs.touchMode.put("1");

        oldFullscreen = prefs.fullscreen.get();
        oldHideCutout = prefs.hideCutout.get();

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.main_activity);
        applyWindowSettings();

        frm = findViewById(R.id.frame);
        findViewById(R.id.preferences_button).setOnClickListener((l) -> startActivity(new Intent(this, LoriePreferences.class) {{ setAction(Intent.ACTION_MAIN); }}));
        findViewById(R.id.help_button).setOnClickListener((l) -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/termux/termux-x11/blob/master/README.md#running-graphical-applications"))));
        findViewById(R.id.exit_button).setOnClickListener((l) -> finish());

        LorieView lorieView = findViewById(R.id.lorieView);
        View lorieParent = (View) lorieView.getParent();

        mInputHandler = new TouchInputHandler(this, new InputEventSender(this, lorieView));
        mLorieKeyListener = (v, k, e) -> {
            InputDevice dev = e.getDevice();
            boolean result = mInputHandler.sendKeyEvent(e);

            // Do not steal dedicated buttons from a full external keyboard.
            if (useTermuxEKBarBehaviour && mExtraKeys != null && (dev == null || dev.isVirtual()))
                mExtraKeys.unsetSpecialKeys();
            return result;
        };

        lorieParent.setOnTouchListener((v, e) -> {
            // Avoid batched MotionEvent objects and reduce potential latency.
            // For reference: https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/advanced-stylus-features#rendering.
            if (e.getAction() == MotionEvent.ACTION_DOWN)
                lorieParent.requestUnbufferedDispatch(e);

            return mInputHandler.handleTouchEvent(lorieParent, lorieView, e);
        });
        lorieParent.setOnHoverListener((v, e) -> mInputHandler.handleTouchEvent(lorieParent, lorieView, e));
        lorieParent.setOnGenericMotionListener((v, e) -> mInputHandler.handleTouchEvent(lorieParent, lorieView, e));
        if (SDK_INT >= VERSION_CODES.O) {
            lorieView.setOnCapturedPointerListener((v, e) -> mInputHandler.handleTouchEvent(lorieView, lorieView, e));
            lorieParent.setOnCapturedPointerListener((v, e) -> mInputHandler.handleTouchEvent(lorieView, lorieView, e));
        }
        lorieView.setOnKeyListener(mLorieKeyListener);

        lorieView.setCallback((screenWidth, screenHeight, inputTransform) ->
                mInputHandler.handleInputTransformChanged(screenWidth, screenHeight, inputTransform));

        displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        orientationListener = new OrientationEventListener(this) {
            @Override public void onOrientationChanged(int orientation) {
                setTerminalToolbarViewLayout();
            }
        };
        frm.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            String savedPos;
            int savedRotation;

            @Override
            public void onGlobalLayout() {
                Display d = frm.getDisplay();
                String pos = prefs.ekbarPosition.get();
                if ((d != null && savedRotation != d.getRotation()) || !Objects.equals(savedPos, pos)) {
                    savedRotation = d == null ? 0 : d.getRotation();
                    savedPos = pos;
                    setTerminalToolbarViewLayout();
                }
            }
        });

        ImeHeightProvider.assistActivity(this);

        if (app.pendingConnection != null) {
            connectToService(app.pendingConnection);
            app.pendingConnection = null;
        }

        if (tryConnect()) {
            final View content = findViewById(android.R.id.content);
            content.getViewTreeObserver().addOnPreDrawListener(mOnPredrawListener);
            handler.postDelayed(this::finishStartupDraw, 500);
        }
        onPreferencesChanged("");

        toggleExtraKeys(false, false);

        initStylusAuxButtons();
        initMouseAuxButtons();
        initVirtualControls();
        initSidePanel();

        if (SDK_INT >= VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PERMISSION_GRANTED
                && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 0);
        }

        findViewById(android.R.id.content).addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> makeSureHelpersAreVisibleAndInScreenBounds());
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(screenIdleTimeoutCheck);
        if (mInputHandler != null)
            mInputHandler.onDestroy();
        if (instance == this)
            instance = null;
        super.onDestroy();
    }

    //Register the needed events to handle stylus as left, middle and right click
    @SuppressLint("ClickableViewAccessibility")
    private void initStylusAuxButtons() {
        final ViewPager pager = getTerminalToolbarViewPager();
        boolean stylusMenuEnabled = prefs.showStylusClickOverride.get() && getLorieView().connected();
        final float menuUnselectedTrasparency = 0.66f;
        final float menuSelectedTrasparency = 1.0f;
        Button left = findViewById(R.id.button_left_click);
        Button right = findViewById(R.id.button_right_click);
        Button middle = findViewById(R.id.button_middle_click);
        Button visibility = findViewById(R.id.button_visibility);
        LinearLayout overlay = findViewById(R.id.mouse_helper_visibility);
        LinearLayout buttons = findViewById(R.id.mouse_helper_secondary_layer);
        overlay.setOnTouchListener((v, e) -> true);
        overlay.setOnHoverListener((v, e) -> true);
        overlay.setOnGenericMotionListener((v, e) -> true);
        if (SDK_INT >= VERSION_CODES.O)
            overlay.setOnCapturedPointerListener((v, e) -> true);
        overlay.setVisibility(stylusMenuEnabled ? View.VISIBLE : View.GONE);
        View.OnClickListener listener = view -> {
            mInputHandler.mStylusInputHelperMode = (view.equals(left) ? 1 : (view.equals(middle) ? 2 : (view.equals(right) ? 4 : 0)));
            left.setAlpha((mInputHandler.mStylusInputHelperMode == 1) ? menuSelectedTrasparency : menuUnselectedTrasparency);
            middle.setAlpha((mInputHandler.mStylusInputHelperMode == 2) ? menuSelectedTrasparency : menuUnselectedTrasparency);
            right.setAlpha((mInputHandler.mStylusInputHelperMode == 4) ? menuSelectedTrasparency : menuUnselectedTrasparency);
            visibility.setAlpha(menuUnselectedTrasparency);
        };

        left.setOnClickListener(listener);
        middle.setOnClickListener(listener);
        right.setOnClickListener(listener);

        visibility.setOnClickListener(view -> {
            if (buttons.getVisibility() == View.VISIBLE) {
                buttons.setVisibility(View.GONE);
                visibility.setAlpha(menuUnselectedTrasparency);
                int m = mInputHandler.mStylusInputHelperMode;
                visibility.setText(m == 1 ? "L" : (m == 2 ? "M" : (m == 3 ? "R" : "U")));
            } else {
                RectF frmRect = getVisibleFrmRect();
                buttons.setVisibility(View.VISIBLE);
                visibility.setAlpha(menuUnselectedTrasparency);
                visibility.setText("X");

                //Calculate screen border making sure btn is fully inside the view
                float maxX = frmRect.right - 4 * left.getWidth();
                float maxY = frmRect.bottom - 4 * left.getHeight();

                //Make sure the Stylus menu is fully inside the screen
                overlay.setX(MathUtils.clamp(overlay.getX(), frmRect.left, maxX));
                overlay.setY(MathUtils.clamp(overlay.getY(), frmRect.top, maxY));

                int m = mInputHandler.mStylusInputHelperMode;
                listener.onClick(m == 1 ? left : (m == 2 ? middle : (m == 3 ? right : left)));
            }
        });
        //Simulated mouse click 1 = left , 2 = middle , 3 = right
        mInputHandler.mStylusInputHelperMode = 1;
        listener.onClick(left);

        visibility.setOnLongClickListener(v -> {
            v.startDragAndDrop(ClipData.newPlainText("", ""), new View.DragShadowBuilder(visibility) {
                public void onDrawShadow(@NonNull Canvas canvas) {}
            }, null, View.DRAG_FLAG_GLOBAL);

            frm.setOnDragListener((v2, event) -> {
                RectF frmRect = getVisibleFrmRect();

                //Calculate screen border making sure btn is fully inside the view
                float minX = frmRect.left, minY = frmRect.top;
                float maxX = frmRect.right;
                float maxY = frmRect.bottom;

                switch (event.getAction()) {
                    case DragEvent.ACTION_DRAG_LOCATION:
                        //Center touch location with btn icon
                        float dX = event.getX() - visibility.getWidth() / 2.0f;
                        float dY = event.getY() + visibility.getHeight() / 2.0f;

                        //Make sure the dragged btn is inside the view with clamp
                        overlay.setX(MathUtils.clamp(dX, frmRect.left, frmRect.right));
                        overlay.setY(MathUtils.clamp(dY, frmRect.top, frmRect.bottom));
                        break;
                    case DragEvent.ACTION_DRAG_ENDED:
                        overlay.setX(MathUtils.clamp(overlay.getX(), frmRect.left, frmRect.right));
                        overlay.setY(MathUtils.clamp(overlay.getY(), frmRect.top, frmRect.bottom));
                        break;
                }
                return true;
            });

            return true;
        });
    }

    private void showStylusAuxButtons(boolean show) {
        LinearLayout buttons = findViewById(R.id.mouse_helper_visibility);
        if (getLorieView().connected() && show) {
            buttons.setVisibility(View.VISIBLE);
            buttons.setAlpha(isInPictureInPictureMode ? 0.f : 1.f);
        } else {
            //Reset default input back to normal
            mInputHandler.mStylusInputHelperMode = 1;
            final float menuUnselectedTrasparency = 0.66f;
            final float menuSelectedTrasparency = 1.0f;
            findViewById(R.id.button_left_click).setAlpha(menuSelectedTrasparency);
            findViewById(R.id.button_right_click).setAlpha(menuUnselectedTrasparency);
            findViewById(R.id.button_middle_click).setAlpha(menuUnselectedTrasparency);
            findViewById(R.id.button_visibility).setAlpha(menuUnselectedTrasparency);
            buttons.setVisibility(View.GONE);
        }
    }

    private RectF getVisibleFrmRect() {
        final ViewPager pager = getTerminalToolbarViewPager();
        final LorieView lorieView = getLorieView();
        final View sharedParent = (View) frm.getParent(); // also mouse/stylus aux buttons' parent

        // lorieView's available rect already excludes insets/caption/IME, just offset it to sharedParent's coordinates.
        RectF result = new RectF(lorieView.getAvailableRect());
        result.offset(frm.getLeft() + lorieView.getLeft(), frm.getTop() + lorieView.getTop());

        // availableRect only excludes the IME when Reseed is on, so clamp against it unconditionally too.
        result.bottom = Math.min(result.bottom, sharedParent.getHeight() - imeHeight);

        // With adjustHeightForEK on, availableRect already excludes the bar's own space.
        if (pager.getVisibility() == View.VISIBLE && !prefs.adjustHeightForEK.get()) {
            // getLayoutParams() is up to date immediately; getWidth()/getHeight() lag until the next layout pass.
            int barThickness = ((FrameLayout.LayoutParams) pager.getLayoutParams()).height;
            switch (getPagerPosition()) {
                case PAGER_POSITION_TOP:    result.top    = Math.max(result.top, barThickness); break;
                case PAGER_POSITION_BOTTOM: result.bottom = Math.min(result.bottom, sharedParent.getHeight() - imeHeight - barThickness); break;
                case PAGER_POSITION_LEFT:   result.left   = Math.max(result.left, barThickness); break;
                case PAGER_POSITION_RIGHT:  result.right  = Math.min(result.right, sharedParent.getWidth() - barThickness); break;
            }
        }
        return result;
    }

    private void makeSureHelpersAreVisibleAndInScreenBounds() {
        final ViewPager pager = getTerminalToolbarViewPager();
        final RectF frmRect = getVisibleFrmRect();
        View mouseAuxButtons = findViewById(R.id.mouse_buttons);
        View stylusAuxButtons = findViewById(R.id.mouse_helper_visibility);

        mouseAuxButtons.setX(MathUtils.clamp(mouseAuxButtons.getX(), frmRect.left, frmRect.right - mouseAuxButtons.getWidth()));
        mouseAuxButtons.setY(MathUtils.clamp(mouseAuxButtons.getY(), frmRect.top, frmRect.bottom - mouseAuxButtons.getHeight()));
        stylusAuxButtons.setX(MathUtils.clamp(stylusAuxButtons.getX(), frmRect.left, frmRect.right - stylusAuxButtons.getWidth()));
        stylusAuxButtons.setY(MathUtils.clamp(stylusAuxButtons.getY(), frmRect.top, frmRect.bottom - stylusAuxButtons.getHeight()));
    }

    public void toggleStylusAuxButtons() {
        showStylusAuxButtons(findViewById(R.id.mouse_helper_visibility).getVisibility() != View.VISIBLE);
        makeSureHelpersAreVisibleAndInScreenBounds();
    }

    private void showMouseAuxButtons(boolean show) {
        View v = findViewById(R.id.mouse_buttons);
        v.setVisibility((getLorieView().connected() && show && "1".equals(prefs.touchMode.get())) ? View.VISIBLE : View.GONE);
        v.setAlpha(isInPictureInPictureMode ? 0.f : 0.7f);
        makeSureHelpersAreVisibleAndInScreenBounds();
    }

    public void toggleMouseAuxButtons() {
        showMouseAuxButtons(findViewById(R.id.mouse_buttons).getVisibility() != View.VISIBLE);
    }

    void setSize(View v, int width, int height) {
        ViewGroup.LayoutParams p = v.getLayoutParams();
        p.width = (int) (width * getResources().getDisplayMetrics().density);
        p.height = (int) (height * getResources().getDisplayMetrics().density);
        v.setLayoutParams(p);
        v.setMinimumWidth((int) (width * getResources().getDisplayMetrics().density));
        v.setMinimumHeight((int) (height * getResources().getDisplayMetrics().density));
    }

    @SuppressLint("ClickableViewAccessibility")
    void initMouseAuxButtons() {
        final ViewPager pager = getTerminalToolbarViewPager();
        Button left = findViewById(R.id.mouse_button_left_click);
        Button right = findViewById(R.id.mouse_button_right_click);
        Button middle = findViewById(R.id.mouse_button_middle_click);
        ImageButton pos = findViewById(R.id.mouse_buttons_position);
        LinearLayout primaryLayer = findViewById(R.id.mouse_buttons);
        LinearLayout secondaryLayer = findViewById(R.id.mouse_buttons_secondary_layer);

        primaryLayer.setOnHoverListener((v, e) -> true);
        primaryLayer.setOnGenericMotionListener((v, e) -> true);
        if (SDK_INT >= VERSION_CODES.O)
            primaryLayer.setOnCapturedPointerListener((v, e) -> true);

        boolean mouseHelperEnabled = prefs.showMouseHelper.get() && "1".equals(prefs.touchMode.get());
        primaryLayer.setVisibility(mouseHelperEnabled ? View.VISIBLE : View.GONE);

        pos.setOnClickListener((v) -> {
            if (secondaryLayer.getOrientation() == LinearLayout.HORIZONTAL) {
                setSize(left, 48, 96);
                setSize(right, 48, 96);
                secondaryLayer.setOrientation(LinearLayout.VERTICAL);
            } else {
                setSize(left, 96, 48);
                setSize(right, 96, 48);
                secondaryLayer.setOrientation(LinearLayout.HORIZONTAL);
            }
            handler.postDelayed(() -> {
                final RectF frmRect = getVisibleFrmRect();
                primaryLayer.setX(MathUtils.clamp(primaryLayer.getX(), frmRect.left, frmRect.right - primaryLayer.getWidth()));
                primaryLayer.setY(MathUtils.clamp(primaryLayer.getY(), frmRect.top, frmRect.bottom - primaryLayer.getHeight()));
            }, 10);
        });

        Map.of(left, InputStub.BUTTON_LEFT, middle, InputStub.BUTTON_MIDDLE, right, InputStub.BUTTON_RIGHT)
                .forEach((v, b) -> v.setOnTouchListener((__, e) -> {
            switch(e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN:
                    getLorieView().sendMouseEvent(0, 0, b, true, true);
                    v.setPressed(true);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                case MotionEvent.ACTION_CANCEL:
                    getLorieView().sendMouseEvent(0, 0, b, false, true);
                    v.setPressed(false);
                    break;
            }
            return true;
        }));

        pos.setOnTouchListener(new View.OnTouchListener() {
            final int touchSlop = (int) Math.pow(ViewConfiguration.get(MainActivity.this).getScaledTouchSlop(), 2);
            final int tapTimeout = ViewConfiguration.getTapTimeout();
            final float[] startRaw = new float[2];
            final float[] startLayerPos = new float[2];
            long startTime;
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch(e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startRaw[0] = e.getRawX();
                        startRaw[1] = e.getRawY();
                        startLayerPos[0] = primaryLayer.getX();
                        startLayerPos[1] = primaryLayer.getY();
                        startTime = SystemClock.uptimeMillis();
                        pos.setPressed(true);
                        break;
                    case MotionEvent.ACTION_MOVE: {
                        final RectF frmRect = getVisibleFrmRect();
                        float newX = startLayerPos[0] + (e.getRawX() - startRaw[0]);
                        float newY = startLayerPos[1] + (e.getRawY() - startRaw[1]);
                        primaryLayer.setX(MathUtils.clamp(newX, frmRect.left, frmRect.right - primaryLayer.getWidth()));
                        primaryLayer.setY(MathUtils.clamp(newY, frmRect.top, frmRect.bottom - primaryLayer.getHeight()));
                        break;
                    }
                    case MotionEvent.ACTION_UP: {
                        pos.setPressed(false);
                        float deltaX = e.getRawX() - startRaw[0];
                        float deltaY = e.getRawY() - startRaw[1];

                        if (deltaX * deltaX + deltaY * deltaY < touchSlop && SystemClock.uptimeMillis() - startTime <= tapTimeout) {
                            v.performClick();
                            return true;
                        }
                        break;
                    }
                    case MotionEvent.ACTION_CANCEL:
                        pos.setPressed(false);
                        break;
                }
                return true;
            }
        });
    }

    void connectToService(IBinder ibinder) {
        service = ICmdEntryInterface.Stub.asInterface(ibinder);
        try {
            if (service != null && service.asBinder().isBinderAlive()) {
                Log.v("MainActivity", "Extracting logcat fd.");
                ParcelFileDescriptor logcatOutput = service.getLogcatOutput();
                if (logcatOutput != null)
                    getLorieView().startLogcat(logcatOutput.detachFd());

                tryConnect();
            }
        } catch (Exception e) {
            Log.e("MainActivity", "Something went wrong while we were establishing connection", e);
        }
    }

    void disconnectService() {
        service = null;

        Log.v("Lorie", "Disconnected");
        runOnUiThread(() -> { getLorieView().connect(-1); clientConnectedStateChanged(); });
    }

    boolean tryConnect() {
        if (getLorieView().connected()) {
            handler.removeCallbacks(connectRetry);
            return false;
        }

        if (service == null) {
            boolean sent = getLorieView().requestConnection();
            scheduleConnect();
            return true;
        }

        try {
            ParcelFileDescriptor fd = service.getXConnection();
            if (fd != null) {
                Log.v("MainActivity", "Extracting X connection socket.");
                getLorieView().connect(fd.detachFd());
                finishStartupDraw();
                getLorieView().triggerCallback();
                clientConnectedStateChanged();
                getLorieView().reloadPreferences(prefs);
            } else
                scheduleConnect();
        } catch (Exception e) {
            Log.e("MainActivity", "Something went wrong while we were establishing connection", e);
            service = null;

            scheduleConnect();
        }
        return false;
    }

    void scheduleConnect() {
        handler.removeCallbacks(connectRetry);
        handler.postDelayed(connectRetry, 250);
    }

    void onPreferencesChanged(String key) {
        if ("additionalKbdVisible".equals(key))
            return;

        handler.removeCallbacks(this::onPreferencesChangedCallback);
        handler.postDelayed(this::onPreferencesChangedCallback, 100);
    }

    @SuppressLint("UnsafeIntentLaunch")
    void onPreferencesChangedCallback() {
        prefs = app.getPrefs(this);

        // There is no way back to the normal size from picture-in-picture, so the window is closed.
        if (isInPictureInPictureMode && !prefs.PIP.get()) {
            finish();
            return;
        }

        applyWindowSettings();
        LorieView lorieView = getLorieView();

        mInputHandler.reloadPreferences(prefs);
        lorieView.reloadPreferences(prefs);

        if (mExtraKeys != null)
            mExtraKeys.reload();
        setTerminalToolbarView();

        lorieView.triggerCallback();

        filterOutWinKey = prefs.filterOutWinkey.get();
        if (prefs.enableAccessibilityServiceAutomatically.get())
            KeyInterceptor.launch(this);
        else if (checkSelfPermission(WRITE_SECURE_SETTINGS) == PERMISSION_GRANTED)
            KeyInterceptor.shutdown(true);

        useTermuxEKBarBehaviour = prefs.useTermuxEKBarBehaviour.get();
        showIMEWhileExternalConnected = prefs.showIMEWhileExternalConnected.get();

        findViewById(R.id.mouse_buttons).setVisibility(prefs.showMouseHelper.get() && "1".equals(prefs.touchMode.get()) && getLorieView().connected() ? View.VISIBLE : View.GONE);
        showMouseAuxButtons(prefs.showMouseHelper.get());
        showStylusAuxButtons(prefs.showStylusClickOverride.get());

        getTerminalToolbarViewPager().setAlpha(isInPictureInPictureMode ? 0.f : prefs.adjustHeightForEK.get() ? 1.f : ((float) prefs.opacityEKBar.get())/100);

        lorieView.requestLayout();
        lorieView.invalidate();

        app.refreshNotificationIfShown();
    }

    @Override
    public void onResume() {
        super.onResume();

        app.onActivityResumed(this);

        orientationListener.enable();
        setTerminalToolbarView();
        getLorieView().requestFocus();
    }

    @Override
    public void onPause() {
        getLorieView().setKeyboardVisible(false);

        orientationListener.disable();
        super.onPause();

        app.onActivityPaused();
    }

    /* ===================== 虚拟控件（移植自 winlator-glibc）===================== */

    /**
     * 初始化虚拟控件：桥接层 + 触摸板 + 控件覆盖层。
     *
     * <p>层级顺序（自下而上）：LorieView（X 画面） → TouchpadView（指针/手势） → InputControlsView（虚拟按键）。
     * InputControlsView 未命中的触摸会转发给 TouchpadView，因此两者必须成对挂载。
     */
    void initVirtualControls() {
        LorieView lorieView = getLorieView();
        ViewGroup parent = (ViewGroup) frm.getParent();

        mXServerBridge = new TX11XServerBridge(lorieView);

        mTouchpadView = new TouchpadView(this, mXServerBridge);
        mTouchpadView.setVisibility(View.GONE);
        parent.addView(mTouchpadView);

        mInputControlsView = new InputControlsView(this);
        mInputControlsView.setXServer(mXServerBridge);
        mInputControlsView.setTouchpadView(mTouchpadView);
        mInputControlsView.setOverlayOpacity(InputControlsView.DEFAULT_OVERLAY_OPACITY);
        mInputControlsView.setVisibility(View.GONE);
        parent.addView(mInputControlsView);

        mInputControlsManager = new InputControlsManager(this);
        mGlobalCursorSpeed = 1.0f; // 方案自带 cursorSpeed，全局系数暂固定为 1
        showVirtualControls(lorieView.connected());
    }

    /**
     * 依连接状态与已选方案决定控件是否可见。
     * 对应 winlator 里的 showInputControls / hideInputControls 一对函数。
     */
    void showVirtualControls(boolean connected) {
        if (mInputControlsView == null)
            return;
        boolean wantShow = connected && prefs.showInputControls.get();
        ControlsProfile profile = null;
        if (wantShow) {
            int id = prefs.activeControlsProfile.get();
            if (id > 0) profile = mInputControlsManager.getProfile(id);
            wantShow = profile != null;
        }
        if (profile != null)
            showInputControls(profile);
        else
            hideInputControls();
    }

    /** 启用并切换到指定方案（照搬 winlator 实现，含触摸板灵敏度跟随）。 */
    void showInputControls(ControlsProfile profile) {
        if (mInputControlsView == null || profile == null)
            return;
        mInputControlsView.setVisibility(View.VISIBLE);
        mInputControlsView.requestFocus();
        mInputControlsView.setProfile(profile);
        if (mTouchpadView != null) {
            mTouchpadView.setVisibility(View.VISIBLE);
            mTouchpadView.setSensitivity(profile.getCursorSpeed() * mGlobalCursorSpeed);
            mTouchpadView.setPointerButtonRightEnabled(true);
        }
        // IntPreference 没有 setter，直接写 SharedPreferences
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putInt("activeControlsProfile", profile.id).apply();
        prefs.showInputControls.put(true);
        mInputControlsView.invalidate();
        syncControlsProfileSpinner();
    }

    /** 关闭虚拟控件（照搬 winlator 的 hideInputControls）。 */
    void hideInputControls() {
        if (mInputControlsView == null)
            return;
        mInputControlsView.setShowTouchscreenControls(true);
        mInputControlsView.setVisibility(View.GONE);
        mInputControlsView.setProfile(null);
        if (mTouchpadView != null) {
            mTouchpadView.setVisibility(View.GONE);
            mTouchpadView.setSensitivity(mGlobalCursorSpeed);
            mTouchpadView.setPointerButtonLeftEnabled(true);
            mTouchpadView.setPointerButtonRightEnabled(true);
        }
        prefs.showInputControls.put(false);
        mInputControlsView.invalidate();
    }

    /** 侧边栏的方案下拉：第一项为「禁用」，其余为各方案。 */
    void loadControlsProfileSpinner() {
        if (mControlsProfileSpinner == null)
            return;
        ArrayList<ControlsProfile> profiles = mInputControlsManager.getProfiles();
        ArrayList<String> items = new ArrayList<>();
        items.add("-- " + getString(R.string.lorie_side_panel_disabled) + " --");
        for (ControlsProfile profile : profiles)
            items.add(profile.getName());
        mControlsProfileSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, items));
        syncControlsProfileSpinner();
    }

    /** 把当前生效的方案在 Spinner 上选中。 */
    void syncControlsProfileSpinner() {
        if (mControlsProfileSpinner == null)
            return;
        ArrayList<ControlsProfile> profiles = mInputControlsManager.getProfiles();
        int selected = 0;
        if (mInputControlsView != null && mInputControlsView.getProfile() != null) {
            int activeId = mInputControlsView.getProfile().id;
            for (int i = 0; i < profiles.size(); i++) {
                if (profiles.get(i).id == activeId) { selected = i + 1; break; }
            }
        }
        mControlsProfileSpinner.setSelection(selected, false);
    }

    public InputControlsView getInputControlsView() {
        return mInputControlsView;
    }

    public IXServerBridge getXServerBridge() {
        return mXServerBridge;
    }

    public void toggleInputControls() {
        boolean show = mInputControlsView == null || mInputControlsView.getVisibility() != View.VISIBLE;
        prefs.showInputControls.put(show);
        showVirtualControls(show);
    }

    /* ===================== 侧边栏（实时设置） ===================== */

    void initSidePanel() {
        mSidePanel = findViewById(R.id.side_panel);
        if (mSidePanel == null)
            return;

        applySidePanelLayout();

        Button softKbdBtn = mSidePanel.findViewById(R.id.button_soft_keyboard);
        if (softKbdBtn != null)
            softKbdBtn.setOnClickListener(v -> toggleKeyboardVisibility());

        bindSidePanelSwitch(R.id.switch_additional_kbd, () -> prefs.showAdditionalKbd.get(),
                checked -> {
                    prefs.showAdditionalKbd.put(checked);
                    if (checked) prefs.additionalKbdVisible.put(true);
                    setTerminalToolbarView();
                });

        bindSidePanelSwitch(R.id.switch_mouse_helper, () -> prefs.showMouseHelper.get(),
                checked -> {
                    prefs.showMouseHelper.put(checked);
                    showMouseAuxButtons(checked);
                });

        bindSidePanelSwitch(R.id.switch_stylus_helper, () -> prefs.showStylusClickOverride.get(),
                checked -> {
                    prefs.showStylusClickOverride.put(checked);
                    showStylusAuxButtons(checked);
                });

        mControlsProfileSpinner = mSidePanel.findViewById(R.id.spinner_controls_profile);
        if (mControlsProfileSpinner != null) {
            loadControlsProfileSpinner();
            mControlsProfileSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                    if (mSidePanelSyncing)
                        return;
                    if (position == 0) {
                        hideInputControls();
                    } else {
                        ArrayList<ControlsProfile> profiles = mInputControlsManager.getProfiles();
                        if (position - 1 < profiles.size())
                            showInputControls(profiles.get(position - 1));
                    }
                }

                @Override
                public void onNothingSelected(android.widget.AdapterView<?> parent) { }
            });
        }

        Button editBtn = mSidePanel.findViewById(R.id.button_edit_controls_profile);
        if (editBtn != null)
            editBtn.setOnClickListener(v -> {
                int id = 0;
                if (mInputControlsView != null && mInputControlsView.getProfile() != null) {
                    id = mInputControlsView.getProfile().id;
                } else {
                    ArrayList<ControlsProfile> profiles = mInputControlsManager.getProfiles();
                    if (!profiles.isEmpty()) id = profiles.get(0).id;
                }
                if (id <= 0) {
                    android.widget.Toast.makeText(this, R.string.no_profile_selected,
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                Intent intent = new Intent(this, ControlsEditorActivity.class);
                intent.putExtra("profile_id", id);
                startActivity(intent);
            });

        bindSidePanelSwitch(R.id.switch_fullscreen, () -> prefs.fullscreen.get(),
                checked -> prefs.fullscreen.put(checked));

        View prefsBtn = mSidePanel.findViewById(R.id.side_panel_preferences);
        if (prefsBtn != null)
            prefsBtn.setOnClickListener(v -> startActivity(
                    new Intent(this, LoriePreferences.class) {{ setAction(Intent.ACTION_MAIN); }}));

        View closeBtn = mSidePanel.findViewById(R.id.side_panel_close);
        if (closeBtn != null)
            closeBtn.setOnClickListener(v -> toggleSidePanel(false));

    }

    private void bindSidePanelSwitch(int id, java.util.function.BooleanSupplier current,
                                     java.util.function.Consumer<Boolean> onChange) {
        Switch sw = mSidePanel.findViewById(id);
        if (sw == null)
            return;
        sw.setChecked(current.getAsBoolean());
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (mSidePanelSyncing)
                return;
            onChange.accept(isChecked);
            syncSidePanelState();
        });
    }

    /** 把当前真实状态同步进侧边栏开关。 */
    public void syncSidePanelState() {
        if (mSidePanel == null)
            return;
        mSidePanelSyncing = true;
        setSwitchSilently(R.id.switch_additional_kbd, prefs.showAdditionalKbd.get() && prefs.additionalKbdVisible.get());
        setSwitchSilently(R.id.switch_mouse_helper, prefs.showMouseHelper.get());
        setSwitchSilently(R.id.switch_stylus_helper, prefs.showStylusClickOverride.get());
        setSwitchSilently(R.id.switch_fullscreen, prefs.fullscreen.get());
        syncControlsProfileSpinner();
        mSidePanelSyncing = false;
    }

    private void setSwitchSilently(int id, boolean checked) {
        Switch sw = mSidePanel.findViewById(id);
        if (sw != null && sw.isChecked() != checked)
            sw.setChecked(checked);
    }

    /**
     * 按当前屏幕方向分别计算侧边栏尺寸。
     *
     * <p>必须在这里算而不能只靠 values-land：{@code MainActivity} 声明了
     * {@code configChanges=orientation|screenSize}，转屏时 Activity 不重建，
     * 已膨胀的视图不会重新解析 dimen，横屏下会保留竖屏高度导致铺满整屏。
     */
    /**
     * 按当前屏幕方向分别计算侧边栏宽度。
     *
     * <p>必须在这里算而不能只靠 values-land：{@code MainActivity} 声明了
     * {@code configChanges=orientation|screenSize}，转屏时 Activity 不重建，
     * 已膨胀的视图不会重新解析 dimen，横屏下会保留竖屏宽度。
     *
     * <p>高度固定 match_parent（左侧栏贯穿全高），内容靠 ScrollView 上下滚动。
     */
    void applySidePanelLayout() {
        if (mSidePanel == null)
            return;

        boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;

        View lorie = getLorieView();
        int availW = lorie != null && lorie.getWidth() > 0
                ? lorie.getWidth()
                : getResources().getDisplayMetrics().widthPixels;

        // 竖屏约占 78% 宽；横屏屏幕宽，约占 34% 且不超过 380dp，避免喧宾夺主
        float ratio = landscape ? 0.34f : 0.78f;
        int width = Math.round(availW * ratio);
        if (landscape) {
            float maxDp = 380f * getResources().getDisplayMetrics().density;
            width = Math.min(width, Math.round(maxDp));
        }

        ViewGroup.LayoutParams lp = mSidePanel.getLayoutParams();
        if (lp != null && lp.width != width) {
            lp.width = width;
            mSidePanel.setLayoutParams(lp);
        }

        // 收起状态下让面板停在屏幕左侧之外（向左移出 = 负值）
        if (!mSidePanelShown)
            mSidePanel.setTranslationX(-width);
    }

    public boolean isSidePanelShown() {
        return mSidePanelShown;
    }

    public void toggleSidePanel() {
        // 返回键可能同时走按键路径与 onBackPressed，防抖避免"开了又关"
        long now = SystemClock.uptimeMillis();
        if (now - mLastSidePanelToggle < 300)
            return;
        mLastSidePanelToggle = now;
        toggleSidePanel(!mSidePanelShown);
    }

    public void toggleSidePanel(boolean show) {
        if (mSidePanel == null)
            return;
        if (show)
            syncSidePanelState();
        mSidePanelShown = show;

        if (show) {
            mSidePanel.setVisibility(View.VISIBLE);
            mSidePanel.post(() -> {
                applySidePanelLayout();
                // 起点在屏幕左侧之外，向右滑入到位（translationX: -width → 0）
                mSidePanel.setTranslationX(-mSidePanel.getWidth());
                mSidePanel.animate().translationX(0).setDuration(220).start();
            });
            mSidePanel.bringToFront();
        } else {
            // 向左滑出屏幕（translationX: 0 → -width）
            mSidePanel.animate().translationX(-mSidePanel.getWidth())
                    .setDuration(200)
                    .withEndAction(() -> mSidePanel.setVisibility(View.GONE))
                    .start();
            getLorieView().requestFocus();
        }
    }

    public LorieView getLorieView() {
        return findViewById(R.id.lorieView);
    }

    public ViewPager getTerminalToolbarViewPager() {
        return findViewById(R.id.terminal_toolbar_view_pager);
    }

    // We can not define function-static variables in Java, so we are defining them outside a function
    private final X11ToolbarViewPager.PageAdapter mPageAdapter =
            new X11ToolbarViewPager.PageAdapter(this, (v, k, e) -> mInputHandler.sendKeyEvent(e));
    private final X11ToolbarViewPager.OnPageChangeListener mOnPageListener = new X11ToolbarViewPager.OnPageChangeListener(this);
    private void setTerminalToolbarView() {
        final ViewPager pager = getTerminalToolbarViewPager();
        ViewGroup parent = (ViewGroup) pager.getParent();

        boolean showNow = !isInPictureInPictureMode && getLorieView().connected() && prefs.showAdditionalKbd.get() && prefs.additionalKbdVisible.get();

        pager.setVisibility(showNow ? View.VISIBLE : View.INVISIBLE);

        if (showNow) {
            if (pager.getAdapter() != mPageAdapter)
                pager.setAdapter(mPageAdapter);
            pager.clearOnPageChangeListeners();
            pager.addOnPageChangeListener(mOnPageListener);
            pager.bringToFront();
        } else {
            parent.removeView(pager);
            parent.addView(pager, 0);
            if (mExtraKeys != null)
                mExtraKeys.unsetSpecialKeys();
        }

        setTerminalToolbarViewLayout();
        getLorieView().requestFocus();
    }

    // Keep in sync with Surface.ROTATION_*
    public static final int PAGER_POSITION_TOP = 0, PAGER_POSITION_LEFT = 1, PAGER_POSITION_BOTTOM = 2, PAGER_POSITION_RIGHT = 3;
    public int getPagerPosition() {
        String _pos = prefs.ekbarPosition.get();
        int pos = "top".equals(_pos) ? PAGER_POSITION_TOP : "left".equals(_pos) ? PAGER_POSITION_LEFT : "bottom".equals(_pos) ? PAGER_POSITION_BOTTOM : "right".equals(_pos) ? PAGER_POSITION_RIGHT : 0;
        if (prefs.ekbarPositionIgnoreOrientation.get()) {
            Display dpy = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
            if (dpy != null)
                pos = (pos + dpy.getRotation()) % 4;
        }
        return pos;
    }

    @SuppressLint("RtlHardcoded")
    private void setTerminalToolbarViewLayout() {
        handler.post(() -> {
            final ViewPager pager = getTerminalToolbarViewPager();
            boolean showNow = pager.getVisibility() == View.VISIBLE;
            ExtraKeysInfo extraKeysInfo = mExtraKeys == null ? null : mExtraKeys.getExtraKeysInfo();
            FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) pager.getLayoutParams();
            int pos = getPagerPosition();

            // The window is not resized for the keyboard, so a bar along a side has to end above it.
            layoutParams.width = (pos == PAGER_POSITION_LEFT || pos == PAGER_POSITION_RIGHT) ? frm.getHeight() - imeHeight : frm.getWidth();
            layoutParams.height = Math.round(37.5f * getResources().getDisplayMetrics().density *
                    (extraKeysInfo == null ? 0 : extraKeysInfo.getMatrix().length));

            switch (pos) {
                case PAGER_POSITION_TOP:
                case PAGER_POSITION_BOTTOM:
                    layoutParams.gravity = (pos == PAGER_POSITION_TOP ? Gravity.TOP : Gravity.BOTTOM) | Gravity.LEFT;
                    // reset everything we set for "left" and "right"
                    pager.setPivotX(layoutParams.width / 2f);
                    pager.setPivotY(layoutParams.width / 2f);
                    pager.setRotation(0f);
                    pager.setTranslationX(0);
                    break;
                case PAGER_POSITION_LEFT:
                case PAGER_POSITION_RIGHT:
                    layoutParams.gravity = (pos == PAGER_POSITION_LEFT ? Gravity.LEFT : Gravity.RIGHT) | Gravity.TOP;
                    pager.setPivotX(pos == PAGER_POSITION_LEFT ? 0 : layoutParams.width);
                    pager.setPivotY(0f);
                    pager.setRotation(90f * (pos == PAGER_POSITION_LEFT ? 1 : -1));
                    pager.setTranslationX(layoutParams.height * (pos == PAGER_POSITION_LEFT ? 1 : -1));
                    break;
            }
            pager.setLayoutParams(layoutParams);

            ekbarContentInset = prefs.adjustHeightForEK.get() && showNow ? layoutParams.height : 0;
            applyContentInsets();
            makeSureHelpersAreVisibleAndInScreenBounds();
        });
    }

    private int ekbarContentInset = 0;
    private int imeHeight = 0;

    private void applyContentInsets() {
        int pos = getPagerPosition();
        // Only a bar at the bottom has to step aside for the keyboard.
        int bottomMargin = pos == PAGER_POSITION_BOTTOM ? imeHeight : 0;

        // A bottom bar reserving its own space always sits right above the keyboard via bottomMargin,
        // so content must stop there too, regardless of Reseed.
        boolean barReservesKeyboardSpace = pos == PAGER_POSITION_BOTTOM && ekbarContentInset > 0;
        int imeContentInset = barReservesKeyboardSpace ? bottomMargin : (prefs.Reseed.get() ? imeHeight : 0);

        getLorieView().setContentInsets(pos == PAGER_POSITION_LEFT ? ekbarContentInset : 0,
                pos == PAGER_POSITION_TOP ? ekbarContentInset : 0,
                pos == PAGER_POSITION_RIGHT ? ekbarContentInset : 0,
                imeContentInset + (pos == PAGER_POSITION_BOTTOM ? ekbarContentInset : 0));
        getLorieView().setObscuredBottom(imeHeight - imeContentInset);

        ViewPager pager = getTerminalToolbarViewPager();
        ViewGroup.MarginLayoutParams pagerParams = (ViewGroup.MarginLayoutParams) pager.getLayoutParams();
        if (pagerParams.bottomMargin != bottomMargin) {
            pagerParams.bottomMargin = bottomMargin;
            pager.setLayoutParams(pagerParams);
        }
    }

    public void setImeHeight(int height) {
        // Reported on every insets dispatch, but relaying out the bar for it must not become a loop.
        if (imeHeight == height)
            return;

        imeHeight = height;
        setTerminalToolbarViewLayout();
    }

    public void toggleExtraKeys(boolean visible, boolean saveState) {
        boolean enabled = prefs.showAdditionalKbd.get();

        if (enabled && getLorieView().connected() && saveState)
            prefs.additionalKbdVisible.put(visible);

        setTerminalToolbarView();
    }

    public void toggleExtraKeys() {
        toggleExtraKeys(getTerminalToolbarViewPager().getVisibility() != View.VISIBLE, true);
    }

    public boolean handleKey(KeyEvent e) {
        if (filterOutWinKey && (e.getKeyCode() == KEYCODE_META_LEFT || e.getKeyCode() == KEYCODE_META_RIGHT || e.isMetaPressed()))
            return false;
        return mLorieKeyListener.onKey(getLorieView(), e.getKeyCode(), e);
    }

    int orientation, densityDpi;

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (newConfig.orientation != orientation)
            getLorieView().setKeyboardVisible(false);

        if (newConfig.densityDpi != densityDpi)
            orientationDeniedAt = null;

        orientation = newConfig.orientation;
        densityDpi = newConfig.densityDpi;
        applyWindowSettings();
        setTerminalToolbarView();
        applySidePanelLayout();
    }

    @SuppressLint("WrongConstant")
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        KeyInterceptor.recheck();

        // The system bars come back when the window loses focus.
        if (hasFocus) {
            applyImmersiveMode();
            LorieView.markUserActivity();
            handler.removeCallbacks(screenIdleTimeoutCheck);
            checkScreenIdleTimeout();
        }
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        LorieView.markUserActivity();
        handler.removeCallbacks(screenIdleTimeoutCheck);
        checkScreenIdleTimeout();
    }

    private void applyImmersiveMode() {
        boolean isFullscreen = prefs.fullscreen.get();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(!isFullscreen);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                if (!isFullscreen)
                    controller.show(WindowInsets.Type.systemBars());
                else {
                    controller.hide(WindowInsets.Type.systemBars());
                    controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            }
        } else
            getWindow().getDecorView().setSystemUiVisibility(!isFullscreen ? 0 :
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void setWindowFlag(int flag, boolean enabled) {
        if (((getWindow().getAttributes().flags & flag) != 0) == enabled)
            return;

        if (enabled)
            getWindow().addFlags(flag);
        else
            getWindow().clearFlags(flag);
    }

    /** Keeps or drops the screen-on flag based on elapsed idle time, rescheduling itself for the remaining time. */
    private void checkScreenIdleTimeout() {
        String mode = prefs.screenIdleTimeout.get();
        if (!getLorieView().connected() || "never".equals(mode) || "system".equals(mode)) {
            screenIdleTimeoutArmedMode = null;
            return;
        }

        long systemTimeoutMs = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 0);
        long timeoutMs = Math.max(Long.parseLong(mode) * 60_000L - systemTimeoutMs, 0);
        long elapsed = (System.nanoTime() / 1_000_000L) - LorieView.getLastInputTimestamp();
        if (elapsed >= timeoutMs) {
            screenIdleTimeoutArmedMode = null;
            setWindowFlag(FLAG_KEEP_SCREEN_ON, false);
        } else {
            screenIdleTimeoutArmedMode = mode;
            setWindowFlag(FLAG_KEEP_SCREEN_ON, true);
            handler.postDelayed(screenIdleTimeoutCheck, timeoutMs - elapsed);
        }
    }

    /** Syncs screenIdleTimeout state/timer to the current preference without extending an already-scheduled check. */
    private void applyScreenIdleTimeout() {
        String mode = prefs.screenIdleTimeout.get();
        boolean connected = getLorieView().connected();
        if (!connected || "never".equals(mode) || "system".equals(mode)) {
            handler.removeCallbacks(screenIdleTimeoutCheck);
            screenIdleTimeoutArmedMode = null;
            setWindowFlag(FLAG_KEEP_SCREEN_ON, connected && "never".equals(mode));
        } else if (!mode.equals(screenIdleTimeoutArmedMode)) {
            handler.removeCallbacks(screenIdleTimeoutCheck);
            checkScreenIdleTimeout();
        }
    }

    void applyWindowSettings() {
        Window window = getWindow();
        boolean fullscreen = prefs.fullscreen.get();
        boolean hideCutout = prefs.hideCutout.get();

        // Recreating would take the window out of picture-in-picture, so it waits for the normal size.
        if (!isInPictureInPictureMode && (oldHideCutout != hideCutout || oldFullscreen != fullscreen)) {
            oldHideCutout = hideCutout;
            oldFullscreen = fullscreen;
            // For some reason cutout or fullscreen change makes layout calculations wrong and invalid.
            // I did not find simple and reliable way to fix it so it is better to start from the beginning.
            recreate();
            return;
        }

        int requestedOrientation;
        switch (isInMultiWindowMode() ? "auto" : prefs.forceOrientation.get()) {
            case "portrait": requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT; break;
            case "landscape": requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE; break;
            case "reverse portrait": requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT; break;
            case "reverse landscape": requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE; break;
            default: requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        }

        // A display ignoring orientation requests letterboxes the window into the requested
        // proportions instead of rotating, leaving the rest of the screen unusable. The request is
        // retried once the display changes, the next one may well honour it.
        if (SDK_INT >= VERSION_CODES.R) {
            WindowManager wm = getWindowManager();
            Rect display = wm.getMaximumWindowMetrics().getBounds();
            if (requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED && !wm.getCurrentWindowMetrics().getBounds().equals(display))
                orientationDeniedAt = display;
            if (display.equals(orientationDeniedAt))
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        }

        if (getRequestedOrientation() != requestedOrientation)
            setRequestedOrientation(requestedOrientation);

        if (SDK_INT >= VERSION_CODES.P) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            int cutoutMode = !hideCutout ? LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER :
                    (SDK_INT >= VERSION_CODES.R ? LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS : LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES);
            if (attributes.layoutInDisplayCutoutMode != cutoutMode) {
                attributes.layoutInDisplayCutoutMode = cutoutMode;
                window.setAttributes(attributes);
            }
        }

        setWindowFlag(FLAG_FULLSCREEN, fullscreen);
        applyScreenIdleTimeout();
        applyImmersiveMode();

        View contentChild = ((FrameLayout) findViewById(android.R.id.content)).getChildAt(0);
        if (contentChild.getFitsSystemWindows() == fullscreen) {
            contentChild.setFitsSystemWindows(!fullscreen);
            ViewCompat.requestApplyInsets(contentChild);
        }
    }

    @Override
    public void onBackPressed() {
        // 返回键不再呼出软键盘，改为呼出/收起侧边栏（未连接时保持退出）
        if (!getLorieView().connected() && !isSidePanelShown()) {
            finish();
            return;
        }
        toggleSidePanel();
    }

    private static float getSystemDimenFloat(String name, float fallback) {
        Resources resources = Resources.getSystem();
        TypedValue value = new TypedValue();
        int id = resources.getIdentifier(name, "dimen", "android");
        if (id != 0)
            resources.getValue(id, value, true);
        return value.type == TypedValue.TYPE_FLOAT ? value.getFloat() : fallback;
    }

    public static boolean hasPipPermission(@NonNull Context context) {
        AppOpsManager appOpsManager = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (appOpsManager == null)
            return false;
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            return appOpsManager.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, android.os.Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
        else
            return appOpsManager.checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, android.os.Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    @RequiresApi(api = VERSION_CODES.O)
    @Override
    public void onUserLeaveHint() {
        if (!prefs.PIP.get() || !hasPipPermission(this) || !getLorieView().connected())
            return;

        PictureInPictureParams.Builder params = new PictureInPictureParams.Builder();
        Rational aspectRatio = getLorieView().getScreenAspectRatio();
        if (aspectRatio != null) {
            float clamped = MathUtils.clamp(aspectRatio.floatValue(), MIN_PIP_ASPECT_RATIO, MAX_PIP_ASPECT_RATIO);
            if (clamped != aspectRatio.floatValue())
                // Truncating instead of rounding keeps the ratio from landing back outside of the range.
                aspectRatio = clamped > 1 ? new Rational((int) (clamped * 1000), 1000) : new Rational(1000, (int) (1000 / clamped));
            params.setAspectRatio(aspectRatio);
        }

        getLorieView().freezeDimensions(true);
        if (!enterPictureInPictureMode(params.build()))
            getLorieView().freezeDimensions(false);
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, @NonNull Configuration newConfig) {
        this.isInPictureInPictureMode = isInPictureInPictureMode;
        getLorieView().onPictureInPictureModeChanged(isInPictureInPictureMode);
        final ViewPager pager = getTerminalToolbarViewPager();
        pager.setAlpha(isInPictureInPictureMode ? 0.f : prefs.adjustHeightForEK.get() ? 1.f : ((float) prefs.opacityEKBar.get())/100);
        findViewById(R.id.mouse_buttons).setAlpha(isInPictureInPictureMode ? 0.f : 0.7f);
        findViewById(R.id.mouse_helper_visibility).setAlpha(isInPictureInPictureMode ? 0.f : 1.f);
        setTerminalToolbarView();
        if (!isInPictureInPictureMode)
            applyWindowSettings();

        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
    }

    /**
     * Manually toggle soft keyboard visibility
     */
    public void toggleKeyboardVisibility() {
        Log.d("MainActivity", "Toggling keyboard visibility");
        LorieView view = getLorieView();
        if (!externalKeyboardConnected || showIMEWhileExternalConnected)
            view.toggleKeyboardVisible();
        else
            view.setKeyboardVisible(false);
    }

    @SuppressWarnings("SameParameterValue")
    void clientConnectedStateChanged() {
        runOnUiThread(()-> {
            boolean connected = getLorieView().connected();
            if (!connected)
                setCapturingEnabled(false);

            // A picture-in-picture window has nothing to show without a client, and there is no way
            // back to the normal size from it, so the window is closed.
            if (!connected && isInPictureInPictureMode) {
                finish();
                return;
            }

            setTerminalToolbarView();
            findViewById(R.id.mouse_buttons).setVisibility(prefs.showMouseHelper.get() && "1".equals(prefs.touchMode.get()) && connected ? View.VISIBLE : View.GONE);
            findViewById(R.id.stub).setVisibility(connected?View.INVISIBLE:View.VISIBLE);
            getLorieView().setVisibility(connected?View.VISIBLE:View.INVISIBLE);

            // We should recover connection in the case if file descriptor for some reason was broken...
            if (!connected)
                tryConnect();
            else
                getLorieView().setPointerIcon(PointerIcon.getSystemIcon(this, PointerIcon.TYPE_NULL));

            applyWindowSettings();
        });
    }

    public void getRealMetrics(DisplayMetrics m) {
        if (getLorieView() != null && getLorieView().getDisplay() != null)
            getLorieView().getDisplay().getRealMetrics(m);
    }

    public void setCapturingEnabled(boolean enabled) {
        if (mInputHandler != null)
            mInputHandler.setCapturingEnabled(enabled);
    }

    public boolean shouldInterceptKeys() {
        View textInput = findViewById(R.id.terminal_toolbar_text_input);
        if (mInputHandler == null || !hasWindowFocus() || (textInput != null && textInput.isFocused()))
            return false;

        return mInputHandler.shouldInterceptKeys();
    }

    public void setExternalKeyboardConnected(boolean connected) {
        externalKeyboardConnected = connected;
        EditText textInput = findViewById(R.id.terminal_toolbar_text_input);
        if (textInput != null)
            textInput.setShowSoftInputOnFocus(!connected || showIMEWhileExternalConnected);
        if (connected && !showIMEWhileExternalConnected)
            getLorieView().setKeyboardVisible(false);
        getLorieView().requestFocus();
    }
}
