# 传感器/位置模拟的统一架构（迁移方案）

> 状态：**步骤①+② 已落地（2026-09-16，提交见下）**；③④ 待做。本文是执行依据 —— 每轮改动
> 照这里的顺序与判据走，不靠对话记忆。第二段"迁移前现状"是既成事实，后面是待办与已落地记录。

## 一、目标（用户口径）

- **App 只当遥控器**：下发参数与数据、读回状态，**不持有任何模拟状态、不推进任何模拟逻辑**。
- **system_server 的钩子层是唯一的模拟世界**：体力、步频/步态、传感器事件、位置交付全在同一进程里，
  **钩子之间直接共享状态**（同一把世界锁 / 同一份结构），从而
  **消除跨进程通信开销与同步问题**。

## 二、迁移前现状（2026-09-16 实测）

| 谁 | 持有什么 | 位置 |
| --- | --- | --- |
| App | 体力状态机 + 参数、**路线/摇杆推进引擎**、每拍位移缩放 | `StaminaController`、`MockServiceViewModel.ensureMotionLoop` |
| system_server | 由交付位移反推速度、由速度推步频、native 世界合成全部传感器事件、位置交付改写 | `BaseLocationHook.injectLocation`、`BinderSensorMock` + `libportalsensor.so` |
| 交界 | `PUT_CONFIG`（50ms 推状态：速度/朝向/步数/开关/噪声档/步频倍率）+ 位置写回 | `ConfigSync` ↔ `RemoteCommandHandler` |

**问题**：同一个"世界"被切成两半 —— 体力在 App（受 Activity 生命周期影响，进程冻结就"停表"），
速度/步频在系统侧（只能**从位移反推**）。两边靠 50ms 的快照与"位移"这根细线同步。

## 三、目标架构

```
system_server（唯一世界，钩子间进程内共享）
  LocConfig + native 世界
    ├─ 体力状态机 + 参数         ← 本方案新增（原在 App）
    ├─ 路线/摇杆推进引擎         ← 本方案新增（原在 App 的 MockServiceViewModel）
    ├─ 步频/步态/传感器事件合成   ← 已有（BinderSensorMock + .so）
    └─ 位置交付改写              ← 已有（BaseLocationHook.injectLocation）
        ↑ 参数/路线数据（遥控指令）      ↓ 状态回读（status / remote prefs）
App（遥控器 + 普通客户端）
  · 设置页/体力页：写参数、显示回读状态
  · 路线编辑：产出路线数据并上传
  · 地图/标记：**像普通应用一样订阅 LocationManager**（顺带自证注入生效）
```

**一条硬约束（设计推出来的）**：位置流只能在**生成它的地方**被缩放，否则 App 的推进与交付流会越差越远、
路线终点永远走不到。所以"App 只当遥控器"必然要求**推进引擎也搬进系统侧** —— 这一步不能省，
省了就只能退回"App 执行器 + 模块状态"的折中（那仍是两层）。

## 四、迁移顺序（每步都可独立验证）

1. **体力进模块** ✅（2026-09-16 落地，与步骤 2 同一提交）
   - `LocConfig.staminaWire` 存参数；模块侧 `StaminaRuntime`（持 `StaminaModel`）由模块时钟 `MotionClock` 推进。
   - 协议：`PUT_CONFIG` 的 `stamina_config` → `StaminaRuntime.applyWire`；`get_stamina` 回传
     `stamina_percent/multiplier/resting/remaining_sec/blend/rest_count/moved_ago_sec/...`，
     `get_sensor_status` 一并携带（诊断页）。
   - App：`StaminaController` 变瘦客户端（写参数 → `ConfigSync` 下发；读状态 → `get_stamina`），
     `model`/`tick`/`noteMoved`/`takeMovedMeters`/`speedMultiplier` 全部删除。
   - 判据：体力页数值与模块回读一致（页面在回读失败时显示"状态不可读"而非假数据）；
     **杀掉 App 再开，体力不回满**（状态不再挂在 App 生命周期上）。
