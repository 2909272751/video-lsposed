package io.github.qqliveclean.apps.heytapmarketclean;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Process;

/** 接收目标进程的上报；API 34+ 必须验发送方，否则任何应用都能伪造状态。 */
public final class StatusReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !"io.github.heytapmarketclean.REPORT".equals(intent.getAction())) return;
        if (Build.VERSION.SDK_INT >= 34) {
            int uid = getSentFromUid();
            String[] packages = context.getPackageManager().getPackagesForUid(uid);
            boolean fromTarget = false;
            if (packages != null) {
                for (String name : packages) if (Config.TARGET.equals(name)) { fromTarget = true; break; }
            }
            if (!fromTarget && uid != Process.INVALID_UID) return;
        }
        if (intent.getExtras() != null) StatusProvider.record(context, intent.getExtras());
    }
}
