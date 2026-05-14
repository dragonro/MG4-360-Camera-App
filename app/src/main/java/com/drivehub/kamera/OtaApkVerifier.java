package com.drivehub.kamera;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.Locale;

final class OtaApkVerifier {

    private OtaApkVerifier() {
    }

    // Verification is intentionally isolated so download integrity stays testable without UI code.
    static VerificationResult verifyDownloadedApk(File apkFile, OtaUpdateManager.UpdateInfo info) {
        boolean success;
        String computed = "";
        String message;
        try {
            if (apkFile == null || !apkFile.exists()) {
                throw new IllegalStateException("Downloaded APK not found");
            }
            if (info == null || info.expectedSha256 == null || info.expectedSha256.isEmpty()) {
                throw new IllegalStateException("Missing expected SHA-256");
            }
            computed = computeSha256(apkFile);
            success = computed.equalsIgnoreCase(info.expectedSha256);
            message = success ? "OK" : "SHA256 mismatch";
        } catch (Exception e) {
            success = false;
            message = e.getClass().getSimpleName();
        }
        return new VerificationResult(success, computed, message);
    }

    private static String computeSha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
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
