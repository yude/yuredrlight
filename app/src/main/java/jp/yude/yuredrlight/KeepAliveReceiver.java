package jp.yude.yuredrlight;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

/**
 * 過酷な環境向け:
 * - 定期的にServiceを起こして生存確認する（AlarmManagerから呼ばれる）
 */
public class KeepAliveReceiver extends BroadcastReceiver {
    private static final String TAG = "YureKA";
    private static final long WAKE_MS = 15_000L; // 15秒だけ起こす

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "KeepAliveReceiver tick");

        // 短時間だけCPUを起こして送信処理に実行時間を与える
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl;
        try {
            if (pm != null) {
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yuredrlight:KeepAlive");
                wl.setReferenceCounted(false);
                wl.acquire(WAKE_MS);
            }
        } catch (Exception e) {
            Log.w(TAG, "wakelock acquire failed: " + e);
        }

        YureForegroundService.start(context);

        // Releaseはタイムアウトで自動解放される
    }
}
