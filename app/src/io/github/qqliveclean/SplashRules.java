package io.github.qqliveclean;

import android.content.Context;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 开屏广告拦截 (splash ad) - Tencent Video's own QAd splash SDK.
 *
 * Evidence (Tencent Video 9.03.95.31890, classes13.dex):
 * {@code com.tencent.qqlive.qadsplash.splash.u} is the splash manager singleton
 * ({@code u.x()}). All three splash entry points consult the same master switch:
 *
 * <pre>
 *   static boolean F()                  // -&gt; QADServiceHandler.needCloseSplashAd(), cached in a static Boolean
 *   void          K(String, String)     // requestPreloadOrder : if (F()) return;
 *   void          N(request, cb)        // requestSplashQAd     : if (F()) return;
 *   boolean       Q(Context)            // start splash         : if (F()) return false;
 * </pre>
 *
 * F() == true is the app's own "no splash ad" path (kid mode / compliance), so forcing
 * it makes Tencent Video skip the ad exactly as designed instead of breaking the launch
 * sequence - unlike forcing an arbitrary return value elsewhere. It also stops the
 * background order preload, which is where the periodic radio wakeups come from.
 *
 * Each rule is independent: a renamed method logs a miss and the others keep working.
 * Intercepts return cached boxed constants and do no allocation, reflection or lookup.
 */
final class SplashRules {
    private static final AtomicBoolean MASTER_HIT = new AtomicBoolean(false);
    private static final AtomicBoolean START_HIT = new AtomicBoolean(false);
    private static final AtomicBoolean PRELOAD_HIT = new AtomicBoolean(false);

    private SplashRules() {}




    // ------------------------------------------- 9.04.55 re-anchors (non-obfuscated classes)

    private static final AtomicBoolean REQUEST_GATE_HIT = new AtomicBoolean(false);
    private static final AtomicBoolean ACTIVITY_GATE_HIT = new AtomicBoolean(false);

