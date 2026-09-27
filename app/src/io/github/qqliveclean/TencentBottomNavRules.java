package io.github.qqliveclean;

import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Filters only the validated presentation snapshot, leaving cache/count validation intact. */
final class TencentBottomNavRules {
    private static final String RULE = "tab_filter";
    private static final AtomicBoolean HIT = new AtomicBoolean();
    private static final AtomicBoolean ERROR = new AtomicBoolean();
    private TencentBottomNavRules() {}

    static void install(MainHook module, ClassLoader loader, final boolean[] hidden) {
        if (!hidden[1] && !hidden[2] && !hidden[21]) {
            H.skipped(RULE, "disabled in settings");
            return;
        }
        try {
            final Class<?> vm = R.load(loader, "com.tencent.kmm.tabbar.viewmodels.TabBarViewModel");
            final Class<?> ref = R.load(loader, "kotlin.jvm.internal.Ref$ObjectRef");
            Method target = null;
            for (Method m : vm.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (!m.isSynthetic() && Modifier.isStatic(m.getModifiers()) && p.length == 2 && p[0] == ref
                        && p[1] == vm && m.getReturnType().getName().equals("kotlin.Unit")) {
                    if (target != null) throw new IllegalStateException("ambiguous snapshot builder");
                    target = m;
                }
            }
            if (target == null) throw new NoSuchMethodException("validated snapshot builder");
            final Field element = ref.getField("element");
            target.setAccessible(true);
            module.hook(target).setId("qqlive_validated_tab_snapshot").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object box = chain.getArg(0);
                    Object original = element.get(box);
                    Object filtered = original;
                    long started = android.os.SystemClock.uptimeMillis();
                    try { filtered = snapshot(original, hidden); }
                    catch (Throwable e) {
                        if (ERROR.compareAndSet(false, true)) H.miss(RULE, "snapshot rejected: " + H.describe(e));
                    }
                    if (filtered == original) return chain.proceed();
                    element.set(box, filtered);
                    try {
                        Object result = chain.proceed();
                        H.hit(RULE, "validated snapshot filtered; cache unchanged; elapsedMs="
                                + (android.os.SystemClock.uptimeMillis() - started), HIT);
                        return result;
                    } finally { element.set(box, original); }
                }
            });
            H.hooked(RULE, vm.getName() + "." + target.getName() + "; post-validation snapshot");
        } catch (Throwable e) { H.miss(RULE, H.describe(e)); }
    }

    private static Object snapshot(Object original, boolean[] hidden) throws Exception {
        if (original == null) return original;
        Class<?> type = original.getClass();
        Constructor<?> ctor = null;
        for (Constructor<?> c : type.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 5 && p[0] == List.class && p[3] == List.class && p[4] == List.class
                    && p[1] == p[2] && p[1].getName().endsWith(".HomeTabResourceInfo")) {
                if (ctor != null) throw new IllegalStateException("ambiguous data constructor");
                ctor = c;
            }
        }
        if (ctor == null) throw new NoSuchMethodException("TabBarData constructor");
        ctor.setAccessible(true);
        Object clone = ctor.newInstance(Collections.emptyList(), null, null, null, null);
        boolean changed = false;
        StringBuilder removed = new StringBuilder();
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            Object value = field.get(original);
            if (value instanceof List) {
                List<?> source = (List<?>) value;
                ArrayList<Object> kept = new ArrayList<Object>();
                for (Object item : source) {
                    // Kotlin semantic labels survive class/getter renaming. Only small
                    // navigation snapshots are processed, never frames or view trees.
                    String description = String.valueOf(item);
                    if (!description.startsWith("TabBarItemData(tabName="))
                        throw new IllegalStateException("unknown tab item shape");
                    int start = description.indexOf(", pageType=") + 11;
                    int end = description.indexOf(',', start);
                    if (start < 11 || end < start) throw new IllegalStateException("missing pageType");
                    int page = Integer.parseInt(description.substring(start, end));
                    boolean hide = (page == 1 || page == 2 || page == 21) && hidden[page];
                    if (hide) removed.append(page).append(',');
                    else kept.add(item);
                }
                if (kept.size() != source.size()) {
                    if (kept.isEmpty() && !source.isEmpty())
                        throw new IllegalStateException("refuse empty navigation list");
                    value = kept;
                    changed = true;
                }
            }
            field.set(clone, value);
        }
        if (!changed) return original;
        if (!HIT.get()) H.info("event=tab_snapshot removed=" + removed + " data=" + type.getName());
        return clone;
    }
}
