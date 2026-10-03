package io.github.qqliveclean;

import java.lang.reflect.Executable;

import android.content.SharedPreferences;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 合并进来的四套规则集（QQ 音乐 / 滴滴 / 淘系 / 微博）用的薄基类。
 *
 * <p>它们原本各自 extends XposedModule，因为每个都是一个独立模块、由 LSPosed 负责实例化并附加框架。
 * 合并成一个 APK 之后由宿主 MainHook 主动构造，而手动 new 出来的 XposedModule 不会被附加框架：
 * 第一次尝试就撞上了 IllegalStateException: Framework not attached。所以这里不再继承 XposedModule，
 * 改为持有一个宿主模块并把用到的那几个成员转发过去。
 *
 * <p>转发而不是改写调用点，是因为这些规则集里 hook()/log() 出现了近百处，逐个改成 module.xxx()
 * 会把一次机械替换变成一次需要逐行复核的改动。
 */
public abstract class RuleHost {

    protected final XposedModule module;

    public RuleHost(XposedModule module) {
        this.module = module;
    }

    /**
     * 原本由 XposedModule 定义、由 LSPosed 调用；这里由宿主 MainHook 转发。
     * 声明成抽象方法而不是继承，是为了让编译器继续检查每套规则集确实实现了这两个入口。
     */
    public abstract void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param);

    public abstract void onPackageReady(XposedModuleInterface.PackageReadyParam param);

    public XposedInterface.HookBuilder hook(Executable target) {
        return module.hook(target);
    }

    public void log(int priority, String tag, String message) {
        module.log(priority, tag, message);
    }

    public void log(int priority, String tag, String message, Throwable throwable) {
        module.log(priority, tag, message, throwable);
    }

    public SharedPreferences getRemotePreferences(String group) {
        return module.getRemotePreferences(group);
    }

    public int getApiVersion() {
        return module.getApiVersion();
    }

    public String getFrameworkName() {
        return module.getFrameworkName();
    }

    public String getFrameworkVersion() {
        return module.getFrameworkVersion();
    }
}