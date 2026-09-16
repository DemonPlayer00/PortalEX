# 性能审计（2026-09-16）

> 口径：**先量后改**。每条都标了"实测"还是"待量"，以及它在什么频率上发生
> （每帧 / 每拍 / 每次命令 / 每次开机一次）。没有频率的量级判断，一律不算结论。
> 本文件只记录**性能**问题；功能与架构见 `docs/architecture.md`、`docs/sensor-architecture.md`。

## 0. 已经做对的（别再来"优化"一遍）

- **原生 `post_process`（每个 HAL poll 都过）走单一标志早退**：`vw_is_active()` 是
  `return g_active;`（普通读，无锁），未激活时一个比较就返回 —— 这是全仓频率最高的一段代码，
  形态是对的。
- **`DeveloperModeHook.hideEnabled()`**：偏好读有 1s 缓存，并写明了"命中路径是热路径"的理由。
- **`SystemRuntimeChannel.attach()`**：解析失败有 `nextAttemptNanos` 退避，不会每拍重来。
- **交付的方法解析**：`LocationServiceHook.deliverFrame` 已按监听器类缓存 `Method`
  （本文件 P3 的同类问题，那里已修）。
- **推进/传感器两个模块线程**：会话关闭即停摆、空闲即无定时器（见第 3 节实测）。

## 1. P0 —— 每帧、每个应用各一次的**未缓存反射**

**位置**：`BaseLocationHook.injectLocation`（`xposed/.../BaseLocationHook.kt:158`）

```kotlin
kotlin.runCatching {
    XposedHelpers.callMethod(location, "makeComplete")   // ← findMethodBestMatch + invoke，每帧
}
```

- **频率**：注入路径对**每个收到位置的应用、每一帧**各跑一遍（10Hz × N 个应用）。
  `buildFrame()`（模块自己的推送链）也走这条。
- **为什么贵**：`XposedHelpers.callMethod` = 反射查找（沿类层次比对参数类型）+ invoke。
  同类问题在 `deliverFrame` 里的实测：按类缓存后每帧省下可观时间。
- **修法**：`Location::class.java → Method` 解析一次存 `ConcurrentHashMap`（`makeComplete`
  在部分 ROM 上不存在 ⇒ 缓存"不存在"这个负结果，别每帧 runCatching + 建异常）。
- **状态**：待改（一次重启周期即可验证）。

## 2. P1 —— 每拍都跑的**反射**（`ptr-pending` 时尤其糟）

**位置**：`BinderSensorMock.tick()` → `SystemRuntimeChannel.ensureCarrier()`（每拍），
以及 `ensurePump()` → `startDelivery()`（每拍，内部再进一次 `ensureCarrier`）。

- **本机形态**：`rtch=ptr-pending`，`ptr == 0`。`ensureCarrier()` 在 `ptr == 0L` 分支
  **直接 return，不设 `failTicks` 退避** ⇒ 每拍都重新走 `refreshInstance()`：
  `serviceInstance == null` 时是 `Class.forName(LocalServices)` + `getMethod("getService")`
  + `invoke` + `outerOf()`（`declaredFields` 扫描 + 反射）。
- **频率**：移动 20Hz、静止 1Hz；两处调用点 ⇒ 每拍两轮反射。
- **修法**：① `ptr == 0` / 实例取不到也走退避（`failTicks = FAIL_BACKOFF_TICKS`）；
  ② 把"实例解析失败"按 TTL 缓存（例如 30s 内不再试）；③ `ensurePump` 在"通道明确不可用"时短路。
- **状态**：待改。

## 3. P2 —— 40KB 的 binder dump 靠**轮询**（实测每次 20~30ms）

**位置**：`SensorRateProbe.pushHints()` → `dumpSensorService()`（`IBinder.dump`，本机约 40KB 文本）。

- **实测**：会话开着但静止时，监督线程占单核 **0.20~0.23%**，而它的 30 秒窗口里
  **每 10 秒出现一次 20~30ms 的尖峰** —— 那正好是 `RATE_HINT_IDLE_NANOS` 到期的一次 dump。
  也就是说：静止时的全部开销 ≈ 这个 dump。
- **移动时**：`RATE_HINT_INTERVAL_NANOS = 2s` ⇒ 每 2 秒 20~30ms ≈ **1~1.5% 单核**，
  而解析结果极少变化（`pushHints` 已加了"结果没变就不下发"，但**dump 本身照做**）。
- **修法（事件驱动）**：hook 框架侧"订阅集合变化"⇒ 事件到达后防抖取一次。
- **已落地**：`SensorRateProbe` 里已实现事件驱动骨架（请求/防抖/兜底周期），并在装载原生层时
  尝试挂 `SensorService.createSensorEventConnection` / `destroySensorEventConnection` /
  内部连接类 `enableDisable`。
- **实测结果**：本机 `hooked=0（候选 0）` —— **`services.jar` 里
  `com.android.server.sensors.SensorService` 只是个壳类**（把 smali 拉下来看过：方法只有
  `onStart/onBootPhase/getWrapper` 与一堆 native 桥），连接管理在 **native 实现**里，
  没有可挂的 Java 方法。于是本机退回兜底轮询（移动 2s / 静止 10s）。
