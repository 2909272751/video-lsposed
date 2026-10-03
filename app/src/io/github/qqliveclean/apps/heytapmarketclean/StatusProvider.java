package io.github.qqliveclean.apps.heytapmarketclean;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/** 被 hook 进程 → 设置页 的只读状态查询通道。 */
public final class StatusProvider extends ContentProvider {
    static final Uri URI = Uri.parse("content://io.github.heytapmarketclean.status");
    static final String FILE = "compatibility_status";

    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!"get".equals(method)) return null;
        SharedPreferences p = prefs();
        Bundle result = new Bundle();
        result.putString("token", p.getString("token", ""));
        result.putLong("run", p.getLong("run", 0));
        result.putString("phase", p.getString("phase", ""));
        result.putInt("done", p.getInt("done", 0));
        result.putInt("total", p.getInt("total", 0));
        result.putLong("time", p.getLong("time", 0));
        for (String key : Config.FEATURES) {
            result.putString(key, p.getString(key, ""));
            result.putString(key + "_detail", p.getString(key + "_detail", ""));
        }
        return result;
    }

    /**
     * 落盘。乱序保护：广播无顺序保证，旧的晚到不能覆盖新结果。
     */
    static void record(Context context, Bundle extras) {
        SharedPreferences p = context.getSharedPreferences(FILE, 0);
        String token = extras.getString("token", "");
        long run = extras.getLong("run", 0);
        long previousRun = p.getLong("run", 0);
        if (run < previousRun) return;                                   // 旧 run，丢
        boolean sameRun = run == previousRun && token.equals(p.getString("token", ""));
        if (sameRun) {
            int previous = p.getInt("done", 0);
            int incoming = extras.getInt("done", 0);
            if (incoming < previous) return;                             // 进度回退，丢
            if (incoming == previous && "complete".equals(p.getString("phase", ""))
                    && !"complete".equals(extras.getString("phase", ""))) return;  // 完成态不被覆盖
        }
        SharedPreferences.Editor edit = p.edit();
        if (!sameRun) edit.clear();
        edit.putString("token", token).putLong("run", run)
                .putString("phase", extras.getString("phase", ""))
                .putInt("done", extras.getInt("done", 0))
                .putInt("total", extras.getInt("total", 0))
                .putLong("time", System.currentTimeMillis());
        String feature = extras.getString("feature", "");
        if (!feature.isEmpty()) {
            edit.putString(feature, extras.getString("state", ""));
            edit.putString(feature + "_detail", extras.getString("detail", ""));
        }
        edit.commit();
    }

    private SharedPreferences prefs() { return getContext().getSharedPreferences(FILE, 0); }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
