package com.drivehub.kamera;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.view.View;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.ImageButton;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import android.content.pm.PackageManager;
public class SettingsActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "rec_prefs";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_SEGMENT_MIN = "segmentMin";
    private static final String KEY_TOTAL_MIN = "totalMin";
    private static final String KEY_OVERLAY_ON_SIGNAL = "overlayOnSignal";
    private static final String KEY_KILL_OEM_ON_REVERSE = "killOemOnReverse";
    private static final String KEY_OVERLAY_HIDE_DELAY_MS = "overlayHideDelayMs";
    private static final String OEM_AVM_PACKAGE = "com.saicmotor.hmi.aroundview";

    private static final int REQ_STORAGE = 1337;
    private static final int REQ_RECORDING_FOLDER = 1338;

    private SwitchCompat swEnabled;
    private SwitchCompat swOverlayOnSignal;
    private SwitchCompat swKillOemOnReverse;
    private TextView tvSegment;
    private TextView tvTotal;
    private TextView tvPath;
    private TextView tvPathValue;
    private EditText etSegmentMin;
    private EditText etTotalMin;
    private EditText etOverlayHideDelaySeconds;
    private Button btnExportUsb;

    private boolean isNormalizingOverlayDelay = false;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateUsbButtonVisibility();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        ImageButton btnBack = findViewById(R.id.btnBackSettings);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> finish());
        }

        swEnabled = findViewById(R.id.swRecordEnabled);
        swOverlayOnSignal = findViewById(R.id.swOverlayOnSignal);
        swKillOemOnReverse = findViewById(R.id.swKillOemOnReverse);
        etOverlayHideDelaySeconds = findViewById(R.id.etOverlayHideDelaySeconds);
        tvSegment = findViewById(R.id.tvSegment);
        etSegmentMin = findViewById(R.id.etSegmentMin);
        tvTotal = findViewById(R.id.tvTotal);
        etTotalMin = findViewById(R.id.etTotalMin);
        btnExportUsb = findViewById(R.id.btnExportUsb);
        tvPath = findViewById(R.id.tvRecordsPath);
        tvPathValue = findViewById(R.id.tvRecordsPathValue);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean enabled = prefs.getBoolean(KEY_ENABLED, false);
        boolean overlayOnSignal = prefs.getBoolean(KEY_OVERLAY_ON_SIGNAL, false);
        boolean killOemOnReverse = prefs.getBoolean(KEY_KILL_OEM_ON_REVERSE, false);
        long overlayHideDelayMs = prefs.getLong(KEY_OVERLAY_HIDE_DELAY_MS, 1500L);
        int segmentMin = prefs.getInt(KEY_SEGMENT_MIN, 3);
        int totalMin = prefs.getInt(KEY_TOTAL_MIN, 30);

        swEnabled.setChecked(enabled);
        swOverlayOnSignal.setChecked(overlayOnSignal);
        swKillOemOnReverse.setChecked(killOemOnReverse);
        etSegmentMin.setText(String.valueOf(segmentMin));
        etTotalMin.setText(String.valueOf(totalMin));
        etOverlayHideDelaySeconds.setText(formatDelaySeconds(overlayHideDelayMs));

        // Hide the other options while recording is disabled.
        setOptionsVisible(enabled);

        swEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            // Update the UI visibility immediately.
            setOptionsVisible(isChecked);
            // Persist the state immediately even if the user does not press save.
            SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            sp.edit().putBoolean(KEY_ENABLED, isChecked).apply();
            // Start or stop the service immediately based on the recording toggle.
            if (isChecked) {
                if (!hasStoragePermission()) {
                    ActivityCompat.requestPermissions(
                            this,
                            new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                            REQ_STORAGE
                    );
                    Toast.makeText(this, R.string.settings_storage_permission_required, Toast.LENGTH_SHORT).show();
                    // It can be enabled again after permission is granted; do not start the service yet.
                    return;
                }
                RecordingService.startIfNeeded(SettingsActivity.this);
            } else {
                RecordingService.stopIfRunning(SettingsActivity.this);
            }
        });

        swOverlayOnSignal.setOnCheckedChangeListener((buttonView, isChecked) -> {
            // Persist the setting immediately.
            SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            sp.edit().putBoolean(KEY_OVERLAY_ON_SIGNAL, isChecked).apply();
            // Listening now always stays active; only the overlay visibility changes.
            if (!isChecked) {
                // Hide any active overlay when the user disables it.
                OverlayService.hideOverlay(SettingsActivity.this);
            }
        });

        swKillOemOnReverse.setOnCheckedChangeListener((buttonView, isChecked) -> {
            SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            sp.edit().putBoolean(KEY_KILL_OEM_ON_REVERSE, isChecked).apply();
            applyOemAvmEnabledState(isChecked);
        });

        // Save duration fields as soon as they lose focus.
        View.OnFocusChangeListener durationFocusListener = (v, hasFocus) -> {
            if (hasFocus) return;
            int seg = parsePositiveInt(etSegmentMin.getText().toString(), 3);
            int total = parsePositiveInt(etTotalMin.getText().toString(), 30);
            if (total < seg) total = seg;
            SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            sp.edit()
                    .putInt(KEY_SEGMENT_MIN, seg)
                    .putInt(KEY_TOTAL_MIN, total)
                    .apply();
        };
        etSegmentMin.setOnFocusChangeListener(durationFocusListener);
        etTotalMin.setOnFocusChangeListener(durationFocusListener);

        // Save the overlay hide delay (seconds) when the field loses focus.
        etOverlayHideDelaySeconds.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) return;
            long ms = parseDelaySecondsToMs(etOverlayHideDelaySeconds.getText().toString(), 1500L);
            SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            sp.edit().putLong(KEY_OVERLAY_HIDE_DELAY_MS, ms).apply();
            // Write the normalized value back so the user sees the cleaned-up version.
            isNormalizingOverlayDelay = true;
            etOverlayHideDelaySeconds.setText(formatDelaySeconds(ms));
            isNormalizingOverlayDelay = false;
        });

        // If the user edits the value and pauses without leaving focus, do not let the signal side use the stale value.
        // That is why we persist on every text change whenever possible.
        etOverlayHideDelaySeconds.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                // no-op
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (isNormalizingOverlayDelay) return;
                String txt = s == null ? null : s.toString();
                Long ms = tryParseDelaySecondsToMsOrNull(txt);
                if (ms == null) return;
                SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                sp.edit().putLong(KEY_OVERLAY_HIDE_DELAY_MS, ms.longValue()).apply();
            }

            @Override
            public void afterTextChanged(Editable s) {
                // no-op
            }
        });

        btnExportUsb.setOnClickListener(v -> {
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
        });

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_MEDIA_MOUNTED);
        f.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        f.addDataScheme("file");
        registerReceiver(usbReceiver, f);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(usbReceiver);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_RECORDING_FOLDER || resultCode != RESULT_OK || data == null) return;
        Uri treeUri = data.getData();
        if (treeUri == null) return;
        final int takeFlags = data.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(treeUri, takeFlags);
            RecordingStorageManager.setTreeUri(this, treeUri);
            Toast.makeText(this, R.string.settings_records_path_selected, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, R.string.settings_records_path_selection_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void updateUsbButtonVisibility() {
        if (btnExportUsb != null) btnExportUsb.setVisibility(View.VISIBLE);
    }

    private File findMountedUsbRoot() {
        // Simple marker: any writable directory inside `/storage/*/`.
        // (This can vary by device; we can broaden it later if needed.)
        File storageDir = new File("/storage");
        if (!storageDir.exists()) return null;
        File[] roots = storageDir.listFiles();
        if (roots == null) return null;
        for (File r : roots) {
            if (r.isDirectory() && r.canRead() && r.canWrite()) {
                // Additional filtering can be added based on app requirements.
                return r;
            }
        }
        return null;
    }

    private File getRecordsBaseDir() {
        return RecordingStorageManager.getWritableBaseDir(this);
    }

    private int parsePositiveInt(String s, int def) {
        try {
            int v = Integer.parseInt(s.trim());
            return Math.max(1, v);
        } catch (Exception e) {
            return def;
        }
    }

    private long parseDelaySecondsToMs(String s, long defMs) {
        try {
            if (s == null) return defMs;
            String t = s.trim().replace(',', '.');
            if (t.isEmpty()) return defMs;
            double sec = Double.parseDouble(t);
            if (sec < 0) sec = 0;
            if (sec > 30) sec = 30; // Safety clamp: 0..30 seconds
            return (long) (sec * 1000.0);
        } catch (Throwable ignored) {
            return defMs;
        }
    }

    private String formatDelaySeconds(long ms) {
        double sec = ms / 1000.0;
        // For values like "1.5"
        if (Math.abs(sec - Math.round(sec)) < 0.0001) {
            return String.valueOf((int) Math.round(sec));
        }
        // Max 1 decimal place
        return String.format(java.util.Locale.US, "%.1f", sec);
    }

    private Long tryParseDelaySecondsToMsOrNull(String s) {
        try {
            if (s == null) return null;
            String t = s.trim().replace(',', '.');
            if (t.isEmpty()) return null;
            double sec = Double.parseDouble(t);
            if (sec < 0) return null;
            if (sec > 30) sec = 30;
            return (long) (sec * 1000.0);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean hasStoragePermission() {
        return ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void setOptionsVisible(boolean visible) {
        int vis = visible ? View.VISIBLE : View.GONE;
        tvSegment.setVisibility(vis);
        etSegmentMin.setVisibility(vis);
        tvTotal.setVisibility(vis);
        etTotalMin.setVisibility(vis);
        if (tvPath != null) tvPath.setVisibility(View.VISIBLE);
        if (tvPathValue != null) tvPathValue.setVisibility(View.VISIBLE);
        // The USB export button is shown only when recording is enabled and a USB device is mounted.
        if (btnExportUsb != null) {
            btnExportUsb.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Thanks to the system signature, we can actually disable and re-enable the OEM 360 package.
     * isDisabled == true -> COMPONENT_ENABLED_STATE_DISABLED_USER
     * isDisabled == false -> COMPONENT_ENABLED_STATE_DEFAULT
     */
    private void applyOemAvmEnabledState(boolean isDisabled) {
        try {
            PackageManager pm = getPackageManager();
            int newState = isDisabled
                    ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
            pm.setApplicationEnabledSetting(
                    OEM_AVM_PACKAGE,
                    newState,
                    PackageManager.DONT_KILL_APP
            );
            Toast.makeText(
                    this,
                    isDisabled
                            ? "OEM 360 devre dışı bırakıldı (cihazı/yazılımı yeniden başlatman gerekebilir)."
                            : "OEM 360 tekrar etkinleştirildi.",
                    Toast.LENGTH_LONG
            ).show();
        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "OEM 360 durum değiştirilemedi: " + e.getClass().getSimpleName(),
                    Toast.LENGTH_LONG
            ).show();
        }
    }
}
