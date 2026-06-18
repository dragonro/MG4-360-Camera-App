// Author: AdrianBega/DualBytes
package com.drivehub.kamera;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

final class RecordingPermissions {

    private RecordingPermissions() {
    }

    static boolean needsLegacyStoragePermission(Context context) {
        return Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && RecordingStorageManager.getTreeUri(context) == null;
    }

    static boolean hasRequiredStoragePermission(Context context) {
        return !needsLegacyStoragePermission(context)
                || ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    static String[] requiredPermissions(Context context) {
        return needsLegacyStoragePermission(context)
                ? new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}
                : new String[0];
    }
}
