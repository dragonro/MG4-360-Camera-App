package com.drivehub.kamera;

import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.appcompat.app.AppCompatActivity;

public class TileViewActivity extends AppCompatActivity {

    private static final int[] SURFACE_IDS     = {R.id.sfFront, R.id.sfRight, R.id.sfLeft, R.id.sfRear};
    private static final int[] CAMERA_INDICES   = {15, 14, 16, 17};

    private final SurfaceHolder[]          holders   = new SurfaceHolder[4];
    private final SurfaceHolder.Callback[] callbacks = new SurfaceHolder.Callback[4];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tile_view);

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
