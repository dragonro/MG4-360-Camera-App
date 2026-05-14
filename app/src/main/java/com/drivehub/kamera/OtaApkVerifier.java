package com.drivehub.kamera;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

final class OtaApkVerifier {

    private OtaApkVerifier() {
    }

    // Verification is intentionally isolated so download integrity stays testable without UI code.
    static VerificationResult verifyDownloadedApk(Context context, Uri apkUri, OtaUpdateManager.UpdateInfo info) {
        boolean success;
        String computed = "";
        String message;
        try (InputStream in = openApkStream(context, apkUri)) {
            if (info == null || info.expectedSha256 == null || info.expectedSha256.isEmpty()) {
                throw new IllegalStateException("Missing expected SHA-256");
            }
            computed = computeSha256(in);
            success = computed.equalsIgnoreCase(info.expectedSha256);
            message = success ? "OK" : "SHA256 mismatch";
        } catch (Exception e) {
            success = false;
            message = e.getClass().getSimpleName();
        }
        return new VerificationResult(success, computed, message);
    }

    private static InputStream openApkStream(Context context, Uri apkUri) throws Exception {
        if (context == null) {
            throw new IllegalStateException("Context missing");
        }
        if (apkUri == null) {
            throw new IllegalStateException("Downloaded APK not found");
        }
        String scheme = apkUri.getScheme();
        if ("file".equalsIgnoreCase(scheme)) {
            File apkFile = new File(apkUri.getPath());
            if (!apkFile.exists()) {
                throw new IllegalStateException("Downloaded APK not found");
            }
            return new FileInputStream(apkFile);
        }

        InputStream stream = context.getContentResolver().openInputStream(apkUri);
        if (stream == null) {
            throw new IllegalStateException("Downloaded APK not found");
        }
        return stream;
    }

    private static String computeSha256(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file)) {
            return computeSha256(in);
        }
    }

    private static String computeSha256(InputStream in) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
        }
        byte[] hash = digest.digest();
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format(Locale.US, "%02x", b));
        }
        return sb.toString();
    }

    static final class VerificationResult {
        final boolean success;
        final String computedSha256;
        final String message;

        VerificationResult(boolean success, String computedSha256, String message) {
            this.success = success;
            this.computedSha256 = computedSha256;
            this.message = message;
        }
    }
}
