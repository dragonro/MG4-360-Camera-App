// Author: AdrianBega/DualBytes
// Updated: AdrianBega/DualBytes
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
        return start(context, cameraIndex, surface, testVideoPlayer, null);
    }

    static boolean start(
            Context context,
            int cameraIndex,
            Surface surface,
            TestVideoPlayer testVideoPlayer,
            SyntheticTestPreview syntheticTestPreview
    ) {
        if (context == null || surface == null || !surface.isValid()) {
            return false;
        }
        applyProcessingMode(context);
        if (testVideoPlayer != null && TestVideoSources.shouldUse(context)) {
            if (testVideoPlayer.start(context, cameraIndex, surface)) {
                return true;
            }
        }
        try {
            if (CameraProbe.startPreview(cameraIndex, surface)) {
                return true;
            }
        } catch (Throwable ignored) {
            // Fall through to the synthetic preview in debug/emulator cases.
        }
        if (BuildConfig.DEBUG && syntheticTestPreview != null) {
            return syntheticTestPreview.start(context, cameraIndex, surface);
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

    static void stopSurface(TestVideoPlayer testVideoPlayer, Surface surface) {
        if (testVideoPlayer != null) {
            testVideoPlayer.stop();
        }
        try {
            if (surface != null) {
                CameraProbe.stopPreviewSurface(surface);
            }
        } catch (Throwable ignored) {
        }
    }

    static void stopNative() {
        try {
            CameraProbe.stopPreview();
        } catch (Throwable ignored) {
        }
    }

    static void applyProcessingMode(Context context) {
        if (context == null) return;
        try {
            CameraProbe.setProcessingMode(UiPrefs.getProcessingMode(UiPrefs.getPrefs(context)));
        } catch (Throwable ignored) {
        }
    }
}
