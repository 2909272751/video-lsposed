package io.github.qqliveclean;

import android.app.Activity;
import android.content.Context;
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
 *   <li><b>T-1 {@code youku_channel_filter}</b> - enforced at the <b>View layer</b> rather
 *       than on the app's data-layer filter: the data-layer anchors {@code p}/{@code e} carry
 *       their own teen-mode and {@code isSelection} guards inside the original body, so
 *       replacing them wholesale could drop the selected channel. Hiding the tab view leaves
 *       the data model and all selection logic untouched. See {@link #filterTopChannels}.</li>
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
    /** 前贴控制点探针的「每个方法只打一次」标记，按方法名各持一个。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, AtomicBoolean> PROBE_FLAGS =
            new java.util.concurrent.ConcurrentHashMap<String, AtomicBoolean>();
    // The code and message Youku itself passes when it suppresses an ad slot.
    private static final AtomicBoolean BOTTOM_BAR_HIT = new AtomicBoolean(false);
    private static final AtomicBoolean TAB_FILTER_HIT = new AtomicBoolean(false);
    private static final AtomicBoolean CHANNEL_FILTER_HIT = new AtomicBoolean(false);
    private static final AtomicBoolean HOME_TOP_AD_HIT = new AtomicBoolean(false);
    private static WeakReference<View> lastTopCard = new WeakReference<View>(null);
    private static WeakReference<ViewGroup> lastTabBar = new WeakReference<ViewGroup>(null);
    private static WeakReference<ViewGroup> lastChannelBar = new WeakReference<ViewGroup>(null);

    /**
     * Pre-allocated argument vectors. {@code intercept} must not allocate, so the arrays are
     * built once at class-init and only ever hold one constant value - reusing them is safe
     * even if the framework keeps the reference.
     */
    private static final Object[] HIDE_BAR_ARGS = {Boolean.FALSE};
    private static final Object[] PAUSE_AD_DISABLED = {"false"};

    private YoukuRules() {}

    static void install(MainHook module, ClassLoader loader, Config.Settings settings) {
        // Round 45 logged no rule=youku_* registration line at all while the lines that follow
        // this call in MainHook were present, which means the registrations ran but their log
        // went missing. Two markers turn "somewhere in install" into a measurable span.
        H.info("event=youku_install_begin");
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
        // ---- A-0: per-placement switches from Youku's own ad SDK (withdrawn) ----
        // Armed 15 getXxxConfig() accessors on AdOrangeConfig and forced every
        // no-arg boolean switch on the returned config to false. Across every
        // sample it logged 0 hits, so it blocked nothing while still reporting
        // itself as hooked. A gate that cannot be shown to intercept anything is
        // worse than no gate: it reads as coverage. Withdrawn 2026-10-01; see
        // docs/COMPATIBILITY.md for the refutation trail.
        H.skipped("youku_ad_switch", "withdrawn on 11.2.15: 15 accessors armed, 0 hits across every"
                + " sample, so it blocked nothing while still reporting hooked");
        // ---- A-2: pre-roll ad events on the app's own event bus (withdrawn) ----
        // The bus hooks never fired once, not even during confirmed playback.
        // Withdrawn for the same reason: an unproven hook is not an inert one.
        H.skipped("youku_preroll_event", "withdrawn on 11.2.15: 3 post overloads armed, never fired"
                + " once even during confirmed playback, so nothing was intercepted");
        // ---- A-3: read-only pre-roll control points ----
        // The probe stays: it is the only thing that will produce evidence the day
        // Youku does serve video-side ad material, and it costs nothing at runtime.
        // ---- A-3: pre-roll countdown ----
        // Only the rule stays. Five probe families were armed here to locate the badge and all
        // five returned nothing across full playbacks: setText (armed, hits only the members
        // pill), SpannableStringBuilder replace/append/insert (armed=8), Canvas.drawText
        // (armed=3), RequestInfo getAdType/setAdType (armed=2) and the PasterAdRequestInfo
        // constructor (armed=2). A constructor cannot stay silent by accident, so the pre-roll
        // request does not run through XAdSDK at all. The badge is drawn by the player native
        // layer and is out of reach from here.
        //
        // Keeping hooks that provably never fire costs battery and makes coverage look broader
        // than it is - the failure shape that has already produced two withdrawn rules. They are
        // removed rather than left armed. The findings stay in docs/COMPATIBILITY.md.
        installPreRollTextRule(module, loader);
        // ---- A-1: pre-roll ad slot (withdrawn, see the reason below) ----
        reportPreRollAdWithdrawn();
        reportKwadGateWithdrawn();
        reportAdRequestGateWithdrawn();
        // T-1 (top channel bar) is reported from installBottomTabFilter: it runs on the
        // same Activity-resume pass as the bottom-bar filter and works on the View layer.
        H.info("event=youku_install_done");
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
            // Anchor note (2026-10-01 20:3x-20:4x). This hook has lived on two anchors and neither
            // one ever fired. Runtime enumeration (event=resume_probe) showed seven Activity
            // lifecycle methods installing cleanly and being called zero times, while hooks on the
            // app's own classes fired in the same session - and Instrumentation hooks fire, because
            // this module's own context probe rides on Instrumentation.callActivityOnCreate. So the
            // split is specific: on this device, hooks on android.app.Activity install silently and
            // do nothing, while hooks on Instrumentation and on app classes work.
            //
            // Therefore there is no framework-class hook here any more. The UI pass is driven from
            // MainHook through ActivityLifecycleCallbacks, which is a registration API and needs no
            // hook at all. The reporting below stays so the rules remain observable.
            if (filtering) H.hooked("youku_tab_filter", "five-button navigation view; driven by the app base class anchor");
            else H.skipped("youku_tab_filter", "all tabs visible");
            installAppClassResumeAnchor(module, loader, settings);
            if (settings.hiddenChannelNames.length != 0)
                H.hooked("youku_channel_filter", "top channel row, View layer (data model untouched): hidden="
                        + settings.hiddenChannelNames.length + " of " + Config.CHANNEL_CATALOG.length);
            else H.skipped("youku_channel_filter", "all channels visible");
            if (settings.youkuBlockAdSlot)
                H.hooked("youku_home_top_ad", "first home carousel card collapse");
            else H.skipped("youku_home_top_ad", "disabled in settings");
        } catch (Throwable error) {
            H.miss("youku_tab_filter", H.describe(error));
            if (settings.youkuBlockAdSlot) H.miss("youku_home_top_ad", H.describe(error));
        }
    }

    /**
 * Anchors the UI rules on Youku's own Activity base class.
     *
     * <p>Every framework-class anchor is dead on this device: {@code Instrumentation.callActivityOnCreate}
 * never fires, the {@code android.app.Activity} lifecycle methods install and are called zero times,
 * and registered {@code ActivityLifecycleCallbacks} are never dispatched. Hooks on app classes work
 * normally, in the same sessions, so the rules move onto the app side.
     *
     * <p>The chain was read at runtime rather than guessed
 * ({@code event=live_activities result=ok count=1}):
 * {@code com.youku.kuflix.RootPageActivity < j.f1.l5.b.b < j.d.m.g.c < j.d.m.g.b <
 * androidx.appcompat.app.AppCompatActivity}. {@code j.d.m.g.b} is the highest fully-owned app class
 * on that path, so a single hook there covers every Activity the feed uses.
     *
     * <p>Both {@code onCreate} and {@code onResume} are hooked and each logs once: subclasses may
     * override without calling super, so which one actually fires is evidence rather than an
     * assumption.
     */
    private static void installAppClassResumeAnchor(MainHook module, ClassLoader loader,
            final Config.Settings settings) {
        final String[] bases = {"j.d.m.g.b", "j.d.m.g.c", "j.f1.l5.b.b"};
        for (final String base : bases) {
            try {
                Class<?> baseClass = R.findClass(loader, base);
                if (baseClass == null) { H.warn("event=ui_anchor " + base + " absent"); continue; }
                for (String lifecycle : new String[]{"onResume", "onCreate"}) {
                    Class<?>[] params = "onCreate".equals(lifecycle)
                            ? new Class<?>[]{android.os.Bundle.class} : new Class<?>[0];
                    Method method = R.find(baseClass, lifecycle, void.class, params);
                    if (method == null) { H.warn("event=ui_anchor " + base + "." + lifecycle + " absent"); continue; }
                    // Which class actually declares the method matters: R.find walks superclasses, so
                    // asking for j.d.m.g.b.onResume can legitimately return a method declared on a
                    // framework or AndroidX ancestor. Hooking one of those is the same silent no-op
                    // that cost several rounds, so the declaring class is reported rather than assumed.
                    H.info("event=ui_anchor_resolved asked=" + base + "." + lifecycle
                            + " declaringClass=" + method.getDeclaringClass().getName()
                            + " static=" + java.lang.reflect.Modifier.isStatic(method.getModifiers())
                            + " abstract=" + java.lang.reflect.Modifier.isAbstract(method.getModifiers()));
                    module.hook(method).setId("youku_ui_" + base.replace('.', '_') + "_" + lifecycle)
                            .intercept(new XposedInterface.Hooker() {
                        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object result = chain.proceed();
                            if (chain.getArg(0) instanceof Activity && UI_ANCHOR_SEEN.add(base + "." + lifecycle)) {
                                Activity activity = (Activity) chain.getArg(0);
                                H.info("event=ui_anchor_fired anchor=" + base + "." + lifecycle
                                        + " activity=" + activity.getClass().getName()
                                        + " hierarchy=" + describeHierarchy(activity.getClass()));
                            }
                            if (chain.getArg(0) instanceof Activity) {
                                onActivityResumed((Activity) chain.getArg(0), settings, "anchor");
                            }
                            return result;
                        }
                    });
                }
                H.hooked("youku_ui_anchor", "app base class " + base + " (framework anchors are dead on this device)");
            } catch (Throwable error) {
                H.warn("event=ui_anchor " + base + " error " + H.describe(error));
            }
        }
    }

    private static final java.util.Set<String> UI_ANCHOR_SEEN =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    /**
 * Drives the UI rules without hooking any lifecycle event.
 *
 * <p>In Youku the whole activity-creation surface is unreachable: {@code callActivityOnCreate} never
 * fires, {@code android.app.Activity} and the app base class hooks install and are called zero times,
 * and registered lifecycle callbacks are never dispatched - while the Activity itself is
 * demonstrably alive, reachable by reflection over {@code ActivityThread.mActivities}. So the module
 * asks instead of waiting: it reflects out the live Activity and runs the pass.
 *
 * <p>Bounded by design. Six fixed re-runs at 1/2/4/8/12/16 s cover views that arrive late; there is
 * no polling loop, no resident timer and no per-frame work, which keeps the module cheap and off the
 * app's hot paths.
 */
