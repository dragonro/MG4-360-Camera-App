// Updated: AdrianBega/DualBytes
package com.drivehub.kamera;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import java.io.FileInputStream;
import java.io.File;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import androidx.documentfile.provider.DocumentFile;

public class RecordingService extends Service {

    public static final String ACTION_START = "com.drivehub.kamera.action.START_RECORDING";
    public static final String ACTION_STOP = "com.drivehub.kamera.action.STOP_RECORDING";
    public static final String ACTION_STATE_CHANGED = "com.drivehub.kamera.action.RECORDING_STATE_CHANGED";
    public static final String ACTION_RECORDING_WARNING = "com.drivehub.kamera.action.RECORDING_WARNING";
    public static final String EXTRA_IS_RECORDING = "extra_is_recording";
    public static final String EXTRA_IS_ENABLED = "extra_is_enabled";
    public static final String EXTRA_WARNING_CODE = "extra_warning_code";
    public static final String WARNING_NOT_ENOUGH_SPACE = "not_enough_space_to_start";
    public static final String WARNING_STORAGE_FULL = "recording_stopped_storage_full";
    public static final String WARNING_PRUNE_FAILED = "recording_prune_failed";

    private static final String PREFS_NAME = "rec_prefs";
    private static final String KEY_ENABLED = "enabled";
    private static final String CHANNEL_ID = "mg4_recording";
    private static final int NOTIF_ID = 42;
    private static final int[] SLOT_IDS = {0, 1, 2, 3};
    private static final int[] CAMERA_INDICES = {15, 14, 16, 17};
    private static final String RECORDING_EXTENSION = ".mp4";
    private static final String RECORDING_MIME_TYPE = "video/mp4";
    private static final long DEFAULT_FRAME_DURATION_US = 33_333L;
    private static final long RECORDING_BITRATE_BITS_PER_SECOND = 2_500_000L;
    private static final int RECORDING_STORAGE_SAFETY_PERCENT = 120;
    private static final String TAG = "RecordingService";

    private Thread worker;
    private volatile boolean stopRequested;
    private volatile boolean recording;

    public static void startRecording(Context context) {
        Intent i = new Intent(context, RecordingService.class);
        i.setAction(ACTION_START);
        context.startForegroundService(i);
    }

    public static void stopRecording(Context context) {
        publishRecordingState(context, false);
        Intent i = new Intent(context, RecordingService.class);
        i.setAction(ACTION_STOP);
        context.startService(i);
    }

    public static boolean isRecording(Context context) {
        SharedPreferences prefs = UiPrefs.getPrefs(context);
        return prefs.getBoolean(EXTRA_IS_RECORDING, false);
    }

    public static boolean isRecordingEnabled(Context context) {
        return UiPrefs.isRecordingButtonEnabled(UiPrefs.getPrefs(context));
    }

