# 迁移到 libxposed 新 API

> **为什么做**：上游通知 —— `XSharedPreferences` 即将废弃，legacy 模块应尽快迁移到 libxposed。
> 本机框架是 **LSPosed v2.2.0 (7854)**（发布这条通知的那一代），模块原本以旧 API 运行。
>
> **纪律**：迁移期**双入口**（旧 API 路径保留可用），每一步都能独立编译与回归，
> 不破坏现有两条线（定位 / 传感器）。**迁移不引入任何业务功能改动。**

> **重做说明（2026-09-14）**：这条迁移第一次是在含相机线的分支上做的，那批提交已随相机线
> 整体离开 `master`。本文件是**在干净的 master 上重做**的记录：步骤照旧、代码重写并逐文件核对，
> 结论重新在真机上取了一遍（见 §4）。原实现里的相机相关内容一律不进本分支。

## 0. 现状

| 项 | 迁移前 | 迁移后 |
|---|---|---|
| 依赖 | `de.robv.android.xposed:api:82`（`compileOnly`） | 再加 `io.github.libxposed:api:101.0.0`（`compileOnly`） |
| 入口 | `assets/xposed_init` → `FakeLocation`（`IXposedHookLoadPackage` / `IXposedHookZygoteInit`） | 双入口：`assets/xposed_init` ＋ `META-INF/xposed/java_init.list` → `PortalExModule`（`XposedModule`） |
| 挂钩原语 | 直接调 `XposedBridge` / `XposedHelpers` / `XC_MethodHook` | 中立层 `Hooks` / `XposedHelpers`（本包实现）/ `MethodHook` |
| 偏好的读法 | `XSharedPreferences`（反射） | `XposedInterface.getRemotePreferences(group)` 优先，旧通道兜底 |
| 仍 import 旧 API 的文件 | 19 | **3**（都是过渡期固有，见 §5） |

调用点几乎没动 —— 这是因为项目把旧 API 的用法**收在自己的 helper 层里**，
迁移的工作量在那一层，不在 44 处回调里。

## 1. 迁移映射（按 `api-101.0.0` 的 sources jar 校正过）

| 旧 | 新（libxposed） | 备注 |
|---|---|---|
| `IXposedHookLoadPackage` | `class X : XposedModule()` | 抽象类；回调 `onModuleLoaded` / `onPackageLoaded` / `onPackageReady` / `onSystemServerStarting` |
| `IXposedHookZygoteInit` | 无对应（`onModuleLoaded` 承担） | 本模块的 `initZygote` 本来就是空实现 |
| `assets/xposed_init` | `META-INF/xposed/java_init.list` | 放 `src/main/resources/META-INF/xposed/`，Gradle 自动打进 APK |
| `XposedBridge.hookMethod` | `hook(executable).intercept(hooker)` → `HookHandle` | `HookBuilder` 可设 `setPriority` / `setExceptionMode` |
| `XC_MethodHook`（before/after） | `XposedInterface.Hooker.intercept(Chain)` —— 单回调拦截器链 | 语义差异见下 |
| `param.setResult()` 后原方法不执行 | **不调 `chain.proceed()`** 并 `return` 结果 | 等价性的关键一条 |
| `MethodHookParam`（`args`/`result`/`thisObject`） | 自建同名兼容类（S2） | 44 处 override 一字不改 |
| `XposedBridge.log(String)` | `log(priority, tag, msg)` | 集中在 `utils/Hooks.kt` 一个出口 |
| `XSharedPreferences(name, file)` | `getRemotePreferences(group)`（被注入进程里**只读**） | S5，通知点名项 |
| `XposedHelpers.findClass/callMethod/…` | 自己的 `java.lang.reflect` 工具（S1） | 上层不再需要框架 |

**`Chain` 与旧 before/after 的等价映射**（S2 的适配器承担，调用点不感知）：

| 旧行为 | 新写法 |
|---|---|
| `beforeHookedMethod` 里改 `args` | 改中立 `param.args`，`proceed(args)` |
| `beforeHookedMethod` 里 `result = x` | 不 `proceed`，直接 `return x` |
| 什么都没做 | `return chain.proceed()` |
| `afterHookedMethod` 里改 `result` | `proceed()` 之后覆盖返回值 |
| `afterHookedMethod` 里 `setThrowable` | `proceed()` 抛出的异常记进 param，after 跑完再抛 |

