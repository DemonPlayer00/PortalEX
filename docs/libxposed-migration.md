# libxposed 迁移（已完成）

> **结论**：模块**只有一个入口**（libxposed），挂钩原语、偏好通道、日志出口全部走现代 API。
> `de.robv.android.xposed.*` 已从源码、构建依赖与 APK dex 中彻底移除
> （实测：APK 全部 24 个 dex 里 `de/robv/android/xposed` 字符串引用数为 **0**）。
>
> 本文件记录：为什么迁、迁成什么样、真机证据、以及一路上踩过的坑（含设备侧陷阱）。
> 历史沿革见文末 §6 —— 迁移是分两步做的（先双入口，再收口），本文件描述的是**最终形态**。

## 1. 最终形态一览

| 项 | 现状 | 位置 |
|---|---|---|
| 依赖 | `io.github.libxposed:api:101.0.0`，**`compileOnly`** | `gradle/libs.versions.toml` / `xposed/build.gradle.kts` |
| 入口 | `META-INF/xposed/java_init.list` → `PortalExModule : XposedModule` | `xposed/src/main/resources/META-INF/xposed/` |
| 安装逻辑 | `FakeLocation.install(ProcessInfo)` —— 入口只做"进程信息翻译" | `FakeLocation.kt` |
| 挂钩原语 | `hook(executable).intercept(chain)` 单回调链 | `utils/Hooks.kt` |
| 回调签名 | `MethodHook` / `MethodHookParam`（Java，平台类型） | `utils/MethodHook.java`、`MethodHookParam.java` |
| 反射工具 | 本包自己的 `java.lang.reflect` 封装 | `utils/Reflect.kt`（导出名 `XposedHelpers`） |
| 偏好通道 | `getRemotePreferences("portal")` | `utils/ModulePrefs.kt` |
| 框架实例 | `ModuleRuntime.attach(module)` / `framework()` | `utils/ModuleRuntime.kt` |
| 日志 | `log(priority, "PortalEX", msg)` | `utils/Hooks.kt`（`Logger` 映射级别） |
| 元数据 | `module.prop`：`minApiVersion=101` / `targetApiVersion=101` / `exceptionMode=protective` | 同上 |
| 语义回归 | `HooksChainTest`（8 例，假框架 + JVM） | `xposed/src/test/.../HooksChainTest.kt` |

**为什么 `compileOnly`**：这些类由框架的 `framework.dex` 注入目标进程，打进模块就是第二份实现
（还会和框架的类冲突）。验证方法不是靠猜：`baksmali list classes <dex>` 对 APK 里 24 个 dex
全部**无** `io/github/libxposed/*` 的类**定义**（只有我们代码里的签名引用
`Lio/github/libxposed/api/XposedModule;` 等）；同法正控能列出 `Lmoe/fuqiuluo/xposed/PortalExModule;`。

## 2. 挂钩语义：旧 before/after → 单回调链

libxposed 的 `Hooker.intercept(chain)` 是**一个**回调；旧 API 是 before/after **两个**。
对齐规则全部收在 `Hooks.intercept` 一处（调用点 87 处一行未改）：

| 旧行为 | 现实现 |
|---|---|
| before 里改 `args` | 把 `chain.args` 拷成数组交给回调；回调改完由 `chain.proceed(args)` 用改后的值 |
| before 里 `result = x` | **不调 `chain.proceed()`**，直接返回该结果（`returnEarly` 即"赋过值"） |
| 只实现了 before | 跑完回调就 `proceed(args)` |
| after 里改 `result` | **先 `proceed()` 拿到原返回值**，再跑 after；after 赋过值才覆盖 |
| after 里 `setThrowable` | 先记下 `proceed()` 抛出的异常，after 跑完再抛 |
| 回调抛异常 | 吞掉 + 记账，**绝不穿给宿主**（本体方法按"没这个钩子"继续） |
| `invokeOriginalMethod` | `getInvoker(m).setType(ORIGIN).invoke(thisObject, args)` |

两处容易写错、已被单测钉死的地方：

- **只实现 after 时不能吞掉原返回值**。旧 API 里框架分别调 before/after，after 不碰
  `result` 就是原值；单回调链里如果不小心把 `proceed()` 的返回值丢掉，就变成"钩子把
  宿主方法的返回值弄没了"——静默且致命。
- **`invokeOriginalMethod` 不是 `chain.proceed()`**。`proceed()` 只能在回调内部用，而本模块
  四个调用点都在**回调之外**（延迟投递/守护线程里补发一次回调对象），所以要用 `getInvoker`
  的 `ORIGIN` 类型（"从原方法起算"，不会再进我们自己的钩子，因此不自递归）。

`hookAllMethods` 的匹配：沿 `superclass` 链收集 `declaredMethods` 里同名者，按
"名字 + 形参类型"去重 ⇒ **重写只挂一次**，且挂在声明它的那个类上（与旧
`XposedBridge.hookAllMethods` 一致）。用 `methods` 会把父类方法以子类为声明者再列一遍，
于是"父类声明 + 子类重写"被挂两次。

## 3. 语义单测（`HooksChainTest`）

真机日志只能证明"钩子装上了"，证明不了"语义对"。所以用**假框架**（`FakeXposedModule`
实现 `XposedInterface`；测试自己驱动 `hooker.intercept(chain)`，chain 真去反射调目标方法）
把规则钉死，8 个用例：

1. before 赋 `result` 必须短路原方法（且原方法体确实没跑）；
2. before 改 `args` 必须传给原方法；
3. after 能看到原返回值并能覆盖它；
4. **只实现 after 时不得丢掉原返回值**；
5. 原方法抛异常 ⇒ after 仍执行、且异常照旧抛出（after 里 `hasThrowable()` 为真）；
6. 回调抛异常不许穿给宿主（原方法照旧执行，且记一条"钩子回调异常"）；
7. `invokeOriginalMethod` 绕开钩子调原方法（挂短路钩子后仍能拿到真值、且原方法体跑到）；
8. `hookAllMethods` 同名只挂一次、且挂到真实方法对象上（构造器挂钩数量一并在内）。

跑法：`./gradlew :xposed:testDebugUnitTest`（含在 `sh scripts/test-all.sh` 里）。

## 4. 设备侧事实与坑（LSPosed v2.2.0 (7854)，2026-09-14 实测）

**框架实现的是 API 102**：`/data/adb/modules/zygisk_lsposed/framework.dex`（234 KB）里有完整的
`io/github/libxposed/api/*`，包括只有 102 才有的 `HotReloadingParam` / `HotReloadedParam`。
模块声明 **101**：用不到 102 的新面，声明 101 就能在只实现 101 的框架上也加载（框架保证向后兼容）。

**模块日志不在 logcat**：libxposed 路径下模块日志由框架代写，`adb logcat | grep Portal`
只有框架转发的零星几行。**权威位置是 `/data/adb/lspd/log/modules_*.log`**（lspd 每次启动
一个新文件）。排查"入口到底走了哪条"必须看这里，否则会误判成"没注入"。
本模块日志现在带自己的 tag：`(system)[moe.fuqiuluo.portalex,PortalEX,<id>,0,1] [Portal] ...`。

**`adb install -r` 换掉模块 APK 之后，框架记的 APK 路径会陈旧**：
`/data/adb/lspd/config/modules_config.db` 的 `modules.apk_path` 仍指向上一次安装的
`/data/app/~~XXX==/.../base.apk`。实测：重装后那个目录已被删，但 lspd 照旧按旧路径跑 ——
重启设备、重启 lspd、`action.sh` 的秘密码广播**都不刷新它**；表现是**模块仍按旧代码运行**，
新加的东西自然看不到。修复：停 lspd → 改该行指向新路径 → 删 `-wal`/`-shm` → 重启设备。
**纪律：换模块 APK 后若"行为没变"，先核对 `modules.apk_path`。**

**换模块 APK 后一律重启设备**再验证：`system_server` 里跑的模块是**开机时注入的那份 dex**；
应用进程 `am force-stop` 后重起即可换新 dex。

**LSPosed 管理器不在 `pm list packages` 里**：它内嵌在
`/data/adb/modules/zygisk_lsposed/manager.apk`，入口是
`am start -a org.lsposed.manager.LAUNCH_MANAGER -p com.android.shell`（由 `com.android.shell`
承载 Activity）。lspd 日志里的 `manager is not installed` 指独立包名未安装，不代表管理器不可用。
**本项目的结论不依赖管理器 UI**：判据是框架日志里那行
`libxposed 入口就绪：进程=… api=102 framework=LSPosed`（只有现代入口会打印）。

**`scope.list` 不只是展示**：它列的包会被**自动加入实际作用域**；把某项从清单里去掉之后，
框架会把它**从作用域里收回** —— 但如果那个应用是用户**手动勾选**过的，就会保留。
⇒ 想让某个目标应用长期留在作用域里，正确做法是在管理器里手动勾它，**不要**塞进 `scope.list`。
`staticScope` 刻意不写：写 `true` 会把作用域变成用户不可改的固定集合，清单必须一次写全；
不写时清单的语义就是"推荐"（打标 + 默认勾上，用户仍可增减），与旧 `xposedscope` 一致。
注意 `scope.list` 里**系统框架写 `system`**，而 legacy 的 `@array/xposed_scope` 里写的是 `android`。

**当前清单**（`xposed/src/main/resources/META-INF/xposed/scope.list`，与
`app/src/main/res/values/arrays.xml` 的 `xposed_scope` **两处必须一致**）：

```
system
com.android.phone
com.android.settings      ← 2026-09-16 加，见下
com.android.location.fused
com.xiaomi.location.fused
com.oplus.location
```

**为什么加 `com.android.settings`**："隐藏开发者模式"那条钩子拦的是 `Settings.Global` 的读取
（`development_settings_enabled` / `adb_enabled` / `adb_wifi_enabled` / `adb_authorization_timeout`），
而**设置应用本身就是这组键的主要读取者与展示者** —— 它不在作用域里时，开发者选项那一栏
照旧显示真实状态。要"系统框架里返回未开启"，这一项得在。

⚠️ **清单里不写注释**：格式是"一行一个包名 / 一行一个入口类"，多写的行有被框架当成包名的风险
（`Hide My Applist` 的 APK 里核出来的格式就是纯列表）。说明写在本文件里，不写进清单。

### 隐藏开发者模式：作用域与"设置应用不读那两条路"（2026-09-16 实测）

**已撤销（2026-09-16，`dd1a60a`）**：曾把 `com.android.settings` 加进推荐清单，
理由写的是"设置应用是这组键的主要读取者"。**A/B 实测证明这个做法两个判据都不成立**：

| 场景 | 注入进程 | 框架侧命中 |
| --- | --- | --- |
| 设置应用在作用域内 | system + phone + **settings** | ✅ `development_settings_enabled ⇒ 0` ×5 |
| 设置应用移出作用域 | system + phone（**0 注入**） | ❌ 零命中 |

⇒ 命中来自**设置应用进程自己的客户端读取**，不是 system_server 的内部读取。
要让设置界面显示"未开启"就必须注入设置应用，而"对目标应用零注入"是红线 ⇒ **选红线**，
`com.android.settings` 已移出两处清单。

**仍然成立的部分**：
- `DeveloperModeHook` 本身合规：安装点只在 `install()` 的 `"android"` 分支，非系统分支直接 return；
- 对**已在作用域内**的目标应用（其进程本身就是被注入的）读 `Settings.Global` 时依然生效 ——
  这是既有作用域的自然结果，不是新增注入；
- 钩子装上：`DeveloperModeHook: 已挂 5 个 Settings.Global 读取口`；
- 开关链路通：`put_config` 里 `hide_developer_mode=false → true` 可见。

**未成立（有定量证据，不是猜）**：设置应用 UI 里"开发者选项"仍显示**真实**状态
（真值 `development_settings_enabled=1`，界面显示"开启"）。两条路都零命中：
- `Settings.Global.getInt/getString/getLong/getBoolean` 的钩子：0 次命中；
- 临时探针（`SettingsProvider.call` 命中我们关心的键时打印 `method` + Binder 调用者 uid/包名）：
  **0 次命中** ⇒ 设置应用打开开发者选项页时**既没走客户端静态方法、也没走 provider 的 call**，
  它读的是**进程内缓存**（Android 14+ 的 `DevelopmentSettingsDashboardFragment`
  有 `rememberIsDevelopmentSettingsEnabled` 这类记忆字段），或经 AIDL 默认接口而非 `call`。

⇒ 想连设置 UI 一起盖住，得再加一层：挂设置应用内部读到该状态的地方（缓存/字段），
或改挂 provider 侧所有读取入口。**当前实现只覆盖"走 `Settings.Global` 公开 API 的被注入进程"。**

⚠️ **设备侧教训（WAL）**：LSPosed 的 `modules_config.db` 是 WAL 模式，**改动可能还只在 `-wal` 里**。
直接用主机 sqlite3 只读主库会看到旧快照（我据此误判过"作用域没加上"）；
**删 `-wal` 前必须先 checkpoint**（`pragma wal_checkpoint(TRUNCATE)`），
否则会把已应用的作用域记录丢掉（实测丢过一次 `com.android.settings`）。
另外：改模块 APK 的推荐清单**不会**让框架重放它 —— 已装模块的 scope 需要
管理器里操作，或由我们直接写入 DB。

### 最近一次实测（2026-09-14 09:28 重启后）

| 进程 | 现代入口 | 证据 |
|---|---|---|
| `system`（system_server） | ✅ | 入口就绪 + `BinderSensorMock: 开机登记`、`SystemRuntimeChannel: 已解析`、`AndroidFusedLocationProvider: 已挂 chooseBestLocation`、`融合定位 hook 状态：… chooseBest=true child=true blindMethods=2` |
| `com.android.phone` | ✅ | 入口就绪 + `Found com.android.phone` |
| 第三方应用（在作用域） | ✅ | 入口就绪 + 应用分支日志；其 `:pushservice` 子进程各自一次 |
- 每进程"入口就绪"**恰好 1 次**（`injectedPackages` 去重有效）；模块 ERROR 级日志 0 行。
- `blindMethods=2` / `child=true` 说明**多个方法与子方法真的挂上了**，是链式挂钩生效的直接证据。
- 已知（**与本次改造无关**）：`com.android.phone` 里会有一条
  `Failed to init mock service in TelephonyHook` —— 它要连 PortalEX 应用的模拟服务，
  开机时应用没起就得失败；改造前的日志里同样存在。

## 5. 边界与风险

- **不再兼容"只认旧 API 的框架"**：`assets/xposed_init` 与 `IXposedHookLoadPackage` 已删除。
  真要在老框架上跑，需要保留旧入口（历史形态见 §6 的 S4）。
- **热路径开销**：每次回调会分配一个参数数组 + 一个 `MethodHookParam`（旧 `XposedBridge`
  自己也会每次分配一个）。定位/传感器路径上是"每次调用"级别，若要再省，可在
  `intercept` 里对"只实现 after 且不需要参数数组"的钩子做特例 —— 但先要证明它值得，
  目前没有实测数据支持，故不做。
- **`Chain.getArgs()` 是 `List`**：本层每次拷成数组以保留旧语义（`args[i] = x` 直接生效、
  `param.args` 可被回调持有）。这份拷贝是语义需要，不是浪费。
- **`proceed()` 与 `proceedWith()`**：本层只用 `proceed(args)`。`proceedWith` 是"直接给结果、
  不跑原方法"的捷径，与旧 API 的 `setResult` 等价，将来若要在钩子里短路可以少一层数组拷贝 ——
  当前实现选择 `returnEarly` + 直接 return，语义更直白。

