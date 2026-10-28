# 移除传感器监督线程：可行方案与边界

> 状态：**已实施，待真机验证**（2026-10-28）。`PortalSensorSupervisor` 已删除；
> 状态推进改由投递泵在每轮取帧前调用 `beforePump`，会话/配置变化时显式调用一次。
> 真机验收项见第 4 节。

## 1. 监督线程现在做什么

`BinderSensorMock.supervisorLoop` 是一个 50ms 节拍的 `Thread.sleep` 循环（空闲时 1Hz），
`tick()` 里承担五件事：

1. **会话生命周期**：首次 tick 把步数计数器锚到真实值、装载 handle 表、`setActive(true)`；
2. **运动学快照**：`averageSpeedOverWindow(1s)` + `processedBearing()`；
3. **步数积分**：按 `cadenceForSpeed(实测速度)` 把时间积成整步；
4. **状态下发**：`BinderSensorNative.updateState(speed, bearing, moving, steps, now)`；
5. **速率提示**：native 脏位 / Java 订阅钩子触发时 `pushHints()`。

成本实测：移动时约 **0.68% 单核**；静止时线程每 50ms 醒来 early-return，约占 **0.1%**。
它不是最大的成本（投递约 1.2%，sensor pump 约 1.0%），但它是"纯轮询"形态的最后一块。

## 2. 为什么不能简单删掉

- `updateState` 必须在**每次原生取帧之前**把最新速度/朝向/步数写进 native 世界；
  删掉监督线程而不换驱动源，传感器会停在最后一帧的状态。
- 静止时也要有 5Hz 朝向更新（摆动的半周期 0.35~0.75s），否则指南针会冻结。
- 会话启动要在**第一条事件之前**完成激活与计数器锚定，不能等第一次运动。
- 步数积分依赖真实 `dt`；用"每次取帧"驱动，dt 会随客户端请求频率变化，需要钳位与补算规则。

## 3. 建议方案：由"取帧需求"驱动，而不是由定时器驱动

```
应用订阅（enableDisable）
        │  native 脏位 / Java 钩子
        ▼
SystemRuntimeChannel.pump(now)  ── 有到期事件 ──► BinderSensorMock.beforeFrame(now)
        │                                              │
        │                                              ├─ 速度/朝向快照（按 now-lastTick 积分）
        │                                              ├─ 步数积分
        │                                              └─ updateState(...)
        ▼
native runtimeFrame(now) 生成事件
```

要点：

1. **删除 `supervisorLoop` / `ensureSupervisor` / `wakeSupervisor` / `park`**，
   把 `tick()` 拆成 `onSessionStart()`、`beforeFrame(now)`、`onSessionStop()`、
   `onConfigChanged()` 四个入口。
2. `beforeFrame(now)` 由 `SystemRuntimeChannel.pump()` 在**每次实际要生成事件前**调用；
   没有订阅者/没有到期事件时它根本不会被调用 —— 这是"无需轮询"的落点。
3. `onSessionStart()` 从 `RemoteCommandHandler.START` 调用：锚定计数器、`setActive(true)`、
   装载 handle 表；`onSessionStop()` 从 `STOP` 调用 `deactivate()`。
4. 静止时的朝向：`pump()` 仍会按客户端请求的速率出事件（指南针 66.7ms），
   `beforeFrame` 顺带更新 `processedBearing()`；没有订阅者时没有必要更新。
5. 速率提示：`beforeFrame` 里读 native 脏位（P1 已实现），脏了才 `pushHints()`；
   不再需要独立线程叫醒。
6. 配置变化（`onConfigChanged`）直接调用一次 `beforeFrame` / `updateState`，
   保证"改完速度但还没动"时原生世界也是新值。

## 4. 风险与验证

| 风险 | 说明 | 缓解 |
|---|---|---|
| 静止但有人订阅 | 指南针/加速度仍要更新；`pump` 有到期事件才会调 `beforeFrame` | 首次订阅时强制 `beforeFrame` 一次；用 Test 页 `Δt/回调` 验证 |
| 步数积分 | 取帧间隔可能 66ms/200ms/1s 不等 | `dt` 钳位 0~5s，按累计时间补整步（与现有 `stepFraction` 同口径） |
| 会话启动竞态 | 激活必须早于第一帧 | `START` 命令内同步调用 `onSessionStart()`；失败则回退旧监督线程一个版本 |
| 客户端全部退订 | 没有取帧 → 不更新状态（正确，但恢复订阅时需要一次即时快照） | `enableDisable` 脏位回调触发一次 `beforeFrame` |
| 兼容旧 .so | 旧库没有 `takeSubscriptionDirty` | P1 已有 runCatching 降级；P5 不依赖脏位也能工作（只是速率提示退回周期） |

验收（真机）：

1. `sh scripts/test-all.sh` 全绿；
2. 移动 30 分钟：步频意图 vs 实际、`Δt` 分布、`同刻/跳值` 计数与改动前一致；
3. 静止 10 分钟：指南针仍在摆动；`PortalSensorSup` 线程消失；
4. 会话停止/重开 50 次：步数基线单调、无补发爆发；
5. CPU：`PortalSensorSup` 的 ~0.68% 应分摊进 `PortalSensorPum`（总 CPU 不应上升）。

## 5. 建议顺序

1. 先落地并真机验证 P1（native 脏位）——它是 P5 的事件源之一；
2. 把 `tick()` 拆成四个入口，**保留**监督线程但让它只做 `beforeFrame`（加开关，默认走旧路径）；
3. 真机 A/B 通过后，删除线程与 park/wake；
4. 失败回滚：保留 `BinderSensorMock.legacySupervisor=true` 开关一个版本。

在拿到第 2 步的真机数据之前，不建议直接删线程——这条链最贵的故障是"数据静默消失"，
而不是 CPU。