## 2. 迁移前的好消息

这个项目的旧 API 调用点**不是散在各处的**：

- 挂钩全部经 `utils/Xposed.kt`（`hookMethod` / `beforeHook` / `afterHook` / `onceHook*` /
  `hookAllMethods*` …）；
- 反射全部经 `XposedHelpers.*`；
- 回调统一写成 `override fun before/afterHookedMethod(param: MethodHookParam)`（44 处），
  回调体只用 `param.args` / `param.result` / `param.thisObject`。

⇒ 只要**把这三层的实现换掉**，调用点不必逐个改写。

## 3. 步骤

### S1 反射工具层 ✅
新建 `utils/Reflect.kt`：**在本包里用同样的名字重新实现我们真正用到的那一小撮**
（`findClass(IfExists)` / `findMethodExact(IfExists)` / `findMethodBestMatch` / `callMethod` /
`callStaticMethod` / `newInstance` / `get·set[Object|Int|Long]Field` / `getStatic*Field` /
`findAndHookMethod` / `findAndHookConstructor`），调用点**只改一行 import**。

规模比预想大：不是 14 处，而是**约 123 处调用、12 种方法**（`findClassIfExists` 37、
`callMethod` 23、`findClass` 15、`findMethodExactIfExists` 11、`getStaticIntField` 10 …）。
逐个改写成裸反射 = 把"参数匹配 / 继承链查找 / 装箱"这些**已有既定语义**的细节在 123 个地方
各重写一遍，纯属放大器式改动；同名兼容层把风险压到"只改 import"。

刻意对齐旧语义的地方（都写在注释里）：找不到时**该抛的抛、该 null 的 null**；
查找沿继承链；`callMethod` **拆掉 `InvocationTargetException` 外壳**（很多调用点靠这个才
catch 得到真正的错误）；`findMethodBestMatch` 按"个数 + 可赋值（含装箱）"挑。
`findAndHook*` 两个是**过渡件**（内部转调本模块的 hook helper）。

### S2 中立层：`MethodHook` / `MethodHookParam` / `Hooks` ✅

新增三个文件，**过渡期唯一允许 import `de.robv.android.xposed.*` 的地方就是 `Hooks.kt`**：

- `utils/MethodHook.java` —— 取代 `XC_MethodHook` 的基类；
- `utils/MethodHookParam.java` —— 取代 `XC_MethodHook.MethodHookParam`；
- `utils/Hooks.kt` —— `hookMethod` / `hookAllMethods` / `hookAllConstructors` /
  `invokeOriginalMethod` / `log`，内部转调 `XposedBridge`，并把中立回调**适配**成 `XC_MethodHook`。

**为什么基类和参数类必须是 Java**（实测逼出来的，不是风格偏好）：
旧 API 是 Java 类，字段在 Kotlin 眼里是**平台类型** —— 于是

- 44 处 override 里 `param: MethodHookParam`（30 处）与 `param: MethodHookParam?`（14 处）
  **同时合法且并存**；
- `param.thisObject.javaClass`、`args[0].javaClass`、`result.javaClass` 这类写法天然合法。

用 Kotlin 写基类/参数类，参数类型只能二选一，另一边的 30 或 14 处立刻编译不过，
就要为一个纯机械的迁移去改 44 个回调 —— 正是迁移里最不该做的事。
libxposed 的 `Hooker`/`Chain` 同样是 Java 类，**这些调用点将来也不用改**。

复刻的语义（写在 `MethodHookParam` 注释里）：`result` 是 **private 字段 + `getResult`/`setResult`**，
Kotlin 里仍是合成属性 —— `param.result = x` 调 `setResult(x)`，同时置 `returnEarly`（原方法不执行）。
项目靠这个行为做权限检查旁路，写成 public 字段就不是这个语义了。

**踩坑一（嵌套类 import）**：全局把 `XC_MethodHook.MethodHookParam` 换成中立名时，
import 行 `import de.robv.android.xposed.XC_MethodHook.MethodHookParam` 被替换成
**不存在的** `de.robv.android.xposed.MethodHookParam` ⇒ 必须单独还原 import 行。
**本次重做时同一个坑换了个形态又踩了一次**：`XC_MethodHook` 是**分开的 import 行**，
全局替换只换了 `XC_MethodHook` 与 `XC_MethodHook.MethodHookParam` 两种形态的 import，
而**中立名 `MethodHookParam` 的 import 行是新增的**，漏加之后编译器报的是
`'beforeHookedMethod' overrides nothing` + `Unresolved reference 'MethodHookParam'`
（症状像是"基类不对"，其实是**少一行 import**）。⇒ 机械替换后要专门核对一遍 import 块。