## 6. 历史沿革（为什么分两步）

1. **S1 反射层**：`utils/Reflect.kt` 用同名实现取代 `XposedHelpers` 的约 123 处调用
   （`findClassIfExists` 37、`callMethod` 23、`findClass` 15 …）。逐个改写成裸反射等于把这
   123 处的参数匹配/继承链查找/装箱语义各重写一遍，纯放大器式改动；同名兼容层把风险压到
   "只改 import"。刻意对齐的旧语义（都写在注释里）：找不到时该抛的抛、该 null 的 null；
   查找沿继承链；`callMethod` 拆掉 `InvocationTargetException` 外壳；`findMethodBestMatch`
   按"个数 + 可赋值（含装箱）"挑。
2. **S2 中立层**：`MethodHook` / `MethodHookParam` 刻意用 **Java** 写 —— 旧 API 是 Java 类，
   字段在 Kotlin 眼里是平台类型，于是 44 处 override 里 `param: MethodHookParam`（30 处）与
   `param: MethodHookParam?`（14 处）**同时合法并存**。用 Kotlin 写基类就只能二选一，
   另一边的 30 或 14 处立刻编译不过 —— 那是"换 API"被顺手变成"改 44 个回调"。
   `MethodHookParam.result` 保持 **private 字段 + setter**：赋值即 `returnEarly`，
   项目靠这个行为做权限检查旁路，写成 public 字段就没有这个语义了。
   `hasBefore()` / `hasAfter()` 是这一层新增的：单回调链下必须自己判断"要不要跑某个阶段"。
3. **S3 helper 层**：`utils/Xposed.kt` 整个建在中立层上，对 15 个 hook 文件只做 import/回调类型替换。
4. **S4 双入口（已成历史）**：过渡期同时登记 `assets/xposed_init` → `FakeLocation` 与
   `META-INF/xposed/java_init.list` → `PortalExModule`，两者共用 `install(ProcessInfo)`。
   真机验证通过后，旧入口已在收口阶段删除。
5. **S5 偏好**：先"新通道优先 + 旧 `XSharedPreferences` 反射兜底"，收口时删掉兜底链
   （它点名要被废弃；旧入口不存在后，那套 classloader 兜底也失去意义）。
6. **S6 元数据**：`java_init.list` / `module.prop` / `scope.list` 三件。

### 收口阶段踩过的坑（值得记）

- **机械替换后必须核对 import 块**：把 `XC_MethodHook.MethodHookParam` 换成中立名时，
  import 行 `import de.robv.android.xposed.XC_MethodHook.MethodHookParam` 会被替换成
  **不存在的** `de.robv.android.xposed.MethodHookParam`；而中立名 `MethodHookParam` 的 import
  是**新增行**，漏加后编译器报的是 `'beforeHookedMethod' overrides nothing` +
  `Unresolved reference 'MethodHookParam'` —— 症状像"基类不对"，其实是**少一行 import**。
- **适配器不能自调**：过渡期 `Hooks.kt` 里若把 `XposedBridge.log` 写成 `Hooks.log`，
  编译期只报"类型推断失败"，运行期就是**静默的自我递归**（栈溢出）。现在这条已经不可能发生
  （旧 API 全删），但"同一层里别用自己的名字调自己"这条纪律留着。
- **假框架不能继承真基类**：单测最初想 `class Fake : XposedModule()`，结果
  `XposedInterfaceWrapper` 上的 `hook`/`log`/`getInvoker` 都是 **final**，没法覆盖。
  正解是**直接实现 `XposedInterface`**（Java 壳里照抄签名，省掉 Kotlin 里自引用泛型的噪音）。
- **`testCompileOnly` 不等于运行期可用**：单测需要 `testImplementation`，否则
  `NoClassDefFoundError: io/github/libxposed/api/XposedInterface`。
