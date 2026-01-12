package jp.yude.yuredrlight;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.PowerManager;
import android.util.Log;

/**
 * ガラホ等で「スリープ中にDNS/回線が落ちる」ケース向け。
 * ネットワーク復帰を検知したら即サービスを起こして再接続を促す。
 *
 * 注意: CONNECTIVITY_CHANGE は新しいAndroidでは制限があるが、過酷端末(古い/特殊)用途では有効なことが多い。
 */
public class NetworkChangeReceiver extends BroadcastReceiver {
    private static final String TAG = "YureNet";
    private static final long WAKE_MS = 20_000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!"android.net.conn.CONNECTIVITY_CHANGE".equals(action)) {
            return;
        }

        boolean connected = isConnected(context);
        Log.d(TAG, "CONNECTIVITY_CHANGE connected=" + connected);
        if (!connected) return;

        // 復帰直後にCPUが寝る/スケジューリングされない端末向けに短時間wake
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        try {
            if (pm != null) {
                PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yuredrlight:NetChange");
                wl.setReferenceCounted(false);
                wl.acquire(WAKE_MS);
            }
        } catch (Exception e) {
            Log.w(TAG, "wakelock acquire failed: " + e);
        }

        YureForegroundService.start(context);
    }

    private static boolean isConnected(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Exception ignored) {
            return false;
        }
    }
}