    /**
     * Suppresses the splash ad REQUEST at its non-obfuscated entry point.
     * {@code QADSplashPreSelectManager.requestSplashQAd(...)} is the request itself; skipping it
     * means no splash ad is ever asked for. Non-obfuscated name, one-shot semantics.
     */
    private static void installRequestGate(MainHook module, ClassLoader loader) {
        final String rule = "splash_request_gate";
        Class<?> owner;
        Method request;
        try {
            owner = R.load(loader, "com.tencent.qqlive.qadsplash.splash.QADSplashPreSelectManager");
            request = R.findByShape(owner, new String[]{"requestSplashQAd"}, void.class,
                    (Class<?>) null, (Class<?>) null);
        } catch (Throwable error) {
            H.miss(rule, "QADSplashPreSelectManager not resolvable: " + H.describe(error));
            return;
        }
        if (request == null) {
            H.miss(rule, "requestSplashQAd(?,?)V not found; void(): " + R.describeMethods(owner));
            return;
        }
        try {
            module.hook(request).setId("qqlive_splash_request_gate").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) {
                    H.hit(rule, "splash ad request suppressed at QADSplashPreSelectManager", REQUEST_GATE_HIT);
                    return null;
                }
            });
            H.hooked(rule, "QADSplashPreSelectManager.requestSplashQAd(?,?)V suppressed");
        } catch (Throwable error) {
            H.miss(rule, "hook failed: " + H.describe(error));
        }
    }

    /**
     * Cold/hot splash decisions on the real helper, matched STRUCTURALLY (boolean return, Activity
     * parameter) rather than by a single-letter name, because those letters are exactly what R8
     * reassigns between releases.
     */
    private static void installActivityGate(MainHook module, ClassLoader loader) {
        final String rule = "splash_activity_gate";
        Class<?> helper;
        try {
            helper = R.load(loader, "com.tencent.qqlive.qadsplash.splash.QADSplashHelper");
        } catch (Throwable error) {
            H.miss(rule, "QADSplashHelper not found: " + H.describe(error));
            return;
        }
        Method gate = R.findByShape(helper, new String[]{"X", "Y", "Z"}, boolean.class,
                android.app.Activity.class);
        if (gate == null) {
            H.miss(rule, "no X/Y/Z(Activity)Z on QADSplashHelper; boolean(): "
                    + R.describeMethods(helper));
            return;
        }
        try {
            module.hook(gate).setId("qqlive_splash_activity_gate").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) {
                    H.hit(rule, "cold/hot splash decision forced to false", ACTIVITY_GATE_HIT);
                    return Boolean.FALSE;
                }
            });
            H.hooked(rule, "QADSplashHelper." + gate.getName() + "(Activity)Z -> false");
        } catch (Throwable error) {
            H.miss(rule, "hook failed: " + H.describe(error));
        }
    }

    /** 9.04.55 splash re-anchors; independent of the legacy splash.u path so both report. */
    static void installModernAnchors(MainHook module, ClassLoader loader) {
        installModernMasterGate(module, loader);
        installRequestGate(module, loader);
        installViewGate(module, loader, "com.tencent.qqlive.qadsplash.view.component.QAdSplashView",
                "splash_view_gate");
        installViewGate(module, loader, "com.tencent.qqlive.ovbsplash.mosaic.QAdSplashMosaicDynamicView",
                "splash_mosaic_gate");
        H.skipped("splash_decision_gate", "withdrawn: QADSplashHelper.r() only reads an unrelated state flag");
        // splash_activity_gate WITHDRAWN: X/Y/Z were guessed as boolean+Activity but the
        // device inventory showed they are void(Activity), so the shape was wrong. Removing it
        // beats leaving a rule that can never match.
    }

    /** 9.04.55 moved the original manager from splash.u to splash.v. F is the SDK's
     * cached needCloseSplashAd switch; its own K/N/R paths all return early when true. */
    private static void installModernMasterGate(MainHook module, ClassLoader loader) {
        final String rule = "splash_modern_master_gate";
        try {
            Class<?> manager = R.load(loader, "com.tencent.qqlive.qadsplash.splash.v");
            Method gate = R.find(manager, "F", boolean.class);
            if (gate == null || !java.lang.reflect.Modifier.isStatic(gate.getModifiers())) {
                H.miss(rule, "splash.v.F()Z static switch unavailable");
                return;
            }
            final AtomicBoolean once = new AtomicBoolean(false);
            module.hook(gate).setId("qqlive_splash_modern_master").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) {
                    H.hit(rule, "SDK needCloseSplashAd -> true", once);
                    return Boolean.TRUE;
                }
            });
            H.hooked(rule, "splash.v.F()Z -> true (native close-splash path)");
        } catch (Throwable error) {
            H.miss(rule, "modern master unavailable: " + H.describe(error));
        }
    }

    /** End a selected cached ad before its display path initializes the artwork/video. */
    private static void installViewGate(MainHook module, ClassLoader loader,
                                        String className, String rule) {
        final Method show;
        final Method end;
        try {
            Class<?> view = R.load(loader, className);
            Class<?> base = R.load(loader, "com.tencent.qqlive.qadsplash.AbsQAdSplashView");
            show = R.find(view, "showSplashAd", void.class);
            end = R.find(base, "forceEndSplashAd", void.class);
            if (show == null || end == null) {
                H.miss(rule, "showSplashAd/forceEndSplashAd signature changed");
                return;
            }
        } catch (Throwable error) {
            H.miss(rule, "splash view unavailable: " + H.describe(error));
            return;
        }
        final AtomicBoolean once = new AtomicBoolean(false);
        try {
            module.hook(show).setId("qqlive_" + rule).intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    try {
                        end.invoke(chain.getThisObject());
                        H.hit(rule, "cached splash ad ended before display", once);
                        return null;
                    } catch (Throwable error) {
                        H.warn("event=splash_view_fallback rule=" + rule + " error=" + H.describe(error));
                        return chain.proceed();
                    }
                }
            });
            H.hooked(rule, className + ".showSplashAd() -> forceEndSplashAd()");
        } catch (Throwable error) {
            H.miss(rule, "hook failed: " + H.describe(error));
        }
    }

    static void install(MainHook module, ClassLoader loader, boolean blockSplash, boolean blockPreload) {
        Class<?> manager;
        try {
            manager = R.load(loader, Config.SPLASH_MANAGER);
        } catch (Throwable error) {
            H.miss("splash_manager", "class not found: " + H.describe(error));
            return;
        }
        H.info("event=splash_manager_found class=" + manager.getName());

        // WITHDRAWN on 9.04.55 - all three legacy splash rules.
        // com.tencent.qqlive.qadsplash.splash.u is a Runnable in 9.04.55: R8 reassigned the
        // single letter, so the anchor points at an unrelated class. That was proved by dumping
        // its method table on device (only Object/Runnable members, no splash logic), and it is
        // why these rules reported "F()Z / Q(Context)Z / K(String,String)V not found" instead of
        // degrading gracefully. They cannot be re-anchored on that class, and the replacement is
        // already armed: splash_request_gate, anchored on the NON-obfuscated
        // QADSplashPreSelectManager.requestSplashQAd(...).
        H.skipped("splash_gate", "withdrawn on 9.04.55: anchor class reassigned by R8"
                + " (splash.u is a Runnable); replaced by splash_request_gate");
        H.skipped("splash_start_gate", "withdrawn on 9.04.55: same reassigned anchor"
                + " (splash.u is a Runnable)");
        H.skipped("splash_preload", "withdrawn on 9.04.55: same reassigned anchor;"
                + " preload request is covered by splash_request_gate");
    }

    /**
     * The funnel: {@code static boolean F()} is "needCloseSplashAd". Always returning
     * true also leaves its cached static flag null, so later calls short-circuit too.
     */
    private static void installMasterGate(MainHook module, Class<?> manager) {
        final String rule = "splash_gate";
        Method gate = R.find(manager, "F", boolean.class);
        if (gate == null) {
            H.miss(rule, "F()Z not found on " + manager.getName()
                    + "; boolean(): " + R.describeMethods(manager));
            return;
        }
        try {
            module.hook(gate).setId("qqlive_splash_master_gate").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) {
                    if (MASTER_HIT.compareAndSet(false, true)) H.info("hit=" + rule + " needCloseSplashAd -> true");
                    return Boolean.TRUE;
                }
            });
            H.hooked(rule, "F()Z -> true (app's own needCloseSplashAd path)");
        } catch (Throwable error) {
            H.miss(rule, "hook failed: " + H.describe(error));
        }
    }

    /** Redundant guard: {@code boolean Q(Context)} is the "begin start splash ad" entry. */
    private static void installStartGate(MainHook module, Class<?> manager) {
        final String rule = "splash_start_gate";
        Method start = R.find(manager, "Q", boolean.class, Context.class);
        if (start == null) {
            H.miss(rule, "Q(Context)Z not found; boolean(...): "
                    + R.describeMethods(manager));
            return;
        }
        try {
            module.hook(start).setId("qqlive_splash_start_gate").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) {
                    if (START_HIT.compareAndSet(false, true)) H.info("hit=" + rule + " start splash -> false");
                    return Boolean.FALSE;
                }
            });
            H.hooked(rule, "Q(Context)Z -> false");
        } catch (Throwable error) {
            H.miss(rule, "hook failed: " + H.describe(error));
        }
    }

    /**
     * {@code void K(String, String)} issues the splash-order network request. Swallowing
     * it keeps the ad cache cold and removes the radio wakeups the stock
     * {@code SplashAdPreloadManager} alarm would otherwise cause.
     */
    private static void installPreloadGate(MainHook module, Class<?> manager) {
        final String rule = "splash_preload";
        // 9.03.95 declares K(String,String)V; 9.04.55 renamed it to run(String,String)V
        // (the install table's own miss line named "run"). Try the old name first so old
        // installs keep working.
        Method preload = R.find(manager, "K", void.class, String.class, String.class);
        String preloadName = "K";
        if (preload == null) {
            preload = R.find(manager, "run", void.class, String.class, String.class);
            preloadName = "run";
        }
        if (preload == null) {
            H.miss(rule, "K(String,String)V not found; void(String,String): "
                    + R.describeMethods(manager));
            return;
        }
        try {
            module.hook(preload).setId("qqlive_splash_preload_gate").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) {
                    if (PRELOAD_HIT.compareAndSet(false, true)) H.info("hit=" + rule + " requestPreloadOrder suppressed");
                    return null;
                }
            });
            H.hooked(rule, preloadName + "(String,String)V suppressed (no order preload request)");
        } catch (Throwable error) {
            H.miss(rule, "hook failed: " + H.describe(error));
        }
    }

    private static final AtomicBoolean DECISION_GATE_HIT = new AtomicBoolean(false);

    /**
     * The REAL splash decision gate on 9.04.55, found by EVIDENCE not guesswork: the read-only
     * boolean sweep over the genuine splash classes reported exactly
     *     "gate consulted: QADSplashHelper.r/0"
     * on a cold start that had a splash opportunity. QADSplashHelper is the non-obfuscated helper
     * (the old anchor splash.u turned out to be a Runnable - R8 had reassigned the letter), and
     * r()Z is a no-arg boolean, i.e. a one-shot decision. Answering false suppresses the ad;
     * worst case is "the ad still shows", never a broken launch.
     */
    private static void installDecisionGate(MainHook module, ClassLoader loader) {
        final String rule = "splash_decision_gate";
        Class<?> helper;
        Method decision;
        try {
            helper = R.load(loader, "com.tencent.qqlive.qadsplash.splash.QADSplashHelper");
            decision = R.find(helper, "r", boolean.class);
        } catch (Throwable error) {
            H.miss(rule, "QADSplashHelper not resolvable: " + H.describe(error));
            return;
        }
        if (decision == null) {
            H.miss(rule, "r()Z not found; boolean(): " + R.describeMethods(helper));
            return;
        }
        try {
            module.hook(decision).setId("qqlive_splash_decision_gate").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) {
                    H.hit(rule, "splash decision forced to false (QADSplashHelper.r)", DECISION_GATE_HIT);
                    return Boolean.FALSE;
                }
            });
            H.hooked(rule, "QADSplashHelper.r()Z -> false");
        } catch (Throwable error) {
            H.miss(rule, "hook failed: " + H.describe(error));
        }
    }}