    public static void startIfNeeded(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_ENABLED, false)) return;
        startRecording(context);
    }

    public static void stopIfRunning(Context context) {
        stopRecording(context);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            requestStop();
            return START_NOT_STICKY;
        }

        if (worker != null) {
            return START_STICKY;
        }

        startRecordingForeground(buildNotification(getString(R.string.notification_recording_starting)));

        SharedPreferences prefs = UiPrefs.getPrefs(this);
        if (!UiPrefs.isRecordingButtonEnabled(prefs)) {
            setRecordingState(false);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!canStartInitialRecording()) {
            setRecordingState(false);
            stopSelf();
            return START_NOT_STICKY;
        }

        stopRequested = false;
        setRecordingState(true);
        worker = new Thread(this::recordOnce, "RecordingServiceWorker");
        worker.start();
        return START_STICKY;
    }

    private boolean canStartInitialRecording() {
        File baseDir = RecordingStorageManager.getWritableBaseDir(this);
        if (!baseDir.exists() && !baseDir.mkdirs()) {
            publishStorageWarning(WARNING_NOT_ENOUGH_SPACE);
            return false;
        }
        int durationMin = UiPrefs.getRecordingDurationMin(UiPrefs.getPrefs(this));
        long segmentDurationMs = Math.max(60_000L, durationMin * 60_000L);
        File segmentBaseDir = RecordingStorageManager.getTreeUri(this) != null
                ? new File(getCacheDir(), "recording_tmp")
                : baseDir;
        if (!segmentBaseDir.exists() && !segmentBaseDir.mkdirs()) {
            publishStorageWarning(WARNING_NOT_ENOUGH_SPACE);
            return false;
        }
        RecordingStoragePolicy.Result result = RecordingStoragePolicy.ensureFileTargetSpace(
                this,
                segmentBaseDir,
                estimateSegmentBytes(segmentDurationMs),
                null
        );
        if (!result.ok) {
            publishStorageWarning(result.warningCode);
            return false;
        }
        return true;
    }

    private void startRecordingForeground(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, notification);
        }
    }

    private void recordOnce() {
        File baseDir = RecordingStorageManager.getWritableBaseDir(this);
        if (!baseDir.exists() && !baseDir.mkdirs()) {
            finishRecording();
            return;
        }

        int durationMin = UiPrefs.getRecordingDurationMin(UiPrefs.getPrefs(this));
        long segmentDurationMs = Math.max(60_000L, durationMin * 60_000L);
        String sessionStamp = buildTimestamp();
        int segmentIndex = 0;

        try {
            while (!stopRequested) {
                String segmentStamp = sessionStamp + "_" + String.format(Locale.US, "%02d", segmentIndex);
                if (!startSegment(baseDir, segmentStamp, segmentDurationMs, segmentIndex)) {
                    break;
                }
                segmentIndex++;
            }
        } finally {
            stopRecordingNative();
            finishRecording();
        }
    }

    private boolean startSegment(File baseDir, String segmentStamp, long segmentDurationMs, int segmentIndex) {
        boolean useTree = RecordingStorageManager.getTreeUri(this) != null;
        File segmentBaseDir = useTree ? new File(getCacheDir(), "recording_tmp") : baseDir;
        if (!segmentBaseDir.exists() && !segmentBaseDir.mkdirs()) {
            return false;
        }
        RecordingStoragePolicy.Result storageReady = RecordingStoragePolicy.ensureFileTargetSpace(
                this,
                segmentBaseDir,
                estimateSegmentBytes(segmentDurationMs),
                segmentStamp
        );
        if (!storageReady.ok) {
            publishStorageWarning(segmentIndex == 0
                    ? storageReady.warningCode
                    : normalizeStorageWarning(storageReady.warningCode));
            return false;
        }
        if (shouldUseDebugDemoRecording()) {
            Log.i(TAG, "Starting debug demo recording segment=" + segmentStamp);
            boolean ok = startDebugDemoSegment(segmentBaseDir, segmentStamp, segmentDurationMs);
            if (useTree && ok) {
                ok = copySegmentToTree(segmentBaseDir, segmentStamp);
                deleteSegmentFiles(segmentBaseDir, segmentStamp);
            }
            if (ok && !enforceCompletedSegmentQuota(useTree, segmentBaseDir, segmentStamp)) {
                return false;
            }
            return ok;
        }
        Log.i(TAG, "Starting native camera recording segment=" + segmentStamp);
        boolean[] started = new boolean[SLOT_IDS.length];
        for (int i = 0; i < SLOT_IDS.length; i++) {
            String outputPath = segmentFile(segmentBaseDir, segmentStamp, CAMERA_INDICES[i])
                    .getAbsolutePath();
            try {
                started[i] = CameraProbe.startMp4Record(SLOT_IDS[i], CAMERA_INDICES[i], outputPath,
                        720, 240, 15, 2_500_000);
            } catch (Throwable t) {
                Log.w(TAG, "Native recorder failed for camera " + CAMERA_INDICES[i], t);
                started[i] = false;
            }
            if (!started[i]) {
                break;
            }
        }

        if (!allStarted(started)) {
            stopRecordingNative();
            deleteSegmentFiles(segmentBaseDir, segmentStamp);
            return false;
        }

        sleepUntilSegmentEnd(segmentDurationMs);
        stopRecordingNative();
        if (useTree) {
            if (!copySegmentToTree(segmentBaseDir, segmentStamp)) {
                publishStorageWarning(WARNING_STORAGE_FULL);
                deleteSegmentFiles(segmentBaseDir, segmentStamp);
                return false;
            }
            deleteSegmentFiles(segmentBaseDir, segmentStamp);
        }
        if (!enforceCompletedSegmentQuota(useTree, segmentBaseDir, segmentStamp)) {
            return false;
        }
        return true;
    }

    private boolean enforceCompletedSegmentQuota(boolean useTree, File segmentBaseDir, String segmentStamp) {
        RecordingStoragePolicy.Result result = useTree
                ? RecordingStoragePolicy.enforceTreeQuota(
                this,
                RecordingStorageManager.resolveTreeDocument(this),
                null
        )
                : RecordingStoragePolicy.enforceFileTargetQuota(this, segmentBaseDir, null);
        if (!result.ok) {
            publishStorageWarning(normalizeStorageWarning(result.warningCode));
            return false;
        }
        return true;
    }

    private boolean copySegmentToTree(File segmentBaseDir, String segmentStamp) {
        DocumentFile tree = RecordingStorageManager.resolveTreeDocument(this);
        if (tree == null) return false;
        boolean copiedAll = true;
        for (int cameraIndex : CAMERA_INDICES) {
            File source = segmentFile(segmentBaseDir, segmentStamp, cameraIndex);
            if (!source.isFile()) {
                copiedAll = false;
                continue;
            }
            String name = source.getName();
            try {
                DocumentFile existing = tree.findFile(name);
                if (existing != null) existing.delete();
                DocumentFile outDoc = tree.createFile(RECORDING_MIME_TYPE, name);
                if (outDoc == null) {
                    copiedAll = false;
                    continue;
                }
                try (FileInputStream in = new FileInputStream(source);
                     OutputStream out = getContentResolver().openOutputStream(outDoc.getUri())) {
                    if (out == null) {
                        copiedAll = false;
                        continue;
                    }
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    out.flush();
                }
            } catch (Throwable t) {
                Log.w(TAG, "Could not copy recording segment to tree: " + name, t);
                copiedAll = false;
            }
        }
        return copiedAll;
    }

    private void deleteSegmentFiles(File segmentBaseDir, String segmentStamp) {
        for (int cameraIndex : CAMERA_INDICES) {
            File source = segmentFile(segmentBaseDir, segmentStamp, cameraIndex);
            //noinspection ResultOfMethodCallIgnored
            source.delete();
        }
    }

    private void sleepUntilSegmentEnd(long segmentDurationMs) {
        long startedAt = SystemClock.elapsedRealtime();
        long deadline = startedAt + segmentDurationMs;
        while (!stopRequested && SystemClock.elapsedRealtime() < deadline) {
            try {
                Thread.sleep(250L);
            } catch (InterruptedException ignored) {
                // Re-check stopRequested and deadline.
            }
        }
    }

    private boolean allStarted(boolean[] started) {
        for (boolean value : started) {
            if (!value) return false;
        }
        return true;
    }

    private boolean shouldUseDebugDemoRecording() {
        return BuildConfig.DEBUG && TestVideoSources.shouldUse(this);
    }

    private boolean startDebugDemoSegment(File segmentBaseDir, String segmentStamp, long segmentDurationMs) {
        long startedAt = SystemClock.elapsedRealtime();
        sleepUntilSegmentEnd(segmentDurationMs);
        long elapsedMs = Math.max(1000L, Math.min(segmentDurationMs, SystemClock.elapsedRealtime() - startedAt));
        long durationUs = elapsedMs * 1000L;
        for (int cameraIndex : CAMERA_INDICES) {
            File source = resolveDebugRecordingSource(cameraIndex);
            if (source == null || !source.isFile()) {
                Log.w(TAG, "Missing debug recording source for camera " + cameraIndex);
                return false;
            }
            File target = segmentFile(segmentBaseDir, segmentStamp, cameraIndex);
            if (!writeDebugVideoSegment(source, target, durationUs)) {
                Log.w(TAG, "Failed debug recording for camera " + cameraIndex + " source=" + source);
                deleteSegmentFiles(segmentBaseDir, segmentStamp);
                return false;
            }
        }
        return true;
    }

    @Nullable
    private File resolveDebugRecordingSource(int cameraIndex) {
        try {
            if (TestVideoSources.hasDebugAsset(this)) {
                return TestVideoSources.materializeDebugAsset(this, cameraIndex);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Could not materialize debug recording asset", t);
        }
        return TestVideoSources.getFile(this, cameraIndex);
    }

    private boolean writeDebugVideoSegment(File source, File target, long durationUs) {
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean muxerStarted = false;
        try {
            extractor.setDataSource(source.getAbsolutePath());
            int sourceTrack = selectVideoTrack(extractor);
            if (sourceTrack < 0) {
                return false;
            }
            extractor.selectTrack(sourceTrack);
            MediaFormat format = extractor.getTrackFormat(sourceTrack);
            muxer = new MediaMuxer(target.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int muxerTrack = muxer.addTrack(format);
            muxer.start();
            muxerStarted = true;

            int maxInputSize = format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                    ? format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                    : 1024 * 1024;
            ByteBuffer buffer = ByteBuffer.allocateDirect(Math.max(maxInputSize, 256 * 1024));
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long loopBaseUs = 0L;
            long firstSampleUs = -1L;
            long lastWrittenUs = -1L;
            int samplesWritten = 0;

            while (loopBaseUs < durationUs) {
                int sampleTrack = extractor.getSampleTrackIndex();
                if (sampleTrack < 0) {
                    if (samplesWritten == 0) return false;
                    loopBaseUs = lastWrittenUs + DEFAULT_FRAME_DURATION_US;
                    firstSampleUs = -1L;
                    extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                    continue;
                }
                if (sampleTrack != sourceTrack) {
                    extractor.advance();
                    continue;
                }
                buffer.clear();
                int size = extractor.readSampleData(buffer, 0);
                long sampleTimeUs = extractor.getSampleTime();
                if (size <= 0 || sampleTimeUs < 0) {
                    extractor.advance();
                    continue;
                }
                if (firstSampleUs < 0L) {
                    firstSampleUs = sampleTimeUs;
                }
                long presentationTimeUs = loopBaseUs + Math.max(0L, sampleTimeUs - firstSampleUs);
                if (presentationTimeUs >= durationUs) {
                    break;
                }
                info.set(0, size, presentationTimeUs, extractor.getSampleFlags());
                muxer.writeSampleData(muxerTrack, buffer, info);
                lastWrittenUs = presentationTimeUs;
                samplesWritten++;
                extractor.advance();
            }
            boolean ok = samplesWritten > 0 && target.isFile();
            if (!ok) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
            }
            Log.i(TAG, "Wrote debug recording " + target.getName() + " samples=" + samplesWritten);
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "Debug mux failed source=" + source + " target=" + target, t);
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            return false;
        } finally {
            try {
                if (muxer != null && muxerStarted) muxer.stop();
            } catch (Throwable ignored) {
            }
            try {
                if (muxer != null) muxer.release();
            } catch (Throwable ignored) {
            }
            try {
                extractor.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private int selectVideoTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) return i;
        }
        return -1;
    }

    private File segmentFile(File dir, String segmentStamp, int cameraIndex) {
        return new File(dir, segmentStamp + "_" + cameraIndex + RECORDING_EXTENSION);
    }

    private long estimateSegmentBytes(long segmentDurationMs) {
        long seconds = Math.max(1L, (segmentDurationMs + 999L) / 1000L);
        long bytesPerCamera = (RECORDING_BITRATE_BITS_PER_SECOND / 8L) * seconds;
        return ((bytesPerCamera * CAMERA_INDICES.length) * RECORDING_STORAGE_SAFETY_PERCENT) / 100L;
    }

    private String normalizeStorageWarning(@Nullable String warningCode) {
        if (WARNING_PRUNE_FAILED.equals(warningCode)) {
            return WARNING_PRUNE_FAILED;
        }
        if (WARNING_NOT_ENOUGH_SPACE.equals(warningCode)) {
            return WARNING_STORAGE_FULL;
        }
        return warningCode == null ? WARNING_STORAGE_FULL : warningCode;
    }

    private void publishStorageWarning(@Nullable String warningCode) {
        Intent warning = new Intent(ACTION_RECORDING_WARNING);
        warning.setPackage(getPackageName());
        warning.putExtra(EXTRA_WARNING_CODE, warningCode == null ? WARNING_STORAGE_FULL : warningCode);
        sendBroadcast(warning);
    }

    private void requestStop() {
        stopRequested = true;
        stopRecordingNative();
        Thread t = worker;
        if (t != null) {
            t.interrupt();
        } else {
            stopSelf();
        }
    }

    private void stopRecordingNative() {
        for (int slot : SLOT_IDS) {
            try {
                CameraProbe.stopMp4Record(slot);
            } catch (Throwable ignored) {
                // Keep shutting down even if one slot fails.
            }
        }
    }

    private void finishRecording() {
        worker = null;
        setRecordingState(false);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void setRecordingState(boolean value) {
        recording = value;
        publishRecordingState(this, value);
    }

    private static void publishRecordingState(Context context, boolean value) {
        if (context == null) return;
        SharedPreferences prefs = UiPrefs.getPrefs(context);
        SharedPreferences.Editor editor = prefs.edit()
                .putBoolean(EXTRA_IS_RECORDING, value);
        if (value) {
            long existingStartedAt = UiPrefs.getRecordingStartedAtMs(prefs);
            if (existingStartedAt <= 0L) {
                editor.putLong(UiPrefs.KEY_RECORDING_STARTED_AT_MS, SystemClock.elapsedRealtime());
            }
        } else {
            editor.putLong(UiPrefs.KEY_RECORDING_STARTED_AT_MS, 0L);
        }
        editor.apply();

        Intent state = new Intent(ACTION_STATE_CHANGED);
        state.setPackage(context.getPackageName());
        state.putExtra(EXTRA_IS_RECORDING, value);
        state.putExtra(EXTRA_IS_ENABLED, UiPrefs.isRecordingButtonEnabled(prefs));
        context.sendBroadcast(state);
    }

    private String buildTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
        return sdf.format(new Date());
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_recording),
                NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.createNotificationChannel(ch);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopRequested = true;
        stopRecordingNative();
        setRecordingState(false);
        Thread t = worker;
        if (t != null) {
            try {
                t.join(1000L);
            } catch (InterruptedException ignored) {
            }
        }
        super.onDestroy();
    }
}
