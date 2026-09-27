package io.github.qqliveclean;

import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 顶部频道栏精简 (top channel bar trimming).
 *
 * The bar is {@code com.tencent.channelnav.*} (classes24.dex). {@code impl.r1.C()} returns the
 * assembled {@code List<PBChannelListItem>} and is a facade over all three data-manager
 * variants (V1 / V1-Opt / V2), so one hook covers them. It is an assembly-time accessor, not a
 * render path: the Compose container classes reference neither {@code impl.r1} nor {@code C()};
 * the UI only observes redux state that this list is pushed into.
 *
 * Filtering is by {@code .title}, the server-supplied display name the user actually sees.
 * (The app's own built-in blacklist key {@code black_channel_list} only matches ids, and the
 * channel list is server-driven, so no static id->name map exists to use instead.)
 *
 * 首页 is never removed: it anchors paging.
 */
final class ChannelRules {

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
    private static final String HOME = "首页";

    private ChannelRules() {}

    static void install(MainHook module, ClassLoader loader, final String[] hiddenNames) {
        final String rule = "channel_bar";
        if (hiddenNames.length == 0) {
            H.skipped(rule, "no channel disabled in settings");
            return;
        }
        Class<?> facade;
        try {
            facade = R.load(loader, "com.tencent.channelnav.impl.r1");
        } catch (Throwable error) {
            H.miss(rule, "com.tencent.channelnav.impl.r1 not found: " + H.describe(error));
            return;
        }
        Method navList = R.find(facade, "C", List.class);
        if (navList == null) {
            H.miss(rule, "r1.C()Ljava/util/List; not found; List(): "
                    + R.describeCandidates(facade, List.class));
            return;
        }
        final NameReader reader = NameReader.resolve(loader);
        if (reader == null) {
            H.miss(rule, "no title accessor on PBChannelListItem (tried field/getTitle()/title()"
                    + " on protocol.pb and protocol.pb.kmm)");
            return;
        }
        try {
            module.hook(navList).setId("qqlive_channel_bar").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    if (!(result instanceof List)) return result;
                    List<?> source = (List<?>) result;
                    ArrayList<Object> kept = null;
                    int removed = 0;
                    for (int i = 0; i < source.size(); i++) {
                        Object item = source.get(i);
                        String name = reader.read(item);
                        boolean hide = name != null && !HOME.equals(name)
                                && contains(hiddenNames, name);
                        if (hide) {
                            if (kept == null) {
                                kept = new ArrayList<Object>(source.size());
                                for (int k = 0; k < i; k++) kept.add(source.get(k));
                            }
                            removed++;
                        } else if (kept != null) {
                            kept.add(item);
                        }
                    }
                    if (kept == null) return result;
                    if (flagFor(rule).compareAndSet(false, true)) {
                        H.info("hit=" + rule + " hidden=" + removed + " kept=" + kept.size()
                                + "/" + source.size() + " config_source=" + H.configSource);
                    }
                    return kept;
                }
            });
            H.hooked(rule, "channelnav.impl.r1.C() filtered by title, hiding "
                    + java.util.Arrays.toString(hiddenNames));
            installVariantFilters(module, loader, hiddenNames, reader);
            installBlacklistProbe(module, loader);
        } catch (Throwable error) {
            H.miss(rule, "install failed: " + H.describe(error));
        }
    }

    /**
     * Observation-only probe on the app's own blacklist getter
     * {@code com.tencent.channelnav.uitls.q.y()}. It does not change behaviour - it exists to
     * answer "does this pipeline run at all", which the four armed channel hooks could not,
     * because arming proves nothing about invocation. If this fires, appending ids here is the
     * route that will work; if it never fires, the channel list we see is produced elsewhere.
     */
    private static void installBlacklistProbe(MainHook module, ClassLoader loader) {
        final String rule = "channel_blacklist_probe";
        Class<?> owner;
        try {
            owner = R.load(loader, "com.tencent.channelnav.uitls.q");
        } catch (Throwable error) {
            H.miss(rule, "uitls.q not found: " + H.describe(error));
            return;
        }
        Method getter = R.find(owner, "y", List.class);
        if (getter == null) {
            H.miss(rule, "uitls.q.y()List not found; List(): "
                    + R.describeCandidates(owner, List.class));
            return;
        }
        final AtomicBoolean once = new AtomicBoolean(false);
        try {
            module.hook(getter).setId("qqlive_channel_blacklist_probe").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    int size = result instanceof List ? ((List<?>) result).size() : -1;
                    H.hit(rule, "blacklist getter called, size=" + size, once);
                    return result;
                }
            });
            H.hooked(rule, "uitls.q.y() observed (no behaviour change)");
        } catch (Throwable error) {
            H.miss(rule, "hook failed: " + H.describe(error));
        }
    }

    /**
     * The three data-manager variants each funnel the assembled list through their own filter.
     * Hooking all three (separate ids) means the live variant is caught regardless of which one
     * it is; a variant that is absent simply logs a miss without affecting the others.
     */
    private static void installVariantFilters(MainHook module, ClassLoader loader,
                                              final String[] hiddenNames, final NameReader reader) {
        final String[][] targets = {
                {"tab_v1", "com.tencent.channelnav.impl.ChannelNavDataManager", "A0"},
                {"tab_v1opt", "com.tencent.channelnav.impl.ChannelNavDataManagerOpt", "K0"},
                {"tab_v2", "com.tencent.channelnav.uitls.k", "b"},
        };
        for (final String[] target : targets) {
            final String rule = "channel_bar_" + target[0];
            Class<?> owner;
            try {
                owner = R.load(loader, target[1]);
            } catch (Throwable error) {
                H.miss(rule, target[1] + " not found: " + H.describe(error));
                continue;
            }
            Method filter = R.find(owner, target[2], List.class, List.class);
            if (filter == null) {
                H.miss(rule, target[2] + "(List)List not found; List(): "
                        + R.describeCandidates(owner, List.class));
                continue;
            }
            try {
                module.hook(filter).setId("qqlive_channel_filter_" + target[0])
                        .intercept(new XposedInterface.Hooker() {
                            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                Object result = chain.proceed();
                                return prune(result, reader, hiddenNames, rule);
                            }
                        });
                H.hooked(rule, target[1] + "." + target[2] + "(List)List filtered by title");
            } catch (Throwable error) {
                H.miss(rule, "hook failed: " + H.describe(error));
            }
        }
    }

    /** Shared filter body: drop entries whose display name the user turned off. */
    private static Object prune(Object result, NameReader reader, String[] hiddenNames, String rule) {
        if (!(result instanceof List)) return result;
        List<?> source = (List<?>) result;
        ArrayList<Object> kept = null;
        int removed = 0;
        for (int i = 0; i < source.size(); i++) {
            Object item = source.get(i);
            String name = reader.read(item);
            boolean hide = name != null && !HOME.equals(name) && contains(hiddenNames, name);
            if (hide) {
                if (kept == null) {
                    kept = new ArrayList<Object>(source.size());
                    for (int k = 0; k < i; k++) kept.add(source.get(k));
                }
                removed++;
            } else if (kept != null) {
                kept.add(item);
            }
        }
        if (kept == null) return result;
        if (flagFor(rule).compareAndSet(false, true)) {
            H.hit(rule, "hidden=" + removed + " kept=" + kept.size() + "/" + source.size(), flagFor(rule));
        }
        return kept;
    }


    /** Resolves the id accessor the same way NameReader resolves the name. */
    private static final class IdReader {
        private final java.lang.reflect.Field field;
        private final Method method;

        private IdReader(java.lang.reflect.Field field, Method method) {
            this.field = field;
            this.method = method;
        }

        String read(Object item) {
            try {
                Object value = field != null ? field.get(item) : method.invoke(item);
                return value == null ? null : String.valueOf(value);
            } catch (Throwable ignored) {
                return null;
            }
        }

        static IdReader resolve(ClassLoader loader) {
            for (String className : new String[]{
                    "com.tencent.qqlive.protocol.pb.PBChannelListItem",
                    "com.tencent.qqlive.protocol.pb.kmm.PBChannelListItem"}) {
                Class<?> itemClass;
                try {
                    itemClass = R.load(loader, className);
                } catch (Throwable ignored) {
                    continue;
                }
                try {
                    java.lang.reflect.Field field = itemClass.getDeclaredField("id");
                    field.setAccessible(true);
                    return new IdReader(field, null);
                } catch (Throwable ignored) {
                    // fall through
                }
                Method getter = R.find(itemClass, "getId", String.class);
                if (getter == null) getter = R.find(itemClass, "id", String.class);
                if (getter != null) return new IdReader(null, getter);
            }
            return null;
        }
    }

    /**
     * Reads a String property from a protobuf-generated item. The real model classes are
     * com.tencent.qqlive.protocol.pb.PBChannelListItem and its .kmm twin (there is no
     * com.tencent.channelnav.PBChannelListItem - that name was round 11's bug). Accessor shape
     * varies between generated variants, so a field then two getter styles are tried.
     */
    private static final class NameReader {
        private final java.lang.reflect.Field field;
        private final Method method;

        private NameReader(java.lang.reflect.Field field, Method method) {
            this.field = field;
            this.method = method;
        }

        String read(Object item) {
            try {
                Object value = field != null ? field.get(item) : method.invoke(item);
                return value == null ? null : String.valueOf(value);
            } catch (Throwable ignored) {
                return null;
            }
        }

        static NameReader resolve(ClassLoader loader) {
            for (String className : new String[]{
                    "com.tencent.qqlive.protocol.pb.PBChannelListItem",
                    "com.tencent.qqlive.protocol.pb.kmm.PBChannelListItem"}) {
                Class<?> itemClass;
                try {
                    itemClass = R.load(loader, className);
                } catch (Throwable ignored) {
                    continue;
                }
                try {
                    java.lang.reflect.Field field = itemClass.getDeclaredField("title");
                    field.setAccessible(true);
                    H.diag("channel name accessor: field title on " + className);
                    return new NameReader(field, null);
                } catch (Throwable ignored) {
                    // fall through to getters
                }
                Method getter = R.find(itemClass, "getTitle", String.class);
                if (getter == null) getter = R.find(itemClass, "title", String.class);
                if (getter != null) {
                    H.diag("channel name accessor: " + getter.getName() + "() on " + className);
                    return new NameReader(null, getter);
                }
            }
            return null;
        }
    }

    private static boolean contains(String[] names, String value) {
        for (String name : names) {
            if (name.equals(value)) return true;
        }
        return false;
    }
}
