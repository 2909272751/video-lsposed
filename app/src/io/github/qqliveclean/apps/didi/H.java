package io.github.qqliveclean.apps.didi;

import android.app.BroadcastOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import io.github.qqliveclean.RuleHost;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 日志 / 命中统计 / 兼容报告上报。
 * 兼容报告参考 video-lsposed（Codex 修复版）的设计：目标进程把「逐条规则结果」按
 * key \t outcome \t hit \t why 组装成 rows 一次性回传，设置页用 CompatibilityScanner 式渲染。
 */
public final class H {
    private H() {}

    private static final ConcurrentHashMap<String, AtomicBoolean> ONCE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicInteger> HITS = new ConcurrentHashMap<>();
    private static final StringBuilder INSTALLED = new StringBuilder();
    private static final AtomicInteger HOOKED = new AtomicInteger();
    private static final AtomicInteger MISS = new AtomicInteger();

    private static final ArrayList<String> ROWS = new ArrayList<>();
    private static final ConcurrentHashMap<String, Integer> ROW_INDEX = new ConcurrentHashMap<>();

    private static volatile RuleHost module;
    private static volatile Context appContext;
    private static volatile String token = "";
    private static volatile long run;
    private static volatile String source = "defaults";
    private static volatile String host = "未知";
    private static volatile String compatDetail = "";
    private static volatile boolean compatOk;

    public static void bind(RuleHost m, Context context, String reportToken, long reportRun) {
        module = m;
        appContext = context;
        token = reportToken == null ? "" : reportToken;
        run = reportRun;
    }

    public static void setSource(String value) {
        if (value != null) source = value;
    }

    public static void attachContext(Context context) {
        if (context == null) return;
        appContext = context;
    }

    public static void setCompat(boolean ok, String detail) {
        compatOk = ok;
        compatDetail = detail == null ? "" : detail;
    }

    public static void detectVersion(ClassLoader loader) {
        Context context = appContext;
        if (context == null) return;
        try {
            android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(Config.PACKAGE, 0);
            host = info.versionName + "(" + info.versionCode + ")";
            token = info.versionCode + ":" + info.lastUpdateTime + ":" + Config.REPORT_SCHEMA;
            info("host=" + host + " tracker=" + token);
            report("start", "", "", "Host=" + host);
            if (compatDetail.length() > 0) {
                report("compat", "compat", compatOk ? "matched" : "partial", compatDetail);
            }
            sendRows();
        } catch (Throwable t) {
            warn("version detect failed: " + t);
        }
    }

    // ------------------------------------------------------------------ 日志

    public static void info(String message) {
        RuleHost m = module;
        if (m == null) return;
        try { m.log(Log.INFO, Config.TAG, "[schema=" + Config.REPORT_SCHEMA + "] " + message); } catch (Throwable ignored) {}
    }

    public static void warn(String message) {
        RuleHost m = module;
        if (m == null) return;
        try { m.log(Log.WARN, Config.TAG, "[schema=" + Config.REPORT_SCHEMA + "] " + message); } catch (Throwable ignored) {}
    }

    public static void error(String message, Throwable t) {
        RuleHost m = module;
        if (m == null) return;
        try { m.log(Log.ERROR, Config.TAG, "[schema=" + Config.REPORT_SCHEMA + "] " + message, t); } catch (Throwable ignored) {}
    }

    /** 命中：每个 id 只打一行日志；按特性累计，并在首次命中时刷新兼容报告。 */
    public static void hit(String feature, String id) {
        AtomicBoolean flag = ONCE.get(id);
        if (flag == null) {
            flag = new AtomicBoolean(false);
            AtomicBoolean prev = ONCE.putIfAbsent(id, flag);
            if (prev != null) flag = prev;
        }
        if (flag.compareAndSet(false, true)) {
            info("hit=" + id);
            AtomicInteger counter = HITS.get(feature);
            if (counter == null) {
                counter = new AtomicInteger();
                AtomicInteger prev = HITS.putIfAbsent(feature, counter);
                if (prev != null) counter = prev;
            }
            int total = counter.incrementAndGet();
            report("running", feature, "matched", "命中 " + id + "（第 " + total + " 类）");
            if (markHit(feature)) sendRows();
        }
    }

