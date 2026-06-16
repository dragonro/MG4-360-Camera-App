package com.drivehub.kamera;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;

import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import java.io.File;

final class RecordingStorageManager {

    private static final String DEFAULT_DIR_NAME = "mg4_cam_records";

    private RecordingStorageManager() {
    }

    static String getDisplayPath(Context context) {
        Uri treeUri = getTreeUri(context);
        if (treeUri == null) {
            return getDefaultFileDir(context).getAbsolutePath();
        }
        return treeUri.toString();
    }

    static File getWritableBaseDir(Context context) {
        return getDefaultFileDir(context);
    }

    @Nullable
    static Uri getTreeUri(Context context) {
        String raw = UiPrefs.getPrefs(context).getString(UiPrefs.KEY_RECORDING_TREE_URI, null);
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            return Uri.parse(raw);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static void setTreeUri(Context context, @Nullable Uri uri) {
        UiPrefs.getPrefs(context).edit()
                .putString(UiPrefs.KEY_RECORDING_TREE_URI, uri == null ? null : uri.toString())
                .apply();
    }

    static File resolveSegmentDir(Context context) {
        // For now we only guarantee the default filesystem path.
        // The UI can persist a tree URI, and the next step should route native recording output through it.
        return getDefaultFileDir(context);
    }

    private static File getDefaultFileDir(Context context) {
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File dir = new File(downloads, DEFAULT_DIR_NAME);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    @Nullable
    static DocumentFile resolveTreeDocument(Context context) {
        Uri uri = getTreeUri(context);
        if (uri == null) return null;
        return DocumentFile.fromTreeUri(context, uri);
    }
}
