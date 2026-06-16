// Author: AdrianBega/DualBytes
package com.drivehub.kamera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Surface;

import java.util.Locale;

final class SyntheticTestPreview {

    private final Object lock = new Object();
    private Thread renderThread;
    private volatile boolean running;
    private Surface surface;
    private int cameraIndex;

    boolean start(Context context, int cameraIndex, Surface surface) {
        stop();
        if (surface == null || !surface.isValid()) return false;
        this.surface = surface;
        this.cameraIndex = cameraIndex;
        running = true;
        renderThread = new Thread(this::renderLoop, "SyntheticTestPreview");
        renderThread.start();
        return true;
    }

    void stop() {
        running = false;
        Thread t = renderThread;
        renderThread = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join(300L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (lock) {
            surface = null;
        }
    }

    private void renderLoop() {
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint scanPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        scanPaint.setColor(0x66FFFFFF);

        float phase = 0f;
        while (running) {
            Surface currentSurface;
            synchronized (lock) {
                currentSurface = surface;
            }
            if (currentSurface == null || !currentSurface.isValid()) {
                break;
            }

            Canvas canvas = null;
            try {
                canvas = currentSurface.lockCanvas(null);
                if (canvas == null) break;

                int w = canvas.getWidth();
                int h = canvas.getHeight();
                bgPaint.setColor(0xFF050505);
                canvas.drawRect(0, 0, w, h, bgPaint);

                framePaint.setStyle(Paint.Style.STROKE);
                framePaint.setStrokeWidth(Math.max(4f, Math.min(w, h) * 0.01f));
                framePaint.setColor(colorForCamera(cameraIndex));
                RectF frame = new RectF(framePaint.getStrokeWidth(), framePaint.getStrokeWidth(),
                        w - framePaint.getStrokeWidth(), h - framePaint.getStrokeWidth());
                canvas.drawRoundRect(frame, 20f, 20f, framePaint);

                framePaint.setStyle(Paint.Style.FILL);
                canvas.drawRect(w * 0.1f, h * 0.2f, w * 0.9f, h * 0.25f, framePaint);

                float scanY = (h * 0.2f) + ((phase % 1f) * (h * 0.55f));
                canvas.drawRect(w * 0.12f, scanY, w * 0.88f, scanY + Math.max(3f, h * 0.008f), scanPaint);

                textPaint.setTextSize(Math.max(18f, Math.min(w, h) * 0.08f));
                canvas.drawText(String.format(Locale.US, "TEST CAMERA %d", cameraIndex),
                        w / 2f, h * 0.52f, textPaint);
                textPaint.setTextSize(Math.max(12f, Math.min(w, h) * 0.04f));
                canvas.drawText("Emulator fallback preview", w / 2f, h * 0.62f, textPaint);
                canvas.drawText("No /dev/video or MP4 source found", w / 2f, h * 0.70f, textPaint);
            } catch (Throwable ignored) {
                break;
            } finally {
                if (canvas != null) {
                    try {
                        currentSurface.unlockCanvasAndPost(canvas);
                    } catch (Throwable ignored) {
                    }
                }
            }

            phase += 0.015f;
            try {
                Thread.sleep(33L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private int colorForCamera(int cameraIndex) {
        switch (cameraIndex) {
            case 14:
                return 0xFF29B6F6;
            case 16:
                return 0xFF66BB6A;
            case 17:
                return 0xFFFFA726;
            case 15:
            default:
                return 0xFFE53935;
        }
    }
}
