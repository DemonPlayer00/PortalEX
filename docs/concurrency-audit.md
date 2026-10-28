# 并发原语与性能：审计与优化方案

> 口径沿用 [`perf-audit.md`](perf-audit.md)：**先量后改**。本文回答两个问题：
> ① 现有并发原语里哪些是必要的、哪些是过度设计；② 在不改变交付语义的前提下，
> 哪些改动真的能降 CPU / 降抖动。没有频率与量级依据的"优化"不列入实施项。

## 0. 结论先行

1. **原语数量不是问题，锁的持有范围才是。** 全仓 `@Volatile` 约 220 处、`synchronized` 约 105 处，
   但 system_server 侧只有 3 条模块线程（`PortalMotionClock`、`PortalSensorSupervisor`、
   `PortalSensorPump`）加若干 binder 回调线程。低竞争场景下，`synchronized` 的重量级路径
   （偏向锁撤销后的一次 CAS）远比一次位置投递便宜。
2. **当前最大的成本不在锁**。分段实测（`perf-audit.md` §6）显示：移动时每帧约 5~6 ms CPU，
   其中"监听器循环"约 5.2 ms；推进、体力、落点合计不到 0.3 ms。**先去交付路径省钱，
   再谈换锁。**
3. **最值得改的三处**（按收益/风险排序，2026-10-28 已落地前两项 + 生成器锁）：
   - `MotionClock` 的固定 `sleep(50ms)` 改成**绝对截止时刻**调度（消除累积漂移与配速尖峰，低风险）；
   - `MotionEngine.setRoute` 把**路线展开/全长计算移出锁**（长路线时锁内可达毫秒级，会阻塞拍子，低风险）；
   - `VirtualWorld` 的朝向/速度/位置抖动生成器**整段进锁**（消除 `processedBearing()` 的跨线程
     读改写竞争），并删除 13 个随之冗余的 `@Volatile`；单写者快照仍是后续可选项。
4. **不建议**为了"减少原语数量"把 `@Volatile` 降级成普通字段，或把 `synchronized` 换成
   `AtomicReference` 却仍在锁内做重活 —— 那只会把可见性问题变成偶发 bug。

## 1. 线程与数据归属

| 线程 | 频率 | 写什么 | 读什么 |
|---|---|---|---|
| `PortalMotionClock` | 50ms/拍，空闲停摆 | 世界坐标、`MotionEngine` 进度、体力 | 配置、路线、摇杆意图 |
| ~~`PortalSensorSupervisor`~~（已移除） | — | — | — |
| `PortalSensorPump` | 事件驱动（`nextDueNs`） | 运行时传感器事件队列 | native 通道状态 |
| binder 回调（多条） | 命令/查询时 | `LocConfig`、路线、会话开关、`randomKey` | 状态回读、门禁 |
| 相机线程 | 仅相机模拟期间 | 帧/录制状态 | 选择器状态 |

共享对象只有四个：`LocConfig`（配置）、`VirtualWorld`（世界 + 生成器）、`MotionEngine`（路线推进）、
`NetworkSimConfig`/`StaminaRuntime`（低频配置）。**锁应围着"状态所有者"设计，而不是围着字段设计。**

## 2. 现状逐项判定

### 2.1 `MotionEngine`：16 处 `synchronized`，但不是热点

- 所有入口共用一把 `lock`。锁内除了推进数学，还包括 `setRoute` 的
  `computeTotalMeters()` + `ensureExpandedTo(0.0)`，以及 `beat()` 的按需展开
  （`ensureExpandedTo` + `indexAt` 二分 + 插值）。
- 实测推进本身 **58 µs/拍**（`perf-audit.md` §6.1），所以常规拍子的锁竞争可以忽略。
- **真正的风险在长路线**：`setRoute` 在锁内做全长估算（平滑段按 16 点采样），
  一条几十公里的路线可能达到毫秒级；这段时间 `beat()` 与 App 的 `get_motion` 都会等锁。
  表现不是"慢"，而是**一次可观测的配速台阶**。
- `status()` / `travelled()` / `takeCompleted()` 每次回读都进锁；App 4Hz + 命令回包，
  频率低，不构成问题。

**结论**：这里的目标不是"去锁"，而是"**锁内只做状态变更，不做几何展开**"。
2026-10-28 已把校验、副本构造、`computeTotalMeters()` 移到锁外；锁内只剩
数组发布与"放起点"（代价与路线长度无关）。可选终态是单写者模型，收益主要是可推理性，不是 CPU。

### 2.2 `VirtualWorld`：生成器曾有多字段读改写竞争（已修）

这是全仓**唯一一处既有性能形态问题、又有正确性风险**的地方：

