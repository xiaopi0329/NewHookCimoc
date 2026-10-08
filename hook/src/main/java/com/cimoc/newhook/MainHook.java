package com.cimoc.newhook;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * LibXposed API 102 版本入口（原 de.robv.android.xposed API 82 迁移而来）。
 * <p>
 * 迁移要点：
 * <ul>
 * <li>入口不再实现 IXposedHookLoadPackage，而是继承 io.github.libxposed.api.XposedModule，
 * 框架自动 attachFramework，模块不得在 onModuleLoaded 之前初始化。</li>
 * <li>lpparam 换成 onPackageReady 的 PackageReadyParam（classloader 就绪、即将创建 Application 时回调）。</li>
 * <li>hook 模型从 XC_MethodHook(before/after/setResult) 改为 OkHttp 式拦截链：
 * hook(Executable).intercept(chain -> ...)；不调用 chain.proceed() 即跳过原方法（等效 setResult 拦截），
 * 返回值即结果；proceed() 返回值等效 param.getResult()。</li>
 * <li>XposedHelpers.findAndHookMethod / findClass / setObjectField / callMethod / XposedBridge.log
 * 没有对应替代 API，用标准反射 + XposedInterface#log 实现（见下方私有工具方法）。</li>
 * <li>保留原“callApplicationOnCreate 之后再装 hook”的时序，行为与旧版一致。</li>
 * </ul>
 */
@SuppressWarnings("RedundantThrows")
public class MainHook extends XposedModule {
    @SuppressWarnings("All")
    private static final boolean DEBUG = BuildConfig.DEBUG && false;

    private static final String TAG = "NewHookCimoc";
    private static final String TARGET_PACKAGE = "com.cimoc.haleydu";

