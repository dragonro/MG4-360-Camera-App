package com.drivehub.kamera;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import androidx.documentfile.provider.DocumentFile;

public class RecordingService extends Service {

    public static final String ACTION_START = "com.drivehub.kamera.action.START_RECORDING";
    public static final String ACTION_STOP = "com.drivehub.kamera.action.STOP_RECORDING";
    public static final String ACTION_STATE_CHANGED = "com.drivehub.kamera.action.RECORDING_STATE_CHANGED";
    public static final String EXTRA_IS_RECORDING = "extra_is_recording";
    public static final String EXTRA_IS_ENABLED = "extra_is_enabled";

    private static final String PREFS_NAME = "rec_prefs";
    private static final String KEY_ENABLED = "enabled";
    private static final String CHANNEL_ID = "mg4_recording";
    private static final int NOTIF_ID = 42;
    private static final int[] SLOT_IDS = {0, 1, 2, 3};
    private static final int[] CAMERA_INDICES = {15, 14, 16, 17};
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

        SharedPreferences prefs = UiPrefs.getPrefs(this);
        if (!UiPrefs.isRecordingButtonEnabled(prefs)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        stopRequested = false;
        prefs.edit().putLong(UiPrefs.KEY_RECORDING_STARTED_AT_MS, SystemClock.elapsedRealtime()).apply();
        setRecordingState(true);
        startRecordingForeground(buildNotification(getString(R.string.notification_recording_starting)));
        worker = new Thread(this::recordOnce, "RecordingServiceWorker");
        worker.start();
        return START_STICKY;
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
                String segmentStamp = sessionStamp + "_" + String.format(Locale.US, "%02d", segmentIndex++);
                if (!startSegment(baseDir, segmentStamp, segmentDurationMs)) {
                    break;
                }
            }
        } finally {
            stopRecordingNative();
            finishRecording();
        }
    }

    private boolean startSegment(File baseDir, String segmentStamp, long segmentDurationMs) {
        boolean useTree = RecordingStorageManager.getTreeUri(this) != null;
        File segmentBaseDir = useTree ? new File(getCacheDir(), "recording_tmp") : baseDir;
        if (!segmentBaseDir.exists() && !segmentBaseDir.mkdirs()) {
            return false;
        }
        boolean[] started = new boolean[SLOT_IDS.length];
        for (int i = 0; i < SLOT_IDS.length; i++) {
            String outputPath = new File(segmentBaseDir, segmentStamp + "_" + CAMERA_INDICES[i] + ".mpg")
                    .getAbsolutePath();
            try {
                started[i] = CameraProbe.startMp4Record(SLOT_IDS[i], CAMERA_INDICES[i], outputPath,
                        720, 240, 15, 2_500_000);
            } catch (Throwable t) {
                started[i] = false;
            }
            if (!started[i]) {
                break;
            }
        }

        if (!allStarted(started)) {
            stopRecordingNative();
            if (BuildConfig.DEBUG) {
                writeDebugFallbackRecordings(baseDir, segmentStamp);
                sleepUntilSegmentEnd(segmentDurationMs);
                return true;
            }
            return false;
        }

        sleepUntilSegmentEnd(segmentDurationMs);
        stopRecordingNative();
        if (useTree) {
            copySegmentToTree(segmentBaseDir, segmentStamp);
            deleteSegmentFiles(segmentBaseDir, segmentStamp);
        }
        return true;
    }

    private void copySegmentToTree(File segmentBaseDir, String segmentStamp) {
        DocumentFile tree = RecordingStorageManager.resolveTreeDocument(this);
        if (tree == null) return;
        for (int cameraIndex : CAMERA_INDICES) {
            File source = new File(segmentBaseDir, segmentStamp + "_" + cameraIndex + ".mpg");
            if (!source.isFile()) continue;
            String name = source.getName();
            try {
                DocumentFile existing = tree.findFile(name);
                if (existing != null) existing.delete();
                DocumentFile outDoc = tree.createFile("application/octet-stream", name);
                if (outDoc == null) continue;
                try (FileInputStream in = new FileInputStream(source);
                     OutputStream out = getContentResolver().openOutputStream(outDoc.getUri())) {
                    if (out == null) continue;
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    out.flush();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void deleteSegmentFiles(File segmentBaseDir, String segmentStamp) {
        for (int cameraIndex : CAMERA_INDICES) {
            File source = new File(segmentBaseDir, segmentStamp + "_" + cameraIndex + ".mpg");
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

    private void writeDebugFallbackRecordings(File baseDir, String segmentStamp) {
        for (int cameraIndex : CAMERA_INDICES) {
            File source = TestVideoSources.getFile(this, cameraIndex);
            File target = new File(baseDir, segmentStamp + "_" + cameraIndex + ".mpg");
            try {
                if (source.isFile()) {
                    copyFile(source, target);
                    Log.i(TAG, "Fallback copied " + source.getName() + " -> " + target.getName());
                } else {
                    writePlaceholderFile(target, cameraIndex);
                    Log.i(TAG, "Fallback wrote placeholder " + target.getName());
                }
            } catch (IOException e) {
                Log.w(TAG, "Fallback copy failed for " + source.getName(), e);
            }
        }
    }

    private void writePlaceholderFile(File target, int cameraIndex) throws IOException {
        try (FileOutputStream out = new FileOutputStream(target)) {
            String content = "debug-placeholder camera=" + cameraIndex + " ts=" + buildTimestamp() + "\n";
            out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.getFD().sync();
        }
    }

    private void copyFile(File source, File target) throws IOException {
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.getFD().sync();
        }
    }

    private void requestStop() {
        stopRequested = true;
        stopRecordingNative();
        Thread t = worker;
        if (t != null) {
            t.interrupt();
        }
        stopSelf();
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
        SharedPreferences prefs = UiPrefs.getPrefs(this);
        prefs.edit().putBoolean(EXTRA_IS_RECORDING, value).apply();
        if (!value) {
            prefs.edit().putLong(UiPrefs.KEY_RECORDING_STARTED_AT_MS, 0L).apply();
        }
        Intent state = new Intent(ACTION_STATE_CHANGED);
        state.setPackage(getPackageName());
        state.putExtra(EXTRA_IS_RECORDING, value);
        state.putExtra(EXTRA_IS_ENABLED, UiPrefs.isRecordingButtonEnabled(prefs));
        sendBroadcast(state);
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
