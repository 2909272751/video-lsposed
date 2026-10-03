package io.github.qqliveclean.apps.qqmusic;

import android.app.Application;
import android.app.Activity;
import android.app.BroadcastOptions;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

/** Per-feature structural probes. No polling or hooks in the player process. */
public final class MainHook extends io.github.qqliveclean.RuleHost {

    public MainHook(io.github.libxposed.api.XposedModule host) {
        super(host);
    }
    private static final String TAG = "QQMusicClean";
    private String processName;
    private final AtomicBoolean installed = new AtomicBoolean(false);
    private final AtomicBoolean configured = new AtomicBoolean(false);
    private final AtomicBoolean splashLogged = new AtomicBoolean(false);
    private final AtomicBoolean hotSplashLogged = new AtomicBoolean(false);
    private final AtomicBoolean recommendLogged = new AtomicBoolean(false);
    private final AtomicBoolean recognizerLogged = new AtomicBoolean(false);
    private final AtomicBoolean benefitsLogged = new AtomicBoolean(false);
    private final AtomicBoolean diagnosticsLogged = new AtomicBoolean(false);
    private Context reportContext;
    private String reportToken;
    private long reportRun;
    private final AtomicInteger reportDone = new AtomicInteger();
    private String probeFailure;
    private boolean probePartial;
    private volatile boolean homeScanPending;
    private volatile boolean configComplete;
    private ScanOverlay scanOverlay;
    private final ConcurrentHashMap<String, String> featureStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> featureDetails = new ConcurrentHashMap<>();

    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        processName = param.getProcessName();
    }

    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!Config.PACKAGE.equals(param.getPackageName())) return;
        if (processName != null && !Config.PACKAGE.equals(processName)) return;
        if (!installed.compareAndSet(false, true)) return;
        try {
            final ClassLoader targetLoader = param.getClassLoader();
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            hook(attach).setId("qqmusic_validate_before_hooks").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    if (configured.compareAndSet(false, true)) {
                        try { configure((Application) chain.getThisObject(), (Context) chain.getArg(0), targetLoader); }
                        catch (Throwable error) { log(Log.ERROR, TAG, "module setup failed", error); }
                    }
                    return result;
                }
            });
            log(Log.INFO, TAG, "hooked: startup validation");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "module setup failed", error);
        }
    }

    private void configure(Application application, Context context, ClassLoader targetLoader) throws Exception {
            PackageInfo info = context.getPackageManager().getPackageInfo(Config.PACKAGE, 0);
            String version = info.versionName;
            reportContext = context;
            reportToken = info.versionCode + ":" + info.lastUpdateTime + ":" + Config.REPORT_SCHEMA;
            reportRun = System.currentTimeMillis();
            reportDone.set(0);
            homeScanPending = false;
            configComplete = false;
            featureStates.clear();
            featureDetails.clear();
            scanOverlay = new ScanOverlay(application, context, reportToken);
            if (scanOverlay.needsPrompt()) installActivityObserver();
            report("running", "", "", "");
            log(Log.INFO, TAG, "checking compatible hooks for QQ Music " + version);
            SharedPreferences preferences = io.github.qqliveclean.FamilySettings.prefs(context, io.github.qqliveclean.FamilySettings.QQMUSIC);
            boolean blockSplash = Config.read(preferences, Config.BLOCK_SPLASH, true);
            probe("cold", blockSplash, new Runnable() { @Override public void run() { installColdSplash(targetLoader); } });
            probe("hot", blockSplash, new Runnable() { @Override public void run() { installHotSplash(targetLoader); } });
            boolean reducePreload = Config.read(preferences, Config.REDUCE_PRELOAD, false);
            Set<String> hiddenTabs = new HashSet<>();
            if (!Config.read(preferences, Config.TAB_VIDEO, true)) hiddenTabs.add("视频");
            if (!Config.read(preferences, Config.TAB_KSONG, true)) hiddenTabs.add("刷歌");
            if (!Config.read(preferences, Config.TAB_STAR, true)) hiddenTabs.add("星光");
            if (!Config.read(preferences, Config.TAB_MY, true)) hiddenTabs.add("我的");
            probe("tabs", !hiddenTabs.isEmpty(), new Runnable() { @Override public void run() { installTabVisibility(targetLoader, hiddenTabs); } });
            probe("home", Config.read(preferences, Config.HOME_ONLY_RECOMMEND, false),
                    new Runnable() { @Override public void run() { installRecommendOnly(context, targetLoader); } });
            probe("promo", Config.read(preferences, Config.HIDE_HOME_PROMO, true),
                    new Runnable() { @Override public void run() { installHomePromoFilter(targetLoader); } });
            probe("recognizer", !Config.read(preferences, Config.SHOW_RECOGNIZER, false),
                    new Runnable() { @Override public void run() { installRecognizerRemoval(targetLoader); } });
            probe("benefits", !Config.read(preferences, Config.SHOW_BENEFITS, false),
                    new Runnable() { @Override public void run() { installBenefitsRemoval(targetLoader); } });
            probe("preload", reducePreload, new Runnable() { @Override public void run() { installPreloadLimit(targetLoader); } });
            if (Config.read(preferences, Config.DIAGNOSTICS, false))
                installHeaderDiagnostics(targetLoader);
            configComplete = true;
            if (!homeScanPending) {
                report("complete", "", "", "");
                showScanResult();
            }
            log(Log.INFO, TAG, "quick compatibility probes complete for QQ Music " + version
                    + (homeScanPending ? "; home fingerprint scan continues in background" : ""));
    }

    private void probe(String feature, boolean enabled, Runnable action) {
        probeFailure = null;
        probePartial = false;
        if (enabled) {
            try { action.run(); }
            catch (Throwable error) { probeFailure = error.toString(); log(Log.WARN, TAG, feature + " probe failed", error); }
        }
        if ("home".equals(feature) && homeScanPending) {
            report("running", feature, "scanning", "读取 QQ 音乐代码，查找首页频道方法");
            return;
        }
        reportDone.incrementAndGet();
        String state = !enabled ? "off" : probePartial ? "partial" : probeFailure == null ? "matched" : "miss";
        String detail = probeFailure == null ? "" : probeFailure;
        report("running", feature, state, detail);
        log(Log.INFO, TAG, "feature=" + feature + " result=" + state + (detail.isEmpty() ? "" : " reason=" + detail));
    }

    private void report(String phase, String feature, String state, String detail) {
        if (!feature.isEmpty()) {
            featureStates.put(feature, state);
            featureDetails.put(feature, detail);
        }
        if (scanOverlay != null && "running".equals(phase)) {
            String stage = detail.isEmpty() ? "检测 " + feature : detail;
            scanOverlay.progress(reportDone.get(), Config.FEATURES.length, stage);
        }
        try {
            Bundle extras = new Bundle();
            extras.putString("token", reportToken);
            extras.putLong("run", reportRun);
            extras.putString("phase", phase);
            extras.putInt("done", reportDone.get());
            extras.putInt("total", Config.FEATURES.length);
            extras.putString("feature", feature);
            extras.putString("state", state);
            extras.putString("detail", detail.length() > 180 ? detail.substring(0, 180) : detail);
            Intent intent = new Intent("io.github.qqliveclean.apps.qqmusic.REPORT");
            intent.setComponent(new ComponentName("io.github.qqliveclean.apps.qqmusic", "io.github.qqliveclean.apps.qqmusic.StatusReceiver"));
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            intent.putExtras(extras);
            if (Build.VERSION.SDK_INT >= 34) {
                Bundle options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle();
                reportContext.sendBroadcast(intent, null, options);
            } else {
                reportContext.sendBroadcast(intent);
            }
        } catch (Throwable error) { log(Log.WARN, TAG, "status report unavailable", error); }
    }

    private void showScanResult() {
        if (scanOverlay == null) return;
        int matched = 0;
        int disabled = 0;
        int ready = 0;
        StringBuilder results = new StringBuilder();
        boolean hasFailures = false;
        for (int i = 0; i < Config.FEATURES.length; i++) {
            String key = Config.FEATURES[i];
            String state = featureStates.get(key);
            if (results.length() > 0) results.append('\n');
            results.append("• ").append(Config.FEATURE_LABELS[i]).append("：");
            if ("matched".equals(state)) matched++;
            else if ("off".equals(state)) disabled++;
            else if ("ready".equals(state)) ready++;
            else hasFailures = true;
            results.append("matched".equals(state) ? "已匹配"
                    : "off".equals(state) ? "已关闭"
                    : "ready".equals(state) ? "已找到，重启后生效"
                    : "partial".equals(state) || "partial_ready".equals(state) ? "部分匹配"
                    : "未匹配");
            if (!"matched".equals(state) && !"off".equals(state) && !"ready".equals(state)) {
                String detail = featureDetails.get(key);
                if (detail != null && !detail.isEmpty()) results.append("（").append(detail).append("）");
            }
        }
        String title = hasFailures ? "模块适配：有未匹配项" : "模块适配完成";
        String message = "检测 " + Config.FEATURES.length + "/" + Config.FEATURES.length
                + " 项；启用规则已匹配 " + matched + "/" + (Config.FEATURES.length - disabled)
                + " 项，待重启 " + ready + " 项，主动关闭 " + disabled + " 项。\n\n"
                + results + (ready > 0 ? "\n\n重启 QQ 音乐后应用新找到的规则。" : "");
        scanOverlay.finish(title, message);
    }

    private void installActivityObserver() {
        try {
            Method resume = Instrumentation.class.getDeclaredMethod("callActivityOnResume", Activity.class);
            hook(resume).setId("qqmusic_scan_dialog_resume").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    if (scanOverlay != null) scanOverlay.activityResumed((Activity) chain.getArg(0));
                    return result;
                }
            });
            Method pause = Instrumentation.class.getDeclaredMethod("callActivityOnPause", Activity.class);
            hook(pause).setId("qqmusic_scan_dialog_pause").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    if (scanOverlay != null) scanOverlay.activityPaused((Activity) chain.getArg(0));
                    return chain.proceed();
                }
            });
            log(Log.INFO, TAG, "hooked: scan result dialog lifecycle");
        } catch (Throwable error) {
            log(Log.WARN, TAG, "scan result dialog lifecycle unavailable", error);
        }
    }

    private void installColdSplash(ClassLoader loader) {
        try {
            Class<?> task = loader.loadClass("com.tencent.qqmusic.boot.task.activitytask.n0");
            Method decision = task.getDeclaredMethod("k");
            if (decision.getReturnType() != int.class || java.lang.reflect.Modifier.isStatic(decision.getModifiers()))
                throw new NoSuchMethodException("splash decision signature changed");
            hook(decision).setId("qqmusic_cold_splash_decision").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) {
                    // In tested QQ Music 20.6.5.8, k()==5 takes its normal no-ad startup path.
                    if (splashLogged.compareAndSet(false, true))
                        log(Log.INFO, TAG, "cold splash: app no-ad path selected");
                    return Integer.valueOf(5);
                }
            });
            log(Log.INFO, TAG, "hooked: cold splash decision");
        } catch (Throwable error) {
            probeFailure = error.toString();
            log(Log.WARN, TAG, "cold splash hook unavailable; app left unchanged", error);
        }
    }

    private void installHotSplash(ClassLoader loader) {
        try {
            Class<?> manager = loader.loadClass("com.tencent.qqmusic.business.ad.splash.hotlaunch.g");
            Method launch = manager.getDeclaredMethod("F", Activity.class, boolean.class);
            Method precheck = manager.getDeclaredMethod("x", Activity.class);
            if (launch.getReturnType() != boolean.class || precheck.getReturnType() != boolean.class)
                throw new NoSuchMethodException("hot splash gates must return boolean");
            XposedInterface.Hooker skip = new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) {
                    if (hotSplashLogged.compareAndSet(false, true))
                        log(Log.INFO, TAG, "hot splash: display gate blocked");
                    return Boolean.FALSE;
                }
            };
            hook(launch).setId("qqmusic_hot_splash_launch_gate").intercept(skip);
            hook(precheck).setId("qqmusic_hot_splash_precheck").intercept(skip);
            log(Log.INFO, TAG, "hooked: hot splash gates");
        } catch (Throwable error) {
            probeFailure = error.toString();
            log(Log.WARN, TAG, "hot splash hook unavailable; app left unchanged", error);
        }
    }

    private void installPreloadLimit(ClassLoader loader) {
        try {
            Class<?> fragment = loader.loadClass("com.tencent.qqmusic.fragment.mainpage.MainDesktopFragment");
            Method showTabs = fragment.getDeclaredMethod("showTabs");
            Field pagerField = fragment.getDeclaredField("mPagerDetail");
            pagerField.setAccessible(true);
            hook(showTabs).setId("qqmusic_optional_preload_limit").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        Object pager = pagerField.get(chain.getThisObject());
                        if (pager != null)
                            pager.getClass().getMethod("setOffscreenPageLimit", int.class).invoke(pager, 1);
                    } catch (Throwable error) {
                        log(Log.WARN, TAG, "preload limit skipped", error);
                    }
                    return result;
                }
            });
            log(Log.INFO, TAG, "hooked: optional pager preload limit");
        } catch (Throwable error) {
            probeFailure = error.toString();
            log(Log.WARN, TAG, "preload limit unavailable", error);
        }
    }

    private void installTabVisibility(ClassLoader loader, final Set<String> hiddenTabs) {
        try {
            Class<?> container = loader.loadClass("com.tencent.qqmusic.ui.minibar.navigation.MainDeskNavigateContainer");
            Method addItem = null;
            for (Method candidate : container.getDeclaredMethods()) {
                Class<?>[] parameters = candidate.getParameterTypes();
                if (candidate.getName().equals("m") && candidate.getReturnType() == void.class
                        && parameters.length == 2 && parameters[1] == int.class
                        && !parameters[0].isPrimitive()) {
                    if (addItem != null) throw new NoSuchMethodException("ambiguous tab insertion methods");
                    addItem = candidate;
                }
            }
            if (addItem == null) throw new NoSuchMethodException("tab insertion method missing");
            Class<?> itemClass = addItem.getParameterTypes()[0];
            Method name = itemClass.getDeclaredMethod("i");
            Method binding = itemClass.getDeclaredMethod("h");
            Method root = binding.getReturnType().getDeclaredMethod("m");
            if (name.getReturnType() != String.class || !View.class.isAssignableFrom(root.getReturnType()))
                throw new NoSuchMethodException("tab item structure changed");
            hook(addItem).setId("qqmusic_optional_tab_visibility").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        Object item = chain.getArg(0);
                        String label = String.valueOf(name.invoke(item));
                        if (hiddenTabs.contains(label)) {
                            View view = (View) root.invoke(binding.invoke(item));
                            view.setVisibility(View.GONE);
                            log(Log.INFO, TAG, "tab hidden: " + label);
                        }
                    } catch (Throwable error) {
                        log(Log.WARN, TAG, "tab visibility skipped", error);
                    }
                    return result;
                }
            });
            log(Log.INFO, TAG, "hooked: optional tab visibility");
        } catch (Throwable error) {
            probeFailure = error.toString();
            log(Log.WARN, TAG, "tab visibility unavailable", error);
        }
    }

    private void installRecommendOnly(Context context, ClassLoader loader) {
        try {
            Class<?> fragment = loader.loadClass("com.tencent.qqmusic.business.timeline.ui.HomePageFragment");
            Method refresh = quickHomeMethod(context, fragment);
            if (refresh == null) {
                homeScanPending = true;
                scanOverlay.begin(reportDone.get(), Config.FEATURES.length, "读取 QQ 音乐首页频道代码");
                Thread discovery = new Thread(new Runnable() {
                    @Override public void run() {
                        String state = "ready";
                        String detail = "已找到首页频道方法，重启 QQ 音乐后生效";
                        try {
                            scanOverlay.awaitVisibleProgress();
                            scanHomeMethod(context, loader, fragment);
                        } catch (Throwable error) {
                            state = "miss";
                            detail = error.toString();
                            Log.e(TAG, "home channel scan failed", error);
                        }
                        reportDone.incrementAndGet();
                        report("running", "home", state, detail);
                        Log.i(TAG, "feature=home result=" + state + " reason=" + detail);
                        homeScanPending = false;
                        if (configComplete) {
                            report("complete", "", "", "");
                            showScanResult();
                        }
                    }
                }, "qqmc-home-discovery");
                discovery.setDaemon(true);
                discovery.start();
                return;
            }
            String detail = hookRecommendOnly(fragment, refresh);
            if (!detail.isEmpty()) {
                probePartial = true;
                probeFailure = detail;
            }
        } catch (Throwable error) {
            probeFailure = error.toString();
            log(Log.WARN, TAG, "recommend-only home unavailable", error);
        }
    }

    private String hookRecommendOnly(Class<?> fragment, Method refresh) throws Exception {
        refresh.setAccessible(true);
        hook(refresh).setId("qqmusic_optional_recommend_only").intercept(new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                if (recommendLogged.compareAndSet(false, true))
                    log(Log.INFO, TAG, "home channels: recommendation retained; extras skipped");
                return chain.proceed(new Object[]{Collections.emptyList()});
            }
        });
        Method linkedAd = firstMethod(fragment, new String[]{"O5", "M5", "T5"}, boolean.class, int.class);
        Method rewardAd = firstMethod(fragment, new String[]{"P5", "N5", "U5"}, int.class);
        if (linkedAd != null && rewardAd != null) {
            XposedInterface.Hooker skipAdChannel = new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) { return Boolean.FALSE; }
            };
            hook(linkedAd).setId("qqmusic_optional_skip_linked_ad_channel").intercept(skipAdChannel);
            hook(rewardAd).setId("qqmusic_optional_skip_reward_ad_channel").intercept(skipAdChannel);
            log(Log.INFO, TAG, "hooked: recommend-only home and ad channels");
            return "";
        }
        log(Log.WARN, TAG, "home channels filtered; ad channel gates not found");
        return "首页频道已过滤，广告频道方法未匹配";
    }

    private Method firstMethod(Class<?> owner, String[] names, Class<?>... params) {
        for (String name : names) {
            try {
                Method method = owner.getDeclaredMethod(name, params);
                if (method.getReturnType() == boolean.class) return method;
            } catch (NoSuchMethodException ignored) { }
        }
        return null;
    }

    private Method quickHomeMethod(Context context, Class<?> owner) {
        SharedPreferences cache = context.getSharedPreferences("qqmusicclean_discovery", Context.MODE_PRIVATE);
        String cached = reportToken.equals(cache.getString("token", ""))
                ? cache.getString("home_method", "") : "";
        for (String name : new String[]{cached, "J6", "H6"}) {
            if (name.isEmpty()) continue;
            try {
                Method method = owner.getDeclaredMethod(name, List.class);
                if (method.getReturnType() == void.class) return method;
            } catch (NoSuchMethodException ignored) { }
        }
        return null;
    }

    private Method scanHomeMethod(Context context, ClassLoader loader, Class<?> owner) throws Exception {
        log(Log.INFO, TAG, "home method quick match missed; starting DexKit string scan");
        report("running", "home", "scanning", "读取 QQ 音乐 DEX，查找首页频道代码特征");
        System.loadLibrary("dexkit");
        Method found = null;
        try (DexKitBridge bridge = DexKitBridge.create(context.getApplicationInfo().sourceDir)) {
            bridge.setThreadNum(2);
            report("running", "home", "scanning", "解析首页频道方法及参数");
            for (MethodData data : bridge.findMethod(FindMethod.create().matcher(
                    MethodMatcher.create().usingStrings("[refreshTabsFragment] drop tab without fragment")))) {
                if (!owner.getName().equals(data.getDeclaredClassName())) continue;
                Method candidate = data.getMethodInstance(loader);
                if (candidate.getReturnType() != void.class
                        || candidate.getParameterTypes().length != 1
                        || candidate.getParameterTypes()[0] != List.class) continue;
                if (found != null) throw new NoSuchMethodException("ambiguous home channel methods");
                found = candidate;
            }
        }
        if (found == null) throw new NoSuchMethodException("home channel method fingerprint not found");
        report("running", "home", "scanning", "校验首页频道方法并保存缓存");
        SharedPreferences cache = context.getSharedPreferences("qqmusicclean_discovery", Context.MODE_PRIVATE);
        cache.edit().putString("token", reportToken).putString("home_method", found.getName()).apply();
        Log.i(TAG, "home method discovered by DexKit: " + found.getName());
        return found;
    }

    private void installHomePromoFilter(ClassLoader loader) {
        try {
            Class<?> cell = loader.loadClass("com.tencent.qqmusic.modular.module.musichall.beans.k");
            Class<?> shelf = loader.loadClass("com.tencent.qqmusic.modular.module.musichall.beans.p0");
            Class<?> helper = loader.loadClass("com.tencent.qqmusic.modular.module.musichall.beans.l");
            Class<?> adapter = loader.loadClass("com.tencent.qqmusic.modular.module.musichall.views.r");
            Class<?> callback = loader.loadClass("kotlin.jvm.functions.Function0");
            Method parent = helper.getDeclaredMethod("b", cell);
            Method title = shelf.getDeclaredMethod("B");
            Method setData = adapter.getDeclaredMethod("b0", List.class, boolean.class);
            Method setDiff = adapter.getDeclaredMethod("f0", List.class, callback, boolean.class);
            if (setData.getReturnType() != void.class || setDiff.getReturnType() != void.class
                    || title.getReturnType() != String.class
                    || parent.getReturnType() != shelf)
                throw new NoSuchMethodException("home shelf model structure changed");
            parent.setAccessible(true);
            title.setAccessible(true);
            XposedInterface.Hooker filter = new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object[] args = new Object[chain.getExecutable().getParameterCount()];
                    for (int i = 0; i < args.length; i++) args[i] = chain.getArg(i);
                    if (!(args[0] instanceof List)) return chain.proceed();
                    List<?> batches = (List<?>) args[0];
                    ArrayList<List<?>> filtered = new ArrayList<>(batches.size());
                    int hidden = 0;
                    for (Object batch : batches) {
                        if (!(batch instanceof List)) return chain.proceed();
                        List<?> rows = (List<?>) batch;
                        ArrayList<Object> kept = new ArrayList<>(rows.size());
                        for (Object row : rows) {
                            Object group;
                            String label;
                            try {
                                group = parent.invoke(null, row);
                                label = group == null ? null : (String) title.invoke(group);
                            } catch (Throwable error) {
                                kept.add(row);
                                continue;
                            }
                            if (label != null && label.contains("随时随地") && label.contains("停不下来")) hidden++;
                            else kept.add(row);
                        }
                        filtered.add(kept);
                    }
                    if (hidden > 0) {
                        log(Log.INFO, TAG, "home promo shelf items removed=" + hidden);
                        args[0] = filtered;
                        return chain.proceed(args);
                    }
                    return chain.proceed();
                }
            };
            hook(setData).setId("qqmusic_hide_home_promo_set_data").intercept(filter);
            hook(setDiff).setId("qqmusic_hide_home_promo_set_diff").intercept(filter);
            log(Log.INFO, TAG, "hooked: home promotional shelf adapter data filter");
        } catch (Throwable error) {
            probeFailure = error.toString();
            log(Log.WARN, TAG, "home promotional shelf filter unavailable", error);
        }
    }

    private void installRecognizerRemoval(ClassLoader loader) {
        boolean installed = false;
        for (String variant : new String[]{"com.tencent.qqmusic.ui.desktopheader.g1",
                "com.tencent.qqmusic.ui.BaseDesktopHeader"}) {
            try {
                Class<?> header = loader.loadClass(variant);
                Method showRecognizer = header.getDeclaredMethod("l0", boolean.class);
                if (showRecognizer.getReturnType() != void.class)
                    throw new NoSuchMethodException("recognizer visibility signature changed");
                hook(showRecognizer).setId("qqmusic_hide_recognizer_" + header.getSimpleName())
                        .intercept(new XposedInterface.Hooker() {
                            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                if (Boolean.TRUE.equals(chain.getArg(0))
                                        && recognizerLogged.compareAndSet(false, true))
                                    log(Log.INFO, TAG, "recognizer entrance: native show request suppressed via " + variant);
                                return chain.proceed(new Object[]{Boolean.FALSE});
                            }
                        });
                installed = true;
                log(Log.INFO, TAG, "hooked: recognizer entrance variant " + variant);
            } catch (Throwable error) {
                log(Log.INFO, TAG, "recognizer variant skipped: " + variant + " (" + error + ")");
            }
        }
        if (!installed) {
            probeFailure = "未找到可兼容的听歌识曲入口";
            log(Log.WARN, TAG, "recognizer entrance: no compatible variant found");
        }
    }

    private void installBenefitsRemoval(ClassLoader loader) {
        boolean installed = false;
        for (String variant : new String[]{"com.tencent.qqmusic.ui.desktopheader.g1",
                "com.tencent.qqmusic.ui.BaseDesktopHeader"}) {
            try {
                Class<?> header = loader.loadClass(variant);
                Method showBenefits = header.getDeclaredMethod("J", int.class, boolean.class);
                if (showBenefits.getReturnType() != void.class)
                    throw new NoSuchMethodException("benefits visibility signature changed");
                hook(showBenefits).setId("qqmusic_hide_benefits_" + header.getSimpleName())
                        .intercept(new XposedInterface.Hooker() {
                            @Override public Object intercept(XposedInterface.Chain chain) {
                                if (benefitsLogged.compareAndSet(false, true))
                                    log(Log.INFO, TAG, "benefits entrance: native inflation skipped via " + variant);
                                return null;
                            }
                        });
                installed = true;
                log(Log.INFO, TAG, "hooked: benefits entrance variant " + variant);
            } catch (Throwable error) {
                log(Log.INFO, TAG, "benefits variant skipped: " + variant + " (" + error + ")");
            }
        }
        if (!installed) {
            probeFailure = "未找到可兼容的福利入口";
            log(Log.WARN, TAG, "benefits entrance: no compatible variant found");
        }
    }

    private void installHeaderDiagnostics(ClassLoader loader) {
        try {
            Method resume = Activity.class.getDeclaredMethod("onResume");
            hook(resume).setId("qqmusic_optional_header_diagnostics").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    Activity activity = (Activity) chain.getThisObject();
                    if (activity.getClass().getName().equals("com.tencent.qqmusic.activity.AppStarterActivity")
                            && diagnosticsLogged.compareAndSet(false, true)) {
                        try {
                            View root = activity.getWindow().getDecorView();
                            root.postDelayed(new Runnable() {
                                @Override public void run() {
                                    try { dumpHeader(root, 0, new int[]{0}); }
                                    catch (Throwable error) { log(Log.WARN, TAG, "header diagnostics failed", error); }
                                }
                            }, 3500);
                        } catch (Throwable error) {
                            log(Log.WARN, TAG, "header diagnostics unavailable", error);
                        }
                    }
                    return result;
                }
            });
            log(Log.INFO, TAG, "hooked: optional header diagnostics");
        } catch (Throwable error) {
            log(Log.WARN, TAG, "header diagnostics unavailable", error);
        }
    }

    private void dumpHeader(View view, int depth, int[] count) {
        if (depth > 22 || count[0] > 160) return;
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        if (location[1] < 450 && location[0] > view.getResources().getDisplayMetrics().widthPixels / 2
                && view.getWidth() > 0 && view.getHeight() > 0) {
            String name = "none";
            try { name = view.getResources().getResourceEntryName(view.getId()); }
            catch (Throwable ignored) { }
            CharSequence description = view.getContentDescription();
            log(Log.INFO, TAG, "header view depth=" + depth + " id=" + name + " class="
                    + view.getClass().getSimpleName() + " xy=" + location[0] + "," + location[1]
                    + " size=" + view.getWidth() + "x" + view.getHeight() + " vis="
                    + view.getVisibility() + " desc=" + description);
            count[0]++;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                dumpHeader(group.getChildAt(i), depth + 1, count);
        }
    }
}
