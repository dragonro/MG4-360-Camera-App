package com.drivehub.kamera;

import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.card.MaterialCardView;

public class TileViewActivity extends AppCompatActivity {

    private static final String REC_PREFS_NAME = "rec_prefs";
    private static final String KEY_TILE_CORNER_RADIUS = "tileCornerRadius";
    private static final int[] SURFACE_IDS   = {R.id.sfFront, R.id.sfRight, R.id.sfLeft, R.id.sfRear};
    private static final int[] TILE_IDS      = {R.id.tileFront, R.id.tileRight, R.id.tileLeft, R.id.tileRear};
    private static final int[] CAMERA_INDICES = {15, 14, 16, 17};

    private final SurfaceHolder[]          holders   = new SurfaceHolder[4];
    private final SurfaceHolder.Callback[] callbacks = new SurfaceHolder.Callback[4];
    private SharedPreferences prefs;
    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            (sharedPreferences, key) -> {
                if (KEY_TILE_CORNER_RADIUS.equals(key)) {
                    applyCornerRadius();
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tile_view);
        prefs = getSharedPreferences(REC_PREFS_NAME, MODE_PRIVATE);

        applyCornerRadius();

        for (int i = 0; i < SURFACE_IDS.length; i++) {
            final int cameraIndex = CAMERA_INDICES[i];
            SurfaceView sv = findViewById(SURFACE_IDS[i]);
            SurfaceHolder holder = sv.getHolder();
            holders[i] = holder;

            callbacks[i] = new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(SurfaceHolder h) {
                    CameraProbe.startPreview(cameraIndex, h.getSurface());
                }

                @Override
                public void surfaceChanged(SurfaceHolder h, int format, int w, int h2) {}

                @Override
                public void surfaceDestroyed(SurfaceHolder h) {}
            };
            holder.addCallback(callbacks[i]);
        }
    }

    private void applyCornerRadius() {
        int sliderValue = prefs.getInt(KEY_TILE_CORNER_RADIUS, 16);
        View container = findViewById(R.id.tileContainer);
        float radiusFraction = Math.max(0f, Math.min(100f, sliderValue)) / 100f;
        container.post(() -> applyCornerRadiusToLaidOutViews(container, radiusFraction));
    }

    private void applyCornerRadiusToLaidOutViews(View container, float radiusFraction) {
        float containerRadiusPx = Math.min(container.getWidth(), container.getHeight()) * 0.5f * radiusFraction;
        if (container.getBackground() instanceof GradientDrawable) {
            GradientDrawable background = (GradientDrawable) container.getBackground().mutate();
            background.setCornerRadius(containerRadiusPx);
        }

        for (int tileId : TILE_IDS) {
            MaterialCardView card = findViewById(tileId);
            float cardRadiusPx = Math.min(card.getWidth(), card.getHeight()) * 0.5f * radiusFraction;
            card.setRadius(cardRadiusPx);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
    }

    @Override
    protected void onStop() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        CameraProbe.stopPreview();
        for (int i = 0; i < holders.length; i++) {
            if (holders[i] != null && callbacks[i] != null) {
                holders[i].removeCallback(callbacks[i]);
            }
        }
    }
}