    private ClassLoader classLoader;
    private volatile boolean hooksInstalled = false;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        log(Log.INFO, TAG, "onModuleLoaded: " + param.getProcessName() + ", isSystemServer=" + param.isSystemServer());
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        classLoader = param.getClassLoader();
        log(Log.INFO, TAG, "onPackageReady: " + param.getPackageName() + ", first=" + param.isFirstPackage());
        hookApplicationCreate();
    }

    private void hookApplicationCreate() {
        try {
            Method m = Instrumentation.class.getDeclaredMethod("callApplicationOnCreate", Application.class);
            hook(m).intercept(chain -> {
                Object result = chain.proceed();
                if (chain.getArg(0) instanceof Application) {
                    installHooks();
                }
                return result;
            });
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook Instrumentation.callApplicationOnCreate failed", t);
        }
    }

    /**
     * 102 新特性：热重载。旧代码允许重载（本模块无需清理线程/JNI 等资源）。
     */
    @Override
    public boolean onHotReloading(XposedModuleInterface.HotReloadingParam param) {
        return true;
    }

    /**
     * 热重载完成（运行在新代码中）：包生命周期回调不会重放，
     * 这里先卸载旧一代全部 hook，再重新安装，避免更新后 hook 丢失。
     */
    @Override
    public void onHotReloaded(XposedModuleInterface.HotReloadedParam param) {
        param.getOldHookHandles().forEach(XposedInterface.HookHandle::unhook);
        log(Log.INFO, TAG, "onHotReloaded: reinstalling hooks");
        synchronized (this) {
            hooksInstalled = false;
        }
        // classLoader 是目标 app 的加载器，跨代仍有效
        hookApplicationCreate();
        installHooks();
    }

    private synchronized void installHooks() {
        if (hooksInstalled) {
            return;
        }
        hooksInstalled = true;
        // 每个 hook 独立安装：目标 app 升级后个别方法不存在时，只影响该处去广告，不再中断其余 hook
        safe("hookPreference", this::hookPreference);
        safe("hookSplash", this::hookSplash);
        safe("hookMain", this::hookMain);
        safe("hookSearch", this::hookSearch);
        // safe("hookResult", this::hookResult);
        safe("hookClip", this::hookClip);
        safe("hookCopyright", this::hookCopyright);
        safe("hookDebug", this::hookDebug);
    }

    private void safe(String name, ThrowingRunnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            log(Log.ERROR, TAG, name + " failed: " + t);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Throwable;
    }

    private void hookCopyright() throws Throwable {
        Method m = findMethod("com.haleydu.cimoc.core.Manga", "isCopyrightManga", String.class);
        // 原 beforeHookedMethod + setResult(false)：直接返回 false，不执行原方法
        hook(m).intercept(chain -> false);
    }

    private void hookPreference() throws Throwable {
        Method m = findMethod("com.haleydu.cimoc.manager.PreferenceManager", "getBoolean", String.class, boolean.class);
        hook(m).intercept(chain -> {
            if (!TextUtils.equals((String) chain.getArg(0), "pref_global_shutdown_ad")) {
                return chain.proceed();
            }
            return true;
        });
    }

    private void hookSplash() {
        XposedInterface.Hooker skipAd = chain -> {
            logHook(chain);
            Object thiz = chain.getThisObject();
            setField(thiz, "canSkip", true);
            invoke(thiz, "gotoMainActivity");
            // 不调 proceed：原方法体不执行（等效旧版 setResult(null) 拦截）
            return null;
        };
        safe("splash.loadSplashAd", () -> hook(findMethod("com.haleydu.cimoc.SplashActivity", "loadSplashAd")).intercept(skipAd));
        safe("splash.showAD", () -> hook(findMethod("com.haleydu.cimoc.SplashActivity", "showAD")).intercept(skipAd));
    }

    private void logHook(XposedInterface.Chain chain) {
        Executable e = chain.getExecutable();
        Object thiz = chain.getThisObject();
        String clazz = thiz != null ? thiz.getClass().getName() : e.getDeclaringClass().getName();
        log(Log.INFO, TAG, "hook: " + clazz + "." + e.getName());
        if (DEBUG) {
            log(Log.INFO, TAG, "stack trace", new Throwable());
        }
    }

    private void hookMain() {
        nothing("com.haleydu.cimoc.ui.activity.MainActivity",
                "loadInteractionAd",
                "loadRewardAd",
                "requestInteractionAd",
                "requestRewardAd",
                "showInteractionAd",
                "showRewardAd",
                "startInteractionAd"
        );
    }

    private void hookSearch() {
        nothing("com.haleydu.cimoc.ui.activity.SearchActivity",
                "initAd",
                "loadBannerAd",
                "requestBannerAd",
                "showAd",
                "showBannerAd"
        );
    }

    // 原调试用代码，注释保留；如需启用按 102 写法：
    // private void hookResult() throws Throwable {
    //     Class<?> clazz = findClass("com.haleydu.cimoc.source.Kuaikanmanhua");
    //     Method m = findMethod(clazz, "getSearchRequest", String.class, int.class);
    //     hook(m).intercept(chain -> {
    //         logHook(chain);
    //         Object builder = findClass("okhttp3.Request.Builder").getDeclaredConstructor().newInstance();
    //         invoke(builder, "url", "http://127.0.0.1/");
    //         return invoke(builder, "build");
    //     });
    // }

    private void hookClip() throws Throwable {
        Method m = findMethod("com.youxiao.ssp.base.tools.n", "a", Context.class, String.class);
        hook(m).intercept(chain -> null);
    }

    private void hookDebug() throws Throwable {
        if (!DEBUG) {
            return;
        }
        Method m = findMethod("android.app.Activity", "onResume");
        hook(m).intercept(chain -> {
            // 无功能，必要时断点使用的，
            logHook(chain);
            return chain.proceed();
        });
    }

    private void nothing(String className, String... methods) {
        for (String method : methods) {
            try {
                hook(findMethod(className, method)).intercept(chain -> {
                    logHook(chain);
                    return null;
                });
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "nothing hook failed: " + t);
            }
        }
    }

    // ---------- 替代 XposedHelpers 的私有工具方法 ----------

    private Class<?> findClass(String name) throws ClassNotFoundException {
        return classLoader.loadClass(name);
    }

    private Method findMethod(String className, String name, Class<?>... argTypes) throws Throwable {
        return findMethod(findClass(className), name, argTypes);
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>... argTypes) throws NoSuchMethodException {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, argTypes);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NoSuchMethodException(clazz.getName() + "." + name);
    }

    private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(clazz.getName() + "." + name);
    }

    private static void setField(Object thiz, String name, Object value) throws Throwable {
        findField(thiz.getClass(), name).set(thiz, value);
    }

    private static Object invoke(Object thiz, String name, Object... args) throws Throwable {
        return findMethod(thiz.getClass(), name).invoke(thiz, args);
    }
}
