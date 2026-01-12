package jp.yude.yuredrlight;

import android.app.Application;
import android.util.Log;

/**
 * まずは「プロセスが起動しているか/即死していないか」を確実に観測するためのApplication。
 */
public class YureApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        Log.d("YureBoot", "Application.onCreate pid=" + android.os.Process.myPid());

        // 例外で即死している場合にlogcatへ出す（logcatに出る前に落ちるケースの保険）
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                Log.e("YureBoot", "UncaughtException in thread=" + t.getName(), e);
            } catch (Throwable ignored) {
            }
            if (prev != null) {
                prev.uncaughtException(t, e);
            }
        });
    }
}

