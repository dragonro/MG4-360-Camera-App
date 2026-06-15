package com.drivehub.kamera;

import android.content.Context;
import java.io.File;

final class TestVideoSources {

    static final String DIRECTORY_NAME = "mg4-camera-test";

    private TestVideoSources() {
    }

    static boolean shouldUse(Context context) {
        if (context == null || !BuildConfig.DEBUG) return false;
        return UiPrefs.isDevTestVideoSourcesEnabled(UiPrefs.getPrefs(context)) || hasAllFiles(context);
    }

    static boolean hasAllFiles(Context context) {
        return getFile(context, 15).isFile()
                && getFile(context, 14).isFile()
                && getFile(context, 16).isFile()
                && getFile(context, 17).isFile();
    }

    static File getFile(Context context, int cameraIndex) {
        return new File(getRootDirectory(context), fileNameFor(cameraIndex));
    }

    static String expectedPath(Context context) {
        return getRootDirectory(context).getAbsolutePath();
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
