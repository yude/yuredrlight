package jp.yude.yuredrlight;

import android.content.pm.PackageInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class MainActivity extends AppCompatActivity implements SensorEventListener {
    private static final String CHARS = "YUREyure";
    private static final Random RANDOM = new Random();

    private static final int BUFFER_SIZE = 30;

    private SensorManager sensorManager;
    private Sensor accel;
    private Sensor linearAccel;

    private final Object bufferLock = new Object();
    private final List<AccelerationData> dataBuffer = new ArrayList<>(BUFFER_SIZE);

    private YureWebSocketClient wsClient;

    private String yureId;
    private String userAgent;

    // UNIX time(ms) 換算のための基準
    private long referenceUnixTimeMs;
    private long referenceElapsedRealtimeMs;

    /** ACCELEROMETER使用時のみ、重力成分をローパスで推定する */
    private final float[] gravity = new float[]{0f, 0f, 0f};
    // センサ更新周期が一定でないため、経験則の係数（0.8〜0.98くらい）。大きいほど平滑。
    private static final float GRAVITY_ALPHA = 0.9f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d("YureBoot", "MainActivity.onCreate pid=" + android.os.Process.myPid());
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        yureId = generateYureId();
        userAgent = buildUserAgent();

        referenceUnixTimeMs = System.currentTimeMillis();
        referenceElapsedRealtimeMs = SystemClock.elapsedRealtime();

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            linearAccel = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
            accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }

        wsClient = new YureWebSocketClient(msg -> Log.d("YureWS", msg));
        Log.d("YureBoot", "MainActivity.onCreate done");
    }

    @Override
    protected void onResume() {
        Log.d("YureBoot", "MainActivity.onResume");
        super.onResume();

        // 画面を閉じても/スリープしても送信継続したいので、Serviceを開始
        YureForegroundService.start(this);

        // Activity自身ではセンサもWSも扱わない
    }

    @Override
    protected void onPause() {
        Log.d("YureBoot", "MainActivity.onPause");
        super.onPause();
        // スリープ中も送信を継続する要件のため、ここでは止めない
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // no-op
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        // Service側で処理するため、Activity側では何もしない
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

    public static String generateYureId() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            int index = RANDOM.nextInt(CHARS.length());
            sb.append(CHARS.charAt(index));
        }
        return sb.toString();
    }
}