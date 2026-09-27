package io.github.qqliveclean;

import java.lang.reflect.Method;

/**
 * Install-time reflection helpers.
 *
 * Everything here runs once, while hooking. The {@code Method} objects it returns are
 * stored and reused, so no lookup ever happens inside an intercept callback.
 */
final class R {
    private R() {}

    /** Loads a class, translating any failure into a diagnosable message. */
    static Class<?> load(ClassLoader loader, String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }

    /**
     * Exact-signature lookup. Returns null instead of throwing so callers can log a
     * precise miss reason; the return type is checked too, because obfuscated names get
     * reused with different shapes across releases.
     */
    static Method find(Class<?> owner, String name, Class<?> returnType, Class<?>... parameterTypes) {
        try {
            Method method = findInHierarchy(owner, name, parameterTypes);
            if (method.getReturnType() != returnType) return null;
            method.setAccessible(true);
            return method;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Comma-joined method names of the given arity/return shape, for miss diagnostics. */
    static String describeCandidates(Class<?> owner, Class<?> returnType) {
        StringBuilder builder = new StringBuilder();
        try {
            for (Method method : owner.getDeclaredMethods()) {
                if (method.getReturnType() != returnType) continue;
                if (method.getParameterTypes().length != 0) continue;
                if (builder.length() > 0) builder.append(',');
                builder.append(method.getName());
            }
        } catch (Throwable ignored) {
            return "?";
        }
        return builder.length() == 0 ? "(none)" : builder.toString();
    }

    /**
     * Shape-based lookup for obfuscated-by-name-but-not-by-signature targets: matches the
     * first declared method with one of {@code names}, the given return type, and the given
     * parameter shape. A {@code null} entry in {@code parameterTypes} is a wildcard, so
     * {@code (null, boolean.class)} means "two params, second is boolean" without pinning the
     * first param's class (which is often an inner/obfuscated type we should not depend on).
     */
    static Method findByShape(Class<?> owner, String[] names, Class<?> returnType,
                              Class<?>... parameterTypes) {
        try {
            for (Method method : owner.getDeclaredMethods()) {
                if (method.getReturnType() != returnType) continue;
                Class<?>[] actual = method.getParameterTypes();
                if (actual.length != parameterTypes.length) continue;
                boolean nameOk = false;
                for (String name : names) {
                    if (name.equals(method.getName())) { nameOk = true; break; }
                }
                if (!nameOk) continue;
                boolean shapeOk = true;
                for (int i = 0; i < actual.length; i++) {
                    if (parameterTypes[i] != null && parameterTypes[i] != actual[i]) { shapeOk = false; break; }
                }
                if (!shapeOk) continue;
                method.setAccessible(true);
                return method;
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return null;
    }

    /** "name(Param;Param)" list of declared methods with the given return type, for misses. */
    static String describeShapes(Class<?> owner, Class<?> returnType) {
        StringBuilder builder = new StringBuilder();
        try {
            for (Method method : owner.getDeclaredMethods()) {
                if (method.getReturnType() != returnType) continue;
                if (builder.length() > 0) builder.append(',');
                builder.append(method.getName()).append('(');
                Class<?>[] parameters = method.getParameterTypes();
                for (int i = 0; i < parameters.length; i++) {
                    if (i > 0) builder.append(';');
                    builder.append(parameters[i].getSimpleName());
                }
                builder.append(')');
            }
        } catch (Throwable ignored) {
            return "?";
        }
        return builder.length() == 0 ? "(none)" : builder.toString();
    }

    /**
     * Full public method list of a framework interface, e.g. the lifecycle param type.
     * Logged once per process so a libxposed API change shows up as a readable line
     * instead of a bare NoSuchMethodError.
     */
    static String describeMethods(Class<?> owner) {
        StringBuilder builder = new StringBuilder("[");
        try {
            for (Method method : owner.getMethods()) {
                if (builder.length() > 1) builder.append(',');
                builder.append(method.getName()).append('(');
                Class<?>[] parameters = method.getParameterTypes();
                for (int i = 0; i < parameters.length; i++) {
                    if (i > 0) builder.append(';');
                    builder.append(parameters[i].getSimpleName());
                }
                builder.append(')');
            }
        } catch (Throwable error) {
            return "?(" + error.getClass().getSimpleName() + ")";
        }
        return builder.append(']').toString();
    }

    /**
     * Resolves a method through the class hierarchy and its interfaces.
     *
     * getDeclaredMethod only sees methods declared on that exact class, so an anchor whose method
     * moved to a base class was reported as a miss even though it was still callable - the
     * splash_preload/K -> run case on 9.04.55. Walking upwards makes such a move re-resolve
     * silently instead of degrading the rule.
     */
    private static Method findInHierarchy(Class<?> owner, String name, Class<?>[] parameterTypes) {
        for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // try the interface list of this level, then the superclass
            }
            for (Class<?> iface : current.getInterfaces()) {
                try {
                    Method method = iface.getDeclaredMethod(name, parameterTypes);
                    method.setAccessible(true);
                    return method;
                } catch (NoSuchMethodException ignored) {
                    // keep walking
                }
            }
        }
        return null;
    }

}
