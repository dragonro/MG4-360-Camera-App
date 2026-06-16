// Updated: AdrianBega/DualBytes
package com.drivehub.kamera;

import android.content.Context;
import android.media.MediaPlayer;
import android.view.Surface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

final class TestVideoPlayer {

    private MediaPlayer mediaPlayer;

    boolean start(Context context, int cameraIndex, Surface surface) {
        stop();
        if (context == null || surface == null || !surface.isValid()) return false;

        File file = TestVideoSources.getFile(context, cameraIndex);
        boolean useAsset = BuildConfig.DEBUG && TestVideoSources.hasDebugAsset(context);

        try {
            if (useAsset) {
                file = materializeDebugAsset(context, cameraIndex);
            }
            if (!file.isFile()) return false;
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

    private File materializeDebugAsset(Context context, int cameraIndex) throws IOException {
        File target = TestVideoSources.getExpectedFile(context, cameraIndex);
        File root = target.getParentFile();
        if (root != null && !root.exists() && !root.mkdirs()) {
            return target;
        }
        try (InputStream in = context.getAssets().open(TestVideoSources.assetNameFor(cameraIndex));
             FileOutputStream out = new FileOutputStream(target, false)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.getFD().sync();
        }
        return target;
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