**踩坑二（适配器自递归）**：`Hooks.kt` 里的实现必须写 `XposedBridge.log` / `XposedBridge.hookMethod`。
一次全局替换把**适配器内部**也改成了 `Hooks.log` / `Hooks.hookMethod` —— 编译期只报
"类型推断失败"，运行期就是**静默的自我递归**（栈溢出）。
⇒ 纪律写进 `Hooks.kt` 文件注释：**本文件内部必须写 `XposedBridge.xxx`**。

### S3 helper 层换实现 ✅（随 S2 完成）
`utils/Xposed.kt` 现在**整个建在中立层上**：`Method.hook(callback)` → `Hooks.hookMethod`、
`hookBefore/hookAfter` 的回调类型是中立 `MethodHookParam`、诊断日志走 `Hooks.log`。
函数名与签名对外保持不变，调用点没有额外改动。⇒ 接 libxposed 时只动 `Hooks.kt` 一个文件。

### S4 双入口 ✅
- 新增 `PortalExModule : XposedModule`（libxposed 入口）；
- 保留 `FakeLocation`（旧入口）；
- 两者**共用** `FakeLocation.install(ProcessInfo)`：入口只负责把"框架给的进程信息"
  翻译成 `ProcessInfo(packageName, processName, classLoader, isSystemApp)`，hook 逻辑一份。

对应关系（写进 `PortalExModule` 的类注释）：

| 旧 | 新 |
|---|---|
| `handleLoadPackage(lpparam)` | `onPackageReady(param)`（用 `Ready` 而不是 `Loaded`：后者拿的是 default classloader，hook 目标类要最终的那个） |
| 包名 `"android"` 的分支 | `onSystemServerStarting(param)`（**它只给类加载器，没有包名** ⇒ 入口显式补 `"android"`，好让 system_server 走同一个 when 分支） |
| `lpparam.processName` | `onModuleLoaded` 的 `processName`（每进程一次，记在字段里） |

**新增一道闸**：旧入口每进程只回调一次，所以历史上没有去重；libxposed 的 `onPackageReady`
对同一进程**可能因多个包而多次回调** ⇒ 重复安装会让每个方法挂两遍钩子。
`FakeLocation.install` 现在对**应用分支**也做 `injectedPackages` 去重
（键是包名，而集合是 `companion object` 的静态状态 ⇒ 天然按进程隔离）。

### S5 偏好通道 ✅
`ModulePrefs.readBoolean` 现在**新通道优先**：

1. `ModuleRuntime.remotePreferences("portal")` → libxposed 的 `getRemotePreferences(group)`；
2. 取不到再退回旧 `XSharedPreferences`（反射，过渡期）。

`ModuleRuntime`（新文件）是模块实例的进程内登记处：入口在 `onModuleLoaded` 里 `attach(this)`，
hook 深处才拿得到实例方法。字段类型刻意写成 `Any?` —— 写成 `XposedInterface?` 的话，
**不支持 libxposed 的框架**上一加载本类就要解析那个类，把"读个偏好"拖成 `NoClassDefFoundError`。

### S6 元数据 ✅
`xposed/src/main/resources/META-INF/xposed/`：

- `java_init.list` → `moe.fuqiuluo.xposed.PortalExModule`；
- `module.prop` → `minApiVersion=101` / `targetApiVersion=101` / `exceptionMode=protective`；
- `scope.list` → 模块自己的推荐应用清单：`system` / `com.android.phone` /
  `com.android.location.fused` / `com.xiaomi.location.fused` / `com.oplus.location`。

**规范来源**：`io.github.libxposed:api` 的 `package-info`：必填 `minApiVersion` / `targetApiVersion`，
可选 `staticScope`（布尔）、`exceptionMode`（`protective` | `passthrough`）；Java 入口在
`java_init.list`，native 入口在 `native_init.list`。`scope.list` 的文件名与**内容格式**是对着
设备上另一个真实 libxposed 模块（Hide My Applist，管理器里带 `API 101` 徽章）的 APK 核出来的：

