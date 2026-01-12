package jp.yude.yuredrlight;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 過酷な環境向け:
 * - 端末再起動後に送信サービスを自動再開する
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "YureBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Log.d(TAG, "BootReceiver onReceive action=" + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            YureForegroundService.start(context);
        }
    }
}

