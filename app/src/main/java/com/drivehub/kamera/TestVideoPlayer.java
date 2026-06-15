package com.drivehub.kamera;

import android.content.Context;
import android.media.MediaPlayer;
import android.view.Surface;

import java.io.File;

final class TestVideoPlayer {

    private MediaPlayer mediaPlayer;

    boolean start(Context context, int cameraIndex, Surface surface) {
        stop();
        if (context == null || surface == null || !surface.isValid()) return false;

        File file = TestVideoSources.getFile(context, cameraIndex);
        if (!file.isFile()) return false;

        try {
            MediaPlayer player = new MediaPlayer();
            player.setDataSource(file.getAbsolutePath());
            player.setSurface(surface);
            player.setLooping(true);
            player.setOnPreparedListener(MediaPlayer::start);
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
        MediaPlayer player = mediaPlayer;
        mediaPlayer = null;
        if (player == null) return;
        try {
            player.setOnPreparedListener(null);
            player.setOnErrorListener(null);
            player.stop();
        } catch (Throwable ignored) {
        }
        try {
            player.release();
        } catch (Throwable ignored) {
        }
    }
}
