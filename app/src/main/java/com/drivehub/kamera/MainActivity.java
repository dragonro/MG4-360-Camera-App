package com.drivehub.kamera;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.app.Dialog;
import android.database.Cursor;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.ImageButton;
import android.widget.Switch;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Environment;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback {

    private static final String AVM_PREFS_NAME = "AVM_Settings";
    private static final String KEY_SAFETY_WARNING = "ShowSafetyWarning";

    private SurfaceHolder surfaceHolder;
    private TextView tvStatus;
    // Initial camera when the app opens: front camera (v15)
    private int currentVideoIndex = 15;
    private boolean previewRunning = false;

    // Swipe detection threshold in pixels
    private static final int SWIPE_THRESHOLD_PX = 140;
    private float downX = 0f;
    private float downY = 0f;
    private static volatile boolean sMainVisible = false;
    private static volatile boolean sSettingsDialogOpen = false;
    private OtaUpdateManager.UpdateInfo lastOtaUpdateInfo;
    private final Handler otaProgressHandler = new Handler(Looper.getMainLooper());
    private Runnable otaProgressRunnable;
    private Dialog otaProgressDialog;
    private long otaVerificationDownloadId = -1L;
    private boolean otaVerificationInFlight = false;
    private boolean otaVerificationPassed = false;
    private OtaUpdateManager.UpdateInfo activeOtaDownloadInfo;

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

    public static boolean isMainVisible() {
        return sMainVisible;
    }

    public static boolean shouldBlockOverlay() {
        return sMainVisible && !sSettingsDialogOpen;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        ImageButton btnSettings = findViewById(R.id.btnSettings);

        btnSettings.setOnClickListener(v -> showSettingsDialog());

        ImageButton btnClose = findViewById(R.id.btnClose);
        btnClose.setOnClickListener(v -> finishAndRemoveTask());

        // Show the initial status label.
        if (tvStatus != null) {
            tvStatus.setText(getString(R.string.main_preview_status, cameraLabel(currentVideoIndex)));
        }
        applyWarningVisibility();

        // Keep signal/gear listening always active; overlay visibility is controlled only by settings.
        try {
            SignalService.start(this);
        } catch (Throwable ignored) {
        }

        // Change cameras with swipe gestures.
        surfaceView.setOnTouchListener((v, event) -> {
            if (event == null) return false;

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    return true;
                case MotionEvent.ACTION_UP:
                    float upX = event.getX();
                    float upY = event.getY();
                    float dx = upX - downX;
                    float dy = upY - downY;

                    // Decide based on the dominant axis:
                    // - Horizontal: left -> v16, right -> v14
                    // - Vertical: up -> v15, down -> v17
                    if (Math.abs(dx) > Math.abs(dy)) {
                        // horizontal
                        if (dx > SWIPE_THRESHOLD_PX) {
                            currentVideoIndex = 14; // right camera
                        } else if (dx < -SWIPE_THRESHOLD_PX) {
                            currentVideoIndex = 16; // left camera
                        } else {
                            return true;
                        }
                    } else {
                        // vertical
                        if (dy < -SWIPE_THRESHOLD_PX) {
                            currentVideoIndex = 15; // front camera
                        } else if (dy > SWIPE_THRESHOLD_PX) {
                            currentVideoIndex = 17; // rear camera
                        } else {
                            return true;
                        }
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

    @Override
    protected void onStart() {
        super.onStart();
        sMainVisible = true;
        try {
            IntentFilter f = new IntentFilter(SignalService.ACTION_ROUTE_CAMERA);
            ContextCompat.registerReceiver(
                    this,
                    cameraRouteReceiver,
                    f,
                    ContextCompat.RECEIVER_NOT_EXPORTED
            );
        } catch (Throwable ignored) {
        }
        // Do not show the overlay while Main is open.
        OverlayService.hideOverlay(this);
        applyWarningVisibility();
    }

    @Override
    protected void onStop() {
        super.onStop();
        sMainVisible = false;
        sSettingsDialogOpen = false;
        stopOtaProgressWatcher();
        if (otaProgressDialog != null && otaProgressDialog.isShowing()) {
            otaProgressDialog.dismiss();
        }
        try {
            unregisterReceiver(cameraRouteReceiver);
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private void showSettingsDialog() {
        sSettingsDialogOpen = true;
        lastOtaUpdateInfo = null;
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
        Switch swSafetyWarning = dialog.findViewById(R.id.switchSafetyWarning);
        TextView tabSettings = dialog.findViewById(R.id.tabSettings);
        TextView tabOptik = dialog.findViewById(R.id.tabOptik);
        TextView tabCredits = dialog.findViewById(R.id.tabCredits);
        View sectionSettings = dialog.findViewById(R.id.sectionSettings);
        View sectionOptik = dialog.findViewById(R.id.sectionOptik);
        View sectionCredits = dialog.findViewById(R.id.sectionCredits);

        swOverlay.setChecked(prefs.getBoolean("overlayOnSignal", false));
        swOverlay.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("overlayOnSignal", isChecked).apply();
            if (!isChecked) {
                OverlayService.hideOverlay(MainActivity.this);
            }
        });

        swSafetyWarning.setChecked(avmPrefs.getBoolean(KEY_SAFETY_WARNING, true));
        swSafetyWarning.setOnCheckedChangeListener((buttonView, isChecked) -> {
            avmPrefs.edit().putBoolean(KEY_SAFETY_WARNING, isChecked).apply();
            applyWarningVisibility();
        });

        SeekBar seekCorner = dialog.findViewById(R.id.seekCornerRadius);
        EditText etCorner = dialog.findViewById(R.id.etCornerRadius);
        int savedRadius = UiPrefs.getTileCornerRadiusSetting(prefs);
        seekCorner.setMax(UiPrefs.MAX_TILE_CORNER_RADIUS);
        seekCorner.setProgress(savedRadius);
        etCorner.setText(String.valueOf(savedRadius));

        seekCorner.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                prefs.edit().putInt(UiPrefs.KEY_TILE_CORNER_RADIUS, progress).apply();
                if (fromUser) {
                    etCorner.setText(String.valueOf(progress));
                    etCorner.setSelection(etCorner.getText().length());
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        etCorner.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (s.length() == 0) return;
                try {
                    int value = Math.min(UiPrefs.MAX_TILE_CORNER_RADIUS, Math.max(0, Integer.parseInt(s.toString())));
                    String normalized = String.valueOf(value);
                    if (!normalized.contentEquals(s)) {
                        etCorner.setText(normalized);
                        etCorner.setSelection(etCorner.getText().length());
                        return;
                    }
                    prefs.edit().putInt(UiPrefs.KEY_TILE_CORNER_RADIUS, value).apply();
                    if (seekCorner.getProgress() != value) {
                        seekCorner.setProgress(value);
                    }
                } catch (NumberFormatException ignored) {}
            }
        });

        bindSettingsTab(tabSettings, tabOptik, tabCredits, sectionSettings, sectionOptik, sectionCredits, 0);
        tabSettings.setOnClickListener(v ->
                bindSettingsTab(tabSettings, tabOptik, tabCredits, sectionSettings, sectionOptik, sectionCredits, 0));
        tabOptik.setOnClickListener(v ->
                bindSettingsTab(tabSettings, tabOptik, tabCredits, sectionSettings, sectionOptik, sectionCredits, 1));
        tabCredits.setOnClickListener(v ->
                bindSettingsTab(tabSettings, tabOptik, tabCredits, sectionSettings, sectionOptik, sectionCredits, 2));

        TextView tvDialogVersion = dialog.findViewById(R.id.tvDialogVersion);
        TextView tvDialogVersionBeta = dialog.findViewById(R.id.tvDialogVersionBeta);
        TextView tvDialogUpdateTag = dialog.findViewById(R.id.tvDialogUpdateTag);
        try {
            String version = getPackageManager()
                    .getPackageInfo(getPackageName(), 0)
                    .versionName;
            tvDialogVersion.setText(getString(R.string.settings_version_format, version));
        } catch (Exception e) {
            tvDialogVersion.setText(R.string.settings_version_unknown);
        }
        tvDialogVersionBeta.setVisibility(BuildConfig.IS_BETA ? View.VISIBLE : View.GONE);
        setupOtaUpdateTag(dialog, tvDialogUpdateTag);

        dialog.findViewById(R.id.btnClose).setOnClickListener(v -> dialog.dismiss());
        dialog.setOnDismissListener(d -> {
            sSettingsDialogOpen = false;
            SignalService.requestRecheck();
        });
        dialog.show();

        Window shownWindow = dialog.getWindow();
        if (shownWindow != null) {
            float density = getResources().getDisplayMetrics().density;
            shownWindow.setLayout((int) (700 * density), (int) (560 * density));
        }
    }

    private void setupOtaUpdateTag(Dialog dialog, TextView updateTag) {
        if (updateTag == null) return;
        renderOtaTagState(updateTag, null, true);
        updateTag.setClickable(true);
        updateTag.setFocusable(true);
        updateTag.setOnClickListener(v -> {
            OtaUpdateManager.UpdateInfo info =
                    (OtaUpdateManager.UpdateInfo) updateTag.getTag(R.id.tag_ota_update_info);
            if (info == null) {
                info = lastOtaUpdateInfo;
            }
            if (info != null && info.success && info.updateAvailable) {
                maybeStartOtaDownload(info);
                return;
            }
            showOtaRefreshDialog(dialog, updateTag);
        });
        triggerOtaCheck(dialog, updateTag, false);
    }

    private void maybeStartOtaDownload(OtaUpdateManager.UpdateInfo info) {
        if (info == null || info.expectedSha256 == null || info.expectedSha256.trim().isEmpty()) {
            OtaDialogs.showMessageDialog(
                    this,
                    info != null && info.message != null && !info.message.trim().isEmpty()
                            ? info.message
                            : getString(R.string.ota_error_no_hash)
            );
            return;
        }
        NetworkStateHelper.Transport transport = NetworkStateHelper.getActiveTransport(this);
        if (transport == NetworkStateHelper.Transport.CELLULAR) {
            OtaDialogs.showConfirmDialog(
                    this,
                    getString(R.string.ota_dialog_mobile_warning_message, info.latestVersion),
                    getString(R.string.ota_action_download_anyway),
                    () -> startOtaDownload(info)
            );
            return;
        }
        startOtaDownload(info);
    }

    private void startOtaDownload(OtaUpdateManager.UpdateInfo info) {
        try {
            long downloadId = OtaUpdateManager.enqueueDownload(MainActivity.this, info);
            activeOtaDownloadInfo = info;
            showOtaProgressDialog(info, downloadId);
        } catch (Throwable t) {
            OtaDialogs.showMessageDialog(
                    this,
                    getString(R.string.ota_dialog_download_failed_message, t.getClass().getSimpleName())
            );
        }
    }

    private void showOtaProgressDialog(OtaUpdateManager.UpdateInfo info, long downloadId) {
        stopOtaProgressWatcher();
        OtaDialogs.ProgressDialogHandle handle = OtaDialogs.showProgressDialog(
                this,
                getString(R.string.ota_dialog_download_started_message, info.latestVersion),
                this::openDownloadsFolder,
                () -> installDownloadedUpdate(downloadId)
        );
        otaProgressDialog = handle.dialog;
        otaProgressDialog.setOnDismissListener(d -> stopOtaProgressWatcher());
        otaVerificationDownloadId = -1L;
        otaVerificationInFlight = false;
        otaVerificationPassed = false;

        final TextView finalTvStatus = handle.statusView;
        final android.widget.ProgressBar finalProgressBar = handle.progressBar;
        final View finalInstallButton = handle.installButton;
        otaProgressRunnable = new Runnable() {
            @Override
            public void run() {
                boolean shouldContinue = updateOtaProgress(downloadId, finalProgressBar, finalTvStatus, finalInstallButton);
                if (shouldContinue && otaProgressDialog != null && otaProgressDialog.isShowing()) {
                    otaProgressHandler.postDelayed(this, 500L);
                }
            }
        };
        otaProgressHandler.post(otaProgressRunnable);
    }

    private boolean updateOtaProgress(long downloadId, android.widget.ProgressBar progressBar, TextView statusView, View installButton) {
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        if (dm == null) {
            if (statusView != null) {
                statusView.setText(R.string.ota_progress_unavailable);
            }
            if (installButton != null) installButton.setVisibility(View.GONE);
            return false;
        }

        DownloadManager.Query query = new DownloadManager.Query().setFilterById(downloadId);
        try (Cursor cursor = dm.query(query)) {
            if (cursor == null || !cursor.moveToFirst()) {
                if (statusView != null) {
                    statusView.setText(R.string.ota_progress_missing);
                }
                if (installButton != null) installButton.setVisibility(View.GONE);
                return false;
            }

            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            long downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            long total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            int progress = (total > 0L) ? (int) ((downloaded * 100L) / total) : 0;

            if (progressBar != null) {
                if (total > 0L) {
                    progressBar.setIndeterminate(false);
                    progressBar.setMax(100);
                    progressBar.setProgress(Math.max(0, Math.min(100, progress)));
                } else {
                    progressBar.setIndeterminate(true);
                }
            }

            if (statusView != null) {
                statusView.setText(getOtaProgressStatusText(status, progress, downloaded, total));
            }
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                if (installButton != null) {
                    installButton.setVisibility(otaVerificationPassed ? View.VISIBLE : View.GONE);
                }
                if (progressBar != null) {
                    progressBar.setIndeterminate(false);
                    progressBar.setMax(100);
                    progressBar.setProgress(100);
                }
                if (otaVerificationPassed) {
                    return false;
                }
                if (otaVerificationInFlight && otaVerificationDownloadId == downloadId) {
                    if (statusView != null) {
                        statusView.setText(R.string.ota_progress_verifying);
                    }
                    return true;
                }
                startOtaIntegrityVerification(downloadId, statusView, installButton);
                return true;
            } else {
                if (installButton != null) {
                    installButton.setVisibility(View.GONE);
                }
            }

            return status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PAUSED;
        } catch (Throwable t) {
            if (statusView != null) {
                statusView.setText(getString(R.string.ota_progress_failed_reason, t.getClass().getSimpleName()));
            }
            if (installButton != null) installButton.setVisibility(View.GONE);
            return false;
        }
    }

    private void startOtaIntegrityVerification(long downloadId, TextView statusView, View installButton) throws Exception {
        if (otaVerificationInFlight && otaVerificationDownloadId == downloadId) {
            return;
        }
        otaVerificationDownloadId = downloadId;
        otaVerificationInFlight = true;
        otaVerificationPassed = false;
        if (installButton != null) {
            installButton.setVisibility(View.GONE);
        }
        if (statusView != null) {
            statusView.setText(R.string.ota_progress_verifying);
        }

        File apkFile = resolveDownloadedApkFile(downloadId);
        OtaUpdateManager.verifyDownloadedApk(apkFile, activeOtaDownloadInfo, (success, computedSha256, message) -> {
            if (downloadId != otaVerificationDownloadId) {
                return;
            }
            otaVerificationInFlight = false;
            otaVerificationPassed = success;
            if (statusView != null) {
                if (success) {
                    statusView.setText(R.string.ota_progress_verified);
                } else {
                    statusView.setText(getString(R.string.ota_progress_integrity_failed, message));
                }
            }
            if (installButton != null) {
                installButton.setVisibility(success ? View.VISIBLE : View.GONE);
            }
        });
    }

    private void installDownloadedUpdate(long downloadId) {
        try {
            File apkFile = resolveDownloadedApkFile(downloadId);
            if (apkFile == null || !apkFile.exists()) {
                throw new IllegalStateException("Downloaded APK not found");
            }

            Uri apkUri = FileProvider.getUriForFile(
                    this,
                    BuildConfig.APPLICATION_ID + ".fileprovider",
                    apkFile
            );

            Intent installIntent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            installIntent.setData(apkUri);
            installIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            installIntent.putExtra(Intent.EXTRA_RETURN_RESULT, false);

            PackageManager pm = getPackageManager();
            if (pm != null && installIntent.resolveActivity(pm) != null) {
                startActivity(installIntent);
                return;
            }

            Intent fallbackIntent = new Intent(Intent.ACTION_VIEW);
            fallbackIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            fallbackIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(fallbackIntent);
        } catch (Exception t) {
            OtaDialogs.showMessageDialog(
                    this,
                    getString(R.string.ota_dialog_install_failed_message, t.getClass().getSimpleName())
            );
        }
    }

    private File resolveDownloadedApkFile(long downloadId) throws Exception {
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        if (dm == null) return null;
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(downloadId);
        try (Cursor cursor = dm.query(query)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }
            String localUri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));
            if (localUri == null || localUri.isEmpty()) {
                return null;
            }
            Uri uri = Uri.parse(localUri);
            if (!"file".equalsIgnoreCase(uri.getScheme())) {
                return null;
            }
            return new File(uri.getPath());
        }
    }

    private CharSequence getOtaProgressStatusText(int status, int progress, long downloaded, long total) {
        if (status == DownloadManager.STATUS_SUCCESSFUL) {
            return getString(R.string.ota_progress_complete);
        }
        if (status == DownloadManager.STATUS_FAILED) {
            return getString(R.string.ota_progress_failed);
        }
        if (status == DownloadManager.STATUS_PAUSED) {
            return getString(R.string.ota_progress_paused, progress);
        }
        if (status == DownloadManager.STATUS_PENDING) {
            return getString(R.string.ota_progress_pending);
        }
        if (total > 0L) {
            return getString(
                    R.string.ota_progress_downloading,
                    progress,
                    formatBytes(downloaded),
                    formatBytes(total)
            );
        }
        return getString(R.string.ota_progress_running_unknown, formatBytes(downloaded));
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024.0) return String.format(java.util.Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024.0) return String.format(java.util.Locale.US, "%.1f MB", mb);
        double gb = mb / 1024.0;
        return String.format(java.util.Locale.US, "%.2f GB", gb);
    }

    private void stopOtaProgressWatcher() {
        if (otaProgressRunnable != null) {
            otaProgressHandler.removeCallbacks(otaProgressRunnable);
            otaProgressRunnable = null;
        }
        otaVerificationDownloadId = -1L;
        otaVerificationInFlight = false;
        otaVerificationPassed = false;
        activeOtaDownloadInfo = null;
        otaProgressDialog = null;
    }

    private void openDownloadsFolder() {
        Intent downloadsIntent = new Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS);
        downloadsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(downloadsIntent);
        } catch (Throwable t) {
            Toast.makeText(this, R.string.ota_toast_open_downloads_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void showOtaRefreshDialog(Dialog parentDialog, TextView updateTag) {
        OtaDialogs.showRefreshDialog(
                this,
                lastOtaUpdateInfo,
                () -> triggerOtaCheck(parentDialog, updateTag, true)
        );
    }

    private void triggerOtaCheck(Dialog dialog, TextView updateTag, boolean showToast) {
        if (showToast) {
            Toast.makeText(this, R.string.ota_toast_checking, Toast.LENGTH_SHORT).show();
        }
        renderOtaTagState(updateTag, null, true);
        OtaUpdateManager.checkForUpdates(this, info -> {
            lastOtaUpdateInfo = info;
            if (dialog == null || !dialog.isShowing()) return;
            renderOtaTagState(updateTag, info, false);
        });
    }

    private void renderOtaTagState(TextView updateTag, OtaUpdateManager.UpdateInfo info, boolean checking) {
        if (updateTag == null) return;
        updateTag.setTag(R.id.tag_ota_update_info, info);
        if (checking) {
            updateTag.setText(R.string.ota_status_checking);
            updateTag.setTextColor(ContextCompat.getColor(this, R.color.settings_update_error_text));
            updateTag.setBackgroundResource(R.drawable.bg_settings_footer_tag_neutral);
            return;
        }
        if (info != null && info.success && info.updateAvailable) {
            updateTag.setText(getString(R.string.ota_status_update_available, info.latestVersion));
            updateTag.setTextColor(ContextCompat.getColor(this, R.color.settings_update_available_text));
            updateTag.setBackgroundResource(R.drawable.bg_settings_footer_tag_update);
            return;
        }
        if (info != null && info.success) {
            updateTag.setText(R.string.ota_status_up_to_date);
            updateTag.setTextColor(ContextCompat.getColor(this, R.color.settings_update_ok_text));
            updateTag.setBackgroundResource(R.drawable.bg_settings_footer_tag_ok);
            return;
        }
        updateTag.setText(R.string.ota_status_check_failed);
        updateTag.setTextColor(ContextCompat.getColor(this, R.color.settings_update_error_text));
        updateTag.setBackgroundResource(R.drawable.bg_settings_footer_tag_neutral);
    }

    private void bindSettingsTab(
            TextView tabSettings, TextView tabOptik, TextView tabCredits,
            View sectionSettings, View sectionOptik, View sectionCredits,
            int active
    ) {
        sectionSettings.setVisibility(active == 0 ? View.VISIBLE : View.GONE);
        sectionOptik.setVisibility(active == 1 ? View.VISIBLE : View.GONE);
        sectionCredits.setVisibility(active == 2 ? View.VISIBLE : View.GONE);
        styleSettingsTab(tabSettings, active == 0);
        styleSettingsTab(tabOptik, active == 1);
        styleSettingsTab(tabCredits, active == 2);
    }

    private void styleSettingsTab(TextView tab, boolean active) {
        tab.setTextColor(active ? 0xFFFFFFFF : 0xFF777777);
        tab.setTextSize(20f);
        tab.setTypeface(tab.getTypeface(), android.graphics.Typeface.BOLD);
    }

    private void applyWarningVisibility() {
        boolean show = getSharedPreferences(AVM_PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_SAFETY_WARNING, true);
        int visibility = show ? View.VISIBLE : View.GONE;
        View bg = findViewById(R.id.bg_tishi);
        View banner = findViewById(R.id.warningBanner);
        if (bg != null) bg.setVisibility(visibility);
        if (banner != null) banner.setVisibility(visibility);
    }

    private void startPreviewIfReady() {
        if (surfaceHolder == null || surfaceHolder.getSurface() == null ||
                !surfaceHolder.getSurface().isValid()) {
            if (tvStatus != null) tvStatus.setText(R.string.main_surface_not_ready);
            return;
        }
        stopPreview();
        boolean ok = CameraProbe.startPreview(currentVideoIndex, surfaceHolder.getSurface());
        previewRunning = ok;
        if (tvStatus != null) {
            tvStatus.setText(ok
                    ? getString(R.string.main_preview_status, cameraLabel(currentVideoIndex))
                    : getString(R.string.main_preview_stopped));
        }
    }

    private void stopPreview() {
        if (previewRunning) {
            CameraProbe.stopPreview();
            previewRunning = false;
            if (tvStatus != null) tvStatus.setText(R.string.main_preview_stopped);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        sMainVisible = false;
        stopOtaProgressWatcher();
        stopPreview();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        // Start the initial camera as soon as the surface is ready.
        startPreviewIfReady();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // Restart here if needed.
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopPreview();
    }

    private String cameraLabel(int videoIndex) {
        switch (videoIndex) {
            case 14:
                return getString(R.string.main_camera_label_right);
            case 15:
                return getString(R.string.main_camera_label_front);
            case 16:
                return getString(R.string.main_camera_label_left);
            case 17:
                return getString(R.string.main_camera_label_rear);
            default:
                return getString(R.string.main_camera_label_unknown, videoIndex);
        }
    }
}
