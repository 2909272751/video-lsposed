package io.github.qqliveclean;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * Read-only config channel from this module's settings page to the hooked process.
 *
 * Why this exists: the settings page originally used {@code XposedServiceHelper} to reach
 * LSPosed's RemotePreferences, but on this device lspd never calls this module's
 * {@code XposedService} provider with {@code SendBinder}, so {@code onServiceBind} never
 * fires and every switch was unusable (it stayed broken across reboots, and the working
 * reference module behaves the same way once inspected). Rather than depend on that
 * handshake, the settings page writes ordinary private SharedPreferences and this exported
 * provider serves them to com.tencent.qqlive, which reads them once per launch.
 *
 * Only the whitelisted preference keys are ever exposed - no arbitrary key reads.
 */
public final class ConfigProvider extends ContentProvider {
    public static final String AUTHORITY = "io.github.qqliveclean.config";
    /** Extras contract used by {@link Config#readFromProvider}. */
    public static final String KEY_AVAILABLE = "_available";

    @Override public boolean onCreate() {
        return true;
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!Config.CALL_GET.equals(method)) return null;
        Context context = getContext();
        if (context == null) return null;
        SharedPreferences preferences =
                context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE);
        Bundle bundle = new Bundle();
        bundle.putBoolean(KEY_AVAILABLE, true);
        for (String key : Config.EXPOSED_KEYS) {
            bundle.putBoolean(key, preferences.getBoolean(key, Config.defaultFor(key)));
        }
        return bundle;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
