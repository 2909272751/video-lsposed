package io.github.qqliveclean;

import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 首页入口精简 (hide sidebar entrance icons).
 *
 * {@code com.tencent.channelnav.uitls.i1} decides which sidebar entrance icon to render; the
 * static {@code n(ng.e)} returns a {@code SidebarContentType}. Forcing it to {@code Default}
 * makes the app take its own "no entrance" branch, and the consumer
 * ({@code SidebarPreloadThunk}) dispatches its redux action from that enum value - so the app
 * handles the consequence itself.
 *
 * Safe shape: a decision method returns one of the app's own enum constants. Nothing is removed
 * from a list the UI builds, so a wrong call degrades to "entrance still visible" instead of
 * breaking a layout.
 *
 * Class and method names are obfuscated (i1 / n), so the lookup is done by SHAPE using the
 * non-obfuscated return type; a rename therefore logs a miss naming the candidates it saw.
 * Verified present in 9.04.55 (com.tencent.channelnav.uitls.i1).
 */
final class HomeRules {
    private static final AtomicBoolean HIT = new AtomicBoolean(false);
    private static final AtomicBoolean TABBAR_HIT = new AtomicBoolean(false);
    private static final java.util.concurrent.ConcurrentHashMap<String, AtomicBoolean>
            TABBAR_SWEEP = new java.util.concurrent.ConcurrentHashMap<String, AtomicBoolean>();

    private HomeRules() {}

    /** Names of the enum constants, for the miss line. */
    private static String constantNames(Class<?> type) {
        try {
            Object[] constants = type.getEnumConstants();
            if (constants == null) return "(not an enum)";
            StringBuilder builder = new StringBuilder("[");
            for (Object constant : constants) {
                if (builder.length() > 1) builder.append(',');
                builder.append(((Enum<?>) constant).name());
            }
            return builder.append(']').toString();
        } catch (Throwable error) {
            return "(unreadable: " + H.describe(error) + ")";
        }
    }

    /**
     * The "nothing to show" enum constant, resolved once at install time.
     *
     * Deliberately does NOT fall back to "the first constant": this enum's other constants are
     * all VISIBLE entrances, so guessing would silently pin an entrance on instead of off.
     * Getting no value means "do not hook this method", which is the safe outcome.
     */
    private static Object defaultConstant(Class<?> type) {
        try {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object value = Enum.valueOf((Class<? extends Enum>) type.asSubclass(Enum.class), "Default");
            return value;
        } catch (Throwable ignored) {
            return null;
        }
    }

}
