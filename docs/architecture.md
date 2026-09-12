# PortalEX 架构说明

> 本文只写"读完代码才能知道的结构与不变量"。具体数字**都标了出处**（代码位置或常量名），
> 改动代码时请同步核对本文 —— 这是这份文档唯一的维护要求。

## 1. 进程拓扑（理解一切的前提）

PortalEX 是**一个 APK 两个身份**：既是用户界面（`:app`），又是 LSPosed 模块本体（`:xposed`）。
注入后同一份 Kotlin 代码会同时活在多个进程里：

```
        ┌──────────────┐   sendExtraCommand("portal", <key>, extras)   ┌────────────────┐
        │  app 进程     │ ───────────────────────────────────────────▶ │  system_server │
        │  (UI + 镜像)  │ ◀─────────────────────────────────────────── │  (权威副本)     │
        └──────────────┘           回包 Bundle（同一条 extras）          └────────────────┘
                                                                              │
                          被作用域的应用 / fused / phone / systemui ◀──────────┘
                          （模块代码在这些进程里只做各自那一层的 hook）
```

- `FakeLoc` **不是单例**：每个进程各有一份副本。
  **权威副本在 system_server** —— 所有注入决策读的都是它。
  这一份状态按职责拆成三层（公开名字仍是 `FakeLoc.xxx`，`FakeLoc` 只是门面）：
  · `utils/LocConfig.kt`（164 行）= **配置面**：开关与可调参数，只在握手/改设置/命令下发时写；
  · `utils/VirtualWorld.kt`（377 行）= **世界状态 + 生成器**：坐标/速度/朝向/游走相位，每帧都在变；
  · `utils/FakeLoc.kt`（238 行）= **门面** + 纯函数（GNSS extras 改写、`WorldMath` 转发）。
  拆分的意义：配置与世界状态的生命周期完全不同，混在一个对象里读代码分不清"这是用户设置"
  还是"这是本帧状态"；门面保证所有调用点（hook/设置页/原生诊断）一行都不用改。
- app 侧那份只是"镜像"：`mirrorLocal()` 写镜像、`push()` 才下发
  （`service/ConfigSync.kt`，**下发的唯一出口**）。
- 因此历史上一大类 bug 都是"两个副本不一致"：改字段只改了本进程、或系统进程重启后配置丢失。

## 2. 跨进程协议

契约的**唯一事实来源**是 `utils/PortalProtocol.kt`：

| 命名空间 | 数量 | 说明 |
|---|---|---|
| `Cmd` | 29 | `sendExtraCommand` 的第二个参数（命令名） |
| `Key` | 37 | extras / 回包里的字段名 |
| `Pref` | — | 模块与 app 共读的偏好键（文件名 `portal`） |

规矩：新增/改名**只改这个文件**，两侧引用常量 ⇒ 编译器替你抓漏；
`PortalProtocolTest` 把值本身钉死（改名必须是一次显式改动）。
`is_start` / `is_gnss_start` / `is_wifi_mock_start` 三个名字既是命令又是回包键 —— 用对命名空间。

**命令通道的门禁**（`RemoteCommandHandler`）：`sendExtraCommand` 是**任何应用都能打**的入口，
所以入口先判调用者 uid（只放行模块自身），代理通道另判"调用者必须是 system_server"。
被拒的请求计入 `PortalDiag.COMMAND_REJECT`。

## 3. 位置模拟

- hook 面铺在 `LocationServiceHook`（位置服务出口）与各厂商/第三方进程的 hook 上；
  核心手法是"在服务出口改写 + 主动定时回推"（`callOnLocationChanged`）。
- `LocationServiceHook.onService()` 已收成**一条 9 段的调用链**（60 行）：每段是 object 内一个
  private fun（`hookLastLocation` … `hookVendorControllerPackage`），函数开头有**分段地图**。
  ⚠️ 同一方法上的多个回调**按注册顺序执行**（XposedBridge 语义）⇒ 调用行的先后不可重排。
- 语义基线：**允许并注入**（不再"吞掉注册/请求"）——"注册成功但永远没有回调"是真机上不存在的
  异常态，本身就是特征。

### 3.0 融合定位（fused）的处置：三态互斥

`FusedMode`（`utils/FusedMode.kt`）三态，设置页是互斥滑块；默认 **伪装**：

| 模式 | 行为 | 说明 |
|---|---|---|
| 拒绝(0) | 报 `isProviderEnabled("fused")==false` + 拦它的 `sendExtraCommand`/批量注册 | 把系统能力报成不可用，本身是一种可观察差异 |
| 放行(1) | **完全不碰融合结果** | 不推荐：融合用真实 WiFi/基站算出的位置会原样交给应用（"被拉回"） |
| 伪装(2) | 让融合照常跑，结果在出口被改写成模拟位置 | 即「拦截-修改-转发」，见 `BaseLocationHook.injectLocation` |

