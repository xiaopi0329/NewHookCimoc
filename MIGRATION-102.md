# Xposed API 82 → LibXposed API 102 迁移说明

本仓库 hook 模块已从传统 XposedBridge API（`de.robv.android.xposed:api:82`）迁移到现代
LibXposed API 102（`io.github.libxposed:api:102.0.0`，Maven Central 正式发布版）。
需要 **LSPosed 1.9.3+**；targeting 102 的模块不能再调用 legacy `de.robv` API（框架会拦截）。

## 代码层面的对应关系（已按 102.0.0 发布包 javap 核实）

| 旧（api 82） | 新（libxposed 102.0.0） |
| --- | --- |
| `IXposedHookLoadPackage.handleLoadPackage(lpparam)` | `class MainHook extends XposedModule`（**无参构造**，框架自动 `attachFramework`），生命周期回调 `onPackageReady(PackageReadyParam)` 取 `getClassLoader()` |
| `XposedHelpers.findAndHookMethod(类名, cl, 方法名, 参数类型..., XC_MethodHook)` | `hook(Executable).intercept(Hooker)`；找方法用标准反射 `classLoader.loadClass` + `getDeclaredMethod`（沿父类查找，`setAccessible(true)`） |
| `beforeHookedMethod` + `param.setResult(v)` | Hooker 里**不调 `chain.proceed()`**、直接 `return v`（原方法被跳过） |
| `afterHookedMethod` + `param.getResult()` | `Object r = chain.proceed(); ...; return r` |
| `param.args[i]` / `param.thisObject` | `chain.getArg(i)` / `chain.getThisObject()` |
| `XposedHelpers.setObjectField / callMethod` | 反射 `Field.set` / `Method.invoke` |
| `XposedBridge.log` | `XposedInterface.log(priority, tag, msg[, throwable])` |
| `assets/xposed_init` | `src/main/resources/META-INF/xposed/java_init.list` |
| manifest `xposedminversion` 等 meta | `META-INF/xposed/module.prop`（minApiVersion/targetApiVersion/staticScope/exceptionMode）+ `META-INF/xposed/scope.list`；manifest meta-data 保留作旧框架兼容 |

## 102 新增能力（本模块已启用）

- **热重载**：`onHotReloading` 返回 true；`onHotReloaded` 中先 unhook 旧代全部 handle，再重装 hook（包生命周期回调不会自动重放）。
- 异常模式 `exceptionMode=protective`：hook 抛异常不影响宿主。
- `module.prop` 位于 APK 的 `META-INF/xposed/`（已验证 release APK 内含 java_init.list / module.prop / scope.list，dex 中 0 处 `de/robv` 引用）。

## 构建工具链（同步升级，否则编不过）

- Gradle 7.2 → **9.8.0**；AGP 7.1.2 → **9.4.1**；CI JDK 11 → **17**（`actions/setup-java@v4`, temurin）。
- `compileSdk 37`（libxposed api 102.0.0 的 AAR 声明 `minCompileSdk=37`；AGP 8+ 还要求 `namespace`，manifest 里的 `package=` 已移除）。
- `buildFeatures { buildConfig true }`：AGP 8+ 默认不再生成 BuildConfig，MainHook 用了 `BuildConfig.DEBUG`。
- `gradle.properties` 增加 `android.overridePathCheck=true`：Windows 上项目路径含中文时 AGP 9 的硬性检查。
- `gradle/signing.gradle`：`v1SigningEnabled/v2SigningEnabled` 在 AGP 8+ 改名为 `enableV1Signing/enableV2Signing`。
- `hook/build.gradle` lint 块：`disable 'ExpiredTargetSdkVersion'`。targetSdk 32 会触发 AGP 9 lintVitalRelease 报错中断 release 构建；本模块走 LSPosed 仓库分发、不上 Google Play，故关闭该校验（如日后上架需把 targetSdk 提到 33+）。
- `settings.gradle` 移除 `maven { url 'https://api.xposed.info/' }`（libxposed api 在 Maven Central）。
- `hook/proguard-rules.pro` 追加官方 keep 规则（当前未开混淆，开启后即生效）。

## 行为保持

hook 时序与旧版完全一致：仍然是在 `Instrumentation.callApplicationOnCreate` 之后才安装各广告点 hook；
各方法的拦截语义（返回 false / 返回 true / 跳过原方法返回 null / 直接跳转 gotoMainActivity）逐条对应旧实现，
仅把 `hookResult`（原注释掉的调试代码）改写成 102 风格的注释示例。

## 验证记录（2026-10-08）

- 本机 `gradle clean :hook:assembleRelease :hook:assembleDebug` BUILD SUCCESSFUL（Gradle 9.8.0 + AGP 9.4.1 + JDK 17 + compileSdk 37）。
- release APK 检查：`META-INF/xposed/module.prop` = 102/102 + staticScope + protective；`java_init.list` = `com.cimoc.newhook.MainHook`；`scope.list` = `com.cimoc.haleydu`；dex 含 MainHook 与 `io/github/libxposed`，无 `de/robv`；manifest 兼容 meta-data 齐全（xposedminversion=102）。
- 旧版整树备份在仓库旁 `_backup-legacy-api/`（未入库）。