- 世界坐标/速度/朝向目标用 `@Volatile`，单字段读没问题；但**多字段一致性**没有保证
  （一帧里可能读到新纬度 + 旧经度），目前靠"50ms 才变一次、误差在米级"兜着。
- `processedBearing()` 对 `centerBearing` / `microOffset` / `lastBearingSampleNanos` 做
  **读-改-写**，而它同时被位置投递线程（约 10Hz）与传感器监督线程（20Hz，经
  `sampleBearingJitter()` 读 `microOffset`）调用；`advanceSway()` 还一次性改 5 个字段。
  这是**数据竞争**（不是性能问题）：极端时序下会丢更新或让摆动相位跳变。
- `speedFloorSample()` 的 `speedFloorSmoothed/Target/lastNanos` 是普通字段，无锁、无 volatile；
  `speedOffsetSample()` 只锁了 `speedFastOffset`，`lastSpeedNoiseNanos`/`speedBiasFactor` 在锁外。
- `moveSamples` 的锁是对的（`averageSpeedOverWindow` / `recordCoordinateChange` 都进
  `synchronized(moveSamples)`），这里不用动。

**处理（2026-10-28）**：三组生成器（朝向、保底速度、位置抖动）各用一把私有锁包住
完整的读改写；`speedOffsetSample()` 的会话沿/时间戳/偏移量整段进锁。随锁提供的
happens-before，删掉了 13 个不再需要的 `@Volatile`（20 → 7），保留的 7 个都是
真正跨线程、且不共享同一把锁的世界坐标/速度/朝向目标字段。
世界坐标的"多字段一致快照"仍是后续项（要改调用面，收益是语义一致性，不是 CPU）。

### 2.3 `LocConfig` / `NetworkSimConfig`：18 + 15 个 `@Volatile`

- 读频率是"每帧 × 若干字段"，写频率是"用户改设置/握手"。
- 单次 volatile 读在 ARM64 上就是一次 `ldar`，**不是**性能瓶颈；真正的问题是
  **一次计算里读了配置的不同版本**（例如速度用了新值、体力倍率用了旧值）。
- 若要做一致性，正确形态是**不可变配置快照 + `AtomicReference`**：写入方构造新对象，
  读方每帧取一次引用。字段访问从 N 次 volatile 读变成 1 次；语义从"逐字段最新"
  变成"每帧一致" —— 后者才是模拟器想要的。

### 2.4 集合与命令通道

- `RemoteCommandHandler.proxyBinders` 用 `Collections.synchronizedList`、
  `warnedDeniedUids` 用 `Collections.synchronizedSet`：写入是"注册代理/首次拒绝"级别，
  读多写少，`CopyOnWriteArrayList` / `ConcurrentHashMap.newKeySet()` 更合适，
  但收益是代码意图清晰，不是吞吐。
- `randomKey` 原先缺 `@Volatile`（写于 `exchange_key` 的 binder 线程、读于门禁线程），
  已在本轮补上；这是**可见性 bug**，不是性能项。
- `StepProbe` / `StepTickProbe`（App 侧）各有 11~12 处 `synchronized`，但只在诊断页
  采样期间运行，不影响主路径。

### 2.5 native `virtual_world.c`

- 一把 `g_lock` 覆盖生成器、通道表、延迟队列；锁区间从几行到整段 `vw_generate` 不等。
  目前没有锁竞争的实测证据（sensor poll 与运行时泵的节拍都受控）。
- 若将来要拆：优先把**只读的世界快照**（坐标/朝向/速度）改成 seqlock 或双缓冲，
  让 poll 出口不必拿世界锁；写侧仍单写者。**在拿到 `perf`/`simpleperf` 的 futex
  等待数据前不做。**

### 2.6 定时与唤醒

- `MotionClock` 已经做到"空闲停摆、意图唤醒"；传感器侧也不再有自己的线程（见 P5）。
  不要为了"统一"再引入 `ScheduledExecutorService`（多一条线程 + 队列）。
- `MotionClock.loop()` 原先睡满 50ms 再干活，**每拍的实际周期 = 50ms + 本拍工作耗时**，
  于是 `dt` 在 50~56ms 之间漂移。2026-10-28 改为**绝对截止时刻**：以"上次截止 + 50ms"
  为下一拍，睡到截止再 `beat()`；单拍超时则从当前时刻重新对齐，宁可少拍也不补拍。
  这不会改变任何推进语义（`beat()` 仍按真实 `dt` 结算），只是把周期误差压到调度器精度。

## 3. 实施顺序与状态

### P1（低风险）—— 已完成（2026-10-28）

