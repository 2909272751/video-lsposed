package io.github.qqliveclean;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Entry point. Three gates, then one install pass.
 *
 * 1. package gate  - {@code com.tencent.qqlive} or {@code com.youku.phone} (see
 *    {@link Config#PACKAGES}); the package decides WHICH rule set is installed
 *    ({@link #installTencentRules} / {@link YoukuRules}), so neither app can affect the other
 * 2. process gate  - main process only; player/cache subprocesses are never touched
 * 3. once gate     - atomic, so re-entrant callbacks cannot double-hook
 *
 * Version handling is deliberately NOT an equality gate: {@code versionCode} is logged and
 * only a coarse floor is enforced, then each rule resolves on its own and reports
 * {@code hooked}/{@code miss}. A Tencent Video update therefore degrades rule by rule with a
 * readable log instead of silently disabling the whole module.
 *
 * Context acquisition. On LSPosed 2.2.0 {@code onPackageReady} fires AFTER the Application
 * has been created, so every "hook early to capture a Context" approach is installed too late
 * and never fires - verified for {@code Application.attach}, {@code Application.onCreate} and
 * {@code Instrumentation.callApplicationOnCreate}. The Application therefore has to be
 * *located* ({@link ContextFinder}); only if that fails do we fall back to hooking the first
 * {@code Instrumentation.callActivityOnCreate}, which is still before that activity's own
 * onCreate body runs.
 */
public final class MainHook extends XposedModule {
    private static final AtomicBoolean CONFIGURED = new AtomicBoolean(false);

    /** Settings resolved by configure(), so the callActivityOnCreate driver can reach them. */
    private static final java.util.concurrent.atomic.AtomicReference<Config.Settings> ACTIVE_SETTINGS =
            new java.util.concurrent.atomic.AtomicReference<Config.Settings>(null);

    private String processName;
    /** version name captured for the compatibility report header. */
    private static String currentVersionName = "unknown";
    private final AtomicBoolean installed = new AtomicBoolean(false);

    @Override public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        processName = param.getProcessName();
        H.attach(this);
        H.info("event=module_loaded process=" + processName + " api=" + getApiVersion()
                + " framework=" + getFrameworkName() + " " + getFrameworkVersion());
    }

    @Override public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        // Gate 1: package. Two targets share this build; the rule set is chosen from the
        // package the module was loaded for, and neither app's rules can reach the other.
        final String target = param.getPackageName();
        if (!Config.handles(target)) return;
        // Gate 2: splash and navigation rules are in the main process. Avoid loading this
        // module into player/download processes where it has no active rules.
        if (processName != null && !target.equals(processName)) {
            H.info("event=skip_subprocess process=" + processName + " target=" + target);
            return;
        }
        // Gate 3: once, so re-entrant callbacks cannot double-hook.
        if (!installed.compareAndSet(false, true)) {
            H.info("event=install_skipped reason=already_installed");
            return;
        }
        try {
            final ClassLoader loader = param.getClassLoader();
            // One line that turns a libxposed API change into a readable diagnosis.
            H.info("event=param_api class=" + param.getClass().getName()
                    + " methods=" + R.describeMethods(param.getClass()));
            H.info("event=target_package package=" + target + " app=" + Config.appLabel(target));
            // A stale build on the device looks exactly like a logic bug in the logs, so the
            // running version is written out and the very next question is always answerable.
            H.info("event=module_build version=" + versionName());

            Context context = ContextFinder.find(loader, target);
            if (context != null) {
                H.info("event=context_source=" + ContextFinder.lastSource());
                configure(context, loader, target);
                return;
            }
            H.warn("event=context_probe_failed detail=" + ContextFinder.lastDetail());
            // Tencent Video returned a null getInitialApplication() here, and neither fallback hook
            // fired afterwards either - that whole session ran with zero rules installed, which is
            // worse than any misfiring rule. Hooking for the Application is a bet on the app taking
            // one particular path; polling does not care which path it takes. Bounded, off the main
            // thread, and it gives up rather than spinning forever.
            // Polling was tried and removed. Three cold starts showed ContextFinder.find
            // hanging on its very first call, so no attempt line, no deadline check and
            // no fallback ever ran - the thread simply parked and leaked. Meanwhile both
            // hooks did their job: hit=ad_request_gate landed in all three sessions.
            // A permanently hung background thread costs battery and buys nothing, so it goes.            H.info("event=context_retry_removed hooks_only=2");
            // Two independent fallbacks: whichever fires first configures (guarded by the
            // CONFIGURED CAS). Youku's activity hook never fires, so the Application hook is
            // needed there; Tencent Video/iQiyi are unaffected because one of them wins.
            installApplicationCreateHook(loader, target);
            if (installFirstActivityHook(loader, target)) return;
            H.warn("event=context_unavailable, configuring without a Context");
            configure(null, loader, target);
        } catch (Throwable error) {
            H.error("event=install_failed", error);
        }
    }

    /**
     * Polls for the app context until it exists. configure() is guarded by a CAS, so whichever of
     * this and the two hooks arrives first wins and the others become no-ops.
     */
    /**
     * Logs the build's own identity. getImplementationVersion() returns 0.0 here, so the constant
     * is the source of truth and is bumped by the same edit that bumps the manifest.
     */
    private static String versionName() {
        return "qlc-0.3.56";
    }

    /**
     * Second fallback: the framework calls this with the Application. Chosen because Youku's
     * activity-create hook was observed to never fire (see the class comment), so relying on the
     * Activity path alone left Youku with zero rules installed.
     */
    private void installApplicationCreateHook(final ClassLoader loader, final String target) {
        try {
            Class<?> instrumentation = Class.forName("android.app.Instrumentation", false, loader);
            Method create = R.find(instrumentation, "callApplicationOnCreate", void.class,
                    android.app.Application.class);
            if (create == null) {
                H.miss("context_probe_app", "callApplicationOnCreate(Application)V not found");
                return;
            }
            hook(create).setId("qqlive_app_create").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object application = chain.getArg(0);
                    CONTEXT_SOURCE.set("app_probe");
                    try {
                        configure(application instanceof Context ? (Context) application : null,
                                loader, target);
                    } catch (Throwable error) {
                        H.error("event=configure_failed", error);
                    }
                    return chain.proceed();
                }
            });
            H.info("rule=context_probe_app status=hooked source=Instrumentation.callApplicationOnCreate");
        } catch (Throwable error) {
            H.miss("context_probe_app", H.describe(error));
        }
    }

    /** Last-resort Context source: the first Activity that gets created. */
    private boolean installFirstActivityHook(final ClassLoader loader, final String target) {
        try {
            Class<?> instrumentation = Class.forName("android.app.Instrumentation", false, loader);
            Method create = R.find(instrumentation, "callActivityOnCreate", void.class,
                    Activity.class, Bundle.class);
            if (create == null) {
                H.miss("context_probe_activity", "Instrumentation.callActivityOnCreate(Activity,Bundle)V not found");
                return false;
            }
            hook(create).setId("qqlive_first_activity").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object activity = chain.getArg(0);
                    H.diag("callActivityOnCreate fired: "
                            + (activity == null ? "null" : activity.getClass().getName()));
                    try {
                        CONTEXT_SOURCE.set("first_activity_probe");
                        configure(activity instanceof Context ? (Context) activity : null, loader, target);
                    } catch (Throwable error) {
                        H.error("event=configure_failed", error);
                        H.diag("configure threw: " + H.describe(error));
                    }
                    // This hook demonstrably fires for every Activity creation, handing over a live
                    // instance - the only trigger proven to reach the UI. The app base class anchor
                    // installs but is never called and ActivityLifecycleCallbacks never dispatch, so
                    // the feed rules run from here.
                    Config.Settings live = ACTIVE_SETTINGS.get();
                    if (activity instanceof android.app.Activity && live != null) {
                        H.info("event=ui_driver source=callActivityOnCreate"
                                + " activity=" + activity.getClass().getName());
                        try {
                            YoukuRules.onActivityResumed((android.app.Activity) activity, live, "callActivityOnCreate");
                            H.info("event=ui_pass_returned source=callActivityOnCreate"
                                    + " activity=" + activity.getClass().getName());
                        } catch (Throwable error) {
                        // An Xposed intercept swallows throwables and leaves no trace, so a pass that
                        // dies mid-way looks exactly like one that never ran. Catch it here, where
                        // the framework eats it, instead of guessing what it was.
                        H.info("event=ui_pass_threw source=callActivityOnCreate"
                                + " activity=" + activity.getClass().getName()
                                + " error=" + H.describe(error));
                        }
                    }
                    return chain.proceed();
                }
            });
            H.info("rule=context_probe_activity status=hooked source=Instrumentation.callActivityOnCreate");
            return true;
        } catch (Throwable error) {
            H.miss("context_probe_activity", H.describe(error));
            return false;
        }
    }

    /**
     * Drives Youku's UI rules through ActivityLifecycleCallbacks.
     *
     * <p>This replaces a hook on {@code android.app.Activity}. Runtime enumeration showed that hooks
     * on that framework class install without error and are then never called, while hooks on
     * {@code Instrumentation} and on app classes do fire. Registering the callbacks needs no hook at
     * all, so it sidesteps the whole failure mode instead of looking for another anchor inside it.
     * Registration is once per process and any failure is reported, never swallowed.
     */
    private void registerUiLifecycle(Context context, final Config.Settings settings) {
        if (context == null || UI_LIFECYCLE_REGISTERED.get()) return;
        android.app.Application application = context instanceof android.app.Application
                ? (android.app.Application) context
                : context instanceof android.app.Activity ? ((android.app.Activity) context).getApplication() : null;
        if (application == null) {
            H.warn("event=ui_lifecycle_unavailable reason=no_Application context=" + context.getClass().getName());
            return;
        }
        try {
            H.warn("event=ui_lifecycle_registering application=" + application.getClass().getName()
                    + " id=" + System.identityHashCode(application)
                    + " registered=" + application.getClass().getName());
            application.registerActivityLifecycleCallbacks(new android.app.Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityCreated(android.app.Activity a, android.os.Bundle b) {
                    if (CREATED_SEEN.compareAndSet(false, true)) {
                        H.warn("event=ui_lifecycle_created activity=" + a.getClass().getName()
                                + " sameApplication=" + (a.getApplication() == application)
                                + " super=" + YoukuRules.describeHierarchy(a.getClass()));
                    }
                }
                @Override public void onActivityStarted(android.app.Activity a) { }
                @Override public void onActivityResumed(final android.app.Activity a) {
                    try {
                        YoukuRules.onActivityResumed(a, settings, "callbacks");
                    } catch (Throwable error) {
                        H.warn("event=ui_lifecycle_error " + H.describe(error));
                    }
                }
                @Override public void onActivityPaused(android.app.Activity a) { }
                @Override public void onActivityStopped(android.app.Activity a) { }
                @Override public void onActivitySaveInstanceState(android.app.Activity a, android.os.Bundle b) { }
                @Override public void onActivityDestroyed(android.app.Activity a) { }
            });
            UI_LIFECYCLE_REGISTERED.set(true);
            H.info("rule=youku_ui_lifecycle status=hooked source=ActivityLifecycleCallbacks (no framework hook)");
        } catch (Throwable error) {
            H.warn("event=ui_lifecycle_error " + H.describe(error));
        }
    }

    private static final java.util.concurrent.atomic.AtomicBoolean UI_LIFECYCLE_REGISTERED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** First Activity created after registration - the earliest possible callback, used as a probe. */
    /**
 * Asks the running process what Activities it actually has, instead of waiting to be told about
 * them. Every Activity-creation signal is dead in Youku - callActivityOnCreate never fires, the
 * android.app.Activity hooks install and are called zero times, and registered lifecycle callbacks
 * are never dispatched - while callApplicationOnCreate fires reliably and hooks on the app's own
 * classes work. So the one dependable moment is used to reflect over ActivityThread.mActivities and
 * read the live state directly. No hook, no lifecycle callback, no framework method on the hot path.
 */
    private static void dumpLiveActivities(final Object application) {
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override public void run() {
                    StringBuilder report = new StringBuilder(512);
                    try {
                        Class<?> threadClass = Class.forName("android.app.ActivityThread", false,
                                application.getClass().getClassLoader());
                        Method current = R.find(threadClass, "currentActivityThread",
                                threadClass, new Class<?>[0]);
                        Object thread = current == null ? null : current.invoke(null);
                        if (thread == null) {
                            H.warn("event=live_activities result=unavailable reason=currentActivityThread");
                            return;
                        }
                        java.lang.reflect.Field activitiesField =
                                R.findField(threadClass, "mActivities");
                        if (activitiesField == null) {
                            H.warn("event=live_activities result=unavailable reason=mActivities_field");
                            return;
                        }
                        Object activities = activitiesField.get(thread);
                        int count = 0;
                        if (activities instanceof java.util.Map) {
                            for (Object record : ((java.util.Map<?, ?>) activities).values()) {
                                if (record == null) continue;
                                java.lang.reflect.Field activityField =
                                        R.findField(record.getClass(), "activity");
                                Object activity = readField(activityField, record);
                                if (!(activity instanceof android.app.Activity)) continue;
                                count++;
                                report.append(count == 1 ? "" : " | ").append("<- ")
                                        .append(activity.getClass().getName())
                                        .append(" visible=").append(((android.app.Activity) activity).isFinishing());
                            }
                        }
                        H.warn("event=live_activities result=ok count=" + count
                                + " hierarchy=" + YoukuRules.describeHierarchy(
                                        (count == 0 ? null : firstActivityClass(activities)))
                                + " list=" + report);
                    } catch (Throwable error) {
                        H.warn("event=live_activities result=error " + H.describe(error));
                    }
                }
            }, 6000);
        } catch (Throwable error) {
            H.warn("event=live_activities result=error " + H.describe(error));
        }
    }

    private static Object readField(java.lang.reflect.Field field, Object owner) {
        if (field == null) return null;
        try {
            return field.get(owner);
        } catch (Throwable error) {
            return null;
        }
    }

    /** First live Activity class in the mActivities map, for reporting its base class. */
    private static Class<?> firstActivityClass(Object activities) {
        if (!(activities instanceof java.util.Map)) return null;
        for (Object record : ((java.util.Map<?, ?>) activities).values()) {
            if (record == null) continue;
            Object activity = readField(R.findField(record.getClass(), "activity"), record);
            if (activity instanceof android.app.Activity) return activity.getClass();
        }
        return null;
    }

    private static final java.util.concurrent.atomic.AtomicBoolean CREATED_SEEN =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Which probe handed configure() its Context - set by the caller before configure runs. */
    private static final java.util.concurrent.atomic.AtomicReference<String> CONTEXT_SOURCE =
            new java.util.concurrent.atomic.AtomicReference<String>("unknown");

    private void configure(Context context, ClassLoader loader, String target) {
        H.targetPackage = target;
        // Identity of the Context that reached us. configure() can be entered from the app probe
        // (the callApplicationOnCreate argument) or from the activity probe (an Activity), and the
        // lifecycle callbacks registered on the Application do not dispatch - so which instance this
        // is has to be recorded rather than assumed.
        H.warn("event=context_identity via=" + CONTEXT_SOURCE.get()
                + " ctx=" + (context == null ? "null" : context.getClass().getName())
                + " id=" + System.identityHashCode(context)
                + " isApplication=" + (context instanceof android.app.Application)
                + " appFromActivity=" + (context instanceof android.app.Activity
                        ? System.identityHashCode(((android.app.Activity) context).getApplication()) : -1));
        H.setReportContext(context);
        H.diag("configure enter context=" + (context == null ? "null" : context.getClass().getName()));
        if (!CONFIGURED.compareAndSet(false, true)) {
            H.info("event=configure_skipped reason=already_configured");            H.diag("configure skipped (already configured)");
            return;
        }
        logTargetVersion(context, target);
        H.scheduleDelayedReport();   // so every app reports even with no hits
        // A launch initiated from the settings page carries the config; persist it first so the
        // resolution below picks it up and later launches get it from the local cache.
        if (context instanceof android.app.Activity) {
            Config.storeFromIntent(context, ((android.app.Activity) context).getIntent());
            H.diag("intent payload: " + Config.cacheReport);
        }

        SharedPreferences remote = null;
        try {
            remote = getRemotePreferences(Config.GROUP);
        } catch (Throwable error) {
            H.warn("event=remote_prefs_unavailable: " + H.describe(error));
        }
        H.diag("remote_prefs=" + (remote != null));

        Config.Settings settings = Config.resolve(context, remote);
        H.configSource = settings.source;
        H.diag("resolved source=" + settings.source);
        if (settings.blockPushNotify) {
            installPushNotify();
        } else {
            H.skipped("push_notify", "disabled in settings");
        }
        if (Config.PACKAGE.equals(target)) {
            H.info("event=config_source=" + settings.source
                    + " splash=" + settings.blockSplash
                    + " splashPreload=" + settings.blockSplashPreload
                    + " playerAds=" + settings.blockPlayerAds
                    + " adRequests=" + settings.blockAdRequests
                    + " feedAutoplay=" + settings.blockFeedAutoplay
                    + " debugLog=" + settings.debugLog);
            installTencentRules(loader, settings);
        } else if (Config.PACKAGE_IQIYI.equals(target)) {
            H.info("event=config_source=" + settings.source
                    + " iqiyiSplash=" + settings.iqiyiBlockSplash
                    + " free=" + settings.iqiyiShowFree
                    + " plus=" + settings.iqiyiShowPlus
                    + " member=" + settings.iqiyiShowMember
                    + " debugLog=" + settings.debugLog);
            IqiyiRules.install(this, loader, settings);
            MinePromoRules.install(this, loader, Config.PACKAGE_IQIYI, settings.iqiyiHideMineBanner);
        } else {
            H.info("event=config_source=" + settings.source
                    + " youkuSplash=" + settings.youkuBlockSplash
                    + " youkuAdSlot=" + settings.youkuBlockAdSlot
                    + " youkuPauseAd=" + settings.youkuBlockPauseAd
                    + " youkuBottomBar=" + settings.youkuHideBottomBar
                    + " debugLog=" + settings.debugLog);
            ACTIVE_SETTINGS.set(settings);
            YoukuRules.install(this, loader, settings);
            // The UI rules are driven from here: reflection over ActivityThread plus a bounded set of
            // delayed passes (1/2/4/8/12/16 s). No lifecycle event is hooked, because in Youku none of
            // them arrive - callActivityOnCreate never fires, the app base class hooks are called zero
            // times and lifecycle callbacks are never dispatched.
            YoukuRules.scheduleUiPasses(context, settings);
            registerUiLifecycle(context, settings);
            MinePromoRules.install(this, loader, Config.PACKAGE_YOUKU, settings.youkuHideMinePromos);
        // scaffolding removed after its findings were captured (see docs/GOAL-HANDOFF.md)
        }

        H.info(H.summary());
        H.diag("configure done " + H.summary());
        if (H.hasMisses()) {
            H.info("event=hint some rules missed - the reason field on each rule= line identifies the drift;"
                    + " report it together with the version= line");
        } else {
            H.info("event=hint all rules armed; if ads still show, enable 诊断日志 and re-launch");
        }
    }

    /**
     * Tencent Video's rule set - unchanged from the single-app build, so its install table and
     * behaviour stay byte-identical.
     */
    private void installTencentRules(ClassLoader loader, Config.Settings settings) {
        SplashRules.install(this, loader, settings.blockSplash, settings.blockSplashPreload);
        H.diag("after splash rules");

        if (settings.blockPlayerAds) {
            AdRules.installPlayStrategy(this, loader);
            AdRules.installPlayerAndPause(this, loader);
            AdRules.installHlsMidRoll(this, loader);
            AdRules.installPendantGate(this, loader);
        } else {
            H.skipped("player_pause_gate", "disabled in settings");
        }

        // WITHDRAWN on 9.04.55: the anchor could not be located. R8 reassigned the old class
        // (com.tencent.channelnav.uitls.i1 is now just a lambda), and a type-aware search over
        // ~120 candidate classes across channelnav / tmmhomesidebar / ona found only CONSUMERS of
        // SidebarContentType. The one direct consumer, SideBarKt.K0, takes a Composer parameter -
        // it is a Compose recomposition function, which this project's performance rules forbid
        // hooking. No non-hot-path producer was found, so the rule is withdrawn rather than left
        // in the build pretending to work.
        H.skipped("sidebar_entrances", "withdrawn: no non-hot-path producer of SidebarContentType"
                + " found on 9.04.55; R8 reassigned the old anchor class and the only direct"
                + " consumer is a Compose function (Composer param) - not hookable per perf rules");
        // scaffolding removed after its findings were captured (see docs/GOAL-HANDOFF.md)
        // scaffolding removed after its findings were captured (see docs/GOAL-HANDOFF.md)
        if (settings.blockSplash) SplashRules.installModernAnchors(this, loader);
        else {
            H.skipped("splash_modern_master_gate", "disabled in settings");
            H.skipped("splash_request_gate", "disabled in settings");
            H.skipped("splash_view_gate", "disabled in settings");
            H.skipped("splash_mosaic_gate", "disabled in settings");
        }
        // scaffolding removed after its findings were captured (see docs/GOAL-HANDOFF.md)
        // scaffolding removed after its findings were captured (see docs/GOAL-HANDOFF.md)
        // scaffolding removed after its findings were captured (see docs/GOAL-HANDOFF.md)
        if (settings.blockPlayerAds) AdRules.installPauseAdGate(this, loader);
        else H.skipped("pause_ad_gate", "disabled in settings");

        if (settings.blockAdRequests) {
            AdRules.installFeedAdCell(this, loader);
            AdRules.installHomePromoGate(this, loader);
        }
        else H.skipped("feed_ad_cell", "disabled in settings");
        H.diag("after player rules");

        if (settings.blockAdRequests) AdRules.installRequestGate(this, loader);
        else H.skipped("ad_request_gate", "disabled in settings");
        TencentHomeRules.install(this, loader, settings.blockAdRequests);
        TencentMineRules.install(this, loader, settings.blockMineAd);
        TencentAutoPlayRules.install(this, loader, settings.blockFeedAutoplay);
        TencentBottomNavRules.install(this, loader, settings.hiddenPageTypes);
        H.diag("after request gate");

        if (settings.reducePreload) PreloadRules.install(this, loader);
        else H.skipped("reduce_preload", "disabled in settings");

        // WITHDRAWN: these rules prune entries from a List the UI builds itself, which is the
        // exact shape that blanked the bottom bar when one tab was hidden (user-reported). They
        // have also never been observed firing, so they currently buy nothing while carrying a
        // proven-hazardous failure mode. Replacement plan: append ids to the app's own blacklist
        // (channelnav.uitls.q.y()) so the app does its own consistent filtering - that needs a
        // runtime id->name mapping first. No rule that removes entries from a UI list is armed.
        // scaffolding removed after its findings were captured (see docs/GOAL-HANDOFF.md)
        H.skipped("channel_bar", "withdrawn: prunes a UI-built list (same shape as the bottom-bar"
                + " blanking bug); replacement is app-blacklist append, see docs/ROUND20.md");

        H.diag("after tab rules");
    }

    private void logTargetVersion(Context context, String target) {
        final String label = Config.appLabel(target);
        if (context == null) {
            H.info("event=target_detected package=" + target + " app=" + label
                    + " version=unavailable (no Context) verifiedAgainst="
                    + Config.verifiedVersionName(target) + "/" + Config.verifiedVersionCode(target));
            return;
        }
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(target, 0);
            currentVersionName = String.valueOf(info.versionName);
            H.reportSubject = label + " " + currentVersionName;
            H.targetVersion = currentVersionName;
            H.exactlyVerified = info.versionCode == Config.verifiedVersionCode(target);
            H.info("event=target_detected package=" + target + " app=" + label
                    + " version=" + info.versionName + " versionCode=" + info.versionCode
                    + " verifiedAgainst=" + Config.verifiedVersionName(target)
                    + "/" + Config.verifiedVersionCode(target)
                    + " exactlyVerified=" + (info.versionCode == Config.verifiedVersionCode(target)));
            final int floor = Config.minVersionCode(target);
            if (floor > 0 && info.versionCode > 0 && info.versionCode < floor) {
                H.warn("event=version_below_floor versionCode=" + info.versionCode
                        + " min=" + floor + " (rules will still be attempted)");
            }
        } catch (Throwable error) {
            H.warn("event=version_probe_failed " + H.describe(error));
        }
    }

    /**
     * 推送通知广告闸门：挂 {@code NotificationManager}，三个 App 共用同一套判据。
     *
     * <p>{@code notify(...)} 是 App 进程内所有通知的唯一出口——厂商推送、自建长连接、轮询
     * 拉回来的推广最终都要调它；而且它是平台类、不参与 R8 混淆，App 改版改名的是它自己的类，
     * 这里不受影响。
     *
     * <p>{@code createNotificationChannel} 只观测不拦截：把渠道拦掉会让后续 notify 抛异常，更糟。
     * <b>追剧提醒、播放与下载通知必须活着</b>，所以判据在 {@link NotifyGate} 里刻意避开
     * 播放/观看/追剧/更新/下载这些词——这是视频 App 相对音乐 App 最容易误伤的地方。
     */
    private void installPushNotify() {
        final int expect = 4;
        int got = 0;
        Class<?>[] plain = {int.class, android.app.Notification.class};
        Class<?>[] tagged = {String.class, int.class, android.app.Notification.class};
        for (Class<?>[] signature : new Class<?>[][]{plain, tagged}) {
            final boolean withTag = signature == tagged;
            try {
                Method target = android.app.NotificationManager.class.getDeclaredMethod("notify", signature);
                hook(target).setId("qlc_push_notify_" + (withTag ? "tagged" : "plain"))
                        .intercept(new XposedInterface.Hooker() {
                            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                // 判据全在 NotifyGate 里，任何异常它自己 fail-open 放行。
                                NotifyGate.Decision decision = NotifyGate.evaluate(
                                        (android.app.Notification) chain.getArg(withTag ? 2 : 1),
                                        withTag ? (String) chain.getArg(0) : null);
                                if (decision.suppress) {
                                    // 不调 proceed() = 通知根本不下发；追剧/播放/下载通知完全不受影响。
                                    H.info("push_notify suppressed by: " + decision.reason);
                                    return null;
                                }
                                return chain.proceed(); // 放行路径零日志、零分配
                            }
                        });
                H.info("hooked: push_notify notify(" + signature.length + " args)");
                got++;
            } catch (Throwable error) {
                H.warn("push_notify notify hook unavailable " + H.describe(error));
            }
        }
        try {
            Method target = android.app.NotificationManager.class.getDeclaredMethod(
                    "createNotificationChannel", android.app.NotificationChannel.class);
            hook(target).setId("qlc_push_notify_channel").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        NotifyGate.Decision d = NotifyGate.evaluateChannel(
                                (android.app.NotificationChannel) chain.getArg(0));
                        if (d.suppress) H.info("push_notify ad channel: " + d.reason);
                    } catch (Throwable ignored) {}
                    return result;
                }
            });
            H.info("hooked: push_notify createNotificationChannel");
            got++;
        } catch (Throwable error) {
            H.warn("push_notify channel hook unavailable " + H.describe(error));
        }
        try {
            Method target = android.app.NotificationManager.class.getDeclaredMethod(
                    "createNotificationChannels", java.util.List.class);
            hook(target).setId("qlc_push_notify_channels").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        Object arg = chain.getArg(0);
                        if (arg instanceof java.util.List) {
                            for (Object channel : (java.util.List<?>) arg) {
                                NotifyGate.Decision d = NotifyGate.evaluateChannel(
                                        (android.app.NotificationChannel) channel);
                                if (d.suppress) H.info("push_notify ad channel: " + d.reason);
                            }
                        }
                    } catch (Throwable ignored) {}
                    return result;
                }
            });
            H.info("hooked: push_notify createNotificationChannels");
            got++;
        } catch (Throwable error) {
            H.warn("push_notify channels hook unavailable " + H.describe(error));
        }
        if (got < expect) {
            // 只挂上部分入口必须单独报出，不许算成"全部生效"。
            H.miss("push_notify", "只挂上 " + got + "/" + expect + " 个通知入口，已挂上的判定仍有效");
        } else {
            // 没有真机广告通知时，用固定样本证明"判定函数本身"是对的（含追剧/播放/下载负样本）。
            String selfTest = NotifyGate.selfTest();
            H.info(selfTest);
            H.hooked("push_notify", "通知下发与渠道创建入口已挂接（" + got + "/" + expect
                    + "）；" + selfTest);
        }
    }

    private static String describeHidden(boolean[] hidden) {
        StringBuilder builder = new StringBuilder("[");
        for (int pageType = 0; pageType < hidden.length; pageType++) {
            if (!hidden[pageType]) continue;
            if (builder.length() > 1) builder.append(',');
            builder.append(Config.pageTypeName(pageType));
        }
        return builder.append(']').toString();
    }
}