    // -------------------------------------------------------------- 兼容报告

    /** 记录一条规则结果（同一 key 只保留一条）。 */
    public static void row(String key, String outcome, String why) {
        synchronized (ROWS) {
            Integer existing = ROW_INDEX.get(key);
            String line = key + "\t" + outcome + "\t0\t" + (why == null ? "" : why);
            if (existing != null && existing < ROWS.size()) {
                ROWS.set(existing, line);
            } else {
                ROW_INDEX.put(key, ROWS.size());
                ROWS.add(line);
            }
        }
    }

    private static boolean markHit(String key) {
        synchronized (ROWS) {
            Integer index = ROW_INDEX.get(key);
            if (index == null || index >= ROWS.size()) return false;
            String[] parts = ROWS.get(index).split("\t", -1);
            if (parts.length >= 3 && !"1".equals(parts[2])) {
                parts[2] = "1";
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < parts.length; i++) {
                    if (i > 0) sb.append('\t');
                    sb.append(parts[i]);
                }
                ROWS.set(index, sb.toString());
                return true;
            }
            return false;
        }
    }

    private static ArrayList<String> snapshotRows() {
        synchronized (ROWS) {
            return new ArrayList<>(ROWS);
        }
    }

    public static void installed(String id) {
        HOOKED.incrementAndGet();
        synchronized (INSTALLED) {
            if (INSTALLED.length() > 0) INSTALLED.append(' ');
            INSTALLED.append(id).append("=ok");
        }
    }

    public static void miss(String id, String reason) {
        MISS.incrementAndGet();
        synchronized (INSTALLED) {
            if (INSTALLED.length() > 0) INSTALLED.append(' ');
            INSTALLED.append(id).append("=miss(").append(reason == null ? "?" : reason).append(')');
        }
    }

    public static int hooked() { return HOOKED.get(); }
    public static int missed() { return MISS.get(); }

    public static void summary() {
        String list;
        synchronized (INSTALLED) { list = INSTALLED.toString(); }
        info("event=install_summary hooked=" + HOOKED.get() + " miss=" + MISS.get() + " installs=[" + list + "]");
    }

    /** 一次性把逐条规则结果回传给设置页（参考 video-lsposed 的 rows 报告）。 */
    public static void sendRows() {
        Context context = appContext;
        if (context == null) return;
        try {
            Intent intent = new Intent(Config.ACTION_COMPAT);
            intent.setComponent(new ComponentName("io.github.qqliveclean.apps.didi", "io.github.qqliveclean.apps.didi.StatusReceiver"));
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            intent.putExtra("package", Config.PACKAGE);
            intent.putExtra("schema", Config.REPORT_SCHEMA);
            intent.putExtra("version", host);
            intent.putExtra("source", source);
            intent.putExtra("compat", compatDetail);
            intent.putStringArrayListExtra("rows", snapshotRows());
            if (Build.VERSION.SDK_INT >= 34) {
                Bundle options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle();
                context.sendBroadcast(intent, null, options);
            } else {
                context.sendBroadcast(intent);
            }
        } catch (Throwable error) {
            warn("compat report unavailable: " + error);
        }
    }

    /** 跨进程上报单条状态（设置页逐项状态用）。 */
    public static void report(String phase, String feature, String state, String detail) {
        Context context = appContext;
        if (context == null) return;
        try {
            Bundle extras = new Bundle();
            extras.putString("token", token);
            extras.putLong("run", run);
            extras.putString("phase", phase);
            extras.putString("feature", feature);
            extras.putString("state", state);
            extras.putString("detail", detail == null ? "" : (detail.length() > 180 ? detail.substring(0, 180) : detail));
            Intent intent = new Intent(Config.ACTION_REPORT);
            intent.setComponent(new ComponentName("io.github.qqliveclean.apps.didi", "io.github.qqliveclean.apps.didi.StatusReceiver"));
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            intent.putExtras(extras);
            if (Build.VERSION.SDK_INT >= 34) {
                Bundle options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle();
                context.sendBroadcast(intent, null, options);
            } else {
                context.sendBroadcast(intent);
            }
        } catch (Throwable error) {
            warn("status report unavailable: " + error);
        }
    }
}
