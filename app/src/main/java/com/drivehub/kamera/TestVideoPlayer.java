// Updated: AdrianBega/DualBytes
package com.drivehub.kamera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.media.MediaPlayer;
import android.media.MediaMetadataRetriever;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.io.File;

final class TestVideoPlayer {

    private static final String TAG = "TestVideoPlayer";
    private static final Object STATE_LOCK = new Object();
    private static long playbackStartedAtMs = 0L;
    private static final int MESH_COLUMNS = 28;
    private static final int MESH_ROWS = 16;
    private static final long DEBUG_RENDER_FRAME_DELAY_MS = 50L;

    private MediaPlayer mediaPlayer;
    private Thread debugRendererThread;
    private volatile boolean debugRendererRunning;

    boolean start(Context context, int cameraIndex, Surface surface) {
        stop();
        if (context == null || surface == null || !surface.isValid()) return false;

        try {
            File file = TestVideoSources.resolveFile(context, cameraIndex);
            if (!file.isFile()) return false;
            if (UiPrefs.getProcessingMode(UiPrefs.getPrefs(context)) == UiPrefs.PROCESSING_MODE_UNDISTORTED) {
                return startUndistortedDebugRenderer(file, cameraIndex, surface);
            }
            MediaPlayer player = new MediaPlayer();
            player.setDataSource(file.getAbsolutePath());
            player.setSurface(surface);
            player.setLooping(false);
            player.setOnPreparedListener(mp -> {
                try {
                    int seekPositionMs = synchronizedPosition(mp.getDuration());
                    Log.i(TAG, "Starting camera " + cameraIndex + " from "
                            + file.getName() + " at " + seekPositionMs + "ms");
                    if (seekPositionMs > 0) {
                        mp.seekTo(seekPositionMs);
                    }
                    mp.start();
                } catch (Throwable t) {
                    stop();
                }
            });
            player.setOnCompletionListener(mp -> {
                try {
                    mp.seekTo(0);
                    mp.start();
                } catch (Throwable t) {
                    stop();
                }
            });
            player.setOnErrorListener((mp, what, extra) -> {
                stop();
                return true;
            });
            player.prepareAsync();
            mediaPlayer = player;
            return true;
        } catch (Throwable ignored) {
            stop();
            return false;
        }
    }

    void stop() {
        stopDebugRenderer();
        MediaPlayer player = mediaPlayer;
        mediaPlayer = null;
        if (player == null) return;
        try {
            player.setOnPreparedListener(null);
            player.setOnErrorListener(null);
            player.setOnCompletionListener(null);
            player.stop();
        } catch (Throwable ignored) {
        }
        try {
            player.release();
        } catch (Throwable ignored) {
        }
    }

    private static int synchronizedPosition(int durationMs) {
        if (durationMs <= 0) return 0;
        synchronized (STATE_LOCK) {
            long nowMs = SystemClock.elapsedRealtime();
            if (playbackStartedAtMs <= 0L) {
                playbackStartedAtMs = nowMs;
            }
            return (int) ((nowMs - playbackStartedAtMs) % durationMs);
        }
    }

    private boolean startUndistortedDebugRenderer(File file, int cameraIndex, Surface surface) {
        int durationMs = readDurationMs(file);
        if (durationMs <= 0) {
            Log.w(TAG, "Cannot start undistorted debug renderer; invalid duration for " + file);
            return false;
        }
        debugRendererRunning = true;
        debugRendererThread = new Thread(
                () -> renderUndistortedDebugVideo(file, cameraIndex, surface, durationMs),
                "TestVideoUndistortRenderer"
        );
        debugRendererThread.start();
        return true;
    }

    private void renderUndistortedDebugVideo(File file, int cameraIndex, Surface surface, int durationMs) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        float[] mesh = null;
        int meshWidth = -1;
        int meshHeight = -1;
        try {
            retriever.setDataSource(file.getAbsolutePath());
            Log.i(TAG, "Starting undistorted debug camera " + cameraIndex + " from " + file.getName());
            while (debugRendererRunning && surface.isValid()) {
                int positionMs = synchronizedPosition(durationMs);
                Bitmap frame = retriever.getFrameAtTime(
                        positionMs * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                );
                if (frame == null) {
                    sleepDebugFrame();
                    continue;
                }
                if (mesh == null || meshWidth != frame.getWidth() || meshHeight != frame.getHeight()) {
                    meshWidth = frame.getWidth();
                    meshHeight = frame.getHeight();
                    mesh = buildUndistortMesh(meshWidth, meshHeight);
                }
                drawUndistortedFrame(surface, frame, mesh, paint);
                frame.recycle();
                sleepDebugFrame();
            }
        } catch (Throwable t) {
            Log.w(TAG, "Undistorted debug renderer failed for camera " + cameraIndex, t);
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private void drawUndistortedFrame(Surface surface, Bitmap frame, float[] mesh, Paint paint) {
        Canvas canvas = null;
        try {
            canvas = surface.lockCanvas(null);
            if (canvas == null) return;
            canvas.drawColor(android.graphics.Color.BLACK);
            canvas.save();
            canvas.scale(
                    canvas.getWidth() / (float) frame.getWidth(),
                    canvas.getHeight() / (float) frame.getHeight()
            );
            canvas.drawBitmapMesh(frame, MESH_COLUMNS, MESH_ROWS, mesh, 0, null, 0, paint);
            canvas.restore();
        } catch (Throwable t) {
            Log.w(TAG, "Failed to draw undistorted debug frame", t);
        } finally {
            if (canvas != null) {
                try {
                    surface.unlockCanvasAndPost(canvas);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void stopDebugRenderer() {
        debugRendererRunning = false;
        Thread thread = debugRendererThread;
        debugRendererThread = null;
        if (thread == null) return;
        thread.interrupt();
        try {
            thread.join(1000L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static int readDurationMs(File file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String raw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return raw == null ? 0 : Math.max(0, Integer.parseInt(raw));
        } catch (Throwable ignored) {
            return 0;
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private static float[] buildUndistortMesh(int width, int height) {
        float[] vertices = new float[(MESH_COLUMNS + 1) * (MESH_ROWS + 1) * 2];
        float cx = (width - 1) * 0.5f;
        float cy = (height - 1) * 0.5f;
        float scale = Math.min(cx, cy);
        float k1 = -0.30f;
        float k2 = 0.08f;
        int index = 0;
        for (int y = 0; y <= MESH_ROWS; y++) {
            float py = height * y / (float) MESH_ROWS;
            float yn = (py - cy) / scale;
            for (int x = 0; x <= MESH_COLUMNS; x++) {
                float px = width * x / (float) MESH_COLUMNS;
                float xn = (px - cx) / scale;
                float r2 = xn * xn + yn * yn;
                float radial = 1.0f + k1 * r2 + k2 * r2 * r2;
                vertices[index++] = cx + xn * radial * scale;
                vertices[index++] = cy + yn * radial * scale;
            }
        }
        return vertices;
    }

    private static void sleepDebugFrame() {
        try {
            Thread.sleep(DEBUG_RENDER_FRAME_DELAY_MS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