2. **推进引擎搬进模块** ✅（2026-09-16 落地）
   - App 上传**展开后的播放路径点**（`set_route`，平滑段的贝塞尔采样仍由路线编辑器产生，它是编辑产物）；
     播放开关 `route_control`；摇杆意图 `set_rocker`（只给方向与激活态，**不给位移**）。
   - 模块侧 `MotionEngine` 按 `速度 × 体力倍率 × Δt` 推进世界位置并给出本拍朝向；
     `MotionClock`（50ms 拍）写坐标 → 投递帧（按 `report_duration` 节流）。
   - 删除 `MockServiceViewModel` 的 `ensureMotionLoop`/`advanceRockerMove`/`advanceRoutePlayback`
     与全部弧长状态；`MockKeepAliveService` 的理由改为"无人值守的**收尾**"（提示音/状态收回），
     不再是"钉住 App 以维持推进"。
   - 判据：位移与交付流**同一处生成**（缩放就在推进公式里），不存在两套口径。

> ⚠️ **与原 §7 的一处偏差（已实测确认必要）**：原计划"App 继续按名义速度推进、模块在
> `injectLocation` 缩放交付位移"落不了地 —— 交付的是**绝对坐标**，那里没有"本次交付的位移"
> 可缩放；硬做只能加一个滞后积分器，路线永远走不到终点（App 的弧长进度到终点、交付流还差一截）。
> 所以推进与缩放**必须同处一个提交**一起搬（用户裁决：一刀搬全）。
3. **App 改读位置** ✅（2026-09-16 落地）
   - 新增 `service/PortalLocationClient`：App 直接 `requestLocationUpdates(GPS)`，与普通客户端同一入口
     （owner 粒度多页共享、`reportDuration` 作为订阅间隔、诊断含"与模块回读差"）。
   - Home / RouteEdit 两页的"我的位置"改由它驱动（`BaiduMapViewModel.applyFix` 统一 WGS84→GCJ02 换算）；
     百度 SDK **只留取城市名**（黑盒融合引擎与注入链不是同一来源，拿它画点等于让地图说谎）。
   - 判据：App 地图上的点与第三方应用收到的点位**逐点相同**（同一 tick 的注册拿到同一个注入对象）；
     Test 页新增"位置订阅"一行作为现场读数。
4. **收敛清理**
   - 删掉 App 侧遗留的模拟状态与重复公式；协议键数断言（`PortalProtocolTest`）同步。
   - 诊断：Test 页三处（`注入计数（原生层）`、`框架(请求/采用)`、`步频：意图 vs 实际`）在会话运行时读数。

## 五、风险与边界

- **模块未装载**（无 LSPosed）：体力等一切模拟没有落脚点 ⇒ 页面显示"未生效"，
  而不是像今天在 App 进程里空转。这条要写进 UI 文案。
- **协议面扩大**：体力参数 + 路线数据都要过 `PUT_CONFIG`/遥控命令 ⇒ 键数断言与两侧解析必须同时改
  （历史上漏改会静默失效）。
- **迁移期双写**：步骤 1–2 之间会出现"模块有体力、App 还在推进"的过渡态 ⇒ 必须**同一提交内**
  完成"谁推进、谁缩放"的切换，不能留半截（否则位移与速度会打架，正是本方案要消灭的那类问题）。
- **回滚点**：迁移前状态 = `e4e960d`（栅格已移除、体力在 App）。任何一步出问题都可回到它。

## 六、验收清单（全部达成才算完成）

1. ✅ `sh scripts/test-all.sh` 全绿（host 不变量 + 两个 JVM 模块；新增 `MotionEngineTest` 钉住
   "位移 = 速度 × 倍率 × Δt"与路线几何）；
2. ✅ 真机 2026-09-16（OnePlus ACE5 / 模块已在 system_server）：Test 页新增"体力（系统侧状态机）"
   与"推进（系统侧引擎）"两段，会话中读数为 `时钟=在跑 上报=100ms`、`推进=rocker/idle`；
   摇杆按住 60 秒：位移 232m 北 + 122m 东 ≈ 4.03 m/s（设定 4.00）⇒ 位移 = 速度 × 倍率 × Δt 成立。
   ⏳ 步频"意图 ≈ 实测"仍需一次**不含 UI 跳点**的干净长跑复核（本轮步数累计 258 步/≈65 秒，
   与公式在 4 m/s 下的 207 步/分同量级，但中途有页面跳点带来的速度尖峰，不作为判据）。
