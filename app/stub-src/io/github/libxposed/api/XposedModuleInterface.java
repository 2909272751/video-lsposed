package io.github.libxposed.api;

import android.app.Application;
import android.content.pm.ApplicationInfo;

/**
 * compile-only stub of libxposed:api 102 XposedModuleInterface lifecycle params.
 *
 * Only the members this module actually calls are declared. That is deliberate: the stub is
 * compile-only and the framework's real interface is authoritative at runtime, so inventing
 * a member here compiles fine and then throws NoSuchMethodError on device.
 *
 * Verified on LSPosed 2.2.0 / Android 16:
 *   ModuleLoadedParam.getProcessName()          - works
 *   PackageReadyParam.getPackageName()          - works
 *   PackageReadyParam.getClassLoader()          - works
 *   PackageReadyParam.getApplication()          - DOES NOT EXIST (NoSuchMethodError)
 */
public interface XposedModuleInterface {

    interface ModuleLoadedParam {
        String getProcessName();
    }

    interface PackageLoadedParam {
        String getPackageName();
        String getProcessName();
        ApplicationInfo getApplication();
        ClassLoader getClassLoader();
    }

    interface PackageReadyParam {
        String getPackageName();
        ClassLoader getClassLoader();
    }

    interface SystemServerStartingParam {
        String getSystemServerProcessName();
        ClassLoader getClassLoader();
    }
}