```
META-INF/xposed/java_init.list   一行一个入口类
META-INF/xposed/module.prop      minApiVersion=101 / targetApiVersion=101
META-INF/xposed/scope.list       一行一个包名，**系统框架写 `system`**
```

⚠️ 注意最后一条：legacy 的 `@array/xposed_scope` 里写的是 **`android`**，
而现代 `scope.list` 里写的是 **`system`**（管理器把它显示成"系统框架 / system"）。

**为什么必须补这份清单**：本项目原来靠 manifest 里的
`<meta-data android:name="xposedscope" android:resource="@array/xposed_scope"/>` 声明推荐应用。
模块一旦带上 `META-INF/xposed/` 元数据，框架就走现代路径，**不再读 legacy 那份** ——
表现是管理器里"推荐应用"标记全部消失（原始迁移的实测）。两处都留着：旧框架读 manifest，新框架读 `scope.list`。

⚠️ **反直觉行为（原始迁移实测）**：`scope.list` **不只是展示**，它列的包会被**自动加入实际作用域**；
把某一项从清单里去掉之后，框架会把它**从作用域里收回** —— 但如果那个应用是用户**手动勾选**过的，就会保留。
⇒ 想让某个目标应用留在作用域里，正确做法是在管理器里手动勾它，**不要**塞进 `scope.list`。

**`staticScope` 刻意不写**：写 `true` 会把作用域变成用户不可改的固定集合，清单必须一次写全；
不写时清单的语义就是"推荐"（打标 + 默认勾上，用户仍可增减），与旧 `xposedscope` 的行为一致。

**为什么声明 101 而不是设备框架实现的 102**：本模块用不到 102 才有的
`HotReloadingParam` / `HotReloadedParam`；声明 101 就能在只实现 101 的框架上也加载
（框架保证向后兼容：本机 framework.dex 自带完整 102 面）。

### S7 验证 ✅（本次重做后重新取过）
1. `sh scripts/test-all.sh` —— host（native）+ `:xposed` + `:app` 共 **58 个任务全过 ✓**；
2. **真机：现代入口在四个进程里各自就绪**（见 §4），并**只有一次**（去重闸有效），模块零错误日志；
3. 定位侧 hook 照常安装（`融合定位 hook 状态`、`ILocationManager` 各 hook）——
   旧新入口的安装路径是同一份代码，行为未变。

## 4. 设备侧事实与坑（2026-09-14 实测）

**框架实现的是 API 102**：`/data/adb/modules/zygisk_lsposed/framework.dex`（234 KB）里
有完整的 `io/github/libxposed/api/*`，包括只有 102 才有的
`XposedModuleInterface$HotReloadingParam` / `HotReloadedParam`。

**API 类必须 `compileOnly`**：它们由框架的 `framework.dex` 提供并注入目标进程，
打进模块就是第二份实现。验证方法（不是靠猜）：`baksmali list classes <dex>` 对 APK 里 **24 个 dex**
全部无 `io/github/libxposed/*` 的**类定义**（只有我们代码里的签名引用 `Lio/github/libxposed/api/XposedModule;` 等，
那是"引用"不是"定义"）；同法正控能列出 `Lmoe/fuqiuluo/xposed/PortalExModule;`。

**模块日志不在 logcat 里**：libxposed 路径下模块日志由框架代写，
`adb logcat | grep Portal` 只看到 `LSPosedFramework` 转发的零散几行。
**权威位置是 `/data/adb/lspd/log/modules_*.log`**（每次 lspd 启动一个新文件）。
排查"入口到底走了哪条"必须看这里，否则会误判成"没注入"。

**`adb install -r` 换掉模块 APK 之后，框架记的 APK 路径会陈旧**：
`/data/adb/lspd/config/modules_config.db` 的 `modules.apk_path` 仍指向上一次安装的
`/data/app/~~XXX==/moe.fuqiuluo.portalex-YYY==/base.apk`。本次实测：重装后该目录已被删，
但 lspd 照旧按旧路径跑（重启设备、重启 lspd、`action.sh` 的秘密码广播都不刷新它）——
表现是**模块仍按旧代码运行**，而新版里加的东西（例如现代入口的就绪日志）自然看不到。
修复：停 lspd → 改写该行指向新路径 → 删 `-wal` / `-shm` → 重启设备。
**纪律：换模块 APK 后若"行为没变"，先核对 `modules.apk_path` 再谈别的**（本次就是被它骗了一轮）。
另外一条从原始迁移继承下来的纪律：换模块 APK 后**一律重启设备**再验证，别在"没注入"上浪费排查时间。

