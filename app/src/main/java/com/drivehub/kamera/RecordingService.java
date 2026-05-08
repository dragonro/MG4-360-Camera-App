package com.drivehub.kamera;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Environment;
import android.os.IBinder;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

public class RecordingService extends Service {

    public static final String ACTION_START = "start_recording";
    public static final String ACTION_STOP = "stop_recording";

    private static final String PREFS_NAME = "rec_prefs";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_SEGMENT_MIN = "segmentMin";
    private static final String KEY_TOTAL_MIN = "totalMin";

    private static final String CHANNEL_ID = "mg4_recording";
    private static final int NOTIF_ID = 42;

    private Thread worker;
    private volatile boolean stopRequested = false;

    public static void startIfNeeded(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean enabled = prefs.getBoolean(KEY_ENABLED, false);
        if (!enabled) return;
        Intent i = new Intent(context, RecordingService.class);
        i.setAction(ACTION_START);
        context.startForegroundService(i);
    }

    public static void stopIfRunning(Context context) {
        Intent i = new Intent(context, RecordingService.class);
        i.setAction(ACTION_STOP);
        context.startService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        String action = intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stopRequested = true;
            // Kayıt devam ediyorsa native tarafı da anında durdur.
            // 4 slot sabit: 0=F (15), 1=R (17), 2=X (16), 3=Y (14)
            try {
                for (int s = 0; s < 4; s++) {
                    CameraProbe.stopMp4Record(s);
                }
            } catch (Throwable ignored) {
                // native hata verirse de servis yine kapanmaya devam etsin
            }
            // Worker thread uyuyorsa uyanması için interrupt et.
            if (worker != null) {
                worker.interrupt();
            }
            stopForeground(true);
            return START_NOT_STICKY;
        }

        if (worker != null) {
            // zaten çalışıyorsa tekrar başlatma
            return START_STICKY;
        }

        stopRequested = false;
        startForeground(NOTIF_ID, buildNotification("Kayıt başlatılıyor..."));
        worker = new Thread(this::recordLoop, "RecordingServiceWorker");
        worker.start();
        return START_STICKY;
    }

    private void recordLoop() {
        // NOT: Şimdilik hız bilgisini / sinyali değil; yalnızca MP4 kayıt çekiyoruz.
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean enabled = prefs.getBoolean(KEY_ENABLED, false);
        int segmentMin = prefs.getInt(KEY_SEGMENT_MIN, 3);
        int totalMin = prefs.getInt(KEY_TOTAL_MIN, 30);

        if (!enabled || segmentMin <= 0) {
            stopSelf();
            return;
        }

        File baseDir = getRecordsBaseDir();
        //noinspection ResultOfMethodCallIgnored
        baseDir.mkdirs();

        // output: 4 kameranın isimleri
        // F = v15 (Ön), R = v17 (Arka), X = v16 (Sol), Y = v14 (Sağ)
        int[] slots = new int[]{0, 1, 2, 3};
        int[] videoIndices = new int[]{15, 17, 16, 14};
        char[] names = new char[]{'F', 'R', 'X', 'Y'};

        long segmentMs = segmentMin * 60L * 1000L;

        // Toplam süreyi segment sayısına çevir: segmentMin=3, totalMin=30 => 10 segment tutulur
        int keepSegments = Math.max(1, totalMin / segmentMin);

        while (!stopRequested) {
            long now = System.currentTimeMillis();
            String ts = makeTimestampBase(now);
            File clipDir = baseDir; // direkt klasör içine koyuyoruz

            String[] outPaths = new String[4];
            for (int i = 0; i < 4; i++) {
                String fileName = ts + "_" + names[i] + ".mp4";
                File out = new File(clipDir, fileName);
                outPaths[i] = out.getAbsolutePath();
            }

            // 4 kamerayı aynı anda kayda başlat
            for (int i = 0; i < 4; i++) {
                // width=720 height=240 fps=15 bitrate default 2.5Mbps
                CameraProbe.startMp4Record(slots[i], videoIndices[i], outPaths[i], 720, 240, 15, 2500000);
            }

            // segment bitene kadar bekle
            long start = SystemClock.elapsedRealtime();
            while (!stopRequested && (SystemClock.elapsedRealtime() - start) < segmentMs) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                }
            }

            // 4 kamerayı durdur
            for (int i = 0; i < 4; i++) {
                CameraProbe.stopMp4Record(slots[i]);
            }

            // Retention: en eski segmentleri sil
            cleanupOldSegments(baseDir, keepSegments);

            // prefs kapatıldı mı?
            enabled = prefs.getBoolean(KEY_ENABLED, false);
            if (!enabled) break;
        }

        worker = null;
        stopForeground(true);
        stopSelf();
    }

    private void cleanupOldSegments(File baseDir, int keepSegments) {
        File[] files = baseDir.listFiles();
        if (files == null) return;

        // baseName => earliestModified
        Map<String, Long> groupTime = new HashMap<>();
        for (File f : files) {
            String name = f.getName();
            if (!name.endsWith(".mp4")) continue;
            // yyaaggssdd_X.mp4
            int underscore = name.indexOf('_');
            if (underscore <= 0) continue;
            String base = name.substring(0, underscore);
            long t = f.lastModified();
            groupTime.merge(base, t, Math::min);
        }

        List<Map.Entry<String, Long>> groups = new ArrayList<>(groupTime.entrySet());
        groups.sort(Comparator.comparingLong(Map.Entry::getValue));

        if (groups.size() <= keepSegments) return;
        int deleteCount = groups.size() - keepSegments;

        char[] suffixes = new char[]{'F', 'R', 'X', 'Y'};
        for (int i = 0; i < deleteCount; i++) {
            String base = groups.get(i).getKey();
            for (char s : suffixes) {
                File f = new File(baseDir, base + "_" + s + ".mp4");
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    private File getRecordsBaseDir() {
        // Android 9: Downloads klasörüne direkt yazacağız.
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File dir = new File(downloads, "mg4_cam_records");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    private String makeTimestampBase(long epochMs) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(epochMs);
        int yy = cal.get(java.util.Calendar.YEAR) % 100;
        int aa = cal.get(java.util.Calendar.MONTH) + 1;
        int gg = cal.get(java.util.Calendar.DAY_OF_MONTH);
        int ss = cal.get(java.util.Calendar.HOUR_OF_DAY);
        int dd = cal.get(java.util.Calendar.MINUTE);
        return String.format(Locale.US, "%02d%02d%02d%02d%02d", yy, aa, gg, ss, dd);
    }

    private Notification buildNotification(String text) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Drivehub Kamera")
                .setContentText(text)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);
        return b.build();
    }

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                CHANNEL_ID,
                NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopRequested = true;
        if (worker != null) {
            try {
                worker.join(1000);
            } catch (InterruptedException ignored) {
            }
        }
        super.onDestroy();
    }
}

