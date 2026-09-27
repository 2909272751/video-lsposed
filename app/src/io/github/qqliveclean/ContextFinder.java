package io.github.qqliveclean;

import android.content.Context;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Finds the already-created Application, because LSPosed's {@code onPackageReady} fires
 * AFTER {@code Application.onCreate} on this framework.
 *
 * Verified dead ends (all installed hooks, none ever fired - so all of these run before
 * onPackageReady): hooking {@code Application.attach}, {@code Application.onCreate}, and
 * {@code Instrumentation.callApplicationOnCreate}. Hooking is therefore the wrong tool here;
 * the instance has to be located instead.
 *
 * Several routes are tried because hidden-API enforcement varies by ROM. Each failure is
 * recorded with its own reason so a miss is diagnosable rather than silent.
 */
final class ContextFinder {
    private static String lastSource = "none";
    private static String lastDetail = "no route attempted";

    private ContextFinder() {}

    static String lastSource() { return lastSource; }
    static String lastDetail() { return lastDetail; }

    static Context find(ClassLoader loader, String packageName) {
        Context context = fromCurrentApplication(loader);
        if (context != null) return context;
        context = fromLoadedApk(loader, packageName);
        if (context != null) return context;
        context = fromActivityThreadField(loader);
        if (context != null) return context;
        context = fromAppGlobals(loader);
        if (context != null) return context;
        lastSource = "none";
        return null;
    }

    /**
     * The route that actually works at onPackageReady: the Application is already created and
     * attached, but ActivityThread has not yet assigned {@code mInitialApplication}, so every
     * "current application" accessor is still null. Its own package table does hold it.
     */
    private static Context fromLoadedApk(ClassLoader loader, String packageName) {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread", false, loader);
            Method current = activityThread.getDeclaredMethod("currentActivityThread");
            current.setAccessible(true);
            Object thread = current.invoke(null);
            if (thread == null) {
                lastDetail = "currentActivityThread() returned null";
                return null;
            }
            // Preferred: mPackages[<target pkg>] -> LoadedApk.mApplication
            try {
                Field packages = activityThread.getDeclaredField("mPackages");
                packages.setAccessible(true);
                Object map = packages.get(thread);
                if (map instanceof java.util.Map) {
                    Object entry = ((java.util.Map<?, ?>) map).get(packageName);
                    Object loaded = entry instanceof java.lang.ref.Reference
                            ? ((java.lang.ref.Reference<?>) entry).get() : entry;
                    if (loaded != null) {
                        Field application = loaded.getClass().getDeclaredField("mApplication");
                        application.setAccessible(true);
                        Object value = application.get(loaded);
                        if (value instanceof Context) {
                            lastSource = "LoadedApk.mApplication";
                            lastDetail = "ok";
                            return (Context) value;
                        }
                        lastDetail = "LoadedApk.mApplication = "
                                + (value == null ? "null" : value.getClass().getName());
                    } else {
                        lastDetail = "mPackages had no entry for " + packageName;
                    }
                }
            } catch (Throwable error) {
                lastDetail = "mPackages route: " + H.describe(error);
            }
            // Secondary: mAllApplications, the flat list of created Applications.
            try {
                Field all = activityThread.getDeclaredField("mAllApplications");
                all.setAccessible(true);
                Object list = all.get(thread);
                if (list instanceof java.util.List) {
                    for (Object candidate : (java.util.List<?>) list) {
                        if (candidate instanceof Context) {
                            lastSource = "ActivityThread.mAllApplications";
                            lastDetail = "ok";
                            return (Context) candidate;
                        }
                    }
                    lastDetail = "mAllApplications empty";
                }
            } catch (Throwable error) {
                lastDetail = "mAllApplications route: " + H.describe(error);
            }
        } catch (Throwable error) {
            lastDetail = "LoadedApk route: " + H.describe(error);
        }
        return null;
    }

    /** {@code ActivityThread.currentApplication()} */
    private static Context fromCurrentApplication(ClassLoader loader) {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread", false, loader);
            Method method = activityThread.getDeclaredMethod("currentApplication");
            method.setAccessible(true);
            Object value = method.invoke(null);
            if (value instanceof Context) {
                lastSource = "ActivityThread.currentApplication";
                lastDetail = "ok";
                return (Context) value;
            }
            lastDetail = "currentApplication() returned " + (value == null ? "null" : value.getClass().getName());
        } catch (Throwable error) {
            lastDetail = "currentApplication: " + H.describe(error);
        }
        return null;
    }

    /** {@code ActivityThread.currentActivityThread().mInitialApplication} */
    private static Context fromActivityThreadField(ClassLoader loader) {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread", false, loader);
            Method current = activityThread.getDeclaredMethod("currentActivityThread");
            current.setAccessible(true);
            Object thread = current.invoke(null);
            if (thread == null) {
                lastDetail = "currentActivityThread() returned null";
                return null;
            }
            Field field = activityThread.getDeclaredField("mInitialApplication");
            field.setAccessible(true);
            Object value = field.get(thread);
            if (value instanceof Context) {
                lastSource = "ActivityThread.mInitialApplication";
                lastDetail = "ok";
                return (Context) value;
            }
            lastDetail = "mInitialApplication = " + (value == null ? "null" : value.getClass().getName());
        } catch (Throwable error) {
            lastDetail = "mInitialApplication: " + H.describe(error);
        }
        return null;
    }

    /** {@code AppGlobals.getInitialApplication()} */
    private static Context fromAppGlobals(ClassLoader loader) {
        try {
            Class<?> globals = Class.forName("android.app.AppGlobals", false, loader);
            Method method = globals.getDeclaredMethod("getInitialApplication");
            method.setAccessible(true);
            Object value = method.invoke(null);
            if (value instanceof Context) {
                lastSource = "AppGlobals.getInitialApplication";
                lastDetail = "ok";
                return (Context) value;
            }
            lastDetail = "getInitialApplication() returned " + (value == null ? "null" : value.getClass().getName());
        } catch (Throwable error) {
            lastDetail = "AppGlobals: " + H.describe(error);
        }
        return null;
    }
}
