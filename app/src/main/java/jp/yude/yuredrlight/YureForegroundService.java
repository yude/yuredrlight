package jp.yude.yuredrlight;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 端末スリープ中も送信を継続するためのフォアグラウンドサービス。
 * Android 4.4(minSdk19) 対応:
 * - startForeground + PARTIAL_WAKE_LOCK を使う
 * - センサ取得とWebSocket送信をServiceへ集約
 */
public class YureForegroundService extends Service implements SensorEventListener {
    private static final String TAG = "YureSvc";

    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "yure_foreground";

    private static final int BUFFER_SIZE = 30;

    private SensorManager sensorManager;
    private Sensor accel;
    private Sensor linearAccel;

    private final Object bufferLock = new Object();
    private final List<AccelerationData> dataBuffer = new ArrayList<>(BUFFER_SIZE);

    private YureWebSocketClient wsClient;

    private String yureId;
    private String userAgent;

    private long referenceUnixTimeMs;
    private long referenceElapsedRealtimeMs;

    /** ACCELEROMETER使用時のみ、重力成分をローパスで推定する */
    private final float[] gravity = new float[]{0f, 0f, 0f};
    private static final float GRAVITY_ALPHA = 0.9f;

    private PowerManager.WakeLock wakeLock;

    /** y軸のオフセット推定（キャリブレーション用） */
    private double yBias = 0.0;
    private long lastCalibUpdateMs = 0L;

    // 静止判定: 線形加速度の大きさが小さいとき（単位 m/s^2）
    private static final double STILL_MAG_THRESHOLD = 0.25; // だいたい 0.25 m/s^2 以内を静止扱い
    // 静止判定: 急に大きい加速度が来たらキャリブレーションしない
    private static final double MAX_REASONABLE_MAG = 30.0;

    // オフセットの追従速度（小さいほどゆっくり）
    private static final double Y_BIAS_ALPHA = 0.995;
    // キャリブレーション更新間隔（ms）
    private static final long CALIBRATION_INTERVAL_MS = 200;

    public static void start(Context context) {
        Intent i = new Intent(context, YureForegroundService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(i);
        } else {
            context.startService(i);
        }
    }

    public static void stop(Context context) {
        Intent i = new Intent(context, YureForegroundService.class);
        context.stopService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");

        yureId = MainActivity.generateYureId();
        userAgent = buildUserAgent();

        referenceUnixTimeMs = System.currentTimeMillis();
        referenceElapsedRealtimeMs = SystemClock.elapsedRealtime();

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            linearAccel = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
            accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }

        wsClient = new YureWebSocketClient(msg -> Log.d("YureWS", msg));

