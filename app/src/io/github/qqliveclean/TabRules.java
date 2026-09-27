package io.github.qqliveclean;

import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 搴曢儴鏍囩鏍忕簿绠€ (bottom tab bar trimming).
 *
 * Tencent Video's bottom bar is the KMM/Compose component {@code com.tencent.kmm.tabbar}.
 * Two independent rules cover it, because the two plausible sources have very different
 * obfuscation stability:
 *
 * <ul>
 *   <li><b>tab_default_list</b> - {@code TabBarItemType$a.f()} returns the default
 *       composition. {@code com.tencent.kmm.tabbar.*} keeps its real names (verified: zero
 *       {@code renamed from} markers in jadx output), so this rule is version-durable.</li>
 *   <li><b>tab_bar_data</b> - {@code re0.i.o()/p()/n()} return the concrete
 *       {@code List<qe0.a>} that {@code TabBarViewModel.C0} turns into bar items. This is
 *       the list actually rendered when data comes from cache/server, but the class and its
 *       pageType getter are obfuscated, so the getter is resolved structurally.</li>
 * </ul>
 *
 * Both filter by pageType and never touch {@code 棣栭〉(0)}: it anchors the bar and the
 * fallback route, so removing it can strand the app.
 *
 * Cost: hooks are only installed when at least one tab is actually disabled, so a default
 * install pays nothing. They run once per tab-bar composition, not per frame.
 */
final class TabRules {
    private static final AtomicBoolean DEFAULT_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean DATA_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean DEBUG_DUMPED = new AtomicBoolean(false);

    private TabRules() {}

    static void install(MainHook module, ClassLoader loader, boolean[] hidden, boolean debugLog) {
        installDefaultComposition(module, loader, hidden, debugLog);
        installBarDataLists(module, loader, hidden, debugLog);
    }

    // ---------------------------------------------------------------- rule 1

