package com.drivehub.kamera;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Environment;
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

    private SwitchCompat swEnabled;
    private SwitchCompat swOverlayOnSignal;
    private SwitchCompat swKillOemOnReverse;
    private TextView tvSegment;
    private TextView tvTotal;
    private TextView tvPath;
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

        String recordsPath = getRecordsBaseDir().getAbsolutePath();
        tvPath.setText(recordsPath);

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

        // Kayıt kapalıyken diğer seçenekleri gizle
        setOptionsVisible(enabled);

        swEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            // Sadece UI görünürlüğünü anlık değiştir
            setOptionsVisible(isChecked);
            // Kullanıcı kaydet'e basmasa bile son durum kalıcı olsun.
            SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            sp.edit().putBoolean(KEY_ENABLED, isChecked).apply();
            // Kayıt durumuna göre servisi anında yönet.
            if (isChecked) {
                if (!hasStoragePermission()) {
                    ActivityCompat.requestPermissions(
                            this,
                            new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                            REQ_STORAGE
                    );
                    Toast.makeText(this, "Depolama izni gerekli", Toast.LENGTH_SHORT).show();
                    // İzin verilince tekrar açılabilir; şimdilik servisi başlatma.
                    return;
                }
                RecordingService.startIfNeeded(SettingsActivity.this);
            } else {
                RecordingService.stopIfRunning(SettingsActivity.this);
            }
        });

        swOverlayOnSignal.setOnCheckedChangeListener((buttonView, isChecked) -> {
            // Ayarı anlık kaydet.
            SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            sp.edit().putBoolean(KEY_OVERLAY_ON_SIGNAL, isChecked).apply();
            // Dinleme artık her zaman açık; sadece overlay göster/gösterme.
            if (!isChecked) {
                // Kullanıcı kapatınca varsa overlay'i gizle.
                OverlayService.hideOverlay(SettingsActivity.this);
            }
        });

        swKillOemOnReverse.setOnCheckedChangeListener((buttonView, isChecked) -> {
            SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            sp.edit().putBoolean(KEY_KILL_OEM_ON_REVERSE, isChecked).apply();
            applyOemAvmEnabledState(isChecked);
        });

        // Süre alanları odak kaybedince anında kaydedilsin.
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

        // Overlay kapanma gecikmesi (sn) odak kaybedince kaydedilsin.
        etOverlayHideDelaySeconds.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) return;
            long ms = parseDelaySecondsToMs(etOverlayHideDelaySeconds.getText().toString(), 1500L);
            SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            sp.edit().putLong(KEY_OVERLAY_HIDE_DELAY_MS, ms).apply();
            // Kullanıcıya görünür hale getirmek için normalize edilmiş değeri geri yaz.
            isNormalizingOverlayDelay = true;
            etOverlayHideDelaySeconds.setText(formatDelaySeconds(ms));
            isNormalizingOverlayDelay = false;
        });

        // Kullanıcı değer değiştirip odaktan çıkmadan beklerse, sinyal tarafı eski değeri kullanmasın.
        // Bu yüzden her metin değişiminde mümkünse anında prefs'e yazıyoruz.
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
            // TODO: USB export kodu sonraki adım.
            Toast.makeText(this, "USB export TODO (şimdilik kapalı)", Toast.LENGTH_LONG).show();
        });

        updateUsbButtonVisibility();

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

    private void updateUsbButtonVisibility() {
        // Basit yaklaşım: USB mount noktası varsa button görünür yap.
        // Cihazdan bağımsız çalışsın diye genişletilebilir.
        boolean usbMounted = findMountedUsbRoot() != null;
        btnExportUsb.setVisibility(usbMounted ? View.VISIBLE : View.GONE);
    }

    private File findMountedUsbRoot() {
        // Basit marker: `/storage/*/` içinde yazılabilir bir dizin varsa.
        // (Bu kısım cihazdan cihaza değişebilir; gerekirse genişletiriz.)
        File storageDir = new File("/storage");
        if (!storageDir.exists()) return null;
        File[] roots = storageDir.listFiles();
        if (roots == null) return null;
        for (File r : roots) {
            if (r.isDirectory() && r.canRead() && r.canWrite()) {
                // Uygulama ihtiyaçlarına göre filtre eklenebilir.
                return r;
            }
        }
        return null;
    }

    private File getRecordsBaseDir() {
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File dir = new File(downloads, "mg4_cam_records");
        // klasör oluşturma
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
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
            if (sec > 30) sec = 30; // güvenlik: 0..30 sn
            return (long) (sec * 1000.0);
        } catch (Throwable ignored) {
            return defMs;
        }
    }

    private String formatDelaySeconds(long ms) {
        double sec = ms / 1000.0;
        // "1.5" gibi
        if (Math.abs(sec - Math.round(sec)) < 0.0001) {
            return String.valueOf((int) Math.round(sec));
        }
        // max 1 decimal
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
        tvPath.setVisibility(vis);
        // USB dışa aktarma butonu kayıt açıkken ve USB takılıyken gösterilir.
        if (btnExportUsb != null) {
            btnExportUsb.setVisibility(visible ? btnExportUsb.getVisibility() : View.GONE);
        }
    }

    /**
     * Sistem imzası sayesinde OEM 360 paketini gerçekten devre dışı / tekrar etkin yapabiliyoruz.
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