3. ✅ 体力延续：摇杆跑到 85.3% → `am force-stop` + 重开 App → 同一会话仍在跑、体力 **85.1%**
   （旧实现在这里必然回满 100%）；App 侧镜像坐标也随回读连续（`23.527406`）。
4. ✅ 位置：App 与第三方客户端走**同一入口**（`PortalLocationClient` 订阅 `LocationManager`）。
   实测（2026-09-16 真机）：App 自己的订阅收到 `23.527401,113.607734`，同一时刻模块世界点
   `23.527406,113.607736`（差 ≈0.6m，即注入帧的保护性抖动，不是误差）；空闲时 ~1Hz 持续到帧
   （33 帧 / ~25 秒），说明"普通客户端视角"拿到的是被注入链改写后的帧。
   ⏳ 地图蓝点的**目视**核对还没做（脚本点不到浮动工具栏的"镜头跟随"，需要手点一下把镜头移到该点）；
5. ✅ App 侧代码里**不再有**任何模拟状态机与推进逻辑 —— 体力模型/推进引擎已清空，
   仅剩路线**编辑**（展开成采样点，见 `MockServiceViewModel.buildPath`）与显示/收尾。

## 七、步骤①+② 的落点清单（2026-09-16 已落地，供复核）

**模块侧（新增）**
- `utils/MotionEngine.kt`：路线数据 + 推进状态（纯数学，无 Android 依赖 ⇒ JVM 可测）。
  `setRoute/setPlaying/setRocker/beat/status/takeCompleted/stopSession/reset`；
  `beat` 返回 `Step(moved, meters, lat, lon, bearing)`，`meters` 是**本拍真实推进量**（末拍截断）。
- `utils/StaminaRuntime.kt`：持 `StaminaModel`，`applyWire/multiplier/tick/reset/writeStatus`。
- `hooks/MotionClock.kt`：**模块唯一节拍**（50ms）。驱动 `MotionEngine.beat` → 写坐标
  （`RemoteCommandHandler.applyMotionCoordinate`）→ `StaminaRuntime.tick` → 按
  `LocConfig.reportDurationMs` 出帧（`LocationServiceHook.callOnLocationChanged(force = true)`）。
  只在 system_server 起拍；`Cmd.START`/`STOP`/`PUT_CONFIG(enable)` 与它同生共死。
- `BinderSensorMock.fillStatus`：追加 `stamina`（人读一行）与 `motion`（推进/时钟/上报间隔）。

**协议新增**（`PortalProtocol`，键数 41 → 63、命令 30 → 36，`PortalProtocolTest` 同步）
- 命令：`set_rocker`、`set_route`、`route_control`、`get_motion`、`get_stamina`、`reset_stamina`。
- 键：`report_duration`、`route_lat/lon/travelled/distance/points`、`motion_mode/playing/completed`、
  `stamina_*`（12 项状态回读）。

**App 侧（变瘦）**
- `MockServiceViewModel`：不再推进任何东西。遥控循环只在**内容变化时**下发
  （路线上传一次、播放开关翻转、摇杆方向变化 >0.5°），另以 4Hz 回读 `get_motion`
  （进度 / 播完收尾 / 坐标镜像）。
- `StaminaController`：瘦客户端（参数落库+下发、状态回读、重置下发）。
- `MockServiceHelper`：新增 6 个发送/查询口；`move`/`setBearing` 两个发送口因无人调用而删除
  （模块侧 `Cmd.MOVE`/`SET_BEARING` 保留，兼容旧版 App）。

**剩余（③④）**
3. App 地图/标记改订阅 `LocationManager`（普通客户端视角），路线进度按交付进度显示。
4. 清理与协议键数断言（已部分完成）、真机验收（六节清单 2–4 项）。
