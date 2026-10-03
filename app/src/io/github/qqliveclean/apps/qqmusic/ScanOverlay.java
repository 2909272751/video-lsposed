package io.github.qqliveclean.apps.qqmusic;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** One-time scan feedback shown inside QQ Music, without blocking background discovery. */
final class ScanOverlay implements Application.ActivityLifecycleCallbacks {
    private final Application app;
    private final String token;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences prompts;
    private final CountDownLatch foreground = new CountDownLatch(1);
    private WeakReference<Activity> resumed = new WeakReference<>(null);
    private Activity dialogOwner;
    private AlertDialog progressDialog;
    private AlertDialog resultDialog;
    private TextView progressText;
    private ProgressBar progressBar;
    private volatile boolean scanning;
    private volatile boolean completed;
    private boolean progressDismissed;
    private volatile int done;
    private volatile int total;
    private volatile String stage = "读取 QQ 音乐代码特征";
    private volatile String resultTitle = "适配完成";
    private volatile String resultMessage = "";

    ScanOverlay(Application app, Context baseContext, String token) {
        this.app = app;
        this.token = token;
        this.prompts = baseContext.getSharedPreferences("qqmusicclean_prompt", Context.MODE_PRIVATE);
        Log.i("QQMusicCleanOverlay", "token=" + token + " prompted=" + prompts.getString("prompted_token", "<none>")
                + " needs=" + needsPrompt() + " data=" + app.getDataDir());
        if (needsPrompt()) app.registerActivityLifecycleCallbacks(this);
    }

    void begin(int done, int total, String stage) {
        if (alreadyPrompted()) return;
        this.scanning = true;
        this.progressDismissed = false;
        progress(done, total, stage);
    }

    void progress(int done, int total, String stage) {
        if (!scanning || completed) return;
        this.done = done;
        this.total = total;
        if (stage != null && !stage.isEmpty()) this.stage = stage;
        main.post(new Runnable() { @Override public void run() { render(); } });
    }

    void finish(String title, String message) {
        if (alreadyPrompted()) return;
        this.scanning = false;
        this.completed = true;
        this.resultTitle = title;
        this.resultMessage = message;
        main.post(new Runnable() { @Override public void run() { render(); } });
    }

    private boolean alreadyPrompted() { return token.equals(prompts.getString("prompted_token", "")); }
    boolean needsPrompt() { return !alreadyPrompted(); }

    void awaitVisibleProgress() {
        if (!needsPrompt()) return;
        try { foreground.await(1500, TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private void render() {
        Activity activity = resumed.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || alreadyPrompted()) return;
        if (dialogOwner != null && dialogOwner != activity) dismissDialogs();
        dialogOwner = activity;
        if (completed) {
            if (progressDialog != null) { progressDialog.dismiss(); progressDialog = null; }
            if (resultDialog == null) showResult(activity);
        } else if (scanning && !progressDismissed) {
            showProgress(activity);
        }
    }

    private void showProgress(Activity activity) {
        if (progressDialog == null) {
            LinearLayout body = new LinearLayout(activity);
            body.setOrientation(LinearLayout.VERTICAL);
            int padding = Math.round(activity.getResources().getDisplayMetrics().density * 22);
            body.setPadding(padding, padding / 2, padding, padding);
            progressText = new TextView(activity);
            progressText.setTextSize(17);
            body.addView(progressText);
            progressBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
            LinearLayout.LayoutParams bar = new LinearLayout.LayoutParams(-1, Math.max(4, padding / 5));
            bar.topMargin = padding / 2;
            body.addView(progressBar, bar);
            progressDialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                    .setTitle("方法特征扫描中…")
                    .setView(body)
                    .setNegativeButton("后台继续", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface dialog, int which) {
                            progressDismissed = true;
                            progressDialog = null;
                        }
                    }).create();
            progressDialog.setCanceledOnTouchOutside(false);
            progressDialog.show();
        }
        progressText.setText(stage + "\n" + done + " / " + total);
        progressBar.setMax(Math.max(1, total));
        progressBar.setProgress(done);
    }

    private void showResult(Activity activity) {
        resultDialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle(resultTitle).setMessage(resultMessage)
                .setPositiveButton("重启 QQ 音乐", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        Intent intent = new Intent();
                        intent.setClassName("io.github.qqliveclean.apps.qqmusic", "io.github.qqliveclean.apps.qqmusic.MainActivity");
                        intent.putExtra("restart_target", true);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        try { activity.startActivity(intent); }
                        catch (Throwable ignored) { }
                    }
                })
                .setNegativeButton("关闭", null).create();
        resultDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface dialog) { resultDialog = null; }
        });
        resultDialog.show();
        prompts.edit().putString("prompted_token", token).apply();
    }

    private void dismissDialogs() {
        if (progressDialog != null) { progressDialog.dismiss(); progressDialog = null; }
        if (resultDialog != null) { resultDialog.dismiss(); resultDialog = null; }
        dialogOwner = null;
    }

    @Override public void onActivityResumed(Activity activity) {
        resumed = new WeakReference<>(activity);
        main.postDelayed(new Runnable() { @Override public void run() {
            render();
            foreground.countDown();
        } }, 150);
    }
    void activityResumed(Activity activity) { onActivityResumed(activity); }
    void activityPaused(Activity activity) { onActivityPaused(activity); }
    @Override public void onActivityPaused(Activity activity) {
        if (resumed.get() == activity) {
            resumed.clear();
            dismissDialogs();
        }
    }
    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
    @Override public void onActivityDestroyed(Activity activity) {
        if (dialogOwner == activity) dismissDialogs();
    }
}
