package io.github.qqliveclean;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Logging and per-rule bookkeeping.
 *
 * Requirement driving this class: when something stops working after a Tencent Video
 * update, the log alone must say which rule missed and why. So every installable rule
 * reports exactly one of {@code status=hooked} / {@code status=miss reason=...}, and a
 * single summary line at the end gives ok/miss counts.
 *
 * All lines are prefixed with the stable tag {@link Config#TAG} so
 * {@code logcat -s QQLiveClean} is enough to diagnose.
 */
public final class H {
    private static volatile MainHook module;
    private static final List<String> misses = new ArrayList<>();
    /** Rule outcomes, re-emitted with the first hit because install-time lines are dropped. */
    private static final StringBuilder installNotes = new StringBuilder();
    /** rule id -> matched | miss | off, for the QQ-Music-shaped compatibility report. */
    private static final java.util.LinkedHashMap<String, String> results = new java.util.LinkedHashMap<>();
    private static final java.util.LinkedHashMap<String, String> reasons = new java.util.LinkedHashMap<>();
    private static final java.util.HashSet<String> hits = new java.util.HashSet<>();
    private static volatile Context reportContext;
    static volatile String targetVersion = "unknown";
    static volatile boolean exactlyVerified;
    private static int hooked;

    private H() {}

    static void attach(MainHook instance) {
        module = instance;
    }

    static void setReportContext(Context context) {
        if (context != null) {
            Context application = context.getApplicationContext();
            reportContext = application == null ? context : application;
        }
    }

    static void log(int priority, String message) {
        MainHook instance = module;
        if (instance != null) {
            try {
                instance.log(priority, Config.TAG, message);
                return;
            } catch (Throwable ignored) {
                // fall through to logcat so diagnostics survive a framework hiccup
            }
        }
        try { Log.println(priority, Config.TAG, message); } catch (Throwable ignored) {}
    }

    static void info(String message) { log(Log.INFO, message); }
    static void warn(String message) { log(Log.WARN, message); }
    static void error(String message, Throwable error) { log(Log.ERROR, message + " :: " + describe(error)); }

    /**
     * Second, independent log channel straight to logcat.
     *
     * LSPosed's module log occasionally shows a process's intercept-time lines (the
     * {@code hit=} lines) while dropping the earlier install-time burst, which made a real
     * failure indistinguishable from a logging artefact. Anything that must be provable is
     * therefore emitted twice: once through {@link #info} and once here, so
     * {@code adb logcat -s QLCDiag} always answers "did this line execute".
     */
    static void diag(String message) {
        try { Log.i("QLCDiag", message); } catch (Throwable ignored) {}
    }

    /**
     * Which config source the current launch used ("module_provider" / "remote_preferences" /
     * "defaults"). Set during install and re-emitted with every first-hit line, because
     * install-time lines are dropped when they are written inside the Activity-launch window
     * while hit lines (emitted later) survive. This is what makes the settings channel
     * provable from the log.
     */
    static volatile String configSource = "unresolved";
    static volatile String targetPackage = "unknown";

    /** First-hit log that always carries the config source, for provable diagnosis. */
    static void hit(String rule, String detail, AtomicBoolean once) {
        if (once.compareAndSet(false, true)) {
            String notes;
            synchronized (H.class) {
                notes = installNotes.toString().trim();
            }
            String line = "hit=" + rule + " " + detail + " config_source=" + configSource;
            info(line + (notes.isEmpty() ? "" : " installs=[" + notes + "]"));
            diag("package=" + targetPackage + " " + line);
            synchronized (H.class) { hits.add(rule); }
            sendReport();
            // Emitted from inside the branch whose flag provably works (the hit= line appears
            // exactly once). An earlier attempt gated this on a SECOND flag, and that flag was
            // already consumed by the time the first logged hit ran, so the report never emitted.
            emitCompatibilityReportLines();
        }
    }


    /** The report body, split out so it can be called from the surviving window. */
    private static void emitCompatibilityReportLines() {
        java.util.LinkedHashMap<String, String> snapshot;
        synchronized (H.class) {
            snapshot = new java.util.LinkedHashMap<>(results);
        }
        info("checking compatible hooks for " + reportSubject + " [schema=" + SCHEMA + "]");
        for (java.util.Map.Entry<String, String> entry : snapshot.entrySet()) {
            info("feature=" + entry.getKey() + " result=" + entry.getValue());
        }
        info("quick compatibility probes complete for " + reportSubject);
    }

    /** Subject of the compatibility report header, set once the target version is known. */
    static volatile String reportSubject = "target app";



    /**
     * Flushes the compatibility report a few seconds after install, on the app's own main looper.
     *
     * WHY: the report used to ride the FIRST HIT line, because lines written during the
     * Activity-launch window are dropped by LSPosed. The side effect was that an app which never
     * hits anything (Youku on a quiet launch, iQiyi whose anchors are withdrawn) produced NO
     * report at all - so "no rule worked" and "nothing was verified" looked identical.
     * Posting with a delay lands after that window, so every app reports every launch.
     *
     * Guarded and one-shot: failures fall back to the old behaviour rather than risk the app.
     */
    static void scheduleDelayedReport() {
        if (!REPORT_SCHEDULED.compareAndSet(false, true)) return;
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        synchronized (H.class) {
                            if (REPORT_EMITTED) return;
                            REPORT_EMITTED = true;
                        }
                        emitCompatibilityReportLines();
                        sendReport();
                    } catch (Throwable ignored) {
                        // diagnostics must never affect the target app
                    }
                }
            }, 3500L);
        } catch (Throwable error) {
            warn("event=delayed_report_unavailable " + describe(error));
        }
    }

    private static final AtomicBoolean REPORT_SCHEDULED = new AtomicBoolean(false);
    private static boolean REPORT_EMITTED = false;
    /**
     * Identity of the CURRENT rule set / diagnostics. Bumped whenever rule semantics or the
     * wording of a diagnostic changes, so a log line proves WHICH build produced it.
     *
     * Added after round 50 burned a whole verification cycle: a widened probe produced output
     * byte-identical to the previous build, and because the miss text was unchanged there was no
     * way to tell "the new code ran and found nothing" from "the new build never loaded". That is
     * the exact ambiguity REPORT_SCHEMA exists to remove; this is it applied properly.
     */
    static final int SCHEMA = 64;

    /** Records a rule that is armed and will take effect. */
    static synchronized void hooked(String rule, String detail) {
        results.put(rule, "matched");
        reasons.remove(rule);
        installNotes.append(rule).append("=ok ");
        hooked++;
        log(Log.INFO, "rule=" + rule + " status=hooked" + (detail == null ? "" : " " + detail));
    }

    /** Records a rule that could not be armed. This is the line to look for after an update. */
    static synchronized void miss(String rule, String reason) {
        misses.add(rule + " [" + reason + "]");
        results.put(rule, "miss");
        reasons.put(rule, reason);
        installNotes.append(rule).append("=miss(").append(reason).append(") ");
        log(Log.WARN, "rule=" + rule + " status=miss reason=" + reason);
    }

    /** A rule that is intentionally not installed (feature off) - not a failure. */
    static void skipped(String rule, String reason) {
        synchronized (H.class) { results.put(rule, "off"); reasons.put(rule, reason); }
        log(Log.INFO, "rule=" + rule + " status=skipped reason=" + reason);
    }

    /** Explicit broadcast works across Android's package-visibility boundary. */
    private static void sendReport() {
        Context context = reportContext;
        if (context == null || "unknown".equals(targetPackage)) return;
        try {
            ArrayList<String> rows = new ArrayList<String>();
            synchronized (H.class) {
                for (java.util.Map.Entry<String, String> entry : results.entrySet()) {
                    String key = entry.getKey();
                    rows.add(key + "\t" + entry.getValue() + "\t" + (hits.contains(key) ? "1" : "0")
                            + "\t" + (reasons.containsKey(key) ? reasons.get(key) : ""));
                }
            }
            Intent intent = new Intent("io.github.qqliveclean.COMPAT_REPORT");
            intent.setComponent(new ComponentName("io.github.qqliveclean",
                    "io.github.qqliveclean.ReportReceiver"));
            intent.putExtra("package", targetPackage);
            intent.putExtra("version", targetVersion);
            intent.putExtra("verified", exactlyVerified);
            intent.putExtra("source", configSource);
            intent.putExtra("schema", SCHEMA);
            intent.putStringArrayListExtra("rows", rows);
            context.sendBroadcast(intent);
        } catch (Throwable error) { warn("event=compat_report_send_failed " + describe(error)); }
    }

    /**
     * Emits the same compatibility-report shape the reference QQ Music module uses, so both
     * modules can be grepped identically:
     *   checking compatible hooks for <subject>
     *   feature=<rule> result=matched|miss|off
     *   quick compatibility probes complete for <subject>
     */
    static void emitCompatibilityReport(String subject) {
        java.util.LinkedHashMap<String, String> snapshot;
        synchronized (H.class) {
            snapshot = new java.util.LinkedHashMap<>(results);
        }
        info("checking compatible hooks for " + subject);
        for (java.util.Map.Entry<String, String> entry : snapshot.entrySet()) {
            info("feature=" + entry.getKey() + " result=" + entry.getValue());
        }
        info("quick compatibility probes complete for " + subject);
    }

    static synchronized boolean hasMisses() {
        return !misses.isEmpty();
    }

    static synchronized String summary() {
        return "event=install_summary hooked=" + hooked + " miss=" + misses.size()
                + (misses.isEmpty() ? "" : " missed=" + misses);
    }

    static String describe(Throwable error) {
        if (error == null) return "null";
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
