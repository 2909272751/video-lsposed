package io.github.qqliveclean.apps.didi;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/** 反射工具：类/方法查找，失败即 fail-open（返回 null / 抛异常由调用点吞掉）。 */
public final class R {
    private R() {}

    public static Class<?> load(ClassLoader loader, String name) {
        try { return Class.forName(name, false, loader); } catch (Throwable t) { return null; }
    }

    public static Class<?>[] paramTypes(Method m) {
        try { return m.getParameterTypes(); } catch (Throwable t) { return new Class<?>[0]; }
    }

    public static boolean isInteresting(Method m) {
        Class<?>[] p;
        try {
            p = m.getParameterTypes();
        } catch (Throwable t) {
            return false;
        }
        if (p.length == 0 || p.length > 3) return false;
        for (Class<?> c : p) {
            String n = c.getName();
            for (String prefix : Config.INTERESTING_TYPE_PREFIXES) {
                if (n.startsWith(prefix) || n.equals(prefix)) return true;
            }
        }
        return false;
    }

    public static String describe(Method m) {
        StringBuilder sb = new StringBuilder(m.getName()).append('(');
        Class<?>[] p = paramTypes(m);
        for (int i = 0; i < p.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(p[i].getSimpleName());
        }
        return sb.append(')').toString();
    }

    public static String describe(Constructor<?> c) {
        StringBuilder sb = new StringBuilder("<init>(");
        Class<?>[] p = c.getParameterTypes();
        for (int i = 0; i < p.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(p[i].getSimpleName());
        }
        return sb.append(')').toString();
    }
}