        acquireWakeLock();
        startAsForeground();
        registerSensor();
        wsClient.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // START_STICKY: メモリ不足などで落ちた場合に再起動されやすくする
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        unregisterSensor();
        if (wsClient != null) {
            wsClient.close();
        }
        synchronized (bufferLock) {
            dataBuffer.clear();
        }
        releaseWakeLock();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void registerSensor() {
        if (sensorManager == null) return;
        Sensor target = linearAccel != null ? linearAccel : accel;
        if (target != null) {
            Log.d(TAG,"sensor="+target.getType());
            sensorManager.registerListener(this, target, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    private void unregisterSensor() {
        if (sensorManager == null) return;
        sensorManager.unregisterListener(this);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // no-op
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || event.values == null || event.values.length < 3) return;

        int type = event.sensor != null ? event.sensor.getType() : -1;
        if (type != Sensor.TYPE_LINEAR_ACCELERATION && type != Sensor.TYPE_ACCELEROMETER) return;

        long tMs = referenceUnixTimeMs + (SystemClock.elapsedRealtime() - referenceElapsedRealtimeMs);

        // raw
        double rx = event.values[0];
        double ry = event.values[1];
        double rz = event.values[2];

        // linear (重力除去済みのつもりの値)
        double lx = rx;
        double ly = ry;
        double lz = rz;

        if (type == Sensor.TYPE_ACCELEROMETER) {
            // 重力ベクトル推定
            gravity[0] = GRAVITY_ALPHA * gravity[0] + (1f - GRAVITY_ALPHA) * event.values[0];
            gravity[1] = GRAVITY_ALPHA * gravity[1] + (1f - GRAVITY_ALPHA) * event.values[1];
            gravity[2] = GRAVITY_ALPHA * gravity[2] + (1f - GRAVITY_ALPHA) * event.values[2];

            // 線形加速度（重力成分を引く）
            lx = rx - gravity[0];
            ly = ry - gravity[1];
            lz = rz - gravity[2];
        }

        // --- y軸キャリブレーション ---
        // 静止判定は「線形加速度の大きさ」で行う（ACCELEROMETER端末でも重力に邪魔されない）
        double mag = Math.sqrt(lx * lx + ly * ly + lz * lz);
        if (mag < MAX_REASONABLE_MAG) {
            long nowMs = SystemClock.elapsedRealtime();
            if (nowMs - lastCalibUpdateMs >= CALIBRATION_INTERVAL_MS) {
                lastCalibUpdateMs = nowMs;

                if (mag <= STILL_MAG_THRESHOLD) {
                    // yBiasを「観測された線形y(ly)」にゆっくり追従させる（EMA）
                    yBias = Y_BIAS_ALPHA * yBias + (1.0 - Y_BIAS_ALPHA) * ly;
                    if ((nowMs / 5000) != ((nowMs - CALIBRATION_INTERVAL_MS) / 5000)) {
                        Log.d(TAG, "yBias=" + yBias + " mag=" + mag + " (type=" + type + ")");
                    }
                }
            }
        }

        // 送信値: xはrawのまま、zは要望どおり重力除去、yはキャリブレーションで0に寄せる
        //   - ACCELEROMETER端末では zは(lz) になる
        //   - LINEAR_ACCELEROMETER端末では zは(lz==rz)
        double y = ly - yBias;
        double z = lz;

        AccelerationData data = new AccelerationData(
                yureId,
                rx,
                y,
                z,
                tMs,
                userAgent
        );

        AccelerationData[] flush = null;
        synchronized (bufferLock) {
            dataBuffer.add(data);
            if (dataBuffer.size() >= BUFFER_SIZE) {
                flush = dataBuffer.toArray(new AccelerationData[0]);
                dataBuffer.clear();
            }
        }

        if (flush != null) {
            wsClient.enqueue(YureJson.toJsonArray(flush));
        }
    }

    private void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Yure sender",
                    NotificationManager.IMPORTANCE_LOW
            );
            nm.createNotificationChannel(channel);
        }

        Intent activityIntent = new Intent(this, MainActivity.class);
        PendingIntent pi;
        // mutabilityフラグは新しいAPIで必須。古いAPIでは無視されるので常に付ける。
        pi = PendingIntent.getActivity(this, 0, activityIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }

        Notification n = b
                .setContentTitle("Yure sending")
                .setContentText("Sending sensor data in background")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();

        startForeground(NOTIFICATION_ID, n);
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null) return;
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yuredrlight:YureWakeLock");
        wakeLock.setReferenceCounted(false);
        try {
            wakeLock.acquire();
        } catch (Exception e) {
            Log.w(TAG, "wakelock acquire failed: " + e);
        }
    }

    private void releaseWakeLock() {
        if (wakeLock == null) return;
        try {
            if (wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception ignored) {
        }
        wakeLock = null;
    }

    private String buildUserAgent() {
        String versionName = "unknown";
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (info != null && info.versionName != null) {
                versionName = info.versionName;
            }
        } catch (Exception ignored) {
        }

        return String.format(
                "yuredrlight %s on %s %s",
                versionName,
                Build.MANUFACTURER,
                Build.MODEL
        );
    }
}