    private static void installDefaultComposition(MainHook module, ClassLoader loader,
                                                  final boolean[] hidden, final boolean debugLog) {
        final String rule = "tab_default_list";
        try {
            Class<?> type = R.load(loader, Config.TAB_ITEM_TYPE);
            Class<?> companion = R.load(loader, Config.TAB_ITEM_TYPE_COMPANION);
            Method pageType = R.find(type, "getDefaultPageType", int.class);
            Method tabName = R.find(type, "getDefaultTabName", String.class);
            Method composition = R.find(companion, "f", List.class);
            if (pageType == null) {
                H.miss(rule, "getDefaultPageType()I not found on " + type.getName()
                        + "; int(): " + R.describeCandidates(type, int.class));
                return;
            }
            if (composition == null) {
                H.miss(rule, "f()Ljava/util/List; not found on " + companion.getName()
                        + "; List(): " + R.describeCandidates(companion, List.class));
                return;
            }
            final Method accessor = pageType;
            final Method nameAccessor = tabName;
            module.hook(composition).setId("qqlive_tab_default_list").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    dumpOnce(rule, result, accessor, nameAccessor, debugLog);
                    return filter(result, accessor, hidden, rule, DEFAULT_LOGGED, debugLog);
                }
            });
            H.hooked(rule, "TabBarItemType$a.f() filtered by getDefaultPageType()");
        } catch (Throwable error) {
            H.miss(rule, "install failed: " + H.describe(error));
        }
    }

    // ---------------------------------------------------------------- rule 2

    private static void installBarDataLists(MainHook module, ClassLoader loader,
                                            final boolean[] hidden, final boolean debugLog) {
        Class<?> dataClass;
        Class<?> itemClass;
        try {
            dataClass = R.load(loader, Config.TAB_BAR_DATA);
            itemClass = R.load(loader, Config.TAB_ITEM_DATA);
        } catch (Throwable error) {
            H.miss("tab_bar_data", "obfuscated class not found: " + H.describe(error));
            return;
        }

        Method preferred = resolvePreferred(itemClass, Config.PAGE_TYPE_ACCESSORS, int.class);
        // Holder so a structural lookup can be cached after the first real list arrives.
        final Method[] accessorRef = {preferred};
        final Method nameAccessor = resolvePreferred(itemClass, Config.TAB_NAME_ACCESSORS, String.class);
        if (preferred != null) {
            H.info("event=tab_accessor rule=tab_bar_data source=preferred name=" + preferred.getName()
                    + " on " + itemClass.getName());
        } else {
            H.info("event=tab_accessor rule=tab_bar_data source=structural-probe pending int(): "
                    + R.describeCandidates(itemClass, int.class));
        }

        int installed = 0;
        // o() = main bar, p() = leading list, n() = trailing list.
        for (final String methodName : new String[]{"o", "p", "n"}) {
            Method method = R.find(dataClass, methodName, List.class);
            if (method == null) {
                H.miss("tab_bar_data." + methodName, methodName + "()Ljava/util/List; not found on "
                        + dataClass.getName() + "; List(): " + R.describeCandidates(dataClass, List.class));
                continue;
            }
            final String rule = "tab_bar_data." + methodName;
            installed++;
            module.hook(method).setId("qqlive_tab_bar_data_" + methodName).intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    Method accessor = accessorRef[0];
                    if (accessor == null) {
                        // A renamed obfuscated getter self-heals here instead of silently
                        // hiding the wrong tab: only a probe exposing 棣栭〉(0) is accepted.
                        accessor = probeAccessor(result, accessorRef);
                        if (accessor == null) return result;
                    }
                    dumpOnce(rule, result, accessor, nameAccessor, debugLog);
                    return filter(result, accessor, hidden, rule, DATA_LOGGED, debugLog);
                }
            });
        }
        if (installed > 0) {
            H.hooked("tab_bar_data", dataClass.getName() + " filtered on " + installed + " list accessor(s)");
        }
    }

    private static Method resolvePreferred(Class<?> owner, String[] names, Class<?> returnType) {
        for (String name : names) {
            Method method = R.find(owner, name, returnType);
            if (method != null) return method;
        }
        return null;
    }

    /**
     * Picks the pageType getter by behaviour when the obfuscated name has drifted: it must
     * be a no-arg int getter whose values are all known pageTypes and which exposes
     * {@code 棣栭〉(0)}. Cached on success; a failure is logged once and then given up on.
     */
    private static Method probeAccessor(Object result, Method[] accessorRef) {
        if (!(result instanceof List)) return null;
        List<?> sample = (List<?>) result;
        if (sample.isEmpty()) return null;   // wait for a real list before deciding
        Class<?> element = sample.get(0).getClass();
        for (Method candidate : element.getDeclaredMethods()) {
            if (candidate.getReturnType() != int.class) continue;
            if (candidate.getParameterTypes().length != 0) continue;
            try { candidate.setAccessible(true); } catch (Throwable ignored) { continue; }
            boolean allKnown = true;
            boolean sawHome = false;
            int limit = Math.min(sample.size(), 8);
            for (int i = 0; i < limit; i++) {
                int value;
                try {
                    value = ((Integer) candidate.invoke(sample.get(i))).intValue();
                } catch (Throwable error) {
                    allKnown = false;
                    break;
                }
                if (!Config.knownPageType(value)) { allKnown = false; break; }
                if (value == Config.PAGE_TYPE_HOME) sawHome = true;
            }
            if (allKnown && sawHome) {
                accessorRef[0] = candidate;
                H.info("event=tab_accessor rule=tab_bar_data source=structural-probe accepted="
                        + candidate.getName() + " on " + element.getName());
                return candidate;
            }
        }
        H.miss("tab_bar_data_accessor",
                "no no-arg int accessor exposing 棣栭〉(0) on " + element.getName()
                        + "; int(): " + R.describeCandidates(element, int.class));
        return null;
    }

    // ---------------------------------------------------------------- shared

    /** Removes hidden pageTypes; returns the original list untouched when nothing matches. */
    private static Object filter(Object result, Method accessor, boolean[] hidden,
                                 String rule, AtomicBoolean logged, boolean debugLog) {
        if (!(result instanceof List)) return result;
        List<?> source = (List<?>) result;
        int size = source.size();
        if (size == 0) return result;
        ArrayList<Object> kept = null;
        int hiddenMask = 0;
        for (int i = 0; i < size; i++) {
            Object item = source.get(i);
            int pageType;
            try {
                pageType = ((Integer) accessor.invoke(item)).intValue();
            } catch (Throwable error) {
                return result;   // fail open: never risk a half-filtered bar
            }
            boolean hide = pageType >= 0 && pageType < hidden.length && hidden[pageType];
            if (hide) {
                if (kept == null) {
                    kept = new ArrayList<Object>(size);
                    for (int k = 0; k < i; k++) kept.add(source.get(k));
                }
                if (pageType < 32) hiddenMask |= 1 << pageType;
            } else if (kept != null) {
                kept.add(item);
            }
        }
        if (kept == null) return result;
        if (logged.compareAndSet(false, true)) {
            H.info("hit=" + rule + " hidden=" + describeMask(hiddenMask) + " kept=" + kept.size() + "/" + size + " config_source=" + H.configSource);
        }
        return kept;
    }

    private static String describeMask(int mask) {
        StringBuilder builder = new StringBuilder("[");
        for (int pageType = 0; pageType < 32; pageType++) {
            if ((mask & (1 << pageType)) == 0) continue;
            if (builder.length() > 1) builder.append(',');
            builder.append(Config.pageTypeName(pageType));
        }
        return builder.append(']').toString();
    }

    /**
     * One-shot content dump for {@code debug_log}: the only reliable way to see what tab
     * list a new Tencent Video build actually ships.
     */
    private static void dumpOnce(String rule, Object result, Method accessor, Method nameAccessor,
                                 boolean debugLog) {
        if (!debugLog) return;
        if (!(result instanceof List)) return;
        if (!DEBUG_DUMPED.compareAndSet(false, true)) return;
        List<?> list = (List<?>) result;
        StringBuilder builder = new StringBuilder("event=tab_dump rule=" + rule + " size=" + list.size() + " [");
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (i > 0) builder.append(", ");
            int pageType = Integer.MIN_VALUE;
            try {
                pageType = ((Integer) accessor.invoke(item)).intValue();
            } catch (Throwable ignored) {
                // leave as MIN_VALUE
            }
            String name = null;
            if (nameAccessor != null) {
                try { name = String.valueOf(nameAccessor.invoke(item)); }
                catch (Throwable ignored) { name = null; }
            }
            builder.append(pageType == Integer.MIN_VALUE ? "?" : String.valueOf(pageType));
            builder.append(':').append(name != null && !"null".equals(name) ? name : Config.pageTypeName(pageType));
        }
        H.info(builder.append(']').toString());
    }
}