- 三态只在**一个**地方判：所有改写路径（provider 层 / `LocationResult` 容器 / 融合进程内
  `chooseBestLocation` / `BlindHook`）最终都汇到 `injectLocation`，"放行"在那里按
  `provider == "fused"` 直通即可。
- **无融合定位的机型**：设置项整块禁用（App 用 `get_fused_state` 查询系统侧的
  `FusedStatus.available`）；各族 hook 缺类一律静默跳过（见 3.2）。
- 调试模式打开时，模块会打一条 `融合定位 hook 状态：available=… chooseBest=… child=… blindMethods=… mode=…`
  （`FusedStatus.logIfDebug`），设置页同一行也显示这条状态。

## 4. 传感器模拟：两条投递路径

```
                   ┌── poll 路径：libsensorservice.so 的 poll/pollFmq vtable 出口（改指针）
   虚拟世界生成器 ─┤     走这条的：3 值类型（加速度/磁场/方向/陀螺/重力/线性加速度）
   (virtual_world) │     理由：精度字段 data[3] 只有自造 sensors_event_t 才塞得进
                   └── 运行时通道：registerRuntimeSensorNative + sendRuntimeSensorEventNative
                         走这条的：其余类型（含步数两条流，可切换）
```

- **为什么必须两条**：poll 的节拍受 HAL 轮询支配（只订 on-change 时投递近乎停滞，实测丢包 1:38）；
  运行时通道由我们自己的时钟推进（Java 侧 5ms 泵 + native 帧接口）。
- **互斥**：运行时通道就绪 ⇒ poll 出口只压制不注入；未就绪 ⇒ 回退 poll 注入，功能不失效。
- **静默**：框架 dump 说没人订阅（`active=0`）⇒ 该类型不出数据；
  速率与批量跟随框架**采用值**（`selected`），而不是应用的原始请求。
- **载体隐身**：载体传感器用 `deviceId=0x0FA0` / `type=0x10000`（非 0，避免现身客户端列表）。

### 关键常量与不变量

| 项 | 值 | 出处 |
|---|---|---|
| 生成栅格 | 2.5 ~ 10 ms（有采用值时可低至 2.5ms） | `virtual_world.c` `MIN_TICK_NS` / `TICK_NS` |
| 延迟队列（两个消费者交接） | 96 条 | `DEFER_CAP` |
| 步事件队列 | 64 条 | `STEP_QUEUE_CAP` |
| 单次追赶上限 | 200 拍（≈2s） | `MAX_TICKS_PER_CALL` |
| 时间戳抖动 | 周期的 ±5%，钳 150µs~2ms，**严格不越过 now** | `jitter_ts` |
| 步态波形 | 一个周期 = **9 步**（"波长 ×3"两次迭代而来） | `vw_gait.c` `g_gait_steps` |
| 噪声档 | 逐轴 σ（20 槽：σ 与陀螺零偏）+ 高丝动态值 | `vw_noise.c` / `SensorNoise.kt` |
| install offsets | 7 项（relro 2 + poll 4 + enableDisable 1） | `InstallOffsets.kt` / `vw_internal.h` |
| 载体 | deviceId `0x0FA0`、type `0x10000` | `SystemRuntimeChannel.kt` |

### native 分层

`portal_sensor.c`（JNI + vtable patch + poll 出口）· `virtual_world.c`（时间轴/通道表/生成器/事件填充）
· `vw_gait.c`（步态与朝向模型）· `vw_noise.c`（噪声档）· `vw_rand.c`（PRNG）· `vw_internal.h`（内部接口，
**世界锁只有一把**）。

## 5. 可观测性（本项目的"测试"）

真机上的验证手段按可信度排序：

1. **host 测试**：`sh scripts/test-all.sh` —— 虚拟世界的投递不变量（时间戳单调不越界、
   静默规则、记账守恒、延迟队列上限、噪声统计）+ `:xposed` 纯函数单测 + `:app` 序列化契约。
2. **Test 页**（应用内）：system_server 侧状态（意图步频 vs 实际步频、速率提示、注入计数、
   **静默失败计数**）。
3. **探针**：`/data/local/tmp/sensorprobe`（19s 窗口的 σ/rangeZ）；门禁探针
   `~/.dsh/workspace/portalex-gate-probe/`（第三方 uid 视角验证命令通道不可越权）。
4. **唯一可靠的判据**：**改一个参数，看数据是否随之变化**。日志在本机不可靠
   （应用日志被 ROM 过滤），"看起来对"不算证据。

**静默失败记账**（`PortalDiag`）：22 处"本该静默降级"的失败点各记一笔，经
`get_sensor_status` 暴露到 Test 页；全零显示 `none`。这条是为了让"降级运行"与"一切正常"
在界面上可区分 —— 本项目最容易出、最难查的一类故障正是"错了但不报错"。
