// Author: AdrianBega/DualBytes
// Updated: AdrianBega/DualBytes
package com.drivehub.kamera;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/**
 * Draggable floating camera window shown above other content.
 * For now it takes a camera index directly; later it can be triggered from turn-signal state.
 */
public class OverlayService extends Service implements TextureView.SurfaceTextureListener {

    public static final String EXTRA_CAMERA_INDEX = "camera_index";
    public static final String EXTRA_POPUP_MODE = "popup_mode";
    public static final String ACTION_POPUP_READY = "com.drivehub.kamera.action.POPUP_READY";

    private static final String CHANNEL_ID = "mg4_overlay";
    private static final int NOTIF_ID = 99;

    /** Default overlay size in px; the aspect ratio is preserved. */
    private static final int DEFAULT_OVERLAY_WIDTH_PX = 1000;
    private static final int DEFAULT_OVERLAY_HEIGHT_PX = 480;
    private static final float POPUP_CORNER_RADIUS_DP = 2f;
    private static final int POPUP_BUTTON_SIZE_SCALE_PERCENT = 120;
    private static final int POPUP_ICON_SIZE_SCALE_PERCENT = 130;
    private static final int POPUP_RESIZE_HANDLE_SIZE_DP = 18;
    private static final float POPUP_MIN_SCREEN_FRACTION = 0.15f;
    private static final float POPUP_MAX_SCREEN_FRACTION = 0.80f;

    private static final String PREFS_NAME = "overlay_prefs";
    private static final String KEY_LAST_X = "last_x";
    private static final String KEY_LAST_Y = "last_y";
    private static final String KEY_OVERLAY_W = "overlay_w";
    private static final String KEY_OVERLAY_H = "overlay_h";
    private static volatile boolean sPopupVisible = false;

    private WindowManager windowManager;
    private View overlayView;
    private TextureView textureView;
    private Surface textureSurface;
    private WindowManager.LayoutParams overlayParams;
    private int cameraIndex = 15; // Default: front
    private boolean popupMode;
    private ImageButton recordingButton;
    private final TestVideoPlayer testVideoPlayer = new TestVideoPlayer();
    private final SyntheticTestPreview syntheticTestPreview = new SyntheticTestPreview();

    /** Current window size, updated via pinch gestures. */
    private int overlayWidthPx = DEFAULT_OVERLAY_WIDTH_PX;
    private int overlayHeightPx = DEFAULT_OVERLAY_HEIGHT_PX;
    private int overlayX = 32;
    private int overlayY = 120;

    private ScaleGestureDetector scaleGestureDetector;

