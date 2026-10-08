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
 * LibXposed API 102 入口（原 de.robv.android.xposed API 82 迁移而来）。
 * <p>
 * hook 点已按真机反编译（运行时反射枚举 dex）适配到 Cimoc 1.7.276：
 * <ul>
 * <li>SplashActivity.loadSplashAd → loadATSplashAd / loadSSPSplashAd / loadSuyiSplashAd（showAD 仍在）</li>
 * <li>MainActivity 的广告方法整体迁移到 com.haleydu.cimoc.utils.AdUtils（带 Activity 参数）</li>
 * <li>剪切板类 com.youxiao.ssp.base.tools.n → com.youxiao.ssp.ax.d.f（a(Context,String) 仍为 static）</li>
 * <li>SearchActivity 的 initAd/loadBannerAd/requestBannerAd/showAd/showBannerAd、
 * Manga.isCopyrightManga、PreferenceManager.getBoolean 未变</li>
 * </ul>
 * 每个 hook 独立安装（safe 包装）：目标 app 升级导致个别方法缺失时，只影响该处，不中断其余 hook。
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
     * 先卸载旧一代全部 hook，再重新安装，避免更新后 hook 丢失。
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

    // ---------- 各去广告点（1.7.276 实测签名） ----------

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
        // 1.7.276：loadSplashAd 拆成三家 SDK 的加载方法，showAD 仍在；逐个尝试，全部跳过
        for (String method : new String[]{"loadATSplashAd", "loadSSPSplashAd", "loadSuyiSplashAd", "loadSplashAd", "showAD"}) {
            safe("splash." + method, () -> hook(findMethod("com.haleydu.cimoc.SplashActivity", method)).intercept(skipAd));
        }
    }

    private void hookMain() {
        // 1.7.276：MainActivity 的广告方法整体迁移到 AdUtils（实例方法，参数带 Activity）
        String clazz = "com.haleydu.cimoc.utils.AdUtils";
        nothing(clazz, "loadSSPInteractionAd", activityClass());
        nothing(clazz, "loadSuyiInteractionAd", activityClass());
        nothing(clazz, "loadTakuInteractionAd", activityClass());
        nothing(clazz, "loadSSPRewardAd", activityClass(), boolean.class);
        nothing(clazz, "loadSuyiRewardAd", activityClass(), boolean.class);
        nothing(clazz, "loadTakuRewardAd", activityClass(), boolean.class);
        nothing(clazz, "showInteractionAd", activityClass());
        nothing(clazz, "showRewardAd", activityClass(), boolean.class);
        nothing(clazz, "startInteractionAd", activityClass());
        // 兼容旧版：MainActivity 上的同名无参方法（1.7.113 及以前）
        nothingAll("com.haleydu.cimoc.ui.activity.MainActivity",
                "loadInteractionAd", "loadRewardAd", "requestInteractionAd", "requestRewardAd",
                "showInteractionAd", "showRewardAd", "startInteractionAd");
    }

    private void hookSearch() {
        String clazz = "com.haleydu.cimoc.ui.activity.SearchActivity";
        nothingAll(clazz, "initAd", "loadBannerAd", "requestBannerAd", "showAd", "showBannerAd");
        // 1.7.276 新增的原生广告入口，一并静默
        nothing(clazz, "releaseAd");
    }

    private void hookClip() {
        // 1.7.276：com.youxiao.ssp.base.tools.n 混淆为 com.youxiao.ssp.ax.d.f，a(Context,String) 仍为 static
        safe("clip.axdf.a", () -> {
            Method m = findMethod("com.youxiao.ssp.ax.d.f", "a", Context.class, String.class);
            hook(m).intercept(chain -> null);
        });
        // 兼容旧版类名
        safe("clip.tools.n.a", () -> {
            Method m = findMethod("com.youxiao.ssp.base.tools.n", "a", Context.class, String.class);
            hook(m).intercept(chain -> null);
        });
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

    private void logHook(XposedInterface.Chain chain) {
        Executable e = chain.getExecutable();
        Object thiz = chain.getThisObject();
        String clazz = thiz != null ? thiz.getClass().getName() : e.getDeclaringClass().getName();
        log(Log.INFO, TAG, "hook: " + clazz + "." + e.getName());
        if (DEBUG) {
            log(Log.INFO, TAG, "stack trace", new Throwable());
        }
    }

    private void nothing(String className, String method, Class<?>... argTypes) {
        safe(className + "." + method, () -> {
            hook(findMethod(className, method, argTypes)).intercept(chain -> {
                logHook(chain);
                return null;
            });
        });
    }

    private void nothingAll(String className, String... methods) {
        for (String method : methods) {
            nothing(className, method, new Class<?>[0]);
        }
    }

    private static Class<?> activityClass() {
        return android.app.Activity.class;
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
