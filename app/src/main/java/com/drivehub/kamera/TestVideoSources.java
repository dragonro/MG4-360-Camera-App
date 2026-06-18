// Updated: AdrianBega/DualBytes
package com.drivehub.kamera;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

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
        return getExpectedFile(context, 15).isFile()
                && getExpectedFile(context, 14).isFile()
                && getExpectedFile(context, 16).isFile()
                && getExpectedFile(context, 17).isFile();
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

    static File resolveFile(Context context, int cameraIndex) throws IOException {
        File exactFile = getExpectedFile(context, cameraIndex);
        if (exactFile.isFile()) {
            return exactFile;
        }
        if (hasDebugAsset(context, cameraIndex)) {
            return materializeDebugAsset(context, cameraIndex);
        }
        return getFile(context, cameraIndex);
    }

    static File getExpectedFile(Context context, int cameraIndex) {
        return new File(getRootDirectory(context), fileNameFor(cameraIndex));
    }

    static String expectedPath(Context context) {
        return getRootDirectory(context).getAbsolutePath();
    }

    static String assetNameFor(int cameraIndex) {
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
                return "front_camera_sample_1.mp4";
        }
    }

    static boolean hasDebugAsset(Context context) {
        return hasDebugAsset(context, 15);
    }

    static boolean hasDebugAsset(Context context, int cameraIndex) {
        if (context == null) return false;
        try {
            context.getAssets().open(assetNameFor(cameraIndex)).close();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static File materializeDebugAsset(Context context, int cameraIndex) throws IOException {
        File target = getExpectedFile(context, cameraIndex);
        File root = target.getParentFile();
        if (root != null && !root.exists() && !root.mkdirs()) {
            return target;
        }
        String assetName = assetNameFor(cameraIndex);
        try (InputStream in = openDebugAsset(context, assetName);
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

    private static InputStream openDebugAsset(Context context, String assetName) throws IOException {
        try {
            return context.getAssets().open(assetName);
        } catch (IOException primary) {
            if (!"front_camera_sample_1.mp4".equals(assetName)) {
                return context.getAssets().open("front_camera_sample_1.mp4");
            }
            throw primary;
        }
    }
}