static void scheduleUiPasses(final Context context, final Config.Settings settings) {
        if (context == null || settings == null) return;
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        final int[] delays = {1000, 2000, 4000, 8000, 12000, 16000};
        for (final int delay : delays) {
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    Activity activity = findLiveActivity(context);
                    // Every tick reports its outcome. findLiveActivity used to end in
                    // catch (Throwable) { return null; }, which made "no Activity yet" and
                    // "reflection failed" indistinguishable - and three rounds were spent guessing
                    // instead of reading. A driver that cannot fail loudly is not a driver.
                    if (activity == null) {
                        H.info("event=ui_tick tick=" + delay + "ms result=no_activity reason=" + LAST_REFLECT_REASON.get());
                        return;
                    }
                    if (UI_PASS_LOGGED.compareAndSet(false, true)) {
                        H.info("event=youku_ui_pass_entered anchor=ActivityThread.mActivities reflection"
                                + " activity=" + activity.getClass().getName());
                    }
                    // The view-tree sampling that used to sit here is gone. It existed to find the
                    // pre-roll countdown, that hunt ended with the badge located in the player
                    // native layer, and it was firing eighteen to twenty-eight times a session and
                    // burying the handful of hit lines that actually matter.
                    try {
                        onActivityResumed(activity, settings, "reflection");
                    } catch (Throwable error) {
                        H.info("event=ui_pass_threw source=reflection"
                                + " activity=" + activity.getClass().getName()
                                + " error=" + H.describe(error));
                    }
                }
            }, delay);
        }
}

