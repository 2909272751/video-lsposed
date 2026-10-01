package io.github.qqliveclean;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 优酷 (com.youku.phone) 规则集。
 *
 * <p>Evidence source: {@code scratch-recon/youku-gates.md} (recon on 11.2.1 / versionCode 901).
 * Every class named below was re-verified against the real {@code class_defs} of
 * {@code reference-apk/youku-11.2.1-base.apk} (index: {@code reference-apk/realclasses-youku.txt},
 * built by {@code tools/dexclasses.py}); jadx-only "ghost" names were not used.
 *
 * <p>Rules, in the report's order of preference:
 * <ol>
 *   <li><b>S-2 {@code youku_splash_cold_switch} / {@code youku_splash_hot_switch}</b> -
 *       {@code SplashConfigInfo.getColdAdEnabled()/getHotAdEnabled() -> false}. This is the
 *       app's OWN "disabled" branch: its own monitor codes 1020/1010 and its own
 *       "return false because disabled." log lines run, so the app is not lied to.</li>
 *   <li><b>S-1 {@code youku_splash_cold_gate}</b> - {@code j.b1.b8.g.c.h(Activity) -> false}</li>
 *   <li><b>A-1 {@code youku_ad_slot_gate}</b> - {@code j.b1.w3.b.c.d.u(int) -> false}, which
 *       routes into the app's own {@code onFailed(3,"当前广告位屏蔽")} branch.</li>
 *   <li><b>B-2 {@code youku_bottom_bar_hide}</b> - {@code j.b1.s.o0.g.c(Z)} driven to false, so
 *       the app calls its own {@code setVisibility(8)}. Whole-bar, opt-in (default OFF).</li>
 *   <li><b>P-1 {@code youku_pause_ad}</b> - the Orange config read that sets
 *       {@code AdPauseFullScreenPlugin.r0} is answered {@code "false"} (the app's own
 *       disabled value). See {@link #installPauseAdGate} for why the field itself is not
 *       hooked.</li>
 *   <li><b>T-1 {@code youku_channel_filter}</b> - deliberately NOT armed; reported as a miss
 *       with its reason (see {@link #reportChannelFilterSkipped}).</li>
 * </ol>
 *
 * <p>Not hookable here on purpose: {@code PlayerEmbeddedStreamAdProviderImpl.hasCutAd} - the
 * same model drives position/seek math, so lying about it desyncs playback (report §4.1).
 *
 * <p>Every rule installs in its own try/catch, reports exactly one of
 * {@code hooked}/{@code miss}, and owns its own once-flag for first-hit logging. Intercepts
 * allocate nothing, do no reflection and log at most once.
 */
final class YoukuRules {

    // ---- per-rule once-flags: a shared flag hides the evidence of every rule after the first ----
    private static final AtomicBoolean COLD_SWITCH_HIT = new AtomicBoolean(false);
    private static final AtomicBoolean HOT_SWITCH_HIT = new AtomicBoolean(false);
    private static final AtomicBoolean COLD_GATE_HIT = new AtomicBoolean(false);
    private static final AtomicBoolean AD_SLOT_HIT = new AtomicBoolean(false);
    private static final AtomicBoolean PAUSE_AD_HIT = new AtomicBoolean(false);
    /** 广告 SDK 开关的「至少命中过一次」标记，只用于避免重复打日志。 */
    private static final AtomicBoolean AD_SWITCH_HIT = new AtomicBoolean(false);
    /** 前贴事件拦截的命中标记。 */
    private static final AtomicBoolean PRE_ROLL_EVENT_HIT = new AtomicBoolean(false);
    /** 前贴控制点探针的「每个方法只打一次」标记，按方法名各持一个。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, AtomicBoolean> PROBE_FLAGS =
            new java.util.concurrent.ConcurrentHashMap<String, AtomicBoolean>();
    /** 事件总线探针的「只打一次」标记（探针本身每条事件只记一次名）。 */
    private static final AtomicBoolean EVENT_PROBE = new AtomicBoolean(false);
    /** 已见过的总线事件名，用于去重，避免刷屏。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> EVENT_NAMES_SEEN =
            new java.util.concurrent.ConcurrentHashMap<String, Boolean>();
    /** 前贴事件的整串匹配式；宁可漏拦不可误拦，理由见 isPreRoll 的注释。 */
    private static final java.util.regex.Pattern PRE_ROLL = java.util.regex.Pattern.compile(
            "pre_?ad(start|end|view|play|playing|show|finish|finished|over|skip|error|load|loaded"
                    + "|request|response|config|info|result|player|countdown|material|source|status)*");
    /** 已挂过的配置开关，键为「实现类#方法名」，防止同一开关被重复挂载。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> AD_SWITCH_ARMED =
            new java.util.concurrent.ConcurrentHashMap<String, Boolean>();
    // The code and message Youku itself passes when it suppresses an ad slot.
    private static final AtomicBoolean BOTTOM_BAR_HIT = new AtomicBoolean(false);
    private static final AtomicBoolean TAB_FILTER_HIT = new AtomicBoolean(false);
    private static final AtomicBoolean HOME_TOP_AD_HIT = new AtomicBoolean(false);
    private static WeakReference<View> lastTopCard = new WeakReference<View>(null);
    private static WeakReference<ViewGroup> lastTabBar = new WeakReference<ViewGroup>(null);

    /**
     * Pre-allocated argument vectors. {@code intercept} must not allocate, so the arrays are
     * built once at class-init and only ever hold one constant value - reusing them is safe
     * even if the framework keeps the reference.
     */
    private static final Object[] HIDE_BAR_ARGS = {Boolean.FALSE};
    private static final Object[] PAUSE_AD_DISABLED = {"false"};

    private YoukuRules() {}

    static void install(MainHook module, ClassLoader loader, Config.Settings settings) {
        // ---- S-2: the app's own master switch (primary, in report order) ----
        if (settings.youkuBlockSplash) {
            installSplashSwitch(module, loader, true);
            installSplashSwitch(module, loader, false);
            // ---- S-1 WITHDRAWN on 11.2.15 ----
            // Its anchor j.b1.b8.g.c is ClassNotFoundException in 11.2.15 (R8 reshuffled the
            // single-letter name), and S-2 above already answers the app's OWN cold switch
            // (SplashConfigInfo.getColdAdEnabled), which is armed and has hit on device. This was
            // only belt-and-braces, so it is withdrawn rather than left missing forever.
            H.skipped("youku_splash_cold_gate", "withdrawn on 11.2.15: obfuscated anchor gone and"
                    + " the app's own getColdAdEnabled switch is already armed and has hit");
        } else {
            H.skipped("youku_splash_cold_switch", "disabled in settings");
            H.skipped("youku_splash_hot_switch", "disabled in settings");
            H.skipped("youku_splash_cold_gate", "disabled in settings");
        }

        // ---- A-1 replacement: CSJ/穿山甲 DSP switch (found in round 80) ----
        // isEnableCsjDsp()Z lives on the NON-obfuscated SplashConfigInfo and is a plain
        // boolean ad-network switch - the same shape as the two Youku rules already
        // verified hitting on device, so it is the safest lever available.
        if (settings.youkuBlockAdSlot) installCsjDspGate(module, loader);

        // ---- A-1 WITHDRAWN on 11.2.15 ----
        // Device evidence (read-only inventory of the readable hub): AdRequestManager exposes only
        // a/b/c/d and ALL of them are void - there is no decision method - while the obfuscated
        // class that did carry one (j.b1.w3.b.c.d.u(int)Z) is gone. With no durable anchor, the
        // rule is withdrawn instead of reporting a permanent miss.
        H.skipped("youku_ad_slot_gate", "withdrawn on 11.2.15: obfuscated anchor gone and the"
                + " readable AdRequestManager has no decision method (all void)");

        // ---- B-2: whole bottom bar (opt-in; hides the bar, not individual tabs) ----
        if (settings.youkuHideBottomBar) installBottomBarHide(module, loader);
        else H.skipped("youku_bottom_bar_hide", "disabled in settings (default OFF: hides the whole bar)");
        installBottomTabFilter(module, loader, settings);

        // ---- P-1: full-screen pause ad ----
        if (settings.youkuBlockPauseAd) installPauseAdGate(module, loader);
        else H.skipped("youku_pause_ad", "disabled in settings");
        // ---- A-0: per-placement switches from Youku's own ad SDK ----
        if (settings.youkuBlockPauseAd) installAdSwitchGate(module, loader);
        else H.skipped("youku_ad_switch", "disabled in settings");
        // ---- A-2: pre-roll ad events on the app's own event bus ----
        // Control run recorded 2026-10-01: with this gate removed entirely the
        // DetailActivity still rendered black, so the blank surface comes from
        // Youku restoring its mini player, not from this rule.
        if (settings.youkuBlockPauseAd) installPreRollEventGate(module, loader);
        else H.skipped("youku_preroll_event", "disabled in settings");
        // ---- A-3: read-only pre-roll control points ----
        installPreRollProbe(module, loader);
        // ---- A-1: pre-roll ad slot (withdrawn, see the reason below) ----
        reportPreRollAdWithdrawn();
        reportKwadGateWithdrawn();
        reportAdRequestGateWithdrawn();

        // ---- T-1: top channel bar, per-channel native filter ----
        reportChannelFilterSkipped(loader);
    }

    /** 11.2.15 inlines HomeBottomNav.b(List); trim only its five rendered buttons. */
    private static void installBottomTabFilter(MainHook module, ClassLoader loader,
                                               final Config.Settings settings) {
        final boolean filtering = !settings.youkuShowShortDrama || !settings.youkuShowVip
                || !settings.youkuShowGoodMovies;
        if (!filtering && !settings.youkuBlockAdSlot) {
            H.skipped("youku_tab_filter", "all tabs visible");
            H.skipped("youku_home_top_ad", "disabled in settings");
            return;
        }
        try {
            Class<?> instrumentation = Class.forName("android.app.Instrumentation", false, loader);
            Method resume = R.find(instrumentation, "callActivityOnResume", void.class, Activity.class);
            if (resume == null) { H.miss("youku_tab_filter", "Activity resume unavailable"); return; }
            module.hook(resume).setId("youku_bottom_tabs_view").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    final Activity activity = (Activity) chain.getArg(0);
                    if (activity != null) {
                        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
                        handler.postDelayed(new Runnable() {
                            @Override public void run() {
                                if (filtering) filterBottomBar(activity, settings);
                                if (settings.youkuBlockAdSlot) hideHomeTopAd(activity);
                            }
                        }, 500);
                        if (settings.youkuBlockAdSlot) {
                            handler.postDelayed(new Runnable() {
                                @Override public void run() { hideHomeTopAd(activity); }
                            }, 3000);
                            handler.postDelayed(new Runnable() {
                                @Override public void run() { hideHomeTopAd(activity); }
                            }, 6000);
                        }
                    }
                    return result;
                }
            });
            if (filtering) H.hooked("youku_tab_filter", "five-button navigation view; runs after Activity resume");
            else H.skipped("youku_tab_filter", "all tabs visible");
            if (settings.youkuBlockAdSlot)
                H.hooked("youku_home_top_ad", "first home carousel card collapse");
            else H.skipped("youku_home_top_ad", "disabled in settings");
        } catch (Throwable error) {
            H.miss("youku_tab_filter", H.describe(error));
            if (settings.youkuBlockAdSlot) H.miss("youku_home_top_ad", H.describe(error));
        }
    }

    private static void hideHomeTopAd(Activity activity) {
        try {
            int recyclerId = activity.getResources().getIdentifier("recycler_view", "id", activity.getPackageName());
            int carouselId = activity.getResources().getIdentifier("lunbo_inner_container", "id", activity.getPackageName());
            if (recyclerId == 0 || carouselId == 0) return;
            View card = activity.findViewById(carouselId);
            if (card == null) return;
            while (card.getParent() instanceof ViewGroup) {
                ViewGroup parent = (ViewGroup) card.getParent();
                if (parent.getId() == recyclerId && recyclerId != 0) {
                    if (lastTopCard.get() == card) return;
                    int[] position = new int[2];
                    card.getLocationOnScreen(position);
                    if (position[1] > activity.getResources().getDisplayMetrics().heightPixels / 2) return;
                    ViewGroup.LayoutParams params = card.getLayoutParams();
                    if (params == null) return;
                    params.height = 0;
                    card.setLayoutParams(params);
                    card.setVisibility(View.GONE);
                    lastTopCard = new WeakReference<View>(card);
                    H.hit("youku_home_top_ad", "home carousel card collapsed", HOME_TOP_AD_HIT);
                    return;
                }
                card = parent;
            }
        } catch (Throwable error) { H.warn("event=youku_home_top_ad_runtime_error " + H.describe(error)); }
    }

    private static void filterBottomBar(Activity activity, Config.Settings settings) {
        try {
            int id = activity.getResources().getIdentifier("kf_root_page", "id", activity.getPackageName());
            if (id == 0) return;
            View root = activity.findViewById(id);
            int[] visited = {0};
            ViewGroup bar = findBottomBar(root, visited);
            if (bar == null || lastTabBar.get() == bar) return;
            int removed = 0;
            if (!settings.youkuShowShortDrama) { bar.getChildAt(1).setVisibility(View.GONE); removed++; }
            if (!settings.youkuShowVip) { bar.getChildAt(2).setVisibility(View.GONE); removed++; }
            if (!settings.youkuShowGoodMovies) { bar.getChildAt(3).setVisibility(View.GONE); removed++; }
            lastTabBar = new WeakReference<ViewGroup>(bar);
            if (removed > 0) H.hit("youku_tab_filter", "hidden=" + removed + " from five-button bar", TAB_FILTER_HIT);
        } catch (Throwable error) { H.warn("event=youku_tab_filter_runtime_error " + H.describe(error)); }
    }

    private static ViewGroup findBottomBar(View view, int[] visited) {
        if (!(view instanceof ViewGroup) || ++visited[0] > 250) return null;
        ViewGroup group = (ViewGroup) view;
        if (group instanceof android.widget.LinearLayout && group.getChildCount() == 5
                && "首页".equals(tabLabel(group.getChildAt(0)))
                && "短剧".equals(tabLabel(group.getChildAt(1)))
                && "会员".equals(tabLabel(group.getChildAt(2)))
                && "淘好片".equals(tabLabel(group.getChildAt(3)))
                && "我的".equals(tabLabel(group.getChildAt(4)))) return group;
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            ViewGroup found = findBottomBar(group.getChildAt(i), visited);
            if (found != null) return found;
        }
        return null;
    }

    private static String tabLabel(View view) {
        if (view instanceof TextView) return String.valueOf(((TextView) view).getText());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                String text = tabLabel(group.getChildAt(i));
                if (text.length() != 0) return text;
            }
        }
        return "";
    }

    // ------------------------------------------------------------------ S-2

    /**
     * Forcing the config getter to false makes the app take its own
     * "isColdSplashAdAllowed return false because disabled." (monitor 1020) /
     * "isHotSplashAdAllowed return false because disabled." (monitor 1010) path, telemetry
     * included. Called once per Activity create / resume-from-background, not from the UI.
     */
    private static void installSplashSwitch(MainHook module, ClassLoader loader, boolean cold) {
        final String rule = cold ? "youku_splash_cold_switch" : "youku_splash_hot_switch";
        final String name = cold ? "getColdAdEnabled" : "getHotAdEnabled";
        final AtomicBoolean flag = cold ? COLD_SWITCH_HIT : HOT_SWITCH_HIT;
        // Pre-built so the intercept itself allocates nothing.
        final String detail = name + "() -> false (app's own disabled branch: monitor "
                + (cold ? 1020 : 1010) + ")";
        Class<?> info;
        try {
            info = R.load(loader, Config.YK_SPLASH_CONFIG_INFO);
        } catch (Throwable error) {
            H.miss(rule, Config.YK_SPLASH_CONFIG_INFO + " not found: " + H.describe(error));
            return;
        }
        Method getter = R.find(info, name, boolean.class);
        if (getter == null) {
            H.miss(rule, name + "()Z not found on " + info.getName()
                    + "; boolean(): " + R.describeCandidates(info, boolean.class));
            return;
        }
        try {
            module.hook(getter).setId("youku_splash_" + (cold ? "cold" : "hot") + "_switch")
                    .intercept(new XposedInterface.Hooker() {
                        @Override public Object intercept(XposedInterface.Chain chain) {
                            H.hit(rule, detail, flag);
                            return Boolean.FALSE;
                        }
                    });
            H.hooked(rule, "SplashConfigInfo." + name + "()Z -> false");
        } catch (Throwable error) {
            H.miss(rule, "hook failed: " + H.describe(error));
        }
    }

    // ------------------------------------------------------------------ S-1

    /**
     * {@code j.b1.b8.g.c.h(Landroid/app/Activity;)Z} - the class's log tag is
     * "SplashAdPresenter", the class and method names are obfuscated.
     *
     * <p>Its only caller is {@code AdOnActivityLifeCycle.onActivityCreated}, i.e. once per cold
     * start; returning false skips the show branch and the app then reports the splash as
     * finished. Worst case the ad still shows.
     *
     * <p>Name drift is handled structurally, but the shape {@code (Activity)Z} is NOT unique in
     * 11.2.1 (h / m / q all match), so more than one candidate is an ambiguity and is reported
     * as a miss instead of a guess.
     */
    private static void installColdSplashGate(MainHook module, ClassLoader loader) {
        final String rule = "youku_splash_cold_gate";
        Class<?> presenter;
        try {
            presenter = R.load(loader, Config.YK_SPLASH_PRESENTER);
        } catch (Throwable error) {
            H.miss(rule, Config.YK_SPLASH_PRESENTER
                    + " (log tag SplashAdPresenter) not found: " + H.describe(error));
            return;
        }
        Method gate = R.find(presenter, Config.YK_SPLASH_COLD_GATE, boolean.class, Activity.class);
        String how = "exact name " + Config.YK_SPLASH_COLD_GATE;
        if (gate == null) {
            ArrayList<String> candidates = new ArrayList<String>();
            Method unique = null;
            try {
                for (Method candidate : presenter.getDeclaredMethods()) {
                    Class<?>[] parameters = candidate.getParameterTypes();
                    if (candidate.getReturnType() != boolean.class) continue;
                    if (parameters.length != 1 || parameters[0] != Activity.class) continue;
                    candidate.setAccessible(true);
                    candidates.add(candidate.getName());
                    unique = candidate;
                }
            } catch (Throwable error) {
                H.miss(rule, "structural scan failed: " + H.describe(error));
                return;
            }
            if (candidates.size() != 1) {
                H.miss(rule, "name " + Config.YK_SPLASH_COLD_GATE + " missing and the (Activity)Z"
                        + " shape is not unique (" + candidates.size() + " candidates: " + candidates
                        + ") - refusing to guess which one is isColdSplashAdAllowed");
                return;
            }
            gate = unique;
            how = "structural, unique (Activity)Z";
        }
        try {
            module.hook(gate).setId("youku_splash_cold_gate").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) {
                    H.hit(rule, "isColdSplashAdAllowed -> false (AdOnActivityLifeCycle.onActivityCreated)",
                            COLD_GATE_HIT);
                    return Boolean.FALSE;
                }
            });
            H.hooked(rule, Config.YK_SPLASH_PRESENTER + "." + gate.getName()
                    + "(Activity)Z -> false (" + how + ")");
        } catch (Throwable error) {
            H.miss(rule, "hook failed: " + H.describe(error));
        }
    }

    // ------------------------------------------------------------------ A-1

    /**
     * {@code j.b1.w3.b.c.d.u(int)Z} is "is this ad slot enabled". Its consumer
     * {@code com.youku.oneadsdk.request.AdRequestManager.d(...)} takes the app's own
     * {@code fVar.onFailed(3, "当前广告位屏蔽")} branch and returns without issuing the request.
     *
     * <p>Caveat carried from the report: only the generic slot types reach this check - the
     * hard-coded types {@code -10090/-10089/-10087/-10086/10000/20000/30000} return earlier in
     * {@code AdRequestManager}, so this gate does not cover them.
     */
    /**
     * A-2: three attempts at the Youku pre-roll, all refuted. Kept as the record of what was ruled
     * out, so the next attempt starts from the evidence rather than repeating the probing.
     *
     * <p>Attempt 1 - the app's own ad layer. {@code j.b1.w3.b.c.d.u(int)Z} does not exist in
     * 11.2.13, {@code AdRequestManager} exposes only void methods, and rewriting the cached Orange
     * configs {@code yk_adsdk_syscfg} ({@code *.enable}) and {@code one_ad_config}
     * ({@code enable_youku_ssp}) to 0 left the ad on screen in both cases.
     *
     * <p>Attempt 2 - the Kwai Union (快手联盟) SDK. Logcat during a live pre-roll does show it
     * running in-process ({@code com.kwad.sdk.o}, {@code com.kwad.sdk.commercial.g.a}, the
     * {@code ksad-sdk_core1} threads), and unlike Youku's own layer a third-party SDK is loaded
     * into this process, so its classes are hookable. Two anchors were hooked and both stayed at
     * zero hits: {@code com.kwad.sdk.o} has no {@code init} in its whole hierarchy (only the
     * zero-arg {@code FG/FH/FI/FJ} getters and {@code GE/GF}), and
     * {@code com.kwad.sdk.commercial.g.a.i(String,String)} - real, but package-private, so only
     * {@code getDeclaredMethods()} sees it - never fires during a pre-roll. The
     * "Long monitor contention ... at ...i(String,String)" line is a contended lock, not the
     * ad request.
     *
     * <p>Attempt 3 - the SDK's real public API, read from the shipped dex instead of guessed.
     * {@code dexdump} on the Youku APK's classes9.dex shows the Kwai API is not obfuscated at all:
     * {@code com.kwad.sdk.api.KsLoadManager.loadFullScreenVideoAd(KsScene, FullScreenVideoAdListener)}
     * is exactly the fullscreen video slot, i.e. the pre-roll, alongside
     * {@code loadInterstitialAd}, {@code loadSplashScreenAd}, {@code loadFeedAd},
     * {@code loadBannerAd} and the rest. The interface declaration is abstract and libxposed
     * refuses abstract methods, so the concrete {@code PUBLIC FINAL} definitions were hooked
     * instead: {@code com.kwad.components.core.b} (the router) and, for the record, the other two
     * are {@code com.kwad.sdk.api.b} and {@code com.kwad.components.ad.fullscreen.a}.
     *
     * <p>That gate armed cleanly and then <b>never fired either</b>, while the pre-roll played to
     * completion with its countdown intact. So the pre-roll is not a Kwai fullscreen video request
     * either: the Kwai SDK is present and active but is serving other slots, and the pre-roll
     * comes from Youku's own ad system through some path this module has not yet located.
     *
     * <p>Nothing is hooked here. Leaving proven-inert hooks installed would only make
     * {@code matched} look like success, which is exactly the misreading this table exists to
     * prevent.
     */
    private static void reportKwadGateWithdrawn() {
        H.skipped("youku_kwad_ad", "withdrawn on 11.2.13 after three refuted anchors. The Kwai Union"
                + " SDK really does run in-process (com.kwad.sdk.*, ksad-sdk_core1 threads) and is"
                + " hookable, but none of these fires during a pre-roll: com.kwad.sdk.o has no init"
                + " in its hierarchy; com.kwad.sdk.commercial.g.a.i(String,String) is 0 hits; and"
                + " com.kwad.components.core.b.loadFullScreenVideoAd(KsScene,"
                + " FullScreenVideoAdListener) - the SDK's own unobfuscated fullscreen-video slot,"
                + " read from classes9.dex - armed cleanly and also stayed at 0 hits while the ad"
                + " played with its countdown intact. Conclusion: the pre-roll is NOT a Kwai"
                + " fullscreen video request; Youku serves it from its own ad system.");
        H.skipped("youku_kwad_api", "diagnostic only, from dexdump of the Youku APK:"
                + " com.kwad.sdk.api.KsLoadManager declares loadFullScreenVideoAd / loadInterstitialAd"
                + " / loadSplashScreenAd / loadRewardVideoAd / loadFeedAd / loadBannerAd / loadDrawAd"
                + " / loadNativeAd, all (KsScene, Listener)V; concrete PUBLIC FINAL definitions are"
                + " com.kwad.components.core.b, com.kwad.sdk.api.b and"
                + " com.kwad.components.ad.fullscreen.a. Note com.kwad.sdk.commercial.g.a is"
                + " package-private, so getMethods() cannot see its i(String,String)/v/w/e/d."
                + " A control run (gate disabled, same episode) still played the pre-roll.");
    }

    /**
     * A-4: the app's own ad request, located by reading the shipped dex rather than guessing.
     *
     * <p>{@code dexdump} of the Youku APK's classes2.dex gives the real shape of the ad layer:
     *
     * <pre>
     *   com.youku.oneadsdk.request.AdRequestManager
     *     void d(int, com.youku.oneadsdk.request.builder.RequestInfo, j.d1.x3.b.g.f)V
     *     void c(com.youku.oneadsdk.request.AdRequestManager$RequestParams)V
     *
     *   j.d1.x3.b.g.f   (the ad callback)
     *     void onFailed(int, String)V
     *     void a(Object, Object, String)V
     * </pre>
     *
     * <p>An earlier note recorded this class as "only void methods" and stopped there, which was the
     * wrong conclusion - {@code d(int, RequestInfo, f)} is exactly a request entry point, and
     * {@code onFailed(int, String)} is precisely the call Youku makes itself when it suppresses an
     * ad slot. Both entry points were hooked to invoke that own failure branch instead of
     * requesting.
     *
     * <p>Result: it armed cleanly and fired - but only once, on the home feed. Neither overload
     * fires while a pre-roll plays, and the pre-roll played to completion with its countdown
     * intact both times (103s and 54s samples). So the pre-roll request bypasses
     * {@code AdRequestManager} entirely, the same way it bypassed the Kwai SDK.
     *
     * <p>Withdrawn rather than left installed. The mechanism is sound - the request really is
     * suppressed and replaced with Youku's own "slot blocked" branch - but no ad was ever observed
     * disappearing, so by the module's own proof-of-effect standard (banner absent <i>and</i> gate
     * hit) this is an unproven hook, and leaving it in would make {@code matched} overstate what is
     * actually known. The next attempt should look for the pre-roll's own request path rather than
     * for yet another overload of a manager that does not serve it.
     */
    private static void reportAdRequestGateWithdrawn() {
        H.skipped("youku_ad_request", "withdrawn on 11.2.13: unproven, not inert. Both real entry"
                + " points of com.youku.oneadsdk.request.AdRequestManager - d(int, RequestInfo,"
                + " j.d1.x3.b.g.f) and c(RequestParams) - were hooked to replace the request with"
                + " Youku's own onFailed(3) blocked-slot branch. The gate armed and fired once on"
                + " the home feed, but never fired during a pre-roll, and the pre-roll played to"
                + " completion with its countdown intact (103s, 54s samples). The pre-roll request"
                + " therefore bypasses AdRequestManager too. Kept off because no ad was observed"
                + " disappearing, so it cannot honestly be reported as blocking ads.");
        H.skipped("youku_ad_request_api", "diagnostic only, from dexdump of the Youku APK:"
                + " com.youku.oneadsdk.request.AdRequestManager declares d(int, RequestInfo,"
                + " j.d1.x3.b.g.f)V, c(RequestParams)V and b()AdRequestManager; the callback"
                + " j.d1.x3.b.g.f declares onFailed(int, String) and a(Object, Object, String);"
                + " RequestParams carries fields adType, callback, adResponse, clazz, content, id.");
    }

    private static void installAdSlotGate(MainHook module, ClassLoader loader) {
        final String rule = "youku_ad_slot_gate";
        Class<?> manager;
        try {
            manager = R.load(loader, Config.YK_AD_CONFIG_MANAGER);
        } catch (Throwable error) {
            H.miss(rule, Config.YK_AD_CONFIG_MANAGER + " not found: " + H.describe(error));
            return;
        }
        Method slot = R.find(manager, "u", boolean.class, int.class);
        if (slot == null) {
            H.miss(rule, "u(I)Z not found on " + manager.getName()
                    + "; boolean(): " + R.describeCandidates(manager, boolean.class));
            return;
        }
        try {
            module.hook(slot).setId("youku_ad_slot_gate").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) {
                    H.hit(rule, "ad slot disabled -> app's own onFailed(3,\"当前广告位屏蔽\") branch",
                            AD_SLOT_HIT);
                    return Boolean.FALSE;
                }
            });
            H.hooked(rule, Config.YK_AD_CONFIG_MANAGER + ".u(I)Z -> false (generic ad slots only)");
        } catch (Throwable error) {
            H.miss(rule, "hook failed: " + H.describe(error));
        }
    }

    // ------------------------------------------------------------------ B-2

    /**
     * A-2 pre-roll. The ad is real and reproducible on 11.2.13 while logged out (screenshot with a
     * live countdown, an 「广告」tag and 会员可关闭此广告), but every device-probed anchor for it
     * is either inert or not the decision:
     *
     * <ul>
     *   <li>{@code j.b1.w3.b.c.d.u(int)Z} is gone in this build (11.2.15 finding, still true here),
     *       and {@code AdRequestManager} exposes only void a/b/c/d - no decision method at all.</li>
     *   <li>The Orange config surface was read straight off the device and two switches were then
     *       tested by rewriting the cached files under
     *       {@code files/occ_configs/} and cold-starting the same episode:
     *       <ul>
     *         <li>{@code yk_adsdk_syscfg}: every ad type flipped {@code "enable":1 -> 0}.
     *             The pre-roll still played (105 s countdown, screenshot kept). That config governs
     *             ad-slot capabilities (shake, responsive modes), not whether a pre-roll is served.</li>
     *         <li>{@code one_ad_config}: {@code "enable_youku_ssp":"1" -> "0"}. The pre-roll still
     *             played (109 s countdown, screenshot kept), so the SSP toggle is not consulted for
     *             this slot either.</li>
     *       </ul>
     *       Both files were restored to their original values afterwards.</li>
     *   <li>{@code OrangeConfigImpl} declares only {@code a/3} and {@code j/2}; the 4-arg
     *       {@code a(String,String,String,String)} that the working pause-ad rule uses comes from a
     *       superclass. Neither {@code yk_adsdk_syscfg} nor {@code one_ad_config} was observed on
     *       any of those getters during playback, so they are read by the ad plugin directly.</li>
     * </ul>
     *
     * <p>Reported as withdrawn rather than {@code miss}: the rule is not failing to find an anchor,
     * it is established that this configuration surface does not control the pre-roll.
     */
    private static void reportPreRollAdWithdrawn() {
        H.skipped("youku_preroll_ad", "withdrawn on 11.2.13: the pre-roll does play while logged out"
                + " (verified on screen), but no device-probed anchor controls it -"
                + " j.b1.w3.b.c.d.u(int)Z is absent, AdRequestManager has only void methods,"
                + " and rewriting the cached Orange configs yk_adsdk_syscfg (*.enable) and"
                + " one_ad_config (enable_youku_ssp) to 0 left the ad on screen in both cases");
        H.skipped("youku_orange_api", "diagnostic only: OrangeConfigImpl declares a/3 and j/2;"
                + " the 4-arg a(String,String,String,String) used by youku_pause_ad is inherited");
    }

    /**
     * {@code j.b1.s.o0.g.c(boolean)} is the ONLY caller of
     * {@code HomeBottomNav.setVisibility(int)} and its body is
     * {@code setVisibility(z ? 0 : 8)}. Forcing the argument to false therefore makes the app
     * hide the bar through its own code path.
     *
     * <p>This is deliberately all-or-nothing: the bar is NOT trimmed item by item. The
     * per-item alternative is the shape that previously blanked the whole navigation
     * ({@code HomeBottomNav.java:528} divides by {@code e0.size()}), and {@code ConfigBean} has
     * no visibility field, so no per-tab hide exists in this build.
     *
     * <p>{@code chain.proceed(HIDE_BAR_ARGS)} passes the pre-allocated vector, so the intercept
     * itself allocates nothing.
     */
    private static void installBottomBarHide(MainHook module, ClassLoader loader) {
        final String rule = "youku_bottom_bar_hide";
        Class<?> nav;
        try {
            nav = R.load(loader, Config.YK_NAV_BAR_MANAGER);
        } catch (Throwable error) {
            H.miss(rule, Config.YK_NAV_BAR_MANAGER + " not found: " + H.describe(error));
            return;
        }
        Method hide = R.find(nav, "c", void.class, boolean.class);
        if (hide == null) {
            H.miss(rule, "c(Z)V not found on " + nav.getName()
                    + "; void(): " + R.describeCandidates(nav, void.class));
            return;
        }
        try {
            module.hook(hide).setId("youku_bottom_bar_hide").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    H.hit(rule, "bottom bar forced to setVisibility(8) via the app's own c(false)",
                            BOTTOM_BAR_HIT);
                    return chain.proceed(HIDE_BAR_ARGS);
                }
            });
            H.hooked(rule, Config.YK_NAV_BAR_MANAGER + ".c(Z) driven to false -> whole bar hidden");
        } catch (Throwable error) {
            H.miss(rule, "hook failed: " + H.describe(error));
        }
    }

    // ------------------------------------------------------------------ P-1

    /**
     * The pause-ad enable flag is the field {@code AdPauseFullScreenPlugin.r0}, assigned once in
     * {@code onStart()} from
     * {@code OrangeConfigImpl.a("ad_fullscreen_pause", "isEnable", "true")}.
     *
     * <p>Why the config read and not the field: libxposed API 102 as shipped here only exposes
     * {@code hook(Executable)} (see the API stub), so there is no field hook to install; and
     * hooking {@code onStart()} to return early would skip the WHOLE pause plugin, not just its
     * ads. Answering the app's own config read with {@code "false"} is the same value the app
     * itself would see if the server turned the placement off, so the plugin keeps running and
     * only its own {@code r0} gate closes.
     *
     * <p>Scope: the intercept only reacts to that exact group+key pair and otherwise proceeds
     * untouched. Cost: two {@code String.equals} on an init-time config read (45 call sites
     * app-wide, all lifecycle/init handlers - verified with the call-graph index), no
     * allocation, no reflection.
     */
    /**
     * A-0：关掉优酷广告 SDK 每个广告位的开关。
     *
     * <p>前贴广告此前五次尝试都失败，根因是锚点全在混淆类或 SDK 内部方法上。这次换了一个<b>不需要猜</b>的入口：
     * {@code com.youku.xadsdk.config.AdOrangeConfig} 没被混淆，而它每个广告位都有一个
     * {@code getXxxConfig()}，返回的正是运行时真正在用的开关对象。
     *
     * <p>关键教训：{@code com.youku.xadsdk.config.model.*ConfigInfo}（Banner/Mid/Pre…）看着完美，
     * 但对整个 dex 做引用分析后，它的 getter <b>没有任何消费者</b>，只是一份 JSON 模型，
     * 挂上去 14 个开关、播放前贴广告时 0 命中——典型的「挂了但根本没被调用」。
     * 真正的开关在 {@code j.f1.c8.h.m/p/r} 上（名字混淆、只有 pause/pre/splash 有布尔方法），
     * 而它们只能经由 AdOrangeConfig 拿���，所以这里不硬编码任何一个混淆名。
     *
     * <p>开关名在运行时按「无参 boolean」形状发现，将来优酷再加广告位也自动覆盖。
     */
    private static void installAdSwitchGate(MainHook module, ClassLoader loader) {
        final String rule = "youku_ad_switch";
        Class<?> orange;
        try {
            orange = R.load(loader, "com.youku.xadsdk.config.AdOrangeConfig");
        } catch (Throwable error) {
            H.miss(rule, "AdOrangeConfig not found: " + H.describe(error));
            return;
        }
        Method[] declared;
        try {
            declared = orange.getDeclaredMethods();
        } catch (Throwable error) {
            H.miss(rule, "cannot enumerate AdOrangeConfig: " + H.describe(error));
            return;
        }
        int getters = 0;
        for (Method getter : declared) {
            if (getter.getParameterTypes().length != 0) continue;
            String name = getter.getName();
            if (!name.startsWith("get") || !name.endsWith("Config")) continue;
            getters++;
            final String label = name;
            try {
                module.hook(getter).setId("youku_adcfg_" + label).intercept(new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        Object config = chain.proceed();
                        if (config != null) armConfigSwitches(module, config, label);
                        return config;
                    }
                });
            } catch (Throwable ignored) {
                // One unreadable accessor must not hide the rest.
            }
        }
        if (getters == 0) {
            H.miss(rule, "no getXxxConfig() accessor on AdOrangeConfig");
            return;
        }
        H.hooked(rule, getters + " getXxxConfig accessors observed; every no-arg boolean switch on the"
                + " returned config -> false");
    }

    /** 在一个配置对象上把所有无参 boolean 方法置 false，每个方法只挂一次。 */
    private static void armConfigSwitches(MainHook module, Object config, String label) {
        final String rule = "youku_ad_switch";
        Class<?> owner = config.getClass();
        Method[] declared;
        try {
            declared = owner.getDeclaredMethods();
        } catch (Throwable ignored) {
            return;
        }
        for (Method switchMethod : declared) {
            if (switchMethod.getParameterTypes().length != 0) continue;
            if (switchMethod.getReturnType() != boolean.class) continue;
            if (java.lang.reflect.Modifier.isAbstract(switchMethod.getModifiers())) continue;
            final String key = owner.getName() + "#" + switchMethod.getName();
            if (AD_SWITCH_ARMED.putIfAbsent(key, Boolean.TRUE) != null) continue;
            final String where = label + "." + switchMethod.getName() + "()";
            try {
                switchMethod.setAccessible(true);
                module.hook(switchMethod).setId("youku_adswitch_" + where).intercept(
                        new XposedInterface.Hooker() {
                            @Override public Object intercept(XposedInterface.Chain chain) {
                                H.hit(rule, where + " -> false", AD_SWITCH_HIT);
                                return Boolean.FALSE;
                            }
                        });
            } catch (Throwable ignored) {
                // Not armable on this build; the other switches still apply.
            }
        }
    }

    /**
     * A-2：拦掉走事件总线的前贴广告事件。
     *
     * <p>前六次都拦在「SDK 发请求 / 读配置」这一层，而截图证明前贴素材是在进播放页<b>之前</b>就已经
     * 预取好的，所以这一层天然拦不到。事件总线是另一个层��，而且是应用自己的一套机制：
     * {@code com.youku.kubus.Event} / {@code EventBus} <b>没有混淆</b>，
     * {@code PlayerCorePlugin.skipPreAd(Event)} 就是处理总线事件的——也就是说「跳过前贴」这个动作
     * 本来就是往总线上丢一个事件，我们要做的是在投递前把它拦下来。
     *
     * <p>事件名是一个 String 字段（构造函数就是 {@code Event(String)}），字段名会变，
     * 所以按<b>类型</b>取 String 字段再读值；命中 {@code PreAd} / {@code PREAD} / {@code pre_ad}
     * 就不往下投递，并打一条命中行。
     *
     * <p>另外对任何名字里带 {@code ad} 的事件<b>按去重</b>打一条 probe 日志：
     * 即便这次拦漏了，也能从日志里看出总线上到底流过哪些广告事件名，下一轮不用再猜。
     */
    private static void installPreRollEventGate(MainHook module, ClassLoader loader) {
        final String rule = "youku_preroll_event";
        Class<?> bus;
        Class<?> event;
        try {
            bus = R.load(loader, "com.youku.kubus.EventBus");
            event = R.load(loader, "com.youku.kubus.Event");
        } catch (Throwable error) {
            H.miss(rule, "Kubus event bus not found: " + H.describe(error));
            return;
        }
        final java.lang.reflect.Field[] names;
        try {
            java.util.List<java.lang.reflect.Field> found =
                    new ArrayList<java.lang.reflect.Field>();
            for (java.lang.reflect.Field field : event.getDeclaredFields()) {
                if (field.getType() != String.class) continue;
                field.setAccessible(true);
                found.add(field);
            }
            names = found.toArray(new java.lang.reflect.Field[0]);
        } catch (Throwable error) {
            H.miss(rule, "cannot read Event fields: " + H.describe(error));
            return;
        }
        if (names.length == 0) {
            H.miss(rule, "Event exposes no String field to read the event type from");
            return;
        }
        // post(Event), post(Event,Object), postSticky(Event) - the three ways a plugin
        // hands work to the player. Missing any one of them would leave a delivery path open.
        int armed = 0;
        for (Method method : bus.getDeclaredMethods()) {
            if (!"post".equals(method.getName()) && !"postSticky".equals(method.getName())) continue;
            final Class<?>[] params = method.getParameterTypes();
            if (params.length == 0 || params[0] != event) continue;
            final String label = method.getName() + "/" + params.length + "arg";
            try {
                module.hook(method).setId("youku_preroll_" + label).intercept(new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        Object posted = chain.getArg(0);
                        String type = eventType(names, posted);
                        if (type == null) return chain.proceed();
                        if (isPreRoll(type)) {
                            H.hit(rule, "dropped Kubus event \"" + type + "\"", PRE_ROLL_EVENT_HIT);
                            return null;
                        }
                        if (EVENT_NAMES_SEEN.putIfAbsent(type, Boolean.TRUE) == null) {
                            H.hit("youku_event_probe", "Kubus event \"" + type + "\"", EVENT_PROBE);
                        }
                        return chain.proceed();
                    }
                });
                armed++;
            } catch (Throwable ignored) {
                // One missing overload must not leave the others unarmed.
            }
        }
        if (armed == 0) {
            H.miss(rule, "no EventBus.post/postSticky(Event) overload resolved");
            return;
        }
        H.hooked(rule, armed + " post overloads gated; Event type read from " + names.length
                + " String field(s)");
    }

    /** 事件类型：读 Event 上所有 String 字段，取第一个非空值。 */
    private static String eventType(java.lang.reflect.Field[] names, Object event) {
        if (event == null) return null;
        for (java.lang.reflect.Field field : names) {
            try {
                Object value = field.get(event);
                if (value instanceof String && ((String) value).length() > 0) return (String) value;
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 事件名是否属于前贴广告。
     *
     * <p>这里必须精确，否则会误杀正常事件：朴素的 {@code contains("pread")} 会命中
     * {@code threadPrepare}（小写后是 t-h-r-ead-prepare，里面就含 "pread"），
     * 把播放器准备事件丢掉，界面会直接黑屏。真正的踩坑是<b>看不见的那种</b>——
     * 拦多了不报错，只会表现为「界面坏了」，比不拦更难查。
     *
     * <p>所以规则是：去掉事件名可能的前缀 on/send/post，然后<b>整串</b>匹配
     * {@code pre_?ad + 已知后缀}。宁可漏拦（探针会把真实事件名打出来，下一轮补），
     * 也不能误拦（会破坏播放流程）。
     */
    private static boolean isPreRoll(String type) {
        String lower = type.toLowerCase();
        if (lower.startsWith("on")) lower = lower.substring(2);
        else if (lower.startsWith("send")) lower = lower.substring(4);
        else if (lower.startsWith("post")) lower = lower.substring(4);
        return PRE_ROLL.matcher(lower).matches();
    }

    /**
     * A-3：前贴控制点的<b>只读</b>探针。
     *
     * <p>为什么需要它：截图抓不到视频 Surface（播放器停在画中画时主窗口就是全黑），
     * 所以「有没有前贴」不能靠肉眼判断，只能靠 logcat 里的 HLS 播放状态
     * （{@code DOWNLOADER_LOG ... playback state updated, source:pulse, buffer_in_ms:N}）。
     * 而前七次尝试都是「猜一个锚点，挂上，看有没有命中」——猜错了连方向都看不出来。
     *
     * <p>所以这里反向做：把 11.2.15 里<b>所有未混淆的前贴控制点</b>一次性挂上只读探针，
     * 每个方法被调用就打一行。这样一次播放就能回答「前贴的控制链到底经过哪几个方法」，
     * 下一轮直接照着命中名单挂闸门，不必再猜。
     *
     * <p>全部只读（{@code chain.proceed()} 原样放行），不影响任何行为。
     */
    private static void installPreRollProbe(MainHook module, ClassLoader loader) {
        String[][] probes = {
            // class, method, params-count
            {"com.youku.alixplayer.system.AndroidPlayer", "initPreAdDuration", "0"},
            {"com.youku.alixplayer.system.AndroidPlayer", "getAdCountDown", "0"},
            {"com.youku.player.plugins.playercore.PlayerCorePlugin", "skipPreAd", "1"},
            {"com.youku.player2.live.LivePlayerView", "onPreAdStart", "1"},
            {"com.youku.player2.live.LivePlayerView", "onPreAdEnd", "1"},
            {"com.youku.player.plugins.multiscreen.MultiScreenPlugin", "isFocusPreAd", "0"},
            // The XAdSDK ad video view. The five points above are pre-roll-specific and
            // were never reached during playback; this one sits on the path of ANY ad
            // video, whatever its slot, so it answers the prior question directly -
            // "was an ad served at all, and of which type". setAdType's int value is
            // the slot id, which is the first hard number for the pre-roll slot.
            {"com.youku.xadsdk.ui.component.AdVideoView", "setAdType", "1"},
            {"com.youku.xadsdk.ui.component.AdVideoView", "setVideoSource", "1"},
            {"com.youku.xadsdk.ui.component.AdVideoView", "onPrepared", "0"},
            {"com.youku.xadsdk.ui.component.AdVideoView", "onStart", "0"},
            {"com.youku.xadsdk.ui.component.AdVideoView", "onComplete", "0"},
            {"com.youku.xadsdk.ui.component.AdVideoView", "setOpVideoInfo", "1"},
        };
        int armed = 0;
        StringBuilder seen = new StringBuilder();
        // Swallowing a load failure looks identical to "the method was never called",
        // and those two mean opposite things. Record why a probe did not arm.
        StringBuilder notArmed = new StringBuilder();
        for (String[] probe : probes) {
            Class<?> owner;
            try {
                owner = R.load(loader, probe[0]);
            } catch (Throwable missing) {
                if (notArmed.length() > 0) notArmed.append("; ");
                notArmed.append(probe[1]).append(": ").append(missing);
                continue;
            }
            for (Method method : owner.getDeclaredMethods()) {
                if (!method.getName().equals(probe[1])) continue;
                if (method.getParameterTypes().length != Integer.parseInt(probe[2])) continue;
                if (java.lang.reflect.Modifier.isAbstract(method.getModifiers())) break;
                final String where = owner.getSimpleName() + "." + method.getName();
                try {
                    module.hook(method).setId("youku_preroll_probe_" + where).intercept(
                            new XposedInterface.Hooker() {
                                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                    Object a0 = null;
                                    try {
                                        a0 = chain.getArg(0);
                                    } catch (Throwable noArgs) {
                                        // no-arg probe methods have nothing to read
                                    }
                                    StringBuilder tail = new StringBuilder();
                                    if (a0 instanceof Integer || a0 instanceof String) {
                                        // setAdType(int) and setVideoSource(String) are the two
                                        // calls that carry the ad's identity; without their values
                                        // a hit only says "something happened", which is not enough
                                        // to tell a pre-roll from a mid-roll.
                                        tail.append(" arg0=").append(a0);
                                    }
                                    H.hit("youku_preroll_probe", where + "() reached" + tail,
                                            probeFlag(where));
                                    return chain.proceed();
                                }
                            });
                    armed++;
                    if (seen.length() > 0) seen.append(", ");
                    seen.append(where);
                    break;
                } catch (Throwable hookFailed) {
                    if (notArmed.length() > 0) notArmed.append("; ");
                    notArmed.append(where).append(": ").append(hookFailed);
                }
            }
        }
        if (armed == 0) {
            H.skipped("youku_preroll_probe", "no pre-roll control point resolved on 11.2.15");
            return;
        }
        H.hooked("youku_preroll_probe", armed + " read-only pre-roll control points: " + seen
                + (notArmed.length() == 0 ? "" : " | not armed: " + notArmed));
    }

    /** 前贴控制点探针：每个探针方法各打一次。H.hit 的 once 是按对象去重的，
     *  六个探针共用一个标记只会记下第一个，必须按方法名各持一个。 */
    private static AtomicBoolean probeFlag(String where) {
        AtomicBoolean flag = PROBE_FLAGS.get(where);
        if (flag != null) return flag;
        AtomicBoolean created = new AtomicBoolean(false);
        AtomicBoolean existing = PROBE_FLAGS.putIfAbsent(where, created);
        return existing != null ? existing : created;
    }

    private static void installPauseAdGate(MainHook module, ClassLoader loader) {
        final String rule = "youku_pause_ad";
        Class<?> orange;
        try {
            orange = R.load(loader, Config.YK_ORANGE_CONFIG);
        } catch (Throwable error) {
            H.miss(rule, Config.YK_ORANGE_CONFIG + " not found: " + H.describe(error));
            return;
        }
        Method getConfig = R.find(orange, "a", String.class, String.class, String.class, String.class);
        if (getConfig == null) {
            H.miss(rule, "OrangeConfigImpl.a(String,String,String)String not found; String(...): "
                    + R.describeShapes(orange, String.class));
            return;
        }
        try {
            module.hook(getConfig).setId("youku_pause_ad_gate").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object group = chain.getArg(0);
                    Object key = chain.getArg(1);
                    if (Config.YK_PAUSE_AD_GROUP.equals(group) && Config.YK_PAUSE_AD_KEY.equals(key)) {
                        H.hit(rule, "ad_fullscreen_pause.isEnable -> \"false\" (r0 stays false)",
                                PAUSE_AD_HIT);
                        return PAUSE_AD_DISABLED[0];
                    }
                    return chain.proceed();
                }
            });
            H.hooked(rule, "Orange " + Config.YK_PAUSE_AD_GROUP + "." + Config.YK_PAUSE_AD_KEY
                    + " -> \"false\"");
        } catch (Throwable error) {
            H.miss(rule, "hook failed: " + H.describe(error));
        }
    }

    // ------------------------------------------------------------------ T-1

    /**
     * T-1 ({@code j.b1.j7.b.p(Channel)Z} / {@code e(Channel)Z}, the app-native per-channel
     * display filter) is deliberately NOT armed and is reported as a miss, because arming it
     * safely is not possible from this module:
     *
     * <ul>
     *   <li>{@code p}/{@code e} carry their OWN guards inside the original body - teen mode
     *       ({@code j.b1.g3.a.z.b.t()}) and {@code channel.isSelection}. Returning a blanket
     *       {@code true} replaces the whole method and loses them, so the currently selected or
     *       teen-mode channel can be dropped.</li>
     *   <li>Returning true only for chosen channels needs a name -&gt; {@code nodeKey} mapping,
     *       and building one requires reading {@code Channel} fields inside the intercept, which
     *       would mean reflection and allocation on the call path.</li>
     * </ul>
     *
     * <p>The anchors are still probed, so the miss line states whether the report's evidence is
     * still present in this build or has drifted.
     */
    private static void reportChannelFilterSkipped(ClassLoader loader) {
        final String rule = "youku_channel_filter";
        String evidence;
        try {
            Class<?> filters = R.load(loader, Config.YK_CHANNEL_FILTER);
            Class<?> channel = R.load(loader, Config.YK_CHANNEL);
            Method keep = R.findByShape(filters, new String[]{"p"}, boolean.class, channel);
            Method show = R.findByShape(filters, new String[]{"e"}, boolean.class, channel);
            evidence = "anchors still present (p=" + (keep != null) + ", e=" + (show != null) + ")";
        } catch (Throwable error) {
            evidence = "anchor probe failed (" + H.describe(error) + ")";
        }
        H.skipped(rule, "not armed by design: " + evidence + ", but p/e hold the teen-mode and"
                + " isSelection guards inside their own bodies and 11.2.1 has no name->nodeKey"
                + " map to target single channels, so any blanket return would drop the selected"
                + " tab; skipped instead of guessing (see scratch-recon/youku-gates.md 5.1)");
    }

    /**
     * Suppresses the CSJ (穿山甲) DSP ad-network switch through Youku's OWN config branch.
     * Non-obfuscated class + app-native boolean + suppressive return = the durable pattern that
     * produced the two verified Youku hits. Worst case is "that network still serves", never a
     * broken UI.
     */
    private static void installCsjDspGate(MainHook module, ClassLoader loader) {
        final String rule = "youku_csj_dsp_off";
        Class<?> config;
        Method enabled;
        try {
            config = R.load(loader, "com.youku.xadsdk.config.model.SplashConfigInfo");
            enabled = R.find(config, "isEnableCsjDsp", boolean.class);
        } catch (Throwable error) {
            H.miss(rule, "SplashConfigInfo/isEnableCsjDsp not resolvable: " + H.describe(error));
            return;
        }
        if (enabled == null) {
            H.miss(rule, "isEnableCsjDsp()Z not found; boolean(): " + R.describeMethods(config));
            return;
        }
        final AtomicBoolean once = new AtomicBoolean(false);
        try {
            module.hook(enabled).setId("youku_csj_dsp_off").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) {
                    H.hit(rule, "CSJ/穿山甲 DSP disabled via the app's own config switch", once);
                    return Boolean.FALSE;
                }
            });
            H.hooked(rule, "SplashConfigInfo.isEnableCsjDsp()Z -> false");
        } catch (Throwable error) {
            H.miss(rule, "hook failed: " + H.describe(error));
        }
    }
}
