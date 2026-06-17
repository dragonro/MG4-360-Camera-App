// Author: AdrianBega/DualBytes
// Updated: AdrianBega/DualBytes
package com.drivehub.kamera;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.os.SystemClock;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback {

    public static final String ACTION_SHOW_APP = "com.drivehub.kamera.action.SHOW_APP";

    private static final String AVM_PREFS_NAME = "AVM_Settings";
    private static final String KEY_SAFETY_WARNING = "ShowSafetyWarning";
    private static final int REQ_RECORDING_FOLDER = 5001;
    private static final int REQ_OVERLAY_PERMISSION = 5002;
    private static final int SWIPE_THRESHOLD_PX = 140;
    private SurfaceHolder surfaceHolder;
    private TextView tvStatus;
    private ImageButton btnRecording;
    private ImageButton btnCameraPopup;
    private TextView tvRecordingTimer;
    private int currentVideoIndex = 15;
    private boolean previewRunning = false;
    private boolean testPreviewRunning = false;
    private boolean mainPreviewOwnsSurface = false;
    private boolean restoreOverlayOnLaunch = false;
    private boolean overlayRestoreStarted = false;
    private float downX = 0f;
    private float downY = 0f;
    private final TestVideoPlayer testVideoPlayer = new TestVideoPlayer();
    private final SyntheticTestPreview syntheticTestPreview = new SyntheticTestPreview();

    private static volatile boolean sMainVisible = false;
    private static volatile boolean sSettingsDialogOpen = false;
    private final SettingsAppearanceController appearanceController = new SettingsAppearanceController(this);
    private final SignalCameraSettingsController signalCameraSettingsController =
            new SignalCameraSettingsController(this);
    private final DevSettingsController devSettingsController = new DevSettingsController();
    private TextView recordingPathValueView;

    private final OtaController otaController = new OtaController(this);
    private final Handler recordingTimerHandler = new Handler(Looper.getMainLooper());
    private final Runnable recordingTimerTick = new Runnable() {
        @Override
        public void run() {
            updateRecordingTimer();
            if (RecordingService.isRecording(MainActivity.this)) {
                recordingTimerHandler.postDelayed(this, 1000L);
            }
        }
    };

    private final BroadcastReceiver cameraRouteReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            if (!SignalService.ACTION_ROUTE_CAMERA.equals(intent.getAction())) return;
            int idx = intent.getIntExtra(SignalService.EXTRA_CAMERA_INDEX, currentVideoIndex);
            if (idx == currentVideoIndex) return;
            currentVideoIndex = idx;
            if (tvStatus != null) {
                tvStatus.setText(getString(R.string.main_preview_status, cameraLabel(currentVideoIndex)));
            }
            startPreviewIfReady();
        }
    };

    private final BroadcastReceiver recordingStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            syncRecordingUi();
        }
    };

    private final BroadcastReceiver recordingWarningReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String code = intent.getStringExtra(RecordingService.EXTRA_WARNING_CODE);
            int messageRes = RecordingService.WARNING_NOT_ENOUGH_SPACE.equals(code)
                    ? R.string.recording_warning_not_enough_space
                    : RecordingService.WARNING_PRUNE_FAILED.equals(code)
                    ? R.string.recording_warning_prune_failed
                    : R.string.recording_warning_storage_full;
            Toast.makeText(MainActivity.this, messageRes, Toast.LENGTH_LONG).show();
            syncRecordingUi();
        }
    };

    private final BroadcastReceiver popupReadyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            if (!OverlayService.ACTION_POPUP_READY.equals(intent.getAction())) return;
            finishMainAfterPopupRequested();
        }
    };

    public static boolean isMainVisible() {
        return sMainVisible;
    }

    public static boolean shouldBlockOverlay() {
        return sMainVisible && !sSettingsDialogOpen;
    }

    public static void launchFromOverlay(Context context) {
        if (context == null) return;
        Intent intent = new Intent(context, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(intent);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String lastUiState = UiPrefs.getLastUiState(UiPrefs.getPrefs(this));
        restoreOverlayOnLaunch = UiPrefs.UI_STATE_OVERLAY.equals(lastUiState)
                || UiPrefs.UI_STATE_POPUP.equals(lastUiState);
        if (restoreOverlayOnLaunch) {
            resumeRecordingIfNeeded();
            restoreOverlayOnly(lastUiState);
            return;
        }

        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        SurfaceView surfaceView = findViewById(R.id.surfaceView);
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(this);

        tvStatus = findViewById(R.id.tvStatus);
        if (tvStatus != null) {
            tvStatus.setText(getString(R.string.main_preview_status, cameraLabel(currentVideoIndex)));
        }

        ImageButton btnSettings = findViewById(R.id.btnSettings);
        btnSettings.setOnClickListener(v -> showSettingsDialog());

        btnRecording = findViewById(R.id.btnRecording);
        btnCameraPopup = findViewById(R.id.btnCameraPopup);
        tvRecordingTimer = findViewById(R.id.tvRecordingTimer);
        if (btnRecording != null) {
            btnRecording.setOnClickListener(v -> toggleRecording());
        }
        if (btnCameraPopup != null) {
            btnCameraPopup.setOnClickListener(v -> {
                if (UiPrefs.isCameraPopupEnabled(UiPrefs.getPrefs(this))) {
                    startPopupOverlay();
                }
            });
        }

        ImageButton btnClose = findViewById(R.id.btnClose);
        btnClose.setOnClickListener(v -> finishAndRemoveTask());

        appearanceController.applyMainUiIconColors();
        applyWarningVisibility();
        applyCameraPopupVisibility();
        resumeRecordingIfNeeded();
        syncRecordingUi();
        updateRecordingTimer();

        try {
            SignalService.start(this);
        } catch (Throwable ignored) {
        }

        surfaceView.setOnTouchListener((v, event) -> {
            if (event == null) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    return true;
                case MotionEvent.ACTION_UP:
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;
                    if (Math.abs(dx) > Math.abs(dy)) {
                        if (dx > SWIPE_THRESHOLD_PX) currentVideoIndex = 14;
                        else if (dx < -SWIPE_THRESHOLD_PX) currentVideoIndex = 16;
                        else return true;
                    } else {
                        if (dy < -SWIPE_THRESHOLD_PX) currentVideoIndex = 15;
                        else if (dy > SWIPE_THRESHOLD_PX) currentVideoIndex = 17;
                        else return true;
                    }
                    if (tvStatus != null) {
                        tvStatus.setText(getString(R.string.main_preview_status, cameraLabel(currentVideoIndex)));
                    }
                    startPreviewIfReady();
                    return true;
            }
            return false;
        });
    }

    private void restoreOverlayOnly(String lastUiState) {
        if (overlayRestoreStarted || isFinishing()) return;
        overlayRestoreStarted = true;
        Window window = getWindow();
        if (window != null) {
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = 1;
            params.height = 1;
            params.gravity = Gravity.TOP | Gravity.START;
            params.alpha = 0f;
            window.setAttributes(params);
        }
        boolean restorePopup = UiPrefs.UI_STATE_POPUP.equals(lastUiState);
        UiPrefs.setLastUiState(
                UiPrefs.getPrefs(this),
                restorePopup ? UiPrefs.UI_STATE_POPUP : UiPrefs.UI_STATE_OVERLAY
        );
        if (restorePopup) {
            OverlayService.showPopup(this, currentVideoIndex);
        } else {
            OverlayService.showOverlay(this, currentVideoIndex);
        }
        finishAndRemoveTask();
    }

    @Override
    protected void onStart() {
        super.onStart();
        sMainVisible = true;
        try {
            ContextCompat.registerReceiver(
                    this,
                    cameraRouteReceiver,
                    new IntentFilter(SignalService.ACTION_ROUTE_CAMERA),
                    ContextCompat.RECEIVER_NOT_EXPORTED
            );
            ContextCompat.registerReceiver(
                    this,
                    recordingStateReceiver,
                    new IntentFilter(RecordingService.ACTION_STATE_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED
            );
            ContextCompat.registerReceiver(
                    this,
                    recordingWarningReceiver,
                    new IntentFilter(RecordingService.ACTION_RECORDING_WARNING),
                    ContextCompat.RECEIVER_NOT_EXPORTED
            );
        } catch (Throwable ignored) {
        }
        if (!OverlayService.isPopupVisible()) {
            if (UiPrefs.UI_STATE_MAIN.equals(UiPrefs.getLastUiState(UiPrefs.getPrefs(this)))) {
                OverlayService.hideOverlay(this);
            }
        }
        applyWarningVisibility();
        applyCameraPopupVisibility();
        syncRecordingUi();
        updateRecordingTimer();
    }

    @Override
    protected void onStop() {
        super.onStop();
        sMainVisible = false;
        sSettingsDialogOpen = false;
        if (!OverlayService.isPopupVisible() && !restoreOverlayOnLaunch) {
            UiPrefs.setLastUiState(UiPrefs.getPrefs(this), UiPrefs.UI_STATE_MAIN);
        }
        otaController.stop();
        recordingTimerHandler.removeCallbacks(recordingTimerTick);
        try {
            unregisterReceiver(cameraRouteReceiver);
        } catch (Throwable ignored) {
        }
        try {
            unregisterReceiver(recordingStateReceiver);
        } catch (Throwable ignored) {
        }
        try {
            unregisterReceiver(recordingWarningReceiver);
        } catch (Throwable ignored) {
        }
        try {
            unregisterReceiver(popupReadyReceiver);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        sMainVisible = false;
        if (!OverlayService.isPopupVisible() && !restoreOverlayOnLaunch) {
            UiPrefs.setLastUiState(UiPrefs.getPrefs(this), UiPrefs.UI_STATE_MAIN);
        }
        otaController.stop();
        stopPreview();
    }

    // -------------------------------------------------------------------------
    // Settings dialog
    // -------------------------------------------------------------------------

    @SuppressWarnings("deprecation")
    private void showSettingsDialog() {
        sSettingsDialogOpen = true;
        SignalService.requestRecheck();

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_settings);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        SharedPreferences prefs = UiPrefs.getPrefs(this);
        SharedPreferences avmPrefs = getSharedPreferences(AVM_PREFS_NAME, MODE_PRIVATE);

        Switch swOverlay = dialog.findViewById(R.id.switchOverlayOnSignal);
        Switch swRotateToDrivingDirection =
                dialog.findViewById(R.id.switchOverlayRotateToDrivingDirection);
        Switch swSafetyWarning = dialog.findViewById(R.id.switchSafetyWarning);
        Switch swEnableCameraPopup = dialog.findViewById(R.id.switchEnableCameraPopup);
        Switch swEnableRecording = dialog.findViewById(R.id.switchEnableRecording);
        TextView tvRecordsPathValue = dialog.findViewById(R.id.tvRecordsPathValue);
        TextView tvRecordingStorageQuotaValue = dialog.findViewById(R.id.tvRecordingStorageQuotaValue);
        Button btnExportUsb = dialog.findViewById(R.id.btnExportUsb);
        Button btnOpenRecordingFolder = dialog.findViewById(R.id.btnOpenRecordingFolder);
        RadioGroup rgRecordingDuration = dialog.findViewById(R.id.rgRecordingDuration);
        RadioButton rbRecordingDuration1 = dialog.findViewById(R.id.rbRecordingDuration1);
        RadioButton rbRecordingDuration2 = dialog.findViewById(R.id.rbRecordingDuration2);
        RadioButton rbRecordingDuration5 = dialog.findViewById(R.id.rbRecordingDuration5);
        RadioButton rbRecordingDuration10 = dialog.findViewById(R.id.rbRecordingDuration10);
        SeekBar seekRecordingStorageQuota = dialog.findViewById(R.id.seekRecordingStorageQuota);
        Switch swLoopRecording = dialog.findViewById(R.id.switchLoopRecording);
        swSafetyWarning.setChecked(avmPrefs.getBoolean(KEY_SAFETY_WARNING, true));
        swSafetyWarning.setOnCheckedChangeListener((btn, checked) -> {
            avmPrefs.edit().putBoolean(KEY_SAFETY_WARNING, checked).apply();
            applyWarningVisibility();
        });
        swEnableCameraPopup.setChecked(UiPrefs.isCameraPopupEnabled(prefs));
        swEnableCameraPopup.setOnCheckedChangeListener((btn, checked) -> {
            prefs.edit().putBoolean(UiPrefs.KEY_ENABLE_CAMERA_POPUP, checked).apply();
            applyCameraPopupVisibility();
        });
        swEnableRecording.setChecked(UiPrefs.isRecordingButtonEnabled(prefs));
        swEnableRecording.setOnCheckedChangeListener((btn, checked) -> {
            prefs.edit().putBoolean(UiPrefs.KEY_ENABLE_RECORDING_BUTTON, checked).apply();
            syncRecordingUi();
            if (!checked && RecordingService.isRecording(this)) {
                RecordingService.stopRecording(this);
            }
        });
        recordingPathValueView = tvRecordsPathValue;
        refreshRecordingPathLabel(tvRecordsPathValue);
        btnExportUsb.setOnClickListener(v -> openRecordingFolderPicker());
        btnOpenRecordingFolder.setOnClickListener(v -> openRecordingFolder());
        int durationMin = UiPrefs.getRecordingDurationMin(prefs);
        if (durationMin == 2) {
            rgRecordingDuration.check(rbRecordingDuration2.getId());
        } else if (durationMin == 5) {
            rgRecordingDuration.check(rbRecordingDuration5.getId());
        } else if (durationMin == 10) {
            rgRecordingDuration.check(rbRecordingDuration10.getId());
        } else {
            rgRecordingDuration.check(rbRecordingDuration1.getId());
        }
        rgRecordingDuration.setOnCheckedChangeListener((group, checkedId) -> {
            int value = checkedId == rbRecordingDuration2.getId() ? 2
                    : checkedId == rbRecordingDuration5.getId() ? 5
                    : checkedId == rbRecordingDuration10.getId() ? 10 : 1;
            prefs.edit().putInt(UiPrefs.KEY_RECORDING_DURATION_MIN, value).apply();
        });
        int quotaPercent = UiPrefs.getRecordingStorageQuotaPercent(prefs);
        tvRecordingStorageQuotaValue.setText(getString(
                R.string.settings_recording_storage_limit_value,
                quotaPercent
        ));
        seekRecordingStorageQuota.setMax(
                UiPrefs.MAX_RECORDING_STORAGE_QUOTA_PERCENT
                        - UiPrefs.MIN_RECORDING_STORAGE_QUOTA_PERCENT
        );
        seekRecordingStorageQuota.setProgress(quotaPercent - UiPrefs.MIN_RECORDING_STORAGE_QUOTA_PERCENT);
        seekRecordingStorageQuota.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = UiPrefs.clampRecordingStorageQuotaPercent(
                        UiPrefs.MIN_RECORDING_STORAGE_QUOTA_PERCENT + progress
                );
                tvRecordingStorageQuotaValue.setText(getString(
                        R.string.settings_recording_storage_limit_value,
                        value
                ));
                if (fromUser) {
                    prefs.edit().putInt(UiPrefs.KEY_RECORDING_STORAGE_QUOTA_PERCENT, value).apply();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int value = UiPrefs.clampRecordingStorageQuotaPercent(
                        UiPrefs.MIN_RECORDING_STORAGE_QUOTA_PERCENT + seekBar.getProgress()
                );
                prefs.edit().putInt(UiPrefs.KEY_RECORDING_STORAGE_QUOTA_PERCENT, value).apply();
            }
        });
        swLoopRecording.setChecked(UiPrefs.isLoopRecordingEnabled(prefs));
        swLoopRecording.setOnCheckedChangeListener((btn, checked) ->
                prefs.edit().putBoolean(UiPrefs.KEY_LOOP_RECORDING, checked).apply());
        Switch swAllowBetaUpdates = dialog.findViewById(R.id.switchAllowBetaUpdates);
        SeekBar seekOverlayHideDelay = dialog.findViewById(R.id.seekOverlayHideDelay);
        EditText etOverlayHideDelayValue = dialog.findViewById(R.id.etOverlayHideDelayValue);
        SeekBar seekOverlayMinShow = dialog.findViewById(R.id.seekOverlayMinShow);
        EditText etOverlayMinShowValue = dialog.findViewById(R.id.etOverlayMinShowValue);

        SeekBar seekCorner = dialog.findViewById(R.id.seekCornerRadius);
        EditText etCorner = dialog.findViewById(R.id.etCornerRadius);
        ImageButton dialogClose = dialog.findViewById(R.id.btnClose);
        TextView tabUpdate = dialog.findViewById(R.id.tabUpdate);
        TextView tabSettings = dialog.findViewById(R.id.tabSettings);
        TextView tabSignalCamera = dialog.findViewById(R.id.tabSignalCamera);
        TextView tabOptik = dialog.findViewById(R.id.tabOptik);
        TextView tabCredits = dialog.findViewById(R.id.tabCredits);
        TextView tabDev = dialog.findViewById(R.id.tabDev);
        View sectionUpdate = dialog.findViewById(R.id.sectionUpdate);
        View sectionSettings = dialog.findViewById(R.id.sectionSettings);
        View sectionSignalCamera = dialog.findViewById(R.id.sectionSignalCamera);
        View sectionOptik = dialog.findViewById(R.id.sectionOptik);
        View sectionCredits = dialog.findViewById(R.id.sectionCredits);
        View sectionDev = dialog.findViewById(R.id.sectionDev);
        View accentRow = dialog.findViewById(R.id.rowAccentColor);
        View accentPreview = dialog.findViewById(R.id.viewAccentPreview);
        EditText etAccentColor = dialog.findViewById(R.id.etAccentColor);
        EditText etDevDefaultPollMs = dialog.findViewById(R.id.etDevDefaultPollMs);
        EditText etDevSignalOffPollMs = dialog.findViewById(R.id.etDevSignalOffPollMs);
        Switch swDevTestVideoSources = dialog.findViewById(R.id.switchDevTestVideoSources);
        TextView tvDevTestVideoPath = dialog.findViewById(R.id.tvDevTestVideoPath);
        Button btnDevOpenTileTest = dialog.findViewById(R.id.btnDevOpenTileTest);
        Button btnDevResetDefaults = dialog.findViewById(R.id.btnDevResetDefaults);
        signalCameraSettingsController.bind(
                prefs,
                swOverlay,
                swRotateToDrivingDirection,
                seekOverlayHideDelay,
                etOverlayHideDelayValue,
                seekOverlayMinShow,
                etOverlayMinShowValue
        );
        devSettingsController.bind(
                prefs,
                etDevDefaultPollMs,
                etDevSignalOffPollMs,
                swDevTestVideoSources,
                tvDevTestVideoPath,
                btnDevOpenTileTest,
                btnDevResetDefaults
        );

        appearanceController.bindSettingsAppearance(
                prefs,
                swOverlay,
                swRotateToDrivingDirection,
                swSafetyWarning,
                swEnableCameraPopup,
                swAllowBetaUpdates,
                dialogClose,
                seekOverlayHideDelay,
                seekOverlayMinShow,
                seekCorner,
                etCorner,
                accentRow,
                accentPreview,
                etAccentColor,
                tabUpdate,
                tabSettings,
                tabSignalCamera,
                tabOptik,
                tabCredits,
                tabDev
        );

        bindSettingsTab(tabUpdate, tabSettings, tabSignalCamera, tabOptik, tabCredits, tabDev,
                sectionUpdate, sectionSettings, sectionSignalCamera, sectionOptik, sectionCredits, sectionDev, 1);
        appearanceController.reapplyForActiveTab(1);
        tabUpdate.setOnClickListener(v -> {
            bindSettingsTab(tabUpdate, tabSettings, tabSignalCamera, tabOptik, tabCredits, tabDev,
                    sectionUpdate, sectionSettings, sectionSignalCamera, sectionOptik, sectionCredits, sectionDev, 0);
            appearanceController.reapplyForActiveTab(0);
        });
        tabSettings.setOnClickListener(v -> {
            bindSettingsTab(tabUpdate, tabSettings, tabSignalCamera, tabOptik, tabCredits, tabDev,
                    sectionUpdate, sectionSettings, sectionSignalCamera, sectionOptik, sectionCredits, sectionDev, 1);
            appearanceController.reapplyForActiveTab(1);
        });
        tabSignalCamera.setOnClickListener(v -> {
            bindSettingsTab(tabUpdate, tabSettings, tabSignalCamera, tabOptik, tabCredits, tabDev,
                    sectionUpdate, sectionSettings, sectionSignalCamera, sectionOptik, sectionCredits, sectionDev, 2);
            appearanceController.reapplyForActiveTab(2);
        });
        tabOptik.setOnClickListener(v -> {
            bindSettingsTab(tabUpdate, tabSettings, tabSignalCamera, tabOptik, tabCredits, tabDev,
                    sectionUpdate, sectionSettings, sectionSignalCamera, sectionOptik, sectionCredits, sectionDev, 3);
            appearanceController.reapplyForActiveTab(3);
        });
        tabCredits.setOnClickListener(v -> {
            bindSettingsTab(tabUpdate, tabSettings, tabSignalCamera, tabOptik, tabCredits, tabDev,
                    sectionUpdate, sectionSettings, sectionSignalCamera, sectionOptik, sectionCredits, sectionDev, 4);
            appearanceController.reapplyForActiveTab(4);
        });
        tabDev.setOnClickListener(v -> {
            bindSettingsTab(tabUpdate, tabSettings, tabSignalCamera, tabOptik, tabCredits, tabDev,
                    sectionUpdate, sectionSettings, sectionSignalCamera, sectionOptik, sectionCredits, sectionDev, 5);
            appearanceController.reapplyForActiveTab(5);
        });

        TextView tvVersion = dialog.findViewById(R.id.tvDialogVersion);
        TextView tvBeta = dialog.findViewById(R.id.tvDialogVersionBeta);
        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            tvVersion.setText(getString(R.string.settings_version_format, version));
        } catch (Exception ignored) {
            tvVersion.setText(R.string.settings_version_unknown);
        }
        tvBeta.setVisibility(BuildConfig.IS_BETA ? View.VISIBLE : View.GONE);
        final int[] versionTapCount = {0};
        tvVersion.setOnClickListener(v -> {
            versionTapCount[0]++;
            if (versionTapCount[0] < 5) return;
            versionTapCount[0] = 0;
            tabDev.setVisibility(View.VISIBLE);
            bindSettingsTab(tabUpdate, tabSettings, tabSignalCamera, tabOptik, tabCredits, tabDev,
                    sectionUpdate, sectionSettings, sectionSignalCamera, sectionOptik, sectionCredits, sectionDev, 5);
            appearanceController.reapplyForActiveTab(5);
            Toast.makeText(this, R.string.settings_dev_unlocked, Toast.LENGTH_SHORT).show();
        });

        otaController.setup(
                dialog,
                dialog.findViewById(R.id.tvDialogUpdateTag),
                dialog.findViewById(R.id.switchAllowBetaUpdates),
                dialog.findViewById(R.id.tvUpdateReleaseTitle),
                dialog.findViewById(R.id.tvUpdateChannelStatus),
                dialog.findViewById(R.id.tvUpdateChangelog),
                dialog.findViewById(R.id.tvUpdateSourceGithub)
        );

        dialogClose.setOnClickListener(v -> dialog.dismiss());
        dialog.setOnDismissListener(d -> {
            sSettingsDialogOpen = false;
            appearanceController.applyMainUiIconColors();
            SignalService.requestRecheck();
        });
        dialog.show();

        Window shownWindow = dialog.getWindow();
        if (shownWindow != null) {
            float density = getResources().getDisplayMetrics().density;
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            int maxWidth = (int) (Math.min(metrics.widthPixels, 700 * density));
            int maxHeight = (int) (metrics.heightPixels * 0.92f);
            shownWindow.setLayout(maxWidth, Math.min((int) (560 * density), maxHeight));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY_PERMISSION) {
            if (Settings.canDrawOverlays(this) && UiPrefs.isCameraPopupEnabled(UiPrefs.getPrefs(this))) {
                startPopupOverlay();
            }
            return;
        }
        if (requestCode != REQ_RECORDING_FOLDER || resultCode != RESULT_OK || data == null) return;
        Uri treeUri = data.getData();
        if (treeUri == null) return;
        int takeFlags = data.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(treeUri, takeFlags);
            RecordingStorageManager.setTreeUri(this, treeUri);
            refreshRecordingPathLabel(recordingPathValueView);
            Toast.makeText(this, R.string.settings_records_path_selected, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, R.string.settings_records_path_selection_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void openRecordingFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_RECORDING_FOLDER);
        } catch (Throwable t) {
            Toast.makeText(this, R.string.settings_records_path_selection_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void openRecordingFolder() {
        Uri treeUri = RecordingStorageManager.getTreeUri(this);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        if (treeUri != null) {
            intent.setData(treeUri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        } else {
            Uri defaultUri = DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:Download/mg4_cam_records"
            );
            intent.setDataAndType(defaultUri, DocumentsContract.Document.MIME_TYPE_DIR);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }

        try {
            startActivity(Intent.createChooser(intent, getString(R.string.settings_open_recording_folder)));
        } catch (Throwable firstFailure) {
            try {
                Intent downloads = new Intent(Intent.ACTION_VIEW);
                downloads.setDataAndType(
                        DocumentsContract.buildRootUri("com.android.externalstorage.documents", "primary"),
                        DocumentsContract.Root.MIME_TYPE_ITEM
                );
                startActivity(Intent.createChooser(downloads, getString(R.string.settings_open_recording_folder)));
            } catch (Throwable ignored) {
                Toast.makeText(this, R.string.settings_records_path_open_failed, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void refreshRecordingPathLabel(TextView tv) {
        if (tv == null) return;
        tv.setText(RecordingStorageManager.getDisplayPath(this));
    }

    private void bindSettingsTab(
            TextView tabUpdate, TextView tabSettings, TextView tabSignalCamera, TextView tabOptik, TextView tabCredits, TextView tabDev,
            View sectionUpdate, View sectionSettings, View sectionSignalCamera, View sectionOptik, View sectionCredits, View sectionDev,
            int active
    ) {
        sectionUpdate.setVisibility(active == 0 ? View.VISIBLE : View.GONE);
        sectionSettings.setVisibility(active == 1 ? View.VISIBLE : View.GONE);
        sectionSignalCamera.setVisibility(active == 2 ? View.VISIBLE : View.GONE);
        sectionOptik.setVisibility(active == 3 ? View.VISIBLE : View.GONE);
        sectionCredits.setVisibility(active == 4 ? View.VISIBLE : View.GONE);
        sectionDev.setVisibility(active == 5 ? View.VISIBLE : View.GONE);
        styleSettingsTab(tabUpdate, active == 0);
        styleSettingsTab(tabSettings, active == 1);
        styleSettingsTab(tabSignalCamera, active == 2);
        styleSettingsTab(tabOptik, active == 3);
        styleSettingsTab(tabCredits, active == 4);
        styleSettingsTab(tabDev, active == 5);
    }

    private void styleSettingsTab(TextView tab, boolean active) {
        appearanceController.styleSettingsTab(tab, active);
    }

    private void toggleRecording() {
        if (!UiPrefs.isRecordingButtonEnabled(UiPrefs.getPrefs(this))) {
            return;
        }
        if (RecordingService.isRecording(this)) {
            RecordingService.stopRecording(this);
        } else {
            RecordingService.startRecording(this);
        }
        syncRecordingUi();
    }

    private void resumeRecordingIfNeeded() {
        SharedPreferences prefs = UiPrefs.getPrefs(this);
        if (UiPrefs.getRecordingStartedAtMs(prefs) <= 0L) {
            return;
        }
        if (RecordingService.isRecording(this)) {
            return;
        }
        RecordingService.startIfNeeded(this);
    }

    private void syncRecordingUi() {
        if (btnRecording == null) return;
        boolean enabled = UiPrefs.isRecordingButtonEnabled(UiPrefs.getPrefs(this));
        boolean recording = RecordingService.isRecording(this);
        btnRecording.setVisibility(enabled ? View.VISIBLE : View.GONE);
        btnRecording.setImageTintList(android.content.res.ColorStateList.valueOf(
                recording ? 0xFFFF3B30 : 0xFFFFFFFF
        ));
        if (recording) {
            if (tvRecordingTimer != null) tvRecordingTimer.setVisibility(View.VISIBLE);
            recordingTimerHandler.removeCallbacks(recordingTimerTick);
            recordingTimerHandler.post(recordingTimerTick);
        } else {
            if (tvRecordingTimer != null) tvRecordingTimer.setVisibility(View.GONE);
            recordingTimerHandler.removeCallbacks(recordingTimerTick);
        }
    }

    private void updateRecordingTimer() {
        if (tvRecordingTimer == null) return;
        if (!RecordingService.isRecording(this)) {
            tvRecordingTimer.setVisibility(View.GONE);
            tvRecordingTimer.setText("");
            return;
        }
        long startedAt = UiPrefs.getRecordingStartedAtMs(UiPrefs.getPrefs(this));
        if (startedAt <= 0L) {
            tvRecordingTimer.setVisibility(View.GONE);
            tvRecordingTimer.setText("");
            return;
        }
        long elapsedMs = Math.max(0L, SystemClock.elapsedRealtime() - startedAt);
        long totalSeconds = elapsedMs / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        tvRecordingTimer.setText(String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds));
        tvRecordingTimer.setVisibility(View.VISIBLE);
    }

    // -------------------------------------------------------------------------
    // Warning banner
    // -------------------------------------------------------------------------

    private void applyWarningVisibility() {
        boolean show = getSharedPreferences(AVM_PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_SAFETY_WARNING, true);
        int visibility = show ? View.VISIBLE : View.GONE;
        View bg = findViewById(R.id.bg_tishi);
        View banner = findViewById(R.id.warningBanner);
        if (bg != null) bg.setVisibility(visibility);
        if (banner != null) banner.setVisibility(visibility);
    }

    private void applyCameraPopupVisibility() {
        if (btnCameraPopup == null) return;
        boolean show = UiPrefs.isCameraPopupEnabled(UiPrefs.getPrefs(this));
        btnCameraPopup.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void startPopupOverlay() {
        if (OverlayService.isPopupVisible()) {
            finishMainAfterPopupRequested();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            try {
                startActivityForResult(intent, REQ_OVERLAY_PERMISSION);
            } catch (Throwable t) {
                Toast.makeText(this, R.string.settings_overlay_permission_required, Toast.LENGTH_LONG).show();
            }
            return;
        }
        try {
            ContextCompat.registerReceiver(
                    this,
                    popupReadyReceiver,
                    new IntentFilter(OverlayService.ACTION_POPUP_READY),
                    ContextCompat.RECEIVER_NOT_EXPORTED
            );
        } catch (Throwable ignored) {
        }
        OverlayService.showPopup(this, currentVideoIndex);
        finishMainAfterPopupRequested();
    }

    private void finishMainAfterPopupRequested() {
        try {
            unregisterReceiver(popupReadyReceiver);
        } catch (Throwable ignored) {
        }
        finishAndRemoveTask();
    }

    static void requestAppVisibility(Context context) {
        if (context == null) return;
        Intent intent = new Intent(ACTION_SHOW_APP);
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent);
    }

    // -------------------------------------------------------------------------
    // Camera preview
    // -------------------------------------------------------------------------

    private void startPreviewIfReady() {
        if (surfaceHolder == null || surfaceHolder.getSurface() == null ||
                !surfaceHolder.getSurface().isValid()) {
            if (tvStatus != null) tvStatus.setText(R.string.main_surface_not_ready);
            return;
        }
        stopPreview();
        boolean ok = PreviewSourceController.start(
                this,
                currentVideoIndex,
                surfaceHolder.getSurface(),
                testVideoPlayer,
                syntheticTestPreview
        );
        testPreviewRunning = ok && TestVideoSources.shouldUse(this);
        previewRunning = ok && !testPreviewRunning;
        mainPreviewOwnsSurface = ok;
        if (tvStatus != null) {
            tvStatus.setText(ok
                    ? getString(R.string.main_preview_status, cameraLabel(currentVideoIndex))
                    : getString(R.string.main_preview_stopped));
        }
    }

    private void stopPreview() {
        if (mainPreviewOwnsSurface || previewRunning || testPreviewRunning) {
            PreviewSourceController.stopSurface(
                    testVideoPlayer,
                    surfaceHolder != null ? surfaceHolder.getSurface() : null
            );
            syntheticTestPreview.stop();
        }
        testPreviewRunning = false;
        previewRunning = false;
        mainPreviewOwnsSurface = false;
        if (tvStatus != null) tvStatus.setText(R.string.main_preview_stopped);
    }

    @Override public void surfaceCreated(SurfaceHolder holder) { startPreviewIfReady(); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
    @Override public void surfaceDestroyed(SurfaceHolder holder) { stopPreview(); }

    private String cameraLabel(int videoIndex) {
        switch (videoIndex) {
            case 14: return getString(R.string.main_camera_label_right);
            case 15: return getString(R.string.main_camera_label_front);
            case 16: return getString(R.string.main_camera_label_left);
            case 17: return getString(R.string.main_camera_label_rear);
            default: return getString(R.string.main_camera_label_unknown, videoIndex);
        }
    }
}
