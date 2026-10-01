package io.github.qqliveclean;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Settings-side storage.
 *
 * This deliberately does NOT use LSPosed's RemotePreferences any more. That path needs lspd
 * to hand this app an {@code XposedService} binder via {@code SendBinder}, which never
 * happens for this module (verified: {@code onServiceBind} never fires, across reboots, and
 * the reference music module's own process is not injected either). Every switch was
 * therefore dead.
 *
 * Now the settings page writes plain private SharedPreferences, and {@link ConfigProvider}
 * serves exactly the whitelisted keys to the hooked process, which reads them once per
 * launch. No framework handshake, so no way for it to silently break.
 */
public final class App extends Application {
    private static volatile String lastError;

    private static SharedPreferences store(Context context) {
        return context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE);
    }

    static boolean read(Context context, String key, boolean fallback) {
        if (context == null) return fallback;
        try {
            return store(context).getBoolean(key, fallback);
        } catch (Throwable error) {
            lastError = "read " + key + ": " + error;
            Log.w(Config.TAG, "settings: " + lastError);
            return fallback;
        }
    }

    static boolean write(Context context, String key, boolean value) {
        return write(context, key, value, null);
    }

    /**
     * Commits the change and, when {@code targetPackage} is given, hands the whole payload to that
     * app so it takes effect without waiting for the provider.
     *
     * <p>This delivery step used to be missing: {@link #applyToTarget} had no caller at all, so on
     * a device where the provider is unreachable (package visibility) and the file routes are
     * blocked (scoped storage), every switch silently fell back to its built-in default while the
     * UI still reported success. Shipping the payload on write is what makes the setting real.
     */
    static boolean write(Context context, String key, boolean value, String targetPackage) {
        if (context == null) return false;
        try {
            boolean ok = store(context).edit().putBoolean(key, value).commit();
            if (!ok) lastError = "commit returned false for " + key;
            publish(context);
            if (ok && targetPackage != null) applyToTarget(context, targetPackage);
            return ok;
        } catch (Throwable error) {
            lastError = "write " + key + ": " + error;
            Log.w(Config.TAG, "settings: " + lastError);
            return false;
        }
    }

    static boolean reset(Context context) {
        if (context == null) return false;
        try {
            boolean ok = store(context).edit().clear().commit();
            lastError = null;
            publish(context);
            return ok;
        } catch (Throwable error) {
            lastError = "reset: " + error;
            Log.w(Config.TAG, "settings: " + lastError);
            return false;
        }
    }

    /** Grants target apps provider visibility and publishes the legacy file fallback.
     * The provider supplies live settings; a successful read updates the target cache.
     */
    static void publish(Context context) {
        if (context == null) return;
        // URI grants also make the provider package visible to the recipients on
        // Android 11+. Targets cannot add our authority to their own manifest.
        android.net.Uri settingsUri = android.net.Uri.parse(
                "content://" + ConfigProvider.AUTHORITY + "/settings");
        for (String target : Config.PACKAGES) {
            try {
                context.grantUriPermission(target, settingsUri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable error) {
                Log.w(Config.TAG, "settings: grant failed for " + target + ": "
                        + error.getClass().getSimpleName());
            }
        }
        SharedPreferences preferences = store(context);
        StringBuilder builder = new StringBuilder("# QQLiveClean settings\n");
        for (String key : Config.EXPOSED_KEYS) {
            builder.append(key).append('=')
                    .append(preferences.getBoolean(key, Config.defaultFor(key)))
                    .append('\n');
        }
        byte[] payload;
        try {
            payload = builder.toString().getBytes("UTF-8");
        } catch (Throwable error) {
            lastError = "publish encode: " + error;
            return;
        }
        StringBuilder report = new StringBuilder();
        for (String path : Config.CHANNEL_CANDIDATES) {
            java.io.FileOutputStream output = null;
            try {
                java.io.File file = new java.io.File(path);
                java.io.File parent = file.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    report.append(path).append("=nomkdir ");
                    continue;
                }
                output = new java.io.FileOutputStream(file);
                output.write(payload);
                output.flush();
                report.append(path).append("=ok ");
                try {
                    file.setReadable(true, false);
                    file.setExecutable(false, false);
                } catch (Throwable ignored) {}
            } catch (Throwable error) {
                report.append(path).append('=').append(error.getClass().getSimpleName()).append(' ');
            } finally {
                if (output != null) { try { output.close(); } catch (Throwable ignored) {} }
            }
        }
        Log.i(Config.TAG, "settings: publish " + report.toString().trim());
        lastError = null;
    }

    /**
     * Hands the current settings to Tencent Video by launching it with a config extra.
     *
     * Legacy intent delivery fallback; the provider route was previously
     * blocked by package visibility, file routes by scoped storage, and lspd never delivers
     * the RemotePreferences binder. Starting the target is allowed because this app's manifest
     * declares <queries> for it and the user is in the foreground.
     */
    static boolean applyToTarget(Context context) {
        return applyToTarget(context, Config.PACKAGE);
    }

    /** Same channel for any handled target (Tencent Video or 优酷). */
    static boolean applyToTarget(Context context, String packageName) {
        if (context == null) return false;
        try {
            android.content.Intent intent =
                    context.getPackageManager().getLaunchIntentForPackage(packageName);
            if (intent == null) {
                lastError = Config.appLabel(packageName) + "未安装";
                return false;
            }
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(Config.EXTRA_CONFIG, Config.exportPayload(context));
            context.startActivity(intent);
            Log.i(Config.TAG, "settings: applied via intent extra to " + packageName);
            return true;
        } catch (Throwable error) {
            lastError = "applyToTarget: " + error;
            Log.w(Config.TAG, "settings: " + lastError);
            return false;
        }
    }

    /** Reason the last storage operation failed, surfaced in the UI. */
    static String lastError() {
        return lastError;
    }

    @Override public void onCreate() {
        super.onCreate();
        Log.i(Config.TAG, "settings: ready, store=" + Config.PREFS_NAME);
    }
}
