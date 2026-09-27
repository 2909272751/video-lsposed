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

            Context context = ContextFinder.find(loader, target);
            if (context != null) {
                H.info("event=context_source=" + ContextFinder.lastSource());
                configure(context, loader, target);
                return;
            }
            H.warn("event=context_probe_failed detail=" + ContextFinder.lastDetail());
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
                        configure(activity instanceof Context ? (Context) activity : null, loader, target);
                    } catch (Throwable error) {
                        H.error("event=configure_failed", error);
                        H.diag("configure threw: " + H.describe(error));
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

    private void configure(Context context, ClassLoader loader, String target) {
        H.targetPackage = target;
        H.setReportContext(context);
        H.diag("configure enter context=" + (context == null ? "null" : context.getClass().getName()));
        if (!CONFIGURED.compareAndSet(false, true)) {
            H.info("event=configure_skipped reason=already_configured");
            H.diag("configure skipped (already configured)");
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
            YoukuRules.install(this, loader, settings);
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
