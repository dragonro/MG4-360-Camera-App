// Author: AdrianBega/DualBytes
package com.drivehub.kamera;

import android.content.Context;
import android.view.Surface;

/**
 * Single source of truth for the MG4 360 preview pipeline.
 * The app first tries the debug test video path when enabled, then falls back to the native car
 * camera probe. Both the main screen and popup overlay call into this helper so they cannot drift.
 */
final class PreviewSourceController {

    private PreviewSourceController() {
    }

    static boolean start(Context context, int cameraIndex, Surface surface, TestVideoPlayer testVideoPlayer) {
        if (context == null || surface == null || !surface.isValid()) {
            return false;
        }
        if (testVideoPlayer != null && TestVideoSources.shouldUse(context)) {
            if (testVideoPlayer.start(context, cameraIndex, surface)) {
                return true;
            }
        }
        try {
            return CameraProbe.startPreview(cameraIndex, surface);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void stop(TestVideoPlayer testVideoPlayer) {
        if (testVideoPlayer != null) {
            testVideoPlayer.stop();
        }
        stopNative();
    }

    static void stopNative() {
        try {
            CameraProbe.stopPreview();
        } catch (Throwable ignored) {
        }
    }
}