**`system_server` 里的模块是开机时注入的**：改模块代码后，`system_server` 侧的行为必须**重启后**
才验证得到；应用进程 `am force-stop` 后重起即可换新 dex。

**LSPosed 管理器在本机不在 `pm list packages` 里**：它内嵌在
`/data/adb/modules/zygisk_lsposed/manager.apk`，正常入口是
`am start -a org.lsposed.manager.LAUNCH_MANAGER -p com.android.shell`
（由 `com.android.shell` 承载 Activity）。lspd 日志里的 `manager is not installed` 指的是
独立包名未安装，不代表管理器不可用。**本次没走 UI**：徽章类结论不靠截图，
靠 S7 第 2 条的框架日志（只有现代入口会打印 `libxposed 入口就绪 … api=102 framework=LSPosed`）。

### 本次实测结果（重启后，09:12–09:13）

| 进程 | 现代入口 | 证据（`/data/adb/lspd/log/modules_*.log`） |
|---|---|---|
| `system`（system_server） | ✅ | `libxposed 入口就绪：进程=system systemServer=true api=102 framework=LSPosed`；随后 `Debug Log Status: true`、`BinderSensorMock: 开机登记`、`SystemRuntimeChannel: 已解析`、`AndroidFusedLocationProvider: 已挂 chooseBestLocation` |
| `com.android.phone` | ✅ | `libxposed 入口就绪：进程=com.android.phone systemServer=false api=102 framework=LSPosed` + `Found com.android.phone` |
| `com.zjwh.android_wh_physicalfitness`（第三方，在作用域） | ✅ | 入口就绪 + `应用侧传感 hook 已临时停用（模拟由系统框架侧接管）`（= `install()` 的应用分支跑到了） |
| `com.zjwh.android_wh_physicalfitness:pushservice` | ✅ | 入口就绪 + 同上（**每个子进程各自一次**） |

- **每个进程的"入口就绪"恰好 1 次**（`system`=1、`phone`=1、运动世界=1）⇒ 去重闸有效，没有重复安装；
- 模块侧错误日志 **0 行**（`grep '\[Portal\].*(错误|失败|异常)'` 为空）；
- 本应用自身（`moe.fuqiuluo.portalex`）**不在作用域**，因此没有它自己的注入记录 —— 这是配置如此，不是缺陷。

## 5. 边界与风险

- **仍 import 旧 API 的只剩 3 个文件**，都是过渡期固有：
  `FakeLocation`（旧入口接口）、`utils/Hooks.kt`（适配层，内部转调 `XposedBridge`）、
  `utils/ModulePrefs.kt`（`XSharedPreferences` 兜底）。
  ⇒ **`Hooks.kt` 仍走旧 `XposedBridge` 挂钩**：现代入口下这是可行的（框架同时提供旧 API），
  但"真正的 libxposed 挂钩"（`hook(executable).intercept(Chain)`）是**尚未做**的一步；
  要做就只动 `Hooks.kt` 一个文件，调用点不用改。
- **`Chain` 的语义差异要当心**：旧 `before` 里 `setResult()` 之后原方法不再执行；新 API 要
  **不调 `chain.proceed()`** 才等价。写 helper 时把这条包进去，别让调用点去理解。
- **热路径**：适配层每次回调都会多分配一个中立 `MethodHookParam`（旧 `XposedBridge` 自己也会
  每次分配一个）。本项目有"每帧/每次调用"的纪律 ⇒ 接上 `Chain` 后要重看一遍
  传感器/定位热路径的开销。
- **别把作用域写宽**：`staticScope` 一旦启用就等于"用户不能扩大范围"，写错会静默改变注入面。
- **摘旧入口的时机**：现代入口在四个进程上都验证过之后才谈摘。摘除内容 =
  `assets/xposed_init` + `FakeLocation` 的框架接口 + `Hooks.kt` 的 `XposedBridge` 转调 +
  `ModulePrefs` 的 `XSharedPreferences` 兜底 —— 是一组连带的清理，不是删一个文件。
