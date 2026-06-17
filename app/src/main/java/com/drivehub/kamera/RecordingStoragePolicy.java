// Author: AdrianBega/DualBytes
package com.drivehub.kamera;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RecordingStoragePolicy {

    static final class Result {
        final boolean ok;
        @Nullable
        final String warningCode;

        private Result(boolean ok, @Nullable String warningCode) {
            this.ok = ok;
            this.warningCode = warningCode;
        }

        static Result ok() {
            return new Result(true, null);
        }

        static Result fail(String warningCode) {
            return new Result(false, warningCode);
        }
    }

    private static final String TAG = "RecordingStoragePolicy";
    private static final Pattern RECORDING_FILE_PATTERN = Pattern.compile(
            "^(\\d{8}_\\d{6}_\\d{2})_(14|15|16|17)\\.mp4$"
    );

    private RecordingStoragePolicy() {
    }

    static Result ensureFileTargetSpace(
            Context context,
            File targetDir,
            long requiredBytes,
            @Nullable String activeSegmentKey
    ) {
        SharedPreferences prefs = UiPrefs.getPrefs(context);
        boolean loopRecording = UiPrefs.isLoopRecordingEnabled(prefs);
        List<SegmentGroup<File>> groups = listFileGroups(targetDir, activeSegmentKey);
        long currentBytes = sumGroupBytes(groups);
        long quotaBytes = calculateQuotaBytes(targetDir.getUsableSpace(), currentBytes,
                UiPrefs.getRecordingStorageQuotaPercent(prefs));
        if (currentBytes + requiredBytes <= quotaBytes) {
            return Result.ok();
        }
        if (!loopRecording) {
            return Result.fail(RecordingService.WARNING_NOT_ENOUGH_SPACE);
        }
        DeleteResult deleteResult = deleteOldestFileGroups(groups, currentBytes + requiredBytes - quotaBytes);
        if (deleteResult.deleteFailed) {
            return Result.fail(RecordingService.WARNING_PRUNE_FAILED);
        }
        if (!deleteResult.freedEnough && !canTemporarilyFitNextLoopSegment(targetDir, requiredBytes)) {
            return Result.fail(RecordingService.WARNING_NOT_ENOUGH_SPACE);
        }
        groups = listFileGroups(targetDir, activeSegmentKey);
        currentBytes = sumGroupBytes(groups);
        quotaBytes = calculateQuotaBytes(targetDir.getUsableSpace(), currentBytes,
                UiPrefs.getRecordingStorageQuotaPercent(prefs));
        if (currentBytes + requiredBytes <= quotaBytes) {
            return Result.ok();
        }
        if (canTemporarilyFitNextLoopSegment(targetDir, requiredBytes)) {
            Log.i(TAG, "Allowing loop recording segment outside quota until completed segment pruning runs");
            return Result.ok();
        }
        return Result.fail(RecordingService.WARNING_NOT_ENOUGH_SPACE);
    }

    static Result enforceFileTargetQuota(
            Context context,
            File targetDir,
            @Nullable String activeSegmentKey
    ) {
        return ensureFileTargetSpace(context, targetDir, 0L, activeSegmentKey);
    }

    static Result enforceTreeQuota(
            Context context,
            @Nullable DocumentFile tree,
            @Nullable String activeSegmentKey
    ) {
        // Android does not reliably expose free bytes for arbitrary SAF tree URIs.
        // Keep SAF safe: never delete based on a guessed quota denominator.
        return Result.ok();
    }

    private static long calculateQuotaBytes(long freeBytes, long currentAppBytes, int quotaPercent) {
        long reclaimableTotal = Math.max(0L, freeBytes) + Math.max(0L, currentAppBytes);
        return Math.max(0L, (reclaimableTotal * UiPrefs.clampRecordingStorageQuotaPercent(quotaPercent)) / 100L);
    }

    private static boolean canTemporarilyFitNextLoopSegment(File targetDir, long requiredBytes) {
        return requiredBytes > 0L && targetDir.getUsableSpace() >= requiredBytes;
    }

    private static List<SegmentGroup<File>> listFileGroups(File targetDir, @Nullable String activeSegmentKey) {
        File[] files = targetDir.listFiles();
        if (files == null || files.length == 0) {
            return Collections.emptyList();
        }
        Map<String, SegmentGroup<File>> groups = new HashMap<>();
        for (File file : files) {
            if (file == null || !file.isFile()) continue;
            String name = file.getName();
            Matcher matcher = RECORDING_FILE_PATTERN.matcher(name);
            if (!matcher.matches()) continue;
            String key = matcher.group(1);
            if (key.equals(activeSegmentKey)) continue;
            SegmentGroup<File> group = groups.get(key);
            if (group == null) {
                group = new SegmentGroup<>(key);
                groups.put(key, group);
            }
            group.items.add(file);
            group.bytes += Math.max(0L, file.length());
            long modified = file.lastModified();
            if (group.oldestModifiedMs == 0L || (modified > 0L && modified < group.oldestModifiedMs)) {
                group.oldestModifiedMs = modified;
            }
        }
        return sortedGroups(groups);
    }

    private static List<SegmentGroup<DocumentFile>> listTreeGroups(DocumentFile tree, @Nullable String activeSegmentKey) {
        DocumentFile[] files;
        try {
            files = tree.listFiles();
        } catch (Throwable t) {
            return Collections.emptyList();
        }
        Map<String, SegmentGroup<DocumentFile>> groups = new HashMap<>();
        for (DocumentFile file : files) {
            if (file == null || !file.isFile()) continue;
            String name = file.getName();
            if (name == null) continue;
            Matcher matcher = RECORDING_FILE_PATTERN.matcher(name);
            if (!matcher.matches()) continue;
            String key = matcher.group(1);
            if (key.equals(activeSegmentKey)) continue;
            SegmentGroup<DocumentFile> group = groups.get(key);
            if (group == null) {
                group = new SegmentGroup<>(key);
                groups.put(key, group);
            }
            group.items.add(file);
            group.bytes += Math.max(0L, file.length());
            long modified = file.lastModified();
            if (group.oldestModifiedMs == 0L || (modified > 0L && modified < group.oldestModifiedMs)) {
                group.oldestModifiedMs = modified;
            }
        }
        return sortedGroups(groups);
    }

    private static <T> List<SegmentGroup<T>> sortedGroups(Map<String, SegmentGroup<T>> groups) {
        List<SegmentGroup<T>> sorted = new ArrayList<>(groups.values());
        sorted.sort(Comparator
                .comparingLong((SegmentGroup<T> group) -> group.oldestModifiedMs > 0L
                        ? group.oldestModifiedMs
                        : Long.MAX_VALUE)
                .thenComparing(group -> group.key));
        return sorted;
    }

    private static long sumGroupBytes(List<? extends SegmentGroup<?>> groups) {
        long total = 0L;
        for (SegmentGroup<?> group : groups) {
            total += Math.max(0L, group.bytes);
        }
        return total;
    }

    private static DeleteResult deleteOldestFileGroups(List<SegmentGroup<File>> groups, long bytesToFree) {
        long freed = 0L;
        for (SegmentGroup<File> group : groups) {
            boolean deletedAll = true;
            for (File file : group.items) {
                if (file.exists() && !file.delete()) {
                    Log.w(TAG, "Could not delete old recording file=" + file.getAbsolutePath());
                    deletedAll = false;
                }
            }
            if (!deletedAll) {
                Log.w(TAG, "Could not delete recording segment group=" + group.key);
                return DeleteResult.failed();
            }
            freed += group.bytes;
            Log.i(TAG, "Deleted old recording segment group=" + group.key + " bytes=" + group.bytes);
            if (freed >= bytesToFree) {
                return DeleteResult.freedEnough();
            }
        }
        return freed >= bytesToFree ? DeleteResult.freedEnough() : DeleteResult.notEnough();
    }

    private static boolean deleteOldestTreeGroups(List<SegmentGroup<DocumentFile>> groups, long bytesToFree) {
        long freed = 0L;
        for (SegmentGroup<DocumentFile> group : groups) {
            boolean deletedAll = true;
            for (DocumentFile file : group.items) {
                if (file.exists() && !file.delete()) {
                    deletedAll = false;
                }
            }
            if (!deletedAll) {
                Log.w(TAG, "Could not delete tree recording segment group=" + group.key);
                return false;
            }
            freed += group.bytes;
            Log.i(TAG, "Deleted old tree recording segment group=" + group.key + " bytes=" + group.bytes);
            if (freed >= bytesToFree) {
                return true;
            }
        }
        return freed >= bytesToFree;
    }

    private static final class SegmentGroup<T> {
        final String key;
        final List<T> items = new ArrayList<>();
        long bytes;
        long oldestModifiedMs;

        SegmentGroup(String key) {
            this.key = key;
        }
    }

    private static final class DeleteResult {
        final boolean freedEnough;
        final boolean deleteFailed;

        private DeleteResult(boolean freedEnough, boolean deleteFailed) {
            this.freedEnough = freedEnough;
            this.deleteFailed = deleteFailed;
        }

        static DeleteResult freedEnough() {
            return new DeleteResult(true, false);
        }

        static DeleteResult notEnough() {
            return new DeleteResult(false, false);
        }

        static DeleteResult failed() {
            return new DeleteResult(false, true);
        }
    }
}
