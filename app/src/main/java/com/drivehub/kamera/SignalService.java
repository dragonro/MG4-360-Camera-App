package com.drivehub.kamera;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.app.ActivityManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Sadece sinyal bilgisini dinler:
 * - sol sinyal -> overlay sol kamera (v16)
 * - sağ sinyal -> overlay sağ kamera (v14)
 * - sinyal yok  -> overlay kapat
 *
 * Öncelik: Car API (reflection).
 * Fallback: SystemProperties polling (arcsoft.avm.mCurCarTurnLamp).
 */
public class SignalService extends Service {

    private static final String TAG = "SignalService";
    private static final String CHANNEL_ID = "mg4_signal";
    private static final int NOTIF_ID = 100;

    private static final int TURN_PROP_ID = 0x21409326;
    private static final int REVERSE_GEAR_VALUE = 2;
    private static final String OEM_AVM_PACKAGE = "com.saicmotor.hmi.aroundview";
    public static final String ACTION_ROUTE_CAMERA = "com.drivehub.kamera.ACTION_ROUTE_CAMERA";
    public static final String EXTRA_CAMERA_INDEX = "camera_index";

    private static volatile SignalService sInstance;

    private Object car;
    private Object lampManager;
    private Object lampCallbackProxy;

    private HandlerThread pollThread;
    private Handler pollHandler;
    private volatile boolean polling;
    private int lastLamp = Integer.MIN_VALUE;
    private int currentLamp = 0;
    private int currentGear = 0;
    private int currentMode = -1; // -1:init, 0:none, 1:left, 2:right, 3:reverse

    private static final String PREFS_NAME = "rec_prefs";
    private static final String KEY_OVERLAY_HIDE_DELAY_MS = "overlayHideDelayMs";
    private final Handler mainHandler = new Handler();
    private final Runnable hideRunnable = new Runnable() {
        @Override
        public void run() {
            OverlayService.hideOverlay(SignalService.this);
            Log.i(TAG, "Signal off (delayed) => overlay hide");
        }
    };

    public static void start(Context context) {
        Intent i = new Intent(context, SignalService.class);
        context.startForegroundService(i);
    }

    public static void requestRecheck() {
        SignalService inst = sInstance;
        if (inst == null) return;
        inst.mainHandler.post(() -> {
            inst.lastLamp = Integer.MIN_VALUE;
            inst.lastGear = Integer.MIN_VALUE;
            inst.currentMode = -1;
            inst.updateOverlayDecision();
        });
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Drivehub Kamera")
                .setContentText("Sinyal dinleyicisi aktif")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        startForeground(NOTIF_ID, n);

        boolean carOk = tryStartCarApiListener();
        if (!carOk) {
            Log.w(TAG, "Car API listener acilamadi, system property polling fallback.");
            startPropertyPollingFallback();
        }
        return START_STICKY;
    }

