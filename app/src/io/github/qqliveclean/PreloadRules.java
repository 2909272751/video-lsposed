package io.github.qqliveclean;

import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 减少预加载 (reduce preload).
 *
 * {@code l81.p} (classes27) is the ChannelNav opt config singleton; {@code A()} and {@code B()}
 * are its master switches (side-channel unit-context preload / target-channel preload), both
 * resolved from the non-obfuscated key {@code channel_nav_opt_config_25h2}. Returning false from
 * both makes the app take its OWN low-preload path - the same path it already implements in
 * {@code RecommendPagerOptFragment.Pc()} (which then calls setOffscreenPageLimit(1)) and in
 * {@code sj4.h}, so this is not a hook that fights the app's logic.
 *
 * Both are boolean getters read at page/selection time, not render-path calls.
 * Caveat carried over from recon: the battery saving is a claim that needs on-device
 * measurement, so this rule is OFF by default.
 */
final class PreloadRules {

    /** Per-rule once-flags: a shared flag makes every rule after the first log nothing. */
    private static final java.util.concurrent.ConcurrentHashMap<String, AtomicBoolean> HIT_FLAGS =
            new java.util.concurrent.ConcurrentHashMap<String, AtomicBoolean>();

    private static AtomicBoolean flagFor(String rule) {
        AtomicBoolean flag = HIT_FLAGS.get(rule);
        if (flag != null) return flag;
        AtomicBoolean created = new AtomicBoolean(false);
        AtomicBoolean previous = HIT_FLAGS.putIfAbsent(rule, created);
        return previous != null ? previous : created;
    }

    private PreloadRules() {}

    static void install(MainHook module, ClassLoader loader) {
        // Candidate holders of the A()/B() master switches, old name first. 9.03.95 used l81.p;
        // 9.04.55 only declares l81.a/b/c, so every candidate is tried and whichever provides
        // the switches gets hooked. A renamed class therefore costs one miss line, not the rule.
        final String[] candidates = {"l81.p", "l81.a", "l81.b", "l81.c"};
        int armed = 0;
        int considered = 0;
        for (final String className : candidates) {
            Class<?> config;
            try {
                config = R.load(loader, className);
            } catch (Throwable ignored) {
                continue;   // absent in this build: not a failure, just not a candidate
            }
            considered++;
            for (final String name : new String[]{"A", "B"}) {
                final String rule = "reduce_preload." + className.replace('.', '_') + "." + name;
                Method getter = R.find(config, name, boolean.class);
                if (getter == null) {
                    H.miss(rule, name + "()Z not found on " + className + "; boolean(): "
                            + R.describeCandidates(config, boolean.class));
                    continue;
                }
                try {
                    module.hook(getter).setId("qqlive_preload_" + className.replace('.', '_') + "_" + name)
                            .intercept(new XposedInterface.Hooker() {
                                @Override public Object intercept(XposedInterface.Chain chain) {
                                    H.hit(rule, "preload switch -> false", flagFor(rule));
                                    return Boolean.FALSE;
                                }
                            });
                    armed++;
                    H.hooked(rule, className + "." + name + "()Z forced false");
                } catch (Throwable error) {
                    H.miss(rule, "hook failed: " + H.describe(error));
                }
            }
        }
        if (armed == 0) {
            H.miss("reduce_preload", "no candidate class provided A()/B() (considered "
                    + considered + " of " + candidates.length + ")");
        }
    }
}