1. **`MotionClock` 绝对截止时刻**：已落地，见 `MotionClock.loop()` 与 §2.6。
   验收：`dtMaxNanos`/`late300` 计数下降；配速曲线尖峰减少（用 Test 页的 `motion` 行）。
2. **`MotionEngine.setRoute` 锁外展开**：校验、数组拷贝、`computeTotalMeters()`（含平滑段
   16 点采样）已移到锁外，锁内只发布数组与放起点。
   验收：长路线（>10km，含平滑段）`setRoute` 期间的 `get_motion` 延迟不再出现毫秒级尖峰。
3. **`proxyBinders` / `warnedDeniedUids` 换并发集合**：未做（纯清理，收益只在可读性）。

### P2（中风险，需要真机验证）

4. **世界状态快照**（部分完成）
   - 生成器（朝向/保底速度/位置抖动）已改为整段加锁，竞争与相位跳变已消除。
   - 仍未做：`data class WorldFrame(lat, lon, alt, speed, bearing, atNanos)` +
     `AtomicReference` 发布——它要改 `FakeLoc` 的全部读取面。收益是"一帧内多字段一致"，
     不是 CPU；等有真机需求再做。
5. **配置快照**
   - `LocConfig` 的 18 个字段打包成不可变 `Config`；setter 构造新对象并原子发布。
   - 过渡期可保留 `FakeLoc.xxx` 门面，避免全仓改调用点。
6. **交付路径（最大收益，但需用户裁决语义）**
   - 事实：每帧 5~6ms CPU，随监听器数量线性增长；用户已裁决"投递保持同步"。
   - 在不改同步语义的前提下可做：每拍只 `buildFrame()` 一次并复用（现在是否每监听器重建
     需先量）、跳过已死亡/重复注册的监听器、缓存 `makeComplete` 的 Method（`perf-audit` P0 已做）。
   - 若将来允许异步投递：有界队列 + 独立投递线程 + 慢客户端超时剔除；这会改变应用看到的
     时序，**必须先问**。

### P3（等数据）

7. **native 世界锁拆分**：先跑 `simpleperf`/`perfetto` 看 `g_lock` 的 futex 等待；
   没有数据不动。
8. **JMH/host 基准**：给 `MotionEngine.beat`、`VirtualWorld` 的纯函数加微基准，
   防止"优化"把 58µs 变成 80µs；host 侧已有 `xposed/src/main/cpp/test` 的框架可复用。

## 4. 不该做的事

- 不要把 `@Volatile` 当性能问题批量删除：可见性错误的代价是"偶发不生效"，比几纳秒贵得多。
- 不要把 `synchronized` 换成 `AtomicReference` 却仍在 CAS 循环里做路线展开/几何计算。
- 不要在 `MotionClock` 每拍里加新定时器、`Handler.postDelayed` 或 `Executors`；
  现有"停摆 + 唤醒"已经是最低唤醒成本形态。
- 不要为了"看起来并发"把单写者状态拆成多线程写；这个模块的正确性依赖"世界只有一个推进者"。

## 5. 验收口径（沿用 perf-audit）

- 线程 CPU：`/proc/<pid>/task/<tid>/stat` 的 utime+stime，30s 窗口；
- 每拍分段：Test 页 `motion` 行的 `推进/体力/落点/投递` 与 `dtMax`/`late*`；
- 交付：`监听器` 段的 CPU 时间（`Debug.threadCpuTimeNanos`），不是墙钟；
- 行为：`sh scripts/test-all.sh` 全绿 + 真机 30 分钟跑动无位置断流/相位跳变。

## 6. 2026-10-28 落地记录

| 项 | 状态 | 落点 |
|---|---|---|
| P1 sensor service dump 轮询 | 已完成（待真机） | native `g_en_dirty` + `takeSubscriptionDirty`；Java 只在脏时 dump |
| P2 诊断页昂贵字段 | 已完成 | `privateTypes` / `gateSelfTest` 10s TTL；页面 2s 刷新 |
| P3 日志开关 | 已完成 | `Logger.debug/info` 自行判定；debug 默认关 |
| P4 位置投递降频 | **未改**（语义取舍） | 保持 100ms 与同步投递 |
| P5 移除传感器监督线程 | 已实施（待真机） | 状态推进并入投递泵的 `beforePump`；见 [`sensor-supervisor-removal.md`](sensor-supervisor-removal.md) |
| P6 KeepAlive / GNSS 常驻轮询 | 已完成 | 私有锁 + 事件唤醒 + 30s 兜底 |

验证口径不变：`sh scripts/test-all.sh` 全绿；P1/P6 需要真机读数（dump 次数、线程唤醒数）确认。