    private boolean tryStartCarApiListener() {
        try {
            Class<?> carClass = Class.forName("android.car.Car");
            Method createCar = carClass.getMethod("createCar", Context.class);
            car = createCar.invoke(null, this);
            if (car == null) return false;

            Method connect = carClass.getMethod("connect");
            connect.invoke(car);

            Method getCarManager = carClass.getMethod("getCarManager", String.class);
            lampManager = getCarManager.invoke(car, "lamp");
            if (lampManager == null) return false;

            Class<?> cbInterface = Class.forName("android.car.hardware.CarLampManager$CarLampEventCallback");
            lampCallbackProxy = Proxy.newProxyInstance(
                    cbInterface.getClassLoader(),
                    new Class[]{cbInterface},
                    new LampCallbackHandler()
            );

            Method register = lampManager.getClass().getMethod("registerCallback", cbInterface);
            register.invoke(lampManager, lampCallbackProxy);
            Log.i(TAG, "CarLampManager callback registered.");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "tryStartCarApiListener failed: " + t);
            return false;
        }
    }

    private class LampCallbackHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method == null) return null;
            String name = method.getName();
            if (!"onChangeEvent".equals(name) || args == null || args.length < 1 || args[0] == null) {
                return null;
            }
            Object carPropertyValue = args[0];
            int propId = readIntMethod(carPropertyValue, "getPropertyId", Integer.MIN_VALUE);
            if (propId != TURN_PROP_ID) return null;

            Object valueObj = carPropertyValue.getClass().getMethod("getValue").invoke(carPropertyValue);
            int lamp = (valueObj instanceof Integer) ? (Integer) valueObj : Integer.MIN_VALUE;
            currentLamp = lamp;
            // Car API başarısız/eksik olabileceği için vitesi mevcut property'den oku.
            currentGear = readGearFromSystemProperty();
            updateOverlayDecision();
            return null;
        }
    }

    private void startPropertyPollingFallback() {
        if (pollThread != null) return;
        pollThread = new HandlerThread("SignalPollThread");
        pollThread.start();
        pollHandler = new Handler(pollThread.getLooper());
        polling = true;
        pollHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!polling) return;
                currentLamp = readTurnLampFromSystemProperty();
                currentGear = readGearFromSystemProperty();
                updateOverlayDecision();
                pollHandler.postDelayed(this, 200);
            }
        });
    }

    private int readTurnLampFromSystemProperty() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class, String.class);
            String s = (String) get.invoke(null, "arcsoft.avm.mCurCarTurnLamp", "");
            if (s == null || s.isEmpty()) return 0;
            return Integer.parseInt(s);
        } catch (Throwable t) {
            return 0;
        }
    }

    private int readGearFromSystemProperty() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class, String.class);
            String s = (String) get.invoke(null, "arcsoft.avm.mCurCarGear", "");
            if (s == null || s.isEmpty()) return 0;
            return Integer.parseInt(s);
        } catch (Throwable t) {
            return 0;
        }
    }

    private int readIntMethod(Object target, String methodName, int def) {
        try {
            Object o = target.getClass().getMethod(methodName).invoke(target);
            if (o instanceof Integer) return (Integer) o;
        } catch (Throwable ignored) {
        }
        return def;
    }

    private void updateOverlayDecision() {
        // Değişiklik yoksa tekrar işlem yapma.
        if (currentLamp == lastLamp && currentGear == readCachedGear()) {
            return;
        }
        cacheGear(currentGear);
        lastLamp = currentLamp;

        int nextMode;
        if (currentGear == REVERSE_GEAR_VALUE) {
            nextMode = 3;
        } else if (currentLamp == 1) {
            nextMode = 1;
        } else if (currentLamp == 2) {
            nextMode = 2;
        } else {
            nextMode = 0;
        }

        if (nextMode == currentMode) return;
        currentMode = nextMode;

        // MainActivity görünürdeyse overlay kullanma; ana preview kamerasını değiştir.
        if (MainActivity.shouldBlockOverlay()) {
            mainHandler.removeCallbacks(hideRunnable);
            OverlayService.hideOverlay(this);
            int targetCamera = (nextMode == 3) ? 17 : (nextMode == 1) ? 16 : (nextMode == 2) ? 14 : 15;
            Intent i = new Intent(ACTION_ROUTE_CAMERA);
            i.setPackage(getPackageName());
            i.putExtra(EXTRA_CAMERA_INDEX, targetCamera);
            sendBroadcast(i);
            Log.i(TAG, "Main visible => route camera to main preview: v" + targetCamera);
            return;
        }

        // Ana ekran görünür değilse, overlay sadece ayar açıksa kullanılacak.
        if (!isOverlayEnabled()) {
            // Ayar kapalıyken olası bir overlay kalıntısını da gizle.
            mainHandler.removeCallbacks(hideRunnable);
            OverlayService.hideOverlay(this);
            Log.i(TAG, "Overlay disabled in settings => no overlay, only listening.");
            return;
        }

        if (nextMode == 3) { // reverse
            // Reverse için overlay İSTENMİYOR: varsa kapat, sadece OEM/ana ekran kalsın.
            mainHandler.removeCallbacks(hideRunnable);
            OverlayService.hideOverlay(this);
            Log.i(TAG, "Reverse => no overlay (disabled for reverse)");
            return;
        }

        // Reverse dışındaki sinyal durumları için: OEM öndeyse overlay göstermeyelim.
        if (isOemAvmInForeground()) {
            mainHandler.removeCallbacks(hideRunnable);
            OverlayService.hideOverlay(this);
            Log.i(TAG, "OEM AVM visible (turn signal) => skip overlay.");
            return;
        }

        if (nextMode == 1) {
            mainHandler.removeCallbacks(hideRunnable);
            OverlayService.showOverlay(this, 16);
            Log.i(TAG, "Left signal => overlay v16");
        } else if (nextMode == 2) {
            mainHandler.removeCallbacks(hideRunnable);
            OverlayService.showOverlay(this, 14);
            Log.i(TAG, "Right signal => overlay v14");
        } else {
            mainHandler.removeCallbacks(hideRunnable);
            long delayMs = readOverlayHideDelayMs();
            mainHandler.postDelayed(hideRunnable, delayMs);
            Log.i(TAG, "Signal/gear off => will hide overlay after " + delayMs + "ms");
        }
    }

    // Basit gear değişimi cache'i (mode geçişlerinin kaçmaması için).
    private int lastGear = Integer.MIN_VALUE;
    private int readCachedGear() {
        return lastGear;
    }
    private void cacheGear(int g) {
        lastGear = g;
    }

    private boolean isOverlayEnabled() {
        try {
            SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            return sp.getBoolean("overlayOnSignal", false);
        } catch (Throwable t) {
            return false;
        }
    }

    private long readOverlayHideDelayMs() {
        try {
            SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            long v = sp.getLong(KEY_OVERLAY_HIDE_DELAY_MS, 1500L);
            if (v < 0) v = 0;
            if (v > 30000L) v = 30000L;
            return v;
        } catch (Throwable ignored) {
            return 1500L;
        }
    }

    private boolean isOemAvmInForeground() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            // System app olduğumuz için getRunningTasks(1) genelde izinli.
            java.util.List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
            if (tasks == null || tasks.isEmpty()) return false;
            ActivityManager.RunningTaskInfo t = tasks.get(0);
            if (t.topActivity == null) return false;
            return OEM_AVM_PACKAGE.equals(t.topActivity.getPackageName());
        } catch (Throwable ignored) {
            return false;
        }
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            if (lampManager != null && lampCallbackProxy != null) {
                Method unregister = lampManager.getClass()
                        .getMethod("unregisterCallback", lampCallbackProxy.getClass().getInterfaces()[0]);
                unregister.invoke(lampManager, lampCallbackProxy);
            }
        } catch (Throwable ignored) {
        }

        try {
            if (car != null) {
                Method disconnect = car.getClass().getMethod("disconnect");
                disconnect.invoke(car);
            }
        } catch (Throwable ignored) {
        }

        polling = false;
        if (pollHandler != null) {
            pollHandler.removeCallbacksAndMessages(null);
        }
        if (pollThread != null) {
            pollThread.quitSafely();
            pollThread = null;
        }
        mainHandler.removeCallbacks(hideRunnable);
        OverlayService.hideOverlay(this);
        sInstance = null;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                "MG4 Signal",
                NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.createNotificationChannel(ch);
    }
}