    private float initialX;
    private float initialY;
    private float initialTouchX;
    private float initialTouchY;
    private float resizeStartRawX;
    private float resizeStartRawY;
    private int resizeStartX;
    private int resizeStartY;
    private int resizeStartW;
    private int resizeStartH;
    private android.content.SharedPreferences uiPrefs;
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            (sharedPreferences, key) -> {
                if (UiPrefs.KEY_TILE_CORNER_RADIUS.equals(key)) {
                    applyOverlayCornerRadius();
                } else if (UiPrefs.KEY_OVERLAY_ROTATE_TO_DRIVING_DIRECTION.equals(key)) {
                    updateOverlayPresentation(true);
                }
            };
    private final BroadcastReceiver recordingStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            syncRecordingUi();
        }
    };
    public static void showOverlay(Context context, int cameraIndex) {
        Intent i = new Intent(context, OverlayService.class);
        i.putExtra(EXTRA_CAMERA_INDEX, cameraIndex);
        i.putExtra(EXTRA_POPUP_MODE, false);
        context.startForegroundService(i);
    }

    public static void showPopup(Context context, int cameraIndex) {
        Intent i = new Intent(context, OverlayService.class);
        i.putExtra(EXTRA_CAMERA_INDEX, cameraIndex);
        i.putExtra(EXTRA_POPUP_MODE, true);
        context.startForegroundService(i);
    }

    public static boolean isPopupVisible() {
        return sPopupVisible;
    }

    public static void hideOverlay(Context context) {
        UiPrefs.setLastUiState(UiPrefs.getPrefs(context), UiPrefs.UI_STATE_MAIN);
        Intent i = new Intent(context, OverlayService.class);
        context.stopService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        uiPrefs = UiPrefs.getPrefs(this);
        uiPrefs.registerOnSharedPreferenceChangeListener(prefListener);
        try {
            registerReceiver(recordingStateReceiver, new IntentFilter(RecordingService.ACTION_STATE_CHANGED));
        } catch (Throwable ignored) {
        }
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra(EXTRA_CAMERA_INDEX)) {
            cameraIndex = intent.getIntExtra(EXTRA_CAMERA_INDEX, 15);
        }
        popupMode = intent != null && intent.getBooleanExtra(EXTRA_POPUP_MODE, false);
        sPopupVisible = popupMode;
        loadSavedOverlayGeometry();
        markCurrentOverlayState();
        // Run as a foreground service so the overlay survives while the app is in the background.
        Notification notif = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notification_overlay_text))
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        startForeground(NOTIF_ID, notif);

        if (overlayView == null) {
            showFloatingWindow();
        } else {
            if (overlayParams != null) {
                overlayParams.width = overlayWidthPx;
                overlayParams.height = overlayHeightPx;
                overlayParams.x = clampLoadedX(overlayX);
                overlayParams.y = clampLoadedY(overlayY);
            }
            applyOverlayCornerRadius();
            updateOverlayPresentation(false);
            // If the overlay is already open and only the camera index changed, switch the feed.
            if (textureSurface != null && textureSurface.isValid()) {
                startPreview();
            }
        }
        return START_STICKY;
    }

    private void showFloatingWindow() {
        if (windowManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }

        int layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

        loadSavedOverlayGeometry();
        normalizeOverlaySizeForCurrentMode();

        overlayParams = new WindowManager.LayoutParams(
                overlayWidthPx,
                overlayHeightPx,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        overlayParams.gravity = Gravity.TOP | Gravity.START;

        overlayParams.x = overlayX;
        overlayParams.y = overlayY;
        clampOverlayPositionToScreen();

        overlayView = createOverlayCard();

        scaleGestureDetector = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        float factor = detector.getScaleFactor();
                        if (factor <= 0f || Float.isNaN(factor)) return true;
                        int newW = Math.round(overlayWidthPx * factor);
                        int[] wh = clampOverlaySize(newW);
                        overlayWidthPx = wh[0];
                        overlayHeightPx = wh[1];
                        overlayParams.width = overlayWidthPx;
                        overlayParams.height = overlayHeightPx;
                        clampOverlayPositionToScreen();
                        windowManager.updateViewLayout(overlayView, overlayParams);
                        applyPreviewTransform();
                        return true;
                    }

                    @Override
                    public void onScaleEnd(ScaleGestureDetector detector) {
                        saveOverlayLayoutPrefs(true);
                    }
                });

        try {
            windowManager.addView(overlayView, overlayParams);
        } catch (SecurityException | IllegalStateException | WindowManager.BadTokenException e) {
            stopSelf();
            return;
        }
        applyPreviewTransform();
        if (popupMode) {
            sendBroadcast(new Intent(ACTION_POPUP_READY));
        }

        overlayView.setOnTouchListener((v, event) -> {
            // Two fingers: pinch to resize, with no dragging.
            scaleGestureDetector.onTouchEvent(event);

            int action = event.getActionMasked();
            int pointerCount = event.getPointerCount();

            if (pointerCount >= 2) {
                if (action == MotionEvent.ACTION_POINTER_UP
                        || action == MotionEvent.ACTION_CANCEL
                        || action == MotionEvent.ACTION_UP) {
                    saveOverlayLayoutPrefs(true);
                }
                return true;
            }

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    initialX = overlayParams.x;
                    initialY = overlayParams.y;
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (scaleGestureDetector.isInProgress()) {
                        return true;
                    }
                    float dx = event.getRawX() - initialTouchX;
                    float dy = event.getRawY() - initialTouchY;
                    int newX = (int) (initialX + dx);
                    int newY = (int) (initialY + dy);
                    overlayParams.x = newX;
                    overlayParams.y = newY;
                    clampOverlayPositionToScreen();
                    windowManager.updateViewLayout(overlayView, overlayParams);
                    saveOverlayLayoutPrefs(false);
                    return true;
                case MotionEvent.ACTION_UP:
                    saveOverlayLayoutPrefs(true);
                    v.performClick();
                    return true;
                default:
                    return false;
            }
        });
    }

    private void loadSavedOverlayGeometry() {
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            int w = sp.getInt(KEY_OVERLAY_W, DEFAULT_OVERLAY_WIDTH_PX);
            int h = sp.getInt(KEY_OVERLAY_H, DEFAULT_OVERLAY_HEIGHT_PX);
            if (w >= 1 && h >= 1) {
                overlayWidthPx = w;
                overlayHeightPx = h;
            }
            overlayX = sp.getInt(KEY_LAST_X, overlayX);
            overlayY = sp.getInt(KEY_LAST_Y, overlayY);
        } catch (Throwable ignored) {
        }
    }

    private int clampLoadedX(int x) {
        int[] screenSize = getAvailableScreenSizePx();
        if (screenSize == null) return x;
        int maxX = Math.max(0, screenSize[0] - overlayWidthPx);
        return Math.max(0, Math.min(maxX, x));
    }

    private int clampLoadedY(int y) {
        int[] screenSize = getAvailableScreenSizePx();
        if (screenSize == null) return y;
        int maxY = Math.max(0, screenSize[1] - overlayHeightPx);
        return Math.max(0, Math.min(maxY, y));
    }

    private View createOverlayCard() {
        FrameLayout card = new FrameLayout(this);
        card.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        card.setBackgroundResource(R.drawable.bg_overlay_tile);
        card.setClipToOutline(true);
        card.setOutlineProvider(ViewOutlineProvider.BACKGROUND);

        textureView = new TextureView(this);
        textureView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        textureView.setOpaque(false);
        textureView.setSurfaceTextureListener(this);
        card.addView(textureView);

        ImageButton btnDismissOverlay = new ImageButton(this);
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(
                scalePopupButtonSize(56),
                scalePopupButtonSize(56),
                Gravity.TOP | Gravity.START
        );
        closeParams.leftMargin = 0;
        closeParams.topMargin = 0;
        btnDismissOverlay.setLayoutParams(closeParams);
        btnDismissOverlay.setBackground(null);
        btnDismissOverlay.setImageResource(R.drawable.ic_close);
        btnDismissOverlay.setColorFilter(0xFFFFFFFF);
        btnDismissOverlay.setPadding(
                scalePopupIconPadding(10),
                scalePopupIconPadding(10),
                scalePopupIconPadding(10),
                scalePopupIconPadding(10)
        );
        btnDismissOverlay.setContentDescription("Close overlay");
        btnDismissOverlay.setOnClickListener(v -> hideOverlay(OverlayService.this));
        card.addView(btnDismissOverlay);

        {
            ImageButton btnShowApp = new ImageButton(this);
            FrameLayout.LayoutParams appParams = new FrameLayout.LayoutParams(
                    scalePopupButtonSize(56),
                    scalePopupButtonSize(56),
                    Gravity.TOP | Gravity.END
            );
            appParams.rightMargin = 0;
            appParams.topMargin = 0;
            btnShowApp.setLayoutParams(appParams);
            btnShowApp.setBackground(null);
            btnShowApp.setImageResource(R.drawable.ic_popup);
            btnShowApp.setColorFilter(0xFFFFFFFF);
            btnShowApp.setPadding(
                    scalePopupIconPadding(10),
                    scalePopupIconPadding(10),
                    scalePopupIconPadding(10),
                    scalePopupIconPadding(10)
            );
            btnShowApp.setContentDescription("Show app");
            btnShowApp.setOnClickListener(v -> {
                markMainVisible();
                try {
                    MainActivity.launchFromOverlay(OverlayService.this);
                } catch (Throwable ignored) {
                }
            });
            card.addView(btnShowApp);
        }

        if (popupMode) {
            ImageButton btnRecording = new ImageButton(this);
            recordingButton = btnRecording;
            FrameLayout.LayoutParams recordParams = new FrameLayout.LayoutParams(
                    scalePopupButtonSize(56),
                    scalePopupButtonSize(56),
                    Gravity.BOTTOM | Gravity.END
            );
            recordParams.rightMargin = 0;
            recordParams.bottomMargin = 0;
            btnRecording.setLayoutParams(recordParams);
            btnRecording.setBackground(null);
            btnRecording.setImageResource(R.drawable.ic_record_circle);
            btnRecording.setPadding(
                    scalePopupIconPadding(10),
                    scalePopupIconPadding(10),
                    scalePopupIconPadding(10),
                    scalePopupIconPadding(10)
            );
            btnRecording.setContentDescription("Toggle recording");
            btnRecording.setOnClickListener(v -> {
                if (RecordingService.isRecording(OverlayService.this)) {
                    RecordingService.stopRecording(OverlayService.this);
                } else {
                    RecordingService.startRecording(OverlayService.this);
                }
                syncRecordingUi();
            });
            card.addView(btnRecording);

            addResizeHandle(card, Gravity.BOTTOM | Gravity.END, false);
            addResizeHandle(card, Gravity.BOTTOM | Gravity.START, true);
        }
        applyOverlayCornerRadius(card);
        if (popupMode) {
            applyPopupCornerRadius(card);
        }

        return card;
    }

    private void syncRecordingUi() {
        ImageButton button = recordingButton;
        if (button == null) return;
        boolean recording = RecordingService.isRecording(this);
        button.setImageTintList(android.content.res.ColorStateList.valueOf(
                recording ? 0xFFFF3B30 : 0xFFFFFFFF
        ));
    }

    private void markMainVisible() {
        if (uiPrefs != null) {
            UiPrefs.setLastUiState(uiPrefs, UiPrefs.UI_STATE_MAIN);
        }
    }

    private void markCurrentOverlayState() {
        if (uiPrefs != null) {
            UiPrefs.setLastUiState(uiPrefs, popupMode ? UiPrefs.UI_STATE_POPUP : UiPrefs.UI_STATE_OVERLAY);
        }
    }

    private void applyOverlayCornerRadius() {
        applyOverlayCornerRadius(overlayView);
    }

    private void applyOverlayCornerRadius(View target) {
        if (target == null || uiPrefs == null) return;
        target.post(() -> {
            if (!(target.getBackground() instanceof android.graphics.drawable.GradientDrawable)) {
                return;
            }
            android.graphics.drawable.GradientDrawable background =
                    (android.graphics.drawable.GradientDrawable) target.getBackground().mutate();
            background.setCornerRadius(UiPrefs.getCornerRadiusPx(target, uiPrefs));
            target.invalidateOutline();
        });
    }

    private void applyPopupCornerRadius(View target) {
        if (target == null) return;
        target.post(() -> {
            if (!(target.getBackground() instanceof android.graphics.drawable.GradientDrawable)) {
                return;
            }
            android.graphics.drawable.GradientDrawable background =
                    (android.graphics.drawable.GradientDrawable) target.getBackground().mutate();
            background.setCornerRadius(POPUP_CORNER_RADIUS_DP * getResources().getDisplayMetrics().density);
            background.setStroke(dpToPx(1), 0xFF8A8A8A);
            target.invalidateOutline();
        });
    }

    private void addResizeHandle(FrameLayout card, int gravity, boolean fromLeft) {
        ImageButton handle = new ImageButton(this);
        int size = dpToPx(POPUP_RESIZE_HANDLE_SIZE_DP);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size, gravity);
        params.leftMargin = 0;
        params.rightMargin = 0;
        params.bottomMargin = -dpToPx(3);
        handle.setLayoutParams(params);
        handle.setBackground(null);
        handle.setImageResource(fromLeft ? R.drawable.ic_resize_corner_bottom_left
                : R.drawable.ic_resize_corner_bottom_right);
        handle.setPadding(0, 0, 0, 0);
        handle.setColorFilter(0xFFFFFFFF);
        handle.setContentDescription(fromLeft ? "Resize popup from lower left" : "Resize popup from lower right");
        handle.setOnTouchListener((v, event) -> {
            if (!popupMode || overlayParams == null || windowManager == null) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    resizeStartRawX = event.getRawX();
                    resizeStartRawY = event.getRawY();
                    resizeStartX = overlayParams.x;
                    resizeStartY = overlayParams.y;
                    resizeStartW = overlayParams.width;
                    resizeStartH = overlayParams.height;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = Math.round(event.getRawX() - resizeStartRawX);
                    int newW = fromLeft ? resizeStartW - dx : resizeStartW + dx;
                    int[] clamped = clampPopupResize(newW);
                    overlayParams.width = clamped[0];
                    overlayParams.height = clamped[1];
                    if (fromLeft) {
                        overlayParams.x = resizeStartX + (resizeStartW - overlayParams.width);
                    } else {
                        overlayParams.x = resizeStartX;
                    }
                    overlayParams.y = resizeStartY;
                    clampOverlayPositionToScreen();
                    overlayWidthPx = overlayParams.width;
                    overlayHeightPx = overlayParams.height;
                    windowManager.updateViewLayout(overlayView, overlayParams);
                    applyPreviewTransform();
                    saveOverlayLayoutPrefs(false);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    saveOverlayLayoutPrefs(true);
                    return true;
                default:
                    return false;
            }
        });
        card.addView(handle);
    }

    private int[] clampPopupResize(int w) {
        float aspect = getActiveOverlayAspect();
        int[] screenSize = getAvailableScreenSizePx();
        int maxW = screenSize != null ? Math.round(screenSize[0] * POPUP_MAX_SCREEN_FRACTION) : w;
        int maxH = screenSize != null ? Math.round(screenSize[1] * POPUP_MAX_SCREEN_FRACTION) : w;
        int minW = screenSize != null ? Math.round(screenSize[0] * POPUP_MIN_SCREEN_FRACTION) : w;
        int minH = screenSize != null ? Math.round(screenSize[1] * POPUP_MIN_SCREEN_FRACTION) : w;
        w = Math.max(minW, Math.min(maxW, w));
        int h = Math.round(w / aspect);
        if (h > maxH) {
            h = maxH;
            w = Math.round(h * aspect);
        }
        if (h < minH) {
            h = minH;
            w = Math.round(h * aspect);
        }
        if (w > maxW) {
            w = maxW;
            h = Math.round(w / aspect);
        }
        if (w < minW) {
            w = minW;
            h = Math.round(w / aspect);
        }
        return new int[]{w, h};
    }

    private int scalePopupButtonSize(int dp) {
        return Math.round(dpToPx(dp) * (POPUP_BUTTON_SIZE_SCALE_PERCENT / 100f));
    }

    private int scalePopupIconPadding(int dp) {
        return Math.round(dpToPx(dp) * (POPUP_ICON_SIZE_SCALE_PERCENT / 100f));
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    /** Keeps the active overlay aspect ratio while clamping size to screen and min/max bounds. */
    private int[] clampOverlaySize(int w) {
        float aspect = getActiveOverlayAspect();
        int[] screenSize = getAvailableScreenSizePx();
        int minW = screenSize != null ? Math.round(screenSize[0] * POPUP_MIN_SCREEN_FRACTION) : w;
        int maxW = screenSize != null ? Math.round(screenSize[0] * POPUP_MAX_SCREEN_FRACTION) : w;
        int maxH = screenSize != null ? Math.round(screenSize[1] * POPUP_MAX_SCREEN_FRACTION) : w;
        int minH = screenSize != null ? Math.round(screenSize[1] * POPUP_MIN_SCREEN_FRACTION) : w;
        w = Math.max(minW, Math.min(maxW, w));
        int h = Math.round(w / aspect);
        if (h > maxH) {
            h = maxH;
            w = Math.round(h * aspect);
            h = Math.round(w / aspect);
        }
        if (h < minH) {
            h = minH;
            w = Math.round(h * aspect);
        }
        if (w > maxW) {
            w = maxW;
            h = Math.round(w / aspect);
        }
        return new int[]{w, h};
    }

    private int clampToPopupBounds(int value, boolean width) {
        int[] screenSize = getAvailableScreenSizePx();
        if (screenSize == null) {
            return value;
        }
        int min = Math.round((width ? screenSize[0] : screenSize[1]) * POPUP_MIN_SCREEN_FRACTION);
        int max = Math.round((width ? screenSize[0] : screenSize[1]) * POPUP_MAX_SCREEN_FRACTION);
        return Math.max(min, Math.min(max, value));
    }

    private void normalizeOverlaySizeForCurrentMode() {
        boolean shouldRotate = shouldRotatePreviewToDrivingDirection();
        boolean isLandscape = overlayWidthPx >= overlayHeightPx;
        if (shouldRotate == isLandscape) {
            int swappedWidth = overlayHeightPx;
            overlayHeightPx = overlayWidthPx;
            overlayWidthPx = swappedWidth;
        }
        int[] clamped = clampOverlaySize(overlayWidthPx);
        overlayWidthPx = clamped[0];
        overlayHeightPx = clamped[1];
    }

    private float getActiveOverlayAspect() {
        return shouldRotatePreviewToDrivingDirection()
                ? (float) DEFAULT_OVERLAY_HEIGHT_PX / (float) DEFAULT_OVERLAY_WIDTH_PX
                : (float) DEFAULT_OVERLAY_WIDTH_PX / (float) DEFAULT_OVERLAY_HEIGHT_PX;
    }

    private boolean shouldRotatePreviewToDrivingDirection() {
        return uiPrefs != null
                && UiPrefs.isOverlayRotationToDrivingDirectionEnabled(uiPrefs)
                && (cameraIndex == 14 || cameraIndex == 16);
    }

    private float getPreviewRotationDegrees() {
        if (!shouldRotatePreviewToDrivingDirection()) {
            return 0f;
        }
        return cameraIndex == 16 ? -90f : 90f;
    }

    private void updateOverlayPresentation(boolean persist) {
        normalizeOverlaySizeForCurrentMode();
        if (overlayParams != null) {
            overlayParams.width = overlayWidthPx;
            overlayParams.height = overlayHeightPx;
            clampOverlayPositionToScreen();
            if (windowManager != null && overlayView != null) {
                windowManager.updateViewLayout(overlayView, overlayParams);
            }
        }
        applyPreviewTransform();
        if (persist) {
            saveOverlayLayoutPrefs(true);
        }
    }

    private void applyPreviewTransform() {
        if (textureView == null) return;
        textureView.post(() -> {
            if (textureView == null) return;
            FrameLayout.LayoutParams params;
            if (shouldRotatePreviewToDrivingDirection()) {
                params = new FrameLayout.LayoutParams(
                        overlayHeightPx,
                        overlayWidthPx,
                        Gravity.CENTER
                );
            } else {
                params = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER
                );
            }
            textureView.setLayoutParams(params);
            textureView.setScaleX(1f);
            textureView.setScaleY(1f);
            textureView.setRotation(getPreviewRotationDegrees());
        });
    }

    private void clampOverlayPositionToScreen() {
        if (windowManager == null || overlayParams == null) return;
        int[] screenSize = getAvailableScreenSizePx();
        if (screenSize == null) return;
        int maxX = Math.max(0, screenSize[0] - overlayParams.width);
        int maxY = Math.max(0, screenSize[1] - overlayParams.height);
        if (overlayParams.x < 0) overlayParams.x = 0;
        if (overlayParams.y < 0) overlayParams.y = 0;
        if (overlayParams.x > maxX) overlayParams.x = maxX;
        if (overlayParams.y > maxY) overlayParams.y = maxY;
    }

    // Android Auto can expose a smaller "current" app viewport than the actual interactive
    // overlay space. Maximum window metrics are a better fit for drag/resize bounds here.
    private int[] getAvailableScreenSizePx() {
        if (windowManager == null) return null;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect bounds = windowManager.getMaximumWindowMetrics().getBounds();
            return new int[]{bounds.width(), bounds.height()};
        }

        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(dm);
        return new int[]{dm.widthPixels, dm.heightPixels};
    }

    private void saveOverlayLayoutPrefs(boolean synchronous) {
        if (overlayParams == null) return;
        try {
            overlayWidthPx = overlayParams.width;
            overlayHeightPx = overlayParams.height;
            overlayX = overlayParams.x;
            overlayY = overlayParams.y;
            android.content.SharedPreferences sp =
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            android.content.SharedPreferences.Editor editor = sp.edit()
                    .putInt(KEY_LAST_X, overlayParams.x)
                    .putInt(KEY_LAST_Y, overlayParams.y)
                    .putInt(KEY_OVERLAY_W, overlayWidthPx)
                    .putInt(KEY_OVERLAY_H, overlayHeightPx);
            if (synchronous && !editor.commit()) {
                android.util.Log.w("OverlayService", "Failed to persist overlay geometry");
            } else if (!synchronous) {
                editor.apply();
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        saveOverlayLayoutPrefs(true);
        sPopupVisible = false;
        if (uiPrefs != null && !UiPrefs.UI_STATE_MAIN.equals(UiPrefs.getLastUiState(uiPrefs))) {
            markCurrentOverlayState();
        }
        if (uiPrefs != null) {
            uiPrefs.unregisterOnSharedPreferenceChangeListener(prefListener);
        }
        try {
            unregisterReceiver(recordingStateReceiver);
        } catch (Throwable ignored) {
        }
        stopPreview();
        if (textureSurface != null) {
            textureSurface.release();
            textureSurface = null;
        }
        if (windowManager != null && overlayView != null) {
            windowManager.removeView(overlayView);
        }
        overlayView = null;
        textureView = null;
    }

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                "MG4 Overlay",
                NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.createNotificationChannel(ch);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // Texture callbacks

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        textureSurface = new Surface(surface);
        applyPreviewTransform();
        startPreview();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        applyPreviewTransform();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        stopPreview();
        if (textureSurface != null) {
            textureSurface.release();
            textureSurface = null;
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        // no-op
    }

    private void startPreview() {
        if (textureSurface == null || !textureSurface.isValid()) {
            return;
        }
        applyPreviewTransform();
        PreviewSourceController.start(this, cameraIndex, textureSurface, testVideoPlayer, syntheticTestPreview);
    }

    private void stopPreview() {
        PreviewSourceController.stop(testVideoPlayer);
        syntheticTestPreview.stop();
    }
}