- **下一步（需要 native 周期）**：native 侧本来就 patch 了连接相关入口（`patched=7 targets=4`）
  ⇒ 让它在那里置一个 `g_sub_dirty` 标志，Java 侧每拍问一次（移动时 20Hz、静止时靠兜底），
  脏了才 `requestRefresh()`。这才是本机唯一可行的事件源。

## 4. P3 —— 会话外仍在转的常驻轮询线程

| 线程 | 起于 | 拍长 | 会话关掉后 |
| --- | --- | --- | --- |
| `LocationKeepAlive`（`LocationServiceHook:543`） | `onService`（开机） | 500ms | `if (!FakeLoc.enable) continue` —— **继续转** |
| `GnssStatusPusher`（`LocationServiceHook:667`） | 开机 | 500/1000ms | GNSS 模拟关着也转 |

- **代价**：CPU 极小（每拍几个 volatile 读），但**每秒 2~4 次无意义唤醒**一直持续到下次开机。
- **修法**：与监督线程同一套 park/wake —— 会话开关、GNSS 开关变化时唤醒，空闲则 `wait`。
- **状态**：待改。

## 5. P4 —— 门禁路径的未缓存反射 + IPC

**位置**：`BinderUtils.getSystemContext()`（`utils/BinderUtils.kt:21`）→ `getUidPackageNames()`
→ `gateAllows(uid)` → `isLocationProviderEnabled(uid)`。

- `getSystemContext()` 每次调用做 **3 次反射查找 + 2 次 invoke**（`Class.forName("android.app.ActivityThread")`
  / `currentActivityThread` / `getSystemContext`），**没有缓存**；`getUidPackageNames()` 的默认实参
  就是它 ⇒ 每次门禁判定都付一遍。随后还有 `PackageManager.getPackagesForUid` 的 IPC。
- **频率**：`isProviderEnabledForUser` / `isProviderEnabled` 钩子里 `provider == "portal"` 时
  （App 握手期间会 100ms 一次地探），以及任何非 owner uid 的探测。
- 另外 `isLocationProviderEnabled` 在拒绝路径上**每次都 `Logger.warn`**（含 `getUidPackageNames`
  的另一次调用）—— 日志写入本身是成本，"被人刷"时更明显。
- **修法**：① `getSystemContext()` 结果缓存（`@Volatile`）；② `gateAllows` 按 uid 缓存结论；
  ③ 拒绝日志按 uid 限流（模块里已有 `warnedDeniedUids` 的先例，`isLocationProviderEnabled` 这条漏了）。
- **状态**：待改（低频，但零风险）。

## 6. P5 —— 移动成本定位：**95% 在交付**（已分段实测）

`MotionClock.beat()` 已加分段计时（调试开关下显示在 Test 页的 `motion` 行）。真机数据
（会话开 + 摇杆持续移动，607 拍累计）：

```
每拍分段(µs 平均/总ns): 推进=58/35.5ms 体力=190/115.2ms 落点=67/40.8ms 投递=5992/3637ms 拍数=607
```

- **投递 = 3.64s / 全部 3.83s ≈ 95%**，平均 **5992µs/次**（`callOnLocationChanged(force=true)`）；
- 推进 58µs、落点 67µs、体力 190µs —— 全是噪声级，**不该再优化**。
- 同轮对照：加 P0（`makeComplete` 缓存）后移动时 `MotionClock` 从 **8.13% → 5.13%**，
  `Supervisor` 1.2% → 0.967% ⇒ 交付里确实有一大块是"每帧反射"，但**剩下仍有 ~6ms/帧**。
- **下一步**：把 `callOnLocationChanged` 内部再分段（`buildFrame` / 监听器循环 / GNSS 推送），
  一次重启周期就能读出这 6ms 归谁。**在那之前不动手**（本轮已经因为"看起来贵"猜错两次）。

## 6.1 原始记录（保留）

## 6. 原始判断（已被实测修正）

- **实测**：会话开 + 摇杆持续移动时，`MotionClock` 占 **8.1% 单核**（30s 窗口 244 jiffies）。
- **已知不在这里**：`deliverFrame` 的反射查找只值 **13%**（9.3% → 8.1%）—— 我先前"反射是主因"
  的判断被这次实测否掉了（见第 0 节的更正）。
- **剩下三个候选**：① `injectLocation` 每帧每应用的重活（含 P0 的反射、Bundle 复制、抖动计算）；
  ② `applyMotionCoordinate` → `updateCoordinate` → `recordCoordinateChange`（Kotlin 世界记录 + 原生）；
  ③ `StaminaRuntime.tick` 的每拍分配（`sanitized()` 数据类复制、窗口 `Pair` 装箱、线性求和）。
- **下一步**：给 `MotionClock.beat()` 加**分段计时**（每段累加纳秒，Test 页显示），
  一次重启周期就能读出哪一段吃掉几毫秒；再决定改谁。**在拿到分段数据前不动手。**

## 附：量测方法（可复现）

```bash
# 线程 CPU（jiffies，CLK_TCK=100；30s 窗口 ⇒ 百分数 = 差值/30）
adb shell su -c 'cat /proc/<system_server pid>/task/<tid>/stat' | awk '{print $14+$15}'
# 找模块线程
adb shell su -c "grep -l Portal /proc/<pid>/task/*/comm"
# 停摆状态（应停在 futex_wait）
adb shell su -c 'cat /proc/<pid>/task/<tid>/wchan'
```
