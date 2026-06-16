// Updated: AdrianBega/DualBytes
package com.drivehub.kamera;

import android.content.Context;
import java.io.File;

final class TestVideoSources {

    static final String DIRECTORY_NAME = "mg4-camera-test";

    private TestVideoSources() {
    }

    static boolean shouldUse(Context context) {
        if (context == null || !BuildConfig.DEBUG) return false;
        return UiPrefs.isDevTestVideoSourcesEnabled(UiPrefs.getPrefs(context))
                || hasAllFiles(context)
                || hasDebugAsset(context);
    }

    static boolean hasAllFiles(Context context) {
        return getFile(context, 15).isFile()
                && getFile(context, 14).isFile()
                && getFile(context, 16).isFile()
                && getFile(context, 17).isFile();
    }

    static File getFile(Context context, int cameraIndex) {
        File file = getExpectedFile(context, cameraIndex);
        if (file.isFile()) {
            return file;
        }
        File frontFallback = getExpectedFile(context, 15);
        if (frontFallback.isFile()) {
            return frontFallback;
        }
        return file;
    }

    static File getExpectedFile(Context context, int cameraIndex) {
        return new File(getRootDirectory(context), fileNameFor(cameraIndex));
    }

    static String expectedPath(Context context) {
        return getRootDirectory(context).getAbsolutePath();
    }

    static String assetNameFor(int cameraIndex) {
        return "front_camera_sample_1.mp4";
    }

    static boolean hasDebugAsset(Context context) {
        if (context == null) return false;
        try {
            context.getAssets().open(assetNameFor(15)).close();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static File getRootDirectory(Context context) {
        return new File(context.getFilesDir(), DIRECTORY_NAME);
    }

    private static String fileNameFor(int cameraIndex) {
        switch (cameraIndex) {
            case 14:
                return "right.mp4";
            case 15:
                return "front.mp4";
            case 16:
                return "left.mp4";
            case 17:
                return "rear.mp4";
            default:
                return "video" + cameraIndex + ".mp4";
        }
    }
}