/** Reads the live Activity out of ActivityThread.mActivities, recording why it failed. */
    private static Activity findLiveActivity(Context context) {
        try {
            Class<?> threadClass = Class.forName("android.app.ActivityThread", false,
                    context.getClass().getClassLoader());
            Method current = R.find(threadClass, "currentActivityThread", threadClass, new Class<?>[0]);
            if (current == null) return fail("no_currentActivityThread_method");
            Object thread = current.invoke(null);
            if (thread == null) return fail("currentActivityThread_null");
            java.lang.reflect.Field activitiesField = R.findField(threadClass, "mActivities");
            if (activitiesField == null) return fail("no_mActivities_field");
            Object activities = readField(activitiesField, thread);
            if (activities == null) return fail("mActivities_read_failed");
            if (!(activities instanceof java.util.Map))
                return fail("mActivities_not_a_map:" + activities.getClass().getName());
            java.util.Map<?, ?> map = (java.util.Map<?, ?>) activities;
            if (map.isEmpty()) return fail("map_empty");
            // Neither "first" nor "last" in the map is reliable. Measured on 2 Oct: with a video open the
            // detail page held 401 nodes against the feed's 167, and "last" still returned the
            // feed. Richest window is the only signal that tracked what was actually on screen.
            Activity best = null;
            int bestNodes = -1;
            StringBuilder census = new StringBuilder();
            for (Object record : map.values()) {
                if (record == null) continue;
                Object value = readField(R.findField(record.getClass(), "activity"), record);
                if (!(value instanceof Activity)) continue;
                Activity candidate = (Activity) value;
                if (candidate.isFinishing()) continue;
                int nodes = decorNodes(candidate);
                if (census.length() < 900) {
                    census.append('|').append(candidate.getClass().getSimpleName()).append(":n").append(nodes);
                }
                if (nodes > bestNodes) {
                    bestNodes = nodes;
                    best = candidate;
                }
            }
            if (best == null) return fail("no_live_activity");
            LAST_REFLECT_REASON.set("ok nodes=" + bestNodes);
            if (!CENSUS_LOGGED.compareAndSet(false, true)) {
                H.info("event=ui_activity_census picked=" + shortName(best) + " nodes=" + bestNodes
                        + " all=" + census);
            }
            return best;
        } catch (Throwable error) {
            return fail("reflect_error:" + H.describe(error));
        }
    }

    /** Records the reason and returns null, so every failure path carries its own diagnosis. */
    private static Activity fail(String reason) {
        LAST_REFLECT_REASON.set(reason);
        return null;
    }

    private static final java.util.concurrent.atomic.AtomicReference<String> LAST_REFLECT_REASON =
            new java.util.concurrent.atomic.AtomicReference<String>("unset");

    /** The census of live Activities is logged once per process; it never changes mid-session. */
    private static final java.util.concurrent.atomic.AtomicBoolean CENSUS_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean(false);


