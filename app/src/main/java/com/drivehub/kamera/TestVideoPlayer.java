// Updated: AdrianBega/DualBytes
package com.drivehub.kamera;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.io.File;

final class TestVideoPlayer {

    private static final String TAG = "TestVideoPlayer";
    private static final Object STATE_LOCK = new Object();
    private static long playbackStartedAtMs = 0L;

    private MediaPlayer mediaPlayer;
    private UndistortedVideoRenderer undistortedRenderer;

    synchronized boolean start(Context context, int cameraIndex, Surface surface) {
        stop();
        if (context == null || surface == null || !surface.isValid()) return false;

        try {
            File file = TestVideoSources.resolveFile(context, cameraIndex);
            if (!file.isFile()) return false;
            if (UiPrefs.getProcessingMode(UiPrefs.getPrefs(context)) == UiPrefs.PROCESSING_MODE_UNDISTORTED) {
                return startUndistortedRenderer(file, cameraIndex, surface);
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

    synchronized void stop() {
        UndistortedVideoRenderer renderer = undistortedRenderer;
        undistortedRenderer = null;
        if (renderer != null) {
            renderer.stop();
        }
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

    private boolean startUndistortedRenderer(File file, int cameraIndex, Surface surface) {
        Log.i(TAG, "Starting GPU undistorted camera " + cameraIndex + " from " + file.getName());
        UndistortedVideoRenderer renderer = new UndistortedVideoRenderer(
                surface,
                file,
                cameraIndex,
                TestVideoPlayer::synchronizedPosition
        );
        if (!renderer.start()) {
            renderer.stop();
            return false;
        }
        undistortedRenderer = renderer;
        return true;
    }
}
