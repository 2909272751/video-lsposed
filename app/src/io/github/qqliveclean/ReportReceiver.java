package io.github.qqliveclean;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import java.util.ArrayList;

/** Stores a small compatibility snapshot sent by an injected target process. */
public final class ReportReceiver extends BroadcastReceiver {
    static final String PREFS = "compat_reports";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !"io.github.qqliveclean.COMPAT_REPORT".equals(intent.getAction())) return;
        String target = intent.getStringExtra("package");
        if (!Config.handles(target) || intent.getIntExtra("schema", 0) < 53) return;
        ArrayList<String> rows = intent.getStringArrayListExtra("rows");
        if (rows == null || rows.size() > 80) return;
        StringBuilder body = new StringBuilder();
        for (String row : rows) {
            if (row == null || row.length() > 1200) return;
            body.append(row).append('\n');
        }
        SharedPreferences.Editor edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        edit.putString(target + ".version", intent.getStringExtra("version"));
        edit.putBoolean(target + ".verified", intent.getBooleanExtra("verified", false));
        edit.putString(target + ".source", intent.getStringExtra("source"));
        edit.putInt(target + ".schema", intent.getIntExtra("schema", 0));
        edit.putLong(target + ".time", System.currentTimeMillis());
        edit.putString(target + ".rows", body.toString());
        edit.apply();
    }
}
