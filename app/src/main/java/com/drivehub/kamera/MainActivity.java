package com.drivehub.kamera;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.ImageButton;
import android.widget.Switch;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

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
    // Program açılınca başlangıç kamerası: ön kamera (v15)
    private int currentVideoIndex = 15;
    private boolean previewRunning = false;

    // Swipe tespit eşiği (piksel)
    private static final int SWIPE_THRESHOLD_PX = 140;
    private float downX = 0f;
    private float downY = 0f;
    private static volatile boolean sMainVisible = false;
    private static volatile boolean sSettingsDialogOpen = false;

    private final BroadcastReceiver cameraRouteReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            if (!SignalService.ACTION_ROUTE_CAMERA.equals(intent.getAction())) return;
            int idx = intent.getIntExtra(SignalService.EXTRA_CAMERA_INDEX, currentVideoIndex);
            if (idx == currentVideoIndex) return;
            currentVideoIndex = idx;
            if (tvStatus != null) {
                tvStatus.setText("Preview: " + cameraLabel(currentVideoIndex));
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

        // Başlangıç etiketini göster
        if (tvStatus != null) {
            tvStatus.setText("Preview: " + cameraLabel(currentVideoIndex));
        }
        applyWarningVisibility();

        // Sinyal/vites dinleme her zaman açık kalsın; overlay sadece ayardan kontrol edilecek.
        try {
            SignalService.start(this);
        } catch (Throwable ignored) {
        }

        // Swipe ile kamera değiştir (yatay)
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

                    // Baskın eksene göre karar ver:
                    // - Yatayda: sola -> v16, sağa -> v14
                    // - Dikeyde: yukarı -> v15, aşağı -> v17
                    if (Math.abs(dx) > Math.abs(dy)) {
                        // yatay
                        if (dx > SWIPE_THRESHOLD_PX) {
                            currentVideoIndex = 14; // sağ kamera
                        } else if (dx < -SWIPE_THRESHOLD_PX) {
                            currentVideoIndex = 16; // sol kamera
                        } else {
                            return true;
                        }
                    } else {
                        // dikey
                        if (dy < -SWIPE_THRESHOLD_PX) {
                            currentVideoIndex = 15; // ön kamera
                        } else if (dy > SWIPE_THRESHOLD_PX) {
                            currentVideoIndex = 17; // arka kamera
                        } else {
                            return true;
                        }
                    }

                    if (tvStatus != null) {
                        tvStatus.setText("Preview: " + cameraLabel(currentVideoIndex));
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
        // Main açıkken overlay görünmesin.
        OverlayService.hideOverlay(this);
        applyWarningVisibility();
    }

    @Override
    protected void onStop() {
        super.onStop();
        sMainVisible = false;
        sSettingsDialogOpen = false;
        try {
            unregisterReceiver(cameraRouteReceiver);
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private void showSettingsDialog() {
        sSettingsDialogOpen = true;
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_settings);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        SharedPreferences prefs = getSharedPreferences("rec_prefs", MODE_PRIVATE);
        SharedPreferences avmPrefs = getSharedPreferences(AVM_PREFS_NAME, MODE_PRIVATE);
        Switch swOverlay = dialog.findViewById(R.id.switchOverlayOnSignal);
        Switch swSafetyWarning = dialog.findViewById(R.id.switchSafetyWarning);

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

        TextView tvDialogVersion = dialog.findViewById(R.id.tvDialogVersion);
        try {
            String version = getPackageManager()
                    .getPackageInfo(getPackageName(), 0)
                    .versionName;
            tvDialogVersion.setText("Version " + version);
        } catch (Exception e) {
            tvDialogVersion.setText("Version ?");
        }

        dialog.findViewById(R.id.btnClose).setOnClickListener(v -> dialog.dismiss());
        dialog.setOnDismissListener(d -> sSettingsDialogOpen = false);
        dialog.show();

        Window shownWindow = dialog.getWindow();
        if (shownWindow != null) {
            float density = getResources().getDisplayMetrics().density;
            shownWindow.setLayout((int) (560 * density), WindowManager.LayoutParams.WRAP_CONTENT);
        }
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
            if (tvStatus != null) tvStatus.setText("Surface not ready");
            return;
        }
        stopPreview();
        boolean ok = CameraProbe.startPreview(currentVideoIndex, surfaceHolder.getSurface());
        previewRunning = ok;
        if (tvStatus != null) {
            tvStatus.setText(ok ? "Preview: " + cameraLabel(currentVideoIndex) : "Başlatılamadı");
        }
    }

    private void stopPreview() {
        if (previewRunning) {
            CameraProbe.stopPreview();
            previewRunning = false;
            if (tvStatus != null) tvStatus.setText("Stopped");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        sMainVisible = false;
        stopPreview();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        // Yüzey hazır olur olmaz ilk kamerayı başlat.
        startPreviewIfReady();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // gerekirse yeniden başlat
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopPreview();
    }

    private String cameraLabel(int videoIndex) {
        switch (videoIndex) {
            case 14:
                return "Sağ kamera (/dev/video14)";
            case 15:
                return "Ön kamera (/dev/video15)";
            case 16:
                return "Sol kamera (/dev/video16)";
            case 17:
                return "Arka kamera (/dev/video17)";
            default:
                return "/dev/video" + videoIndex;
        }
    }
}