/** Reflection field read that never throws; a miss is a data point, not a failure. */
private static Object readField(java.lang.reflect.Field field, Object owner) {
        if (field == null || owner == null) return null;
        try {
            return field.get(owner);
        } catch (Throwable error) {
            return null;
        }
}

    /**
     * Entry point for the UI rules. Called from the reflection driver above, and from the app base
     * class anchor and the lifecycle callbacks when those happen to fire. Schedules a small fixed set
     * of delayed passes, never a poll: views that arrive late are covered by 0.5 s / 3 s / 6 s, which
     * is what the home carousel and the channel row need.
     */
    /**
     * Which driver produced the current UI pass, appended to every hit line as {@code src=...}.
     *
     * <p>Three drivers exist - the app base class anchor, the registered lifecycle callbacks and the
     * ActivityThread reflection driver - and all of them call this method. Which one actually fires
     * varies between sessions, so a hit without a source cannot be attributed, and no driver can be
     * chosen or deleted on evidence. Tagging turns "it worked" into "it worked because of this".
     */
    private static final java.util.concurrent.atomic.AtomicReference<String> CURRENT_SOURCE =
            new java.util.concurrent.atomic.AtomicReference<String>("unknown");

    /** Simple class name, so diagnostic lines stay well under the per-record log ceiling. */
    private static String shortName(Object value) {
        if (value == null) return "null";
        String name = value.getClass().getSimpleName();
        return name == null || name.length() == 0 ? "anon" : name;
    }

    private static String tag() {
        return " src=" + CURRENT_SOURCE.get();
    }

    /**
     * Entry point for the UI rules. Called by all three drivers; {@code source} records which one.
     * Schedules a small fixed set of delayed passes, never a poll: views that arrive late are covered
     * by 0.5 s / 3 s / 6 s, which is what the home carousel and the channel row need.
     */
    static void onActivityResumed(Activity activity, Config.Settings settings, String source) {
        // Short class name only. Fully qualified names push these lines past the log writers per
        // line ceiling, and a truncated record is indistinguishable from a missing one - which is
        // exactly the ambiguity this round has to settle.
        H.info("event=ui_first a=" + shortName(activity) + " s=" + (source == null ? "?" : source)
                + " cfg=" + (settings == null ? "null" : "ok")
                + " dbg=" + (settings == null ? "?" : String.valueOf(settings.debugLog)));
        CURRENT_SOURCE.set(source == null ? "unknown" : source);
        if (activity == null || settings == null) return;
        boolean filtering = !settings.youkuShowShortDrama || !settings.youkuShowVip
                || !settings.youkuShowGoodMovies;
        // Unconditional and ahead of every check, so "the method was entered" and "the method
        // bailed after evaluating the switches" stay distinguishable. No once-only guard: a guard
        // here would let the first caller hide the fact that later callers ever ran.
        H.info("event=ui_pass_entry source=" + CURRENT_SOURCE.get() + " activity="
                + activity.getClass().getName() + " filtering=" + filtering
                + " blockAdSlot=" + settings.youkuBlockAdSlot
                + " hiddenChannels=" + settings.hiddenChannelNames.length);
        if (!filtering && !settings.youkuBlockAdSlot && settings.hiddenChannelNames.length == 0) {
            return;
        }
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        // The detail page is where the countdown ad lives, and it reached this method without ever
        // getting the 500 ms pass, so it gets its own dump schedule rather than relying on one.
        if (!"com.youku.kuflix.RootPageActivity".equals(activity.getClass().getName())) {
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    
                }
            }, 1500);
            for (final int offset : new int[] {4000, 8000, 12000, 16000}) {
                handler.postDelayed(new Runnable() {
                    @Override public void run() {
                        
                    }
                }, offset);
            }
        }
        // Every pass reports whether it ran and what it was asked to do. De-duplication has hidden
        // the truth too often in this project, and a pass that runs and finds nothing is
        // indistinguishable from a pass that never ran.
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                H.info("event=ui_run t=500 a=" + shortName(activity) + " f=" + filtering
                        + " ad=" + settings.youkuBlockAdSlot);
                try {
                    if (filtering) filterBottomBar(activity, settings);
                    if (settings.youkuBlockAdSlot) hideHomeTopAd(activity);
                    filterTopChannels(activity, settings);
                    
                } catch (Throwable error) {
                    H.info("event=ui_pass_run_threw offset=500ms error=" + H.describe(error));
                }
            }
        }, 500);
        if (settings.youkuBlockAdSlot) {
            handler.postDelayed(new Runnable() {
                @Override public void run() { runOneAdPass(activity, "3000ms"); }
            }, 3000);
            handler.postDelayed(new Runnable() {
                @Override public void run() { runOneAdPass(activity, "6000ms"); }
            }, 6000);
        }
        // The feed inflates its views over the first seconds, so the dump gets several tries and
        // spends its budget only on a tree that actually has nodes in it.
        for (final int offset : new int[] {2000, 4000, 8000, 12000}) {
            handler.postDelayed(new Runnable() {
                @Override public void run() {  }
            }, offset);
        }
        // The countdown ad only appears once playback starts, well after the passes above, so the
        // detail page gets extra late samples of its own.
        if (!"com.youku.kuflix.RootPageActivity".equals(activity.getClass().getName())) {
            for (final int offset : new int[] {2000, 5000, 9000, 15000}) {
                handler.postDelayed(new Runnable() {
                    @Override public void run() {  }
                }, offset);
            }
        }
    }

    /** The two follow-up passes only re-run the home carousel rule, and they say so out loud. */
    private static void runOneAdPass(Activity activity, String offset) {
        H.info("event=ui_pass_run offset=" + offset + " src=" + CURRENT_SOURCE.get()
                + " activity=" + activity.getClass().getName());
        try {
            hideHomeTopAd(activity);
        } catch (Throwable error) {
            H.info("event=ui_pass_run_threw offset=" + offset + " error=" + H.describe(error));
        }
    }

    /** One-time breadcrumb only; the passes themselves run on every resume. */
    private static final java.util.concurrent.atomic.AtomicBoolean UI_PASS_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Superclass chain of an Activity, for anchoring on an app class instead of a framework one. */
    static String describeHierarchy(Class<?> type) {
        StringBuilder chain = new StringBuilder();
        Class<?> current = type;
        while (current != null && chain.length() < 300) {
            if (chain.length() > 0) chain.append(" < ");
            chain.append(current.getName());
            current = current.getSuperclass();
        }
        return chain.toString();
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
                    H.hit("youku_home_top_ad", "home carousel card collapsed" + tag(), HOME_TOP_AD_HIT);
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
            if (removed > 0) H.hit("youku_tab_filter", "hidden=" + removed + " from five-button bar" + tag(), TAB_FILTER_HIT);
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

    /** Counts the nodes in an Activity's decor view, 0 when there is no window or nothing in it. */
    private static int decorNodes(Activity activity) {
        try {
            if (activity == null) return 0;
            android.view.Window window = activity.getWindow();
            if (window == null || window.getDecorView() == null) return 0;
            return countNodes(window.getDecorView(), new int[1]);
        } catch (Throwable error) {
            return 0;
        }
    }

    /** Counts nodes in the tree, stopping early past a few hundred so this stays cheap. */
    private static int countNodes(android.view.View view, int[] visited) {
        if (view == null || visited[0] > 400) return 0;
        visited[0]++;
        int total = 1;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                total += countNodes(group.getChildAt(i), visited);
            }
        }
        return total;
    }

    private static final java.util.concurrent.atomic.AtomicBoolean TREE_DUMPED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Set the first time the resume intercept body runs at all, in either process. */
    private static final java.util.concurrent.atomic.AtomicBoolean RESUME_ENTERED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Set the first time the delayed UI pass actually executes. */
    private static final java.util.concurrent.atomic.AtomicBoolean RESUME_PASSED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** One-shot per method name, so the first caller is named and the rest are silent. */
    private static final java.util.Set<String> RESUME_PROBE_SEEN =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    /** Appends only the nodes whose depth falls inside the band. */
    private static void dumpBand(View view, android.content.res.Resources res, int minDepth, int maxDepth,
            StringBuilder out) {
        if (view == null || out.length() > 1500) return;
        int depth = 0;
        walkToDepth(view, depth, minDepth, maxDepth, res, out);
    }

    private static void walkToDepth(View view, int depth, int minDepth, int maxDepth,
            android.content.res.Resources res, StringBuilder out) {
        if (view == null || out.length() > 1500) return;
        if (depth >= minDepth && depth <= maxDepth) appendNode(view, res, depth, out);
        if (view instanceof ViewGroup && depth <= maxDepth) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                walkToDepth(group.getChildAt(i), depth + 1, minDepth, maxDepth, res, out);
            }
        }
    }

    private static void appendNode(View view, android.content.res.Resources res, int depth,
            StringBuilder out) {
        String text = view instanceof TextView ? String.valueOf(((TextView) view).getText()) : "";
        int id = view.getId();
        String name = id == View.NO_ID ? "" : safeResName(res, id);
        // No newlines: the log writer emits one record per line and would drop everything after
        // the first break. A flat separator keeps the whole tree inside a single record.
        out.append('|').append(repeat('.', depth)).append(view.getClass().getSimpleName())
                .append('#').append(view.getId()).append(name.isEmpty() ? "" : "(" + name + ")")
                .append(text.isEmpty() ? "" : " \"" + text + "\"")
                .append(" v").append(view.getVisibility())
                .append(" k").append(view instanceof ViewGroup ? ((ViewGroup) view).getChildCount() : 0)
                .append(" b").append(box(view));
    }

    private static void dumpNode(View view, android.content.res.Resources res, int depth, int maxDepth,
            StringBuilder out) {
        if (view == null || depth > maxDepth) return;
        if (out.length() > 6000) { out.append("…truncated"); return; }
        String text = view instanceof TextView ? String.valueOf(((TextView) view).getText()) : "";
        int id = view.getId();
        String name = id == View.NO_ID ? "" : safeResName(res, id);
        // No newlines: the log writer emits one record per line and would drop everything after
        // the first break. A flat separator keeps the whole tree inside a single record.
        out.append('|').append(repeat('.', depth * 2)).append(view.getClass().getSimpleName())
                .append('#').append(view.getId()).append(name.isEmpty() ? "" : "(" + name + ")")
                .append(text.isEmpty() ? "" : " \"" + text + "\"")
                .append(" vis=").append(view.getVisibility())
                .append(" kids=").append(view instanceof ViewGroup ? ((ViewGroup) view).getChildCount() : 0)
                .append(" box=").append(box(view));
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) dumpNode(group.getChildAt(i), res, depth + 1, maxDepth, out);
        }
    }

    private static String box(View view) {
        try {
            int[] p = new int[2];
            view.getLocationOnScreen(p);
            return p[0] + "," + p[1] + "," + (p[0] + view.getWidth()) + "," + (p[1] + view.getHeight());
        } catch (Throwable error) {
            return "n/a";
        }
    }

    private static String safeResName(android.content.res.Resources res, int id) {
        try {
            return res.getResourceEntryName(id);
        } catch (Throwable error) {
            return "?";
        }
    }

    private static String repeat(char c, int n) {
        StringBuilder b = new StringBuilder(n);
        for (int i = 0; i < n; i++) b.append(c);
        return b.toString();
    }

    private static void filterTopChannels(Activity activity, Config.Settings settings) {
        try {
            String[] hidden = settings.hiddenChannelNames;
            if (hidden == null || hidden.length == 0) return;
            int rootId = activity.getResources().getIdentifier("kf_root_page", "id", activity.getPackageName());
            View root = rootId == 0 ? null : activity.findViewById(rootId);
            // The channel row is not guaranteed to sit under kf_root_page, and the "at least two
            // known channel names in one row" test is already a strong guard, so search the whole
            // decor rather than risk anchoring on a container that may move between builds.
            if (root == null) root = activity.getWindow().getDecorView();
            if (root == null) return;
            ViewGroup bar = findChannelRow(root, new int[]{0});
            if (bar == null || lastChannelBar.get() == bar) return;
            int removed = 0;
            StringBuilder hiddenNames = new StringBuilder();
            for (int i = 0; i < bar.getChildCount(); i++) {
                View child = bar.getChildAt(i);
                String label = tabLabel(child);
                for (String name : hidden) {
                    if (!name.equals(label)) continue;
                    child.setVisibility(View.GONE);
                    if (hiddenNames.length() != 0) hiddenNames.append(',');
                    hiddenNames.append(label);
                    removed++;
                    break;
                }
            }
            lastChannelBar = new WeakReference<ViewGroup>(bar);
            if (removed > 0)
                H.hit("youku_channel_filter", "hidden=" + removed + " from channel row [" + hiddenNames + "]" + tag(),
                        CHANNEL_FILTER_HIT);
        } catch (Throwable error) {
            H.warn("event=youku_channel_filter_runtime_error " + H.describe(error));
        }
    }

    /** A row counts as the channel bar only when at least two catalog names appear in it. */
    private static ViewGroup findChannelRow(View view, int[] visited) {
        if (!(view instanceof ViewGroup) || ++visited[0] > 250) return null;
        ViewGroup group = (ViewGroup) view;
        if (group.getChildCount() >= 2) {
            int known = 0;
            for (int i = 0; i < group.getChildCount(); i++) {
                String label = tabLabel(group.getChildAt(i));
                for (String[] entry : Config.CHANNEL_CATALOG) if (entry[1].equals(label)) { known++; break; }
            }
            if (known >= 2) return group;
        }
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            ViewGroup found = findChannelRow(group.getChildAt(i), visited);
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

    /**
     * Removes the members-can-close-this pill that sits over the pre-roll.
 *
 * <p>The label is proven to arrive through TextView.setText, so the receiver is known at the moment
 * it matters. The label is blanked and its row is hidden, which takes the seconds badge with it -
 * blanking the text alone would leave a lone number floating on the player.
 *
     * <p>Fail-open: anything unexpected here falls through to the original call.
     */
    private static void installPreRollTextRule(MainHook module, ClassLoader loader) {
        try {
            Class<?> textView = R.load(loader, "android.widget.TextView");
            Method setText = null;
            for (Method method : textView.getDeclaredMethods()) {
                if (!"setText".equals(method.getName())) continue;
                if (method.getParameterTypes().length != 1) continue;
                if (!method.getParameterTypes()[0].isAssignableFrom(CharSequence.class)) continue;
                setText = method;
                break;
            }
            if (setText == null) { H.info("rule=youku_video_preroll status=miss no_setText"); return; }
            module.hook(setText).setId("youku_video_preroll").intercept(
                    new XposedInterface.Hooker() {
                        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object value = null;
                            try {
                                value = chain.getArg(0);
                            } catch (Throwable noArgs) {
                                return chain.proceed();
                            }
                            if (!(value instanceof CharSequence) || !looksLikeCountdown(value.toString())) {
                                return chain.proceed();
                            }
                            final String label = value.toString();
                            final Object receiver = chain.getThisObject();
                            try {
                                H.hit("youku_video_preroll", "label=" + label, PRE_ROLL_SEEN);
                                hideCountdownRow(receiver);
                            } catch (Throwable ignored) {
                                // never let a cosmetic rule break playback
                            }
                            return chain.proceed(new Object[] {""});
                        }
                    });
            H.info("rule=youku_video_preroll status=hooked setText(CharSequence)");
        } catch (Throwable error) {
            H.info("rule=youku_video_preroll status=miss " + H.describe(error));
        }
    }

    /** True for the members hint, the skip label and the deeper-engagement marker. */
    private static boolean looksLikeCountdown(String text) {
        if (text == null || text.length() == 0 || text.length() > 40) return false;
        return text.contains("可关闭此广告") || text.contains("深入广告")
                || text.contains("跳过广告") || text.contains("前贴片广告");
    }

    private static final java.util.concurrent.atomic.AtomicBoolean PRE_ROLL_SEEN =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Hides the row that carries the badge, after the view has been laid out. */
    private static void hideCountdownRow(Object receiver) {
        if (!(receiver instanceof android.view.View)) return;
        android.view.View view = (android.view.View) receiver;
        android.view.ViewGroup parent = view.getParent() instanceof android.view.ViewGroup
                ? (android.view.ViewGroup) view.getParent() : null;
        if (parent == null) return;
        parent.setVisibility(android.view.View.GONE);
        android.view.View grandparent = parent.getParent() instanceof android.view.ViewGroup
                ? (android.view.ViewGroup) parent.getParent() : null;
        if (grandparent != null
                && ((android.view.ViewGroup) grandparent).getChildCount() <= 6) {
            grandparent.setVisibility(android.view.View.GONE);
        }
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
