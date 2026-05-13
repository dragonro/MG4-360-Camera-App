package com.drivehub.kamera;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class OtaUpdateManager {

    private static final String LATEST_RELEASE_URL =
            "https://api.github.com/repos/jamakr4/MG4-360-Camera-App/releases/latest";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private OtaUpdateManager() {
    }

    interface CheckCallback {
        void onResult(UpdateInfo info);
    }

    static final class UpdateInfo {
        final boolean success;
        final boolean updateAvailable;
        final String currentVersion;
        final String latestVersion;
        final String releaseName;
        final String downloadUrl;
        final String assetFileName;
        final String expectedSha256;
        final String message;

        UpdateInfo(
                boolean success,
                boolean updateAvailable,
                String currentVersion,
                String latestVersion,
                String releaseName,
                String downloadUrl,
                String assetFileName,
                String expectedSha256,
                String message
        ) {
            this.success = success;
            this.updateAvailable = updateAvailable;
            this.currentVersion = currentVersion;
            this.latestVersion = latestVersion;
            this.releaseName = releaseName;
            this.downloadUrl = downloadUrl;
            this.assetFileName = assetFileName;
            this.expectedSha256 = expectedSha256;
            this.message = message;
        }
    }

    interface VerifyCallback {
        void onResult(boolean success, String computedSha256, String message);
    }

    static void checkForUpdates(Context context, CheckCallback callback) {
        final Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            UpdateInfo info = fetchLatestRelease(appContext);
            MAIN_HANDLER.post(() -> callback.onResult(info));
        });
    }

    static long enqueueDownload(Context context, UpdateInfo info) {
        if (info == null || info.downloadUrl == null || info.downloadUrl.isEmpty()) {
            throw new IllegalArgumentException("No APK download URL available.");
        }

        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) {
            throw new IllegalStateException("DownloadManager not available.");
        }

        String fileName = sanitizeFileName(
                (info.assetFileName == null || info.assetFileName.trim().isEmpty())
                        ? String.format(Locale.US, "mg4-360-cam-%s.apk", info.latestVersion)
                        : info.assetFileName
        );

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(info.downloadUrl))
                .setTitle("360 Cam Update")
                .setDescription("Downloading version " + info.latestVersion)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true);

        return dm.enqueue(request);
    }

    static void verifyDownloadedApk(File apkFile, UpdateInfo info, VerifyCallback callback) {
        EXECUTOR.execute(() -> {
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
            final boolean resultSuccess = success;
            final String resultComputed = computed;
            final String resultMessage = message;
            MAIN_HANDLER.post(() -> callback.onResult(resultSuccess, resultComputed, resultMessage));
        });
    }

    private static UpdateInfo fetchLatestRelease(Context context) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(LATEST_RELEASE_URL).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(7000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "MG4-360-Camera-App/" + BuildConfig.VERSION_NAME);

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                return new UpdateInfo(
                        false,
                        false,
                        BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_NAME,
                        null,
                        null,
                        null,
                        null,
                        "HTTP " + status
                );
            }

            String json = readFully(connection.getInputStream());
            JSONObject release = new JSONObject(json);
            String latestVersion = normalizeVersion(
                    firstNonEmpty(
                            release.optString("tag_name", null),
                            release.optString("name", null),
                            BuildConfig.VERSION_NAME
                    )
            );
            String releaseName = firstNonEmpty(release.optString("name", null), latestVersion);

            ApkAsset apkAsset = selectApkAsset(release.optJSONArray("assets"));
            if (apkAsset == null) {
                return new UpdateInfo(
                        false,
                        false,
                        BuildConfig.VERSION_NAME,
                        latestVersion,
                        releaseName,
                        null,
                        null,
                        null,
                        context.getString(R.string.ota_error_no_apk)
                );
            }

            boolean updateAvailable = compareVersions(latestVersion, BuildConfig.VERSION_NAME) > 0;
            String expectedSha256 = null;
            if (updateAvailable) {
                HashAsset hashAsset = selectHashAsset(release.optJSONArray("assets"), apkAsset.name);
                if (hashAsset == null) {
                    return new UpdateInfo(
                            false,
                            false,
                            BuildConfig.VERSION_NAME,
                            latestVersion,
                            releaseName,
                            null,
                            null,
                            null,
                            context.getString(R.string.ota_error_no_hash)
                    );
                }
                String hashFileContent = readUrl(hashAsset.downloadUrl);
                expectedSha256 = parseExpectedSha256(hashFileContent, apkAsset.name);
                if (expectedSha256 == null || expectedSha256.isEmpty()) {
                    return new UpdateInfo(
                            false,
                            false,
                            BuildConfig.VERSION_NAME,
                            latestVersion,
                            releaseName,
                            null,
                            null,
                            null,
                            context.getString(R.string.ota_error_invalid_hash)
                    );
                }
            }
            return new UpdateInfo(
                    true,
                    updateAvailable,
                    BuildConfig.VERSION_NAME,
                    latestVersion,
                    releaseName,
                    apkAsset.downloadUrl,
                    apkAsset.name,
                    expectedSha256,
                    updateAvailable
                            ? context.getString(R.string.ota_status_update_available, latestVersion)
                            : context.getString(R.string.ota_status_up_to_date)
            );
        } catch (Exception t) {
            return new UpdateInfo(
                    false,
                    false,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_NAME,
                    null,
                    null,
                    null,
                    null,
                    t.getClass().getSimpleName()
            );
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static ApkAsset selectApkAsset(JSONArray assets) {
        if (assets == null || assets.length() == 0) return null;

        List<ApkAsset> candidates = new ArrayList<>();
        for (int i = 0; i < assets.length(); i += 1) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;
            String name = asset.optString("name", "");
            String downloadUrl = asset.optString("browser_download_url", "");
            if (!name.toLowerCase(Locale.US).endsWith(".apk") || downloadUrl.isEmpty()) continue;
            candidates.add(new ApkAsset(name, downloadUrl));
        }
        if (candidates.isEmpty()) return null;

        ApkAsset best = candidates.get(0);
        int bestScore = scoreAsset(best.name);
        for (int i = 1; i < candidates.size(); i += 1) {
            ApkAsset candidate = candidates.get(i);
            int score = scoreAsset(candidate.name);
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    private static HashAsset selectHashAsset(JSONArray assets, String apkFileName) {
        if (assets == null || assets.length() == 0 || apkFileName == null || apkFileName.isEmpty()) return null;
        String expectedSidecarName = apkFileName + ".sha256";
        HashAsset fallback = null;
        for (int i = 0; i < assets.length(); i += 1) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;
            String name = asset.optString("name", "");
            String downloadUrl = asset.optString("browser_download_url", "");
            if (downloadUrl.isEmpty()) continue;
            if (expectedSidecarName.equalsIgnoreCase(name)) {
                return new HashAsset(name, downloadUrl);
            }
            if ("SHA256SUMS".equalsIgnoreCase(name)) {
                fallback = new HashAsset(name, downloadUrl);
            }
        }
        return fallback;
    }

    private static int scoreAsset(String name) {
        String lower = name.toLowerCase(Locale.US);
        int score = 0;
        if (lower.endsWith(".apk")) score += 10;
        if (lower.contains("release")) score += 5;
        if (lower.contains("arm64")) score += 3;
        if (lower.contains("universal")) score += 2;
        if (lower.contains("debug")) score -= 8;
        return score;
    }

    private static String readFully(InputStream inputStream) throws Exception {
        try (InputStream in = inputStream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String readUrl(String url) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(7000);
            connection.setRequestProperty("Accept", "text/plain, application/octet-stream");
            connection.setRequestProperty("User-Agent", "MG4-360-Camera-App/" + BuildConfig.VERSION_NAME);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("HTTP " + status);
            }
            return readFully(connection.getInputStream());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String parseExpectedSha256(String hashFileContent, String apkFileName) {
        if (hashFileContent == null) return null;
        String[] lines = hashFileContent.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isEmpty()) continue;
            String[] parts = trimmed.split("\\s+");
            if (parts.length == 1 && isSha256(parts[0])) {
                return parts[0].toLowerCase(Locale.US);
            }
            if (parts.length >= 2 && isSha256(parts[0])) {
                String candidateName = parts[parts.length - 1].replace("*", "");
                if (apkFileName.equals(candidateName) || trimmed.endsWith(apkFileName)) {
                    return parts[0].toLowerCase(Locale.US);
                }
            }
        }
        return null;
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("(?i)[a-f0-9]{64}");
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

    private static String normalizeVersion(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        while (trimmed.startsWith("v") || trimmed.startsWith("V")) {
            trimmed = trimmed.substring(1);
        }
        return trimmed;
    }

    private static int compareVersions(String leftRaw, String rightRaw) {
        String left = normalizeVersion(leftRaw);
        String right = normalizeVersion(rightRaw);

        String[] leftParts = left.split("[^0-9]+");
        String[] rightParts = right.split("[^0-9]+");
        int max = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < max; i += 1) {
            int l = parsePart(leftParts, i);
            int r = parsePart(rightParts, i);
            if (l != r) return Integer.compare(l, r);
        }
        return 0;
    }

    private static int parsePart(String[] parts, int index) {
        if (index >= parts.length) return 0;
        String part = parts[index];
        if (part == null || part.isEmpty()) return 0;
        try {
            return Integer.parseInt(part);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String sanitizeFileName(String fileName) {
        return fileName.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static final class ApkAsset {
        final String name;
        final String downloadUrl;

        ApkAsset(String name, String downloadUrl) {
            this.name = name;
            this.downloadUrl = downloadUrl;
        }
    }

    private static final class HashAsset {
        final String name;
        final String downloadUrl;

        HashAsset(String name, String downloadUrl) {
            this.name = name;
            this.downloadUrl = downloadUrl;
        }
    }
}
