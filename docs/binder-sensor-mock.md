# Binder 外周传感器模拟（实验性）

> 设置项：**设置 → Binder 外周传感器模拟**（默认**关**）。

## 是什么

在**系统框架层**伪造外周传感器数据：步频（步数计数器 / 步数检测器）、加速度
（加速度计 / 未校准加速度计 / 线性加速度 / 重力）、角度（方向 / 旋转矢量 /
游戏旋转矢量 / 地磁旋转矢量 / 陀螺仪及其未校准版）、指南针（磁场 / 未校准磁场）。

与既有的应用侧传感 hook 的根本区别：

| | 应用侧传感 hook（既有，恒装） | Binder 外周传感器模拟（本项） |
|:--|:--|:--|
| 注入位置 | 被勾选应用的进程内（`SystemSensorManager`） | **system_server（系统框架）** |
| 覆盖范围 | 仅 LSPosed 作用域内的应用 | **所有客户端**：作用域外的应用、系统应用、走 NDK `ASensorEventQueue` 的原生消费者 |
| 目标应用 hook | 需要 | **一个都不装** |
| 数据来源 | 改写真实回调（需要真实事件驱动） | **自产事件**（真实事件被丢弃，HAL 报什么不影响推送内容） |
| 开关 | 无（由作用域决定） | **按传感器类别拆成两个**，分别在「步频 Mock」与「角度和指南针 Mock」两页上，**都默认开** |

## 原理

框架把 HAL 事件分发给各客户端，路径是：

```
SensorService::threadLoop
  → mSensorDevice.poll(buf, n)              [libsensorservice.so，内联]
  → mHalWrapper->supportsMessageQueues()    [虚调用]
  → mHalWrapper->pollFmq(buf, n)            [虚调用] ← 本机（AIDL HAL / FMQ）实际走这条
    （轮询式 HAL 走 mHalWrapper->poll(buf, n)）
  → 按客户端注册情况分发到各自的 BitTube（binder 建立的事件通道）
```

我们在最后一步之前接管：把 `poll` 与 `pollFmq` 两类出口都挂上，
**丢弃被接管类型的真实事件，追加自己生成的传感器事件**。框架照常做路由、过滤、
批处理与唤醒锁管理——所以每个客户端拿到的都是"形状完全正常"的事件流。

注入的数据由模块自己生成（虚拟方位 + 步频模型，与 `SystemSensorManagerHook`、
`FakeLoc` 同一套口径：同一方位派生欧拉角 / 四元数 / 地磁矢量 / 重力 / 角速度），
因此**与底层传感器是否在工作无关**。

### 两个实现要点

**1. 符号定位走平台库自带的 mini debug info。**
目标函数（`SensorHalWrapper::poll` / `pollFmq`）是隐藏可见性：`.dynsym` 里没有，
`dlsym` 拿不到，文件也被 strip。平台库默认带 `.gnu_debugdata`（xz 压缩的一份只含
`.symtab`/`.strtab` 的 ELF），它是随**该机型固件**构建的，按名字查表得到的地址对当前
设备永远精确——既不用为每个 ROM 维护偏移表，也不用按字节特征猜代码。

解 xz 在 **Java 侧**完成（纯 Java 的 `org.tukaani:xz` 随模块 dex 进 system_server）：
本机 `/system/lib64/liblzma.so` 是 7-Zip LZMA SDK，不提供 xz-utils 的
`lzma_stream_buffer_decode`，系统里也没有别的库导出 xz 解码符号（已扫过
`/system/lib64`、`/system_ext/lib64`、ART apex），原生侧无库可用。

**2. 只改指针，不改代码，且改前必核对。**
改写的是 `.data.rel.ro` 里"正好等于该函数指针"的那些槽位（虚函数表槽）。规则：

* 来自 Java 的每个偏移必须落在模块的**可执行段**内，否则拒绝；
* 要扫描/改写的区间必须完整落在某个 **PT_LOAD** 内，否则拒绝（这条是踩过一次
  `system_server` 段错误换来的）；
* 只有当该位置当前**确实**存着目标函数指针时才写入。

于是：解析错、ROM 不同、库被换过，结果都只是"功能不生效"，不会写坏系统进程。

## 为什么不是 ioctl / 为什么不改 HAL

本机（Android 16 + 高通/OPPO）的传感器 HAL 是**独立 vendor 进程**里的 AIDL 服务
（`vendor.oplusSensor-aidl-1`），ioctl 发生在那个进程里，LSPosed 注入不到；即便能注入，
也只是把同一份数据换个地方截。框架内的这道事件出口是**上游唯一汇合点**，改这里等价于
换掉整个 HAL，而不需要内核/驱动层面的改动，也不会碰 `system_server` 以外的任何进程。

## handle 从哪来（"无数据也能推"的关键）

注入必须知道"传感器类型 → handle"。两条来源：

1. **框架自己的传感器表**（主路径）：system_server 拿系统 Context → `SensorManager`
   → `getSensorList(TYPE_ALL)` → 每个 `Sensor` 的隐藏 `getHandle()`。这是应用注册传感器
   走的同一条链，**与 HAL 是否在出数据无关**，也不需要对平台内部结构体做任何布局假设。
2. **真实事件**（兜底/校正）：事件里带 `sensor` 字段，首见即登记；如果它与 1 不一致，
   以事件为准并打告警（框架实际分发用的就是那个 handle）。

这条主路径是必须的：像**步数计数器 / 步数检测器**这种 on-change 传感器，手机不走路就
根本没有事件 —— 只靠事件学习的话，"模拟走路"时步频永远推不出去（实测：修好前
`type=18/19` 全程 0 事件）。另外步数两兄弟**不在周期通道里**（由步事件队列驱动，on-change），
早期版本忘了把它们登记进生成器通道表，生成出来的步事件 handle 是 0，同样推不出去。

刻意**不用** HAL 的 `getSensorsList()`：它返回平台内部 `std::vector<Sensor>`
（元素大小随版本变化，本机实测 104 字节而 `sensor_t` 是 96），按结构体遍历会错位、
读出垃圾 handle 并覆盖掉正确映射（实测踩到）——猜错等于把 A 传感器的数据写进 B 传感器。

## 步态：IMU 里必须"看得见在走路"

虚拟世界一开始只给加速度计一个平放常量（0, 0, 9.81）——那对"姿态一致"是够的，
但对**任何从 IMU 推算步频的应用**（带通/FFT/过零估计，是运动类应用的常见做法）等于
一条直线：估计器只能漂到一个荒谬值并锁死，也不可能跟着速度变。实测数据：
模拟走路时加速度计 `sd=0.006`、`range=0.02`，即完全没有步态信号。

现在的步态模型（真机量级）：

* **相位是"步幅相位"：一个波形周期 = N 步**，每步推进 `2π/N`。
  N 默认 **9**（原始波形是"一周期 = 一步"；用户按"波长 ×3"迭代两次得到 9），为**编译期常量**，没有任何运行时校准入口。
* **竖直**：`A·sin φ`，`A = 0.35 × v + 0.55`（封顶 3.0 m/s²）。
  **纯正弦，不做任何谐波加工**——按用户要求"只原本地延长波长"。
* **前后/左右**：`0.25·A·sin(φ + 0.05)` / `0.12·A·sin(φ − 0.05)`（三分量几乎同相 ⇒ 模长形状与 az 一致；
  相位差一大模长会出现额外过阈值，阈值类检测器多计一步——踩到过）。
* 静止（v ≤ 0.05）三项归零；`GRAVITY` 恒定 `(0,0,9.81)`，
  `ACCELEROMETER = GRAVITY + LINEAR_ACCELERATION` 严格成立（真机的物理关系）。
* **稳频**：相位由**实测步间隔**推进（首选），没有步事件时才回退到 `(60+30v)×1.15`（夹取 60~220）。
  PLL 每步把相位拉向**周期内轮转**的目标 `((k mod N)+1)·2π/N`，增益 0.02 rad（≈0.02 m/s²，低于噪声）：
  增益一大就在每个步点留下台阶，而**台阶本身会被检测器算成额外一步**（实测 180 步/分被读成 202）。
* **步事件率不变**（一步一个 counter/detector 事件，由 Java 侧按步频公式积分、≤220 步/分），
  改的只是 IMU 波形口径：`波形频率 = 步频 / N`。

历史踩坑（保留在代码注释里）：对称 `sin(2φ)`（每步两个等高峰，基频被读成 2× 步频）；
三分量相位差过大（模长出现小双峰，多计一步）；PLL 增益过大（每步留下台阶，台阶本身就是"额外一步"）。
另有一版为兼容"峰值计数"与"基频 ×2"两类估计器补过二次谐波（`0.88·sin φ + 0.72·sin(2φ+1.2)`），
后按用户要求**去掉**，只留纯正弦 —— 代价是：一个周期内只有一个正峰，纯峰值计数类估计器会读到
步频的 1/N。

实测（Android 16 + 高通/OPPO 机型，手机静置桌面，按住悬浮摇杆让模拟行走）：

| 设定速度 | IMU 基频（加速度计） | 步检测器事件率 | 理论步频 `(60+30v)×1.15` |
|:--|:--|:--|:--|
| 1.0 m/s | 1.67 Hz = 100 步/分 | 10 事件 / 6s = 100 步/分 | 103.5 步/分 |
| ~3.05 m/s | 2.95 Hz = 177 步/分 | 14 事件 / 5s = 168 步/分 | 174 步/分 |

两条独立通道（IMU 频谱 / 步事件流）报出同一个数，且都随速度变化。

**注意**：步频模型沿用 `FakeLoc.cadenceForSpeed` 的人体上限（60~220 步/分）——
速度超过约 4.6 m/s 后步频会饱和在 220 步/分（这是刻意的：人跑步的步频本来就在
180~200 附近，继续线性外推到 20 m/s 会让数据更像机器）。IMU 的**幅度**仍随速度增长。

## 投递节拍：框架的轮询由"连续型传感器订阅"驱动

这是注入架构里最容易被忽略、却直接决定应用看到什么的一条：

`SensorService` 的取事件循环由 **HAL 的投递**驱动，而 HAL 只为"当前被订阅的传感器"投递。
若目标应用**只订阅了 on-change 类型**（步数计数器 / 检测器）而没有任何连续型传感器，
框架就长时间阻塞在等待里 —— 本模块在框架层注入的事件于是只能**零星到达**。
实测（手机静置、模拟行走中，无其他订阅者）：

| | emitted | dropped | 说明 |
|:--|:--|:--|:--|
| 无连续订阅 | 2108 | **82039**（1:38） | 走动几分钟只发出 **2** 次步事件 |
| 有一个 20ms 连续订阅 | 9292（+7184/15s） | 90273 | step 计数开始增长，suppressed 同步上涨 |
| App 自带 keep-alive（本模块新增） | 35560 | **8108**（4.4:1） | 投递恢复正常 |

应用若按**到达时间**估算步频（不读事件时间戳），这种"半天来一批"就会读出离谱值
（用户报告的"稳定 277 步/分、理论 178"符合这一形态）。

**修法**：模拟会话期间，PortalEX 自己挂一个 `SENSOR_DELAY_UI`（≈16Hz）的加速度计订阅
（`MockServiceHelper.startSensorPollKeepAlive`，不取数、随会话启停）—— 把框架的轮询
撑到足够密，注入的事件平滑送达**所有**客户端，对目标应用同样有效。

## 排查页面：侧边栏 Test

`Test` 页每秒刷新一次，把两侧原始数值摊开，专门用于对照"应用看到的数"：

* **步频：意图 vs 实际** —— `意图步频`（按实测速度算）与原生层的 `step_rate`
  （近 5 秒实际发出的步事件换算）不一致，就说明问题在生成/投递环节；
  一致而应用读数仍离谱，则问题在应用侧算法。
* 注入计数：`emitted / dropped / suppressed / steps / step_rate` 与 `type→handle` 映射。
* 设定值、实测速度、移动判定、步数累计、坐标/海拔/朝向、GNSS 开关。

## 架构翻新：运行时投递通道（设计定稿）

目标：投递 **100% 由我们掌握**，彻底摆脱上面那条"框架轮询节拍"的约束；仍然只 hook
**系统框架**，一个目标应用都不碰。

结论先行：框架为"没有 HAL 背书的传感器"准备的**运行时传感器（runtime sensor）**机制
就是我们要的东西，而且 —— 这是本轮翻新的关键发现 —— **它的入口在 system_server 的
Java 层就是现成的**，整套方案因此**不需要任何原生代码，也不需要伪造对象、虚表或 RefBase**。

### 事实 1：运行时传感器有 Java/JNI 入口，且本机 ROM 确认存在

AOSP 的 `com.android.server.sensors.SensorService`（Java，系统服务）自身就带这几个
**私有静态 JNI** 入口：

```java
private static native long    startSensorServiceNative(ProximityActiveListener listener);
private static native int     registerRuntimeSensorNative(long ptr, int deviceId, int type,
                              String name, String vendor, float maximumRange, float resolution,
                              float power, int minDelay, int maxDelay, int flags,
                              SensorManagerInternal.RuntimeSensorCallback callback);
private static native void    unregisterRuntimeSensorNative(long ptr, int handle);
private static native boolean sendRuntimeSensorEventNative(long ptr, int handle, int type,
                              long timestampNanos, float[] values);
```

`SensorManagerInternal.RuntimeSensorCallback` 是**接口**（4 个方法：`onConfigurationChanged`
`onDirectChannelCreated` `onDirectChannelDestroyed` `onDirectChannelConfigured`），
所以回调可以直接用 `java.lang.reflect.Proxy` 实现 —— 不需要编译期依赖任何隐藏类。

已在**本机固件**上核对（`/system/framework/services.jar` 内的 dex 字符串）：
`com/android/server/sensors/SensorService`、`SensorService$LocalService`、
`SensorManagerInternal$RuntimeSensorCallback`、`createRuntimeSensor`、
`registerRuntimeSensorNative`、`sendRuntimeSensorEventNative` 全部存在；
`libsensorservice.so` 在本机只被 **system_server 一个进程**映射
（`grep -l libsensorservice /proc/*/maps` → 只有 system_server），
即原生 `SensorService` 就活在我们注入的进程里。

所需条件只有一个：拿到 `SensorService` 实例（读它的 `private long mPtr`）与
`mPtr != 0`（它在构造时异步启动，见 `SystemServerInitThreadPool`）。
实例有两条互为备份的获取路径：hook 它的构造函数、或
`LocalServices.getService(SensorManagerInternal.class)`（返回 `LocalService`，
再读其 `this$0` 合成字段）。

### 事实 2：投递是"广播式"的 ⇒ 用真实 handle 就能送达应用

`SensorService::processRuntimeSensorEvents()`（`RuntimeSensorHandler` 线程的循环体）做的是：

```
从队列取事件（最多 256 条，缓冲 = new sensors_event_t[256]）
recordLastValueLocked() → sortEventBuffer()（按时间戳排序）
对**每一个活跃连接**：connection->sendEvents(buffer, count, /*scratch=*/nullptr, nullptr)
```

而 `SensorEventConnection::sendEvents(buffer, n, scratch, map)` 在 `scratch == nullptr` 时
走的是**不过滤**的分支（源码 + 反汇编一致）：

```cpp
} else {
    if (hasSensorAccess()) { scratch = buffer; count = numEvents; }   // 整批照发
    else { /* 只留 META_DATA */ }
}
...
SensorEventQueue::write(mChannel, scratch, count);
```

真正的"事件属于哪个传感器"的路由发生在**客户端进程**：`SystemSensorManager` 按事件里的
`sensor`（handle）在自己的传感器表里查到 `Sensor`，再派发给为它注册的监听器；查不到就静默丢弃。

**推论（本方案的地基）**：事件里只要填**真实 HAL handle**，应用就会像收到真实事件一样收到它 ——
不需要让应用改订阅对象，不需要伪造框架的传感器表，也不需要目标应用侧任何 hook。
本模块自己的数据由我们自己填，所以内容 100% 可控。

代价（如实记录）：

* 事件会写进**所有**有传感器连接的进程（未订阅者解不出 handle，静默丢弃），
  比 HAL 路径多一份广播开销；速率越高越明显，属可接受量级，且开关可在对应功能页关闭。
* 这批事件不经 `noteOpIfRequired()`（AppOp 检查在过滤分支里）——但无权限的应用
  既看不到该 `Sensor`，也没有 `mSensorsEvents` 条目，因此实际到达不了它的监听器。
* 事件 `flags = 0`：不参与唤醒锁 / `NEEDS_ACK` 协议（不伪造唤醒事件）。

### 事实 3：必须先注册一个"载体"传感器

`mRuntimeSensorEventBuffer` 与 `RuntimeSensorHandler` 线程**只在第一次成功注册时创建**
（`registerRuntimeSensor` 里）。不注册就推事件：队列无人消费（无限增长），
且缓冲为空指针。所以启动时先注册**一个载体传感器**，它的数据我们根本不推。

载体必须**对客户端不可见**：`SensorList::getUserSensors()`（`getSensorList` 的数据源）
的过滤条件是 `!isForDebug && !isDynamicSensor() && deviceId == DEFAULT_DEVICE_ID`
—— 已在本机 `libsensorservice.so` 的 `getUserSensors` 里逐条核对反汇编
（`ldr w8,[entry+0x3c]; cbnz w8, skip`，entry 偏移：`si` @+0x28、`isForDebug` @+0x38、
`deviceId` @+0x3c），且 `RuntimeSensor::DEFAULT_DEVICE_ID == 0`
（`unregisterRuntimeSensor` 里 `str wzr,[sp]` 即为该初值）。

于是载体取：`deviceId = 0x0FA0`（非 0，且不可能是真实虚拟设备号）、
`type = SENSOR_TYPE_DEVICE_PRIVATE_BASE (0x10000)`、`flags = 0`（不能带 dynamic 位）、
`minDelay/maxDelay = 0`。它只会出现在 `dumpsys sensorservice` 里，客户端列表里没有它。

### 事实 4：绕过 Java 包装的 handle 白名单

`SensorService$LocalService.sendSensorEvent()` 会检查 `mRuntimeSensorHandles.contains(handle)`
—— 只允许推自己注册过的 handle，这与"用真实 handle 投递"冲突。
原生 `SensorService::sendRuntimeSensorEvent` 本身**不检查**（源码与反汇编一致：push 队列 + 通知条件变量）。
因此我们直接反射调用**私有静态 JNI** `sendRuntimeSensorEventNative(mPtr, handle, ...)`，
绕过 `LocalService` 那一层。这是本方案唯一一处刻意越过框架封装的地方，理由与范围都明确：
只用来推送数据，不改框架状态；`mRuntimeSensorHandles` 是 `LocalService` 自己的记账，
不参与投递路径。

### 为什么放弃"原生自建回调"那条路

上一版尝试在原生侧自己取实例、造回调对象、调 `registerRuntimeSensor`。
它崩了两次 `system_server`，现在原因已经查清（反汇编 + AOSP 源码）：

* `AIBinder_toPlatformBinder` 的返回类型是 **`sp<IBinder>`（非平凡类型）**，
  按 AArch64 ABI 通过 **x8 间接返回**。用 C 原型 `void *(*)(void *)` 调用它，
  被调方会把 8 字节写向一个**未初始化的 x8** —— 这就是修掉虚表越界之后仍然崩的原因。
  （同一轮里 `AServiceManager_getService` 在 ROM 里其实是 **extern "C" 未修饰名**，
  签名里的 `_Z25...` 长度前缀也是错的 —— 靠"猜名字"叠上"猜 ABI"，两次都猜错。）
* `registerRuntimeSensor` 的第三个参数是按值传递的 `sp<RuntimeSensorCallback>`，
  其生命周期归属（谁 decStrong）依赖编译器实现细节；自建对象的引用计数必须
  "自持"才能两种约定下都安全——这些都是可以做的，但已经没有必要做了。

Java 路径把这些全部规避：所有参数都是 JNI 平凡类型 + 一个 `Proxy` 对象，
最坏结果是抛异常/返回 false，**不存在内存写坏的可能**。

### 分层设计

```
[设置项开 + 模拟会话启动]
   │
   ├─ L1 载体引导（一次性）：反射 registerRuntimeSensorNative(mPtr, 0x0FA0, 0x10000, …)
   │     → 拿到载体 handle；副作用：分配 256×104 事件缓冲 + 启动 RuntimeSensorHandler 线程
   │
   ├─ L2 生成：原生虚拟世界/步态引擎（既有，单一事实源）
   │     新增**独立时钟驱动**（不再由 HAL poll 驱动）→ JNI 取一帧"到期事件"
   │
   ├─ L3 投递：反射 sendRuntimeSensorEventNative(mPtr, 真实handle, type, ts, values)
   │     → RuntimeSensorHandler 线程 → 广播 → 客户端按 handle 派发给应用监听器
   │
   └─ L4 隔离：既有 poll 路径**只压制不注入**（丢弃被接管类型的真实事件）
         → 应用只会拿到我们的数据，不会真/假各一份
```

* **互斥规则**：运行时通道就绪 ⇒ poll 路径停注入、只压制；未就绪（ROM 不支持、
  反射失败、`mPtr == 0`）⇒ 退回现状（poll 路径注入），功能不失效。
  因此上面那条"应用侧 keep-alive 订阅"在运行时通道生效后只是兜底路径的补丁，
  对主投递路径不再必要（保留不冲突）。
* **生命周期**：关开关 / 结束模拟时先停 L3，再 `unregisterRuntimeSensorNative` 释放载体
  （线程与缓冲留着复用，代价是一个阻塞在条件变量上的线程）。
* **全链路 `runCatching`**：任何一步失败都只降级 + 日志，绝不抛出到 system_server 的调用栈。

### 生成时钟与帧接口（L2 的落地形态）

现状：原生虚拟世界/步态引擎的推进挂在 poll 回调上（一次 poll 顺带推进一拍），
所以"生成节拍 = HAL 轮询节拍"。要摆脱它，推进必须由**我们自己的时钟**驱动：

* 新增 `nativeTick(nowNanos) -> 帧`（现为 `runtimeFrame`；**已无栅格**：按每通道到点时刻生成），
  把本拍"到期"的事件写进一个固定大小的帧缓冲（不分配、不装箱）。
* Java 侧用**独立 HandlerThread**（5 ms 周期）驱动 tick，然后逐条转发到
  `sendRuntimeSensorEventNative`（或按当前模式交给 poll 路径）。
* 帧用 JNI 平凡类型表达，四段平行数组：`int[] handles`、`int[] types`、
  `long[] timestamps`、`float[] values`（每事件固定 5 个槽，按类型取前 N 个）。
* **值布局必须与框架 JNI 的 switch 一致**（`com_android_server_sensor_SensorService.cpp`：
  加速度/磁场/方向/陀螺仪/重力/线性加速度 → 3 floats 写进 `acceleration`；
  步数计数器/检测器/接近/光/压力 → 1 float 写进 `data[0]`；
  旋转矢量等其余类型 → 前 16 floats 原样 `memcpy` 进 `data`）。
  写错槽位不会崩，但应用读到的是别的物理量 —— 这是"数值对不上"最容易的成因。
* 时间戳取 `CLOCK_BOOTTIME`（框架口径，与 poll 路径同源），`flags = 0`。

### 分阶段验证（每级独立可回退）

| 级 | 动作 | 判据 | 状态 |
|:--|:--|:--|:--|
| S1 | 只做反射解析 + 读 `mPtr`，不注册不发送 | `rtch=… resolved=true ptr=0x…` | ✅ 已装机验证 |
| S2 | 注册载体，记下 handle | `dumpsys` 里有它；NDK 客户端传感器列表里**没有**它；框架出现 `RuntimeSensorHandler` 线程 | ✅ 已装机验证 |
| S3 | 向**真实 handle** 推事件，无 hook 的客户端订阅 | 客户端收到并解出正确数值 | ✅ 已装机验证 |
| S4 | 运行时通道转主投递（poll 路径只压制），保持隔离 | 步频随速度变化；无重复投递；压制计数继续增长 | ✅ 已装机验证 |
| S5 | 速率/类型集合按目标应用调优（可选） | —— | 待做 |

S3/S4 的实测（Android 16 + 高通/OPPO，2026-09-11）：

```
# 无 hook 的纯 NDK 客户端（/data/local/tmp/sensorprobe）在模拟会话中读到：
type=3   last=[63.027 …]                     ← 方向角 = 虚拟方位 63.0°，逐位吻合
type=11  last=[0.000 -0.001 -0.523 0.853]     ← -sin(63°/2) = -0.5225，四元数自洽
type=2   last=[-29.299 14.483 -35.901]        ← 地磁矢量 |B|≈48µT、XY 夹角 63.6°
type=9   last=[-0.003 0.004 9.813]            ← 重力恒定
type=35  last=[-0.105 -0.185 9.020] sd=0.141 rangeZ=3.56   ← 走动时 IMU 上真的看得见步态
type=19  last=[7723.0]（计数器单调增长）      ← 步数计数器在走动中推进
type=18  last=[1.0] events=57                 ← 步检测器同步

# 模拟走动中（摇杆按住）系统侧快照：
意图步频 176 步/分（实测速度 3.12 m/s） | 移动判定 是
emitted=82846  suppressed=86076  steps=173  step_rate=156/min
delivering=true  frames=8331  events=82846  sent=82846  fails=0
```

**互斥的证据不是"看起来没翻倍"，而是计数器恒等**：`emitted`（`vw_generate` 里每生成一条就 +1，
两条路径共用）**恰好等于** `sent`（运行时通道真正投出去的条数）⇒ poll 路径在该窗口内
生成 **0** 条事件（它只压制：`suppressed` 同期涨到 8.6 万）。两条路径同时注入的情形下
`emitted` 必然大于 `sent`。`fails=0` 表示一次投递都没失败。

已知取舍（如实记录）：

* 投递是**广播式**的（框架 `sendEvents` 在 `scratch == nullptr` 时不按 handle 过滤），
  所以事件会写进**每个**活跃传感器连接；解不出 handle 的客户端静默丢弃。
* 客户端如果消费不过来，框架自己的**每连接事件缓存**会兜住并稍后补发 ——
  慢消费者看到的是"积压后成批到达"（实测纯 NDK 探针就是慢消费者：5 秒窗口里收到 58 条
  计数事件，而原生层同期只发出约 13 步）。这与真机 HAL 路径在客户端跟不上时的行为一致。
* 生成速率**跟随框架采用值**（旧实现是固定 50/25Hz 的 10ms 栅格，与应用的请求无关 ⇒ 本身就是破绽），
  **不跟随客户端请求的采样率**；调优空间在 S5。

S1/S2 的实测结论（Android 16 + 高通/OPPO 机型，2026-09-11）：

```
[Portal] BinderSensorMock: rtch=carrier-ok resolved=true ptr=0xb400007ce988ea80 carrier=0x5f000000 cb=0 sent=0
$ dumpsys sensorservice | grep portalex
0x5f000000) portalex-runtime | portalex | ver: 104 | type: (65536) | perm: n/a | flags: 0x00000000
$ cat /proc/<system_server>/task/*/comm | grep -i runtime
RuntimeSensorHa            # 框架的 RuntimeSensorHandler 线程（注册载体时才创建）
$ /data/local/tmp/sensorprobe 1 2500 | head
=== sensor list (42) ===   # 42 条，**没有** portalex-runtime ⇒ 客户端看不见载体
```

三条附带确认：

* 载体 handle 是 `0x5f000000` —— 与反汇编里 `registerRuntimeSensor` 的
  `mov w26, #0x5fffffff`（`RUNTIME_SENSORS_HANDLE_END - 1` 之类）完全吻合，
  说明这是一条真实的运行时 handle 区段，而不是我们"恰好蒙对"的路径。
* `ver: 104` = `sizeof(sensor_t)`（框架 JNI 也是这么填的）；`flags: 0x0` 表示不是唤醒、
  不是动态、不是 one-shot ⇒ 不会进动态传感器表。
* 载体注册**只在开关打开且模拟会话启动后**发生（开机阶段只登记开关，不装载原生层）。

**踩坑记录（值得记住的一条）**：`attach()` 一开始用的是
`ActivityThread.currentActivityThread().classLoader`，在 system_server 里它是
**BootClassLoader**，看不到 `services.jar` 里的 `com.android.server.*` ——
报 `ClassNotFoundException`。要用 **LSPosed 传给 hook 的 `lpparam.classLoader`**
（既有 hook 找 `com.android.server.*` 用的就是它）。现在按候选列表逐个试，失败会打印
试过哪些加载器，并在 10s 后退避重试（失败原因也逐字保留在状态串里）。

## 诚实的边界

* **设备根本没有该传感器时：没有回调。** 它在框架的传感器表里就不存在，应用连
  `getDefaultSensor(type)` 都拿不到，注册无从谈起；模块也不会为"框架表里没有"的类型
  编造 handle（注入只发生在确实存在的 handle 上）。要让这种设备上出现传感器，需要伪造
  **框架的传感器表本身**（`getSensorList` / `getDefaultSensor` / 动态 handle 分配与路由），
  那是另一层系统原生 hook，本轮未做。这种场景目前只能靠应用侧 hook（LSPosed 作用域内）
  伪造步数传感器。
* **传感器存在但暂时没有数据时：有回调** ✓ —— 已在真机实测：手机静置桌面（真机计步器
  全程不响），手持悬浮摇杆让模拟行走，`type=18` 4 秒内 12 个事件、`type=19` 计数持续增长。
* **循环活性依赖框架的 poll 循环**（**仅指当前的 poll 注入路径**）：AIDL/HIDL 的 `pollFmq`
  在 FMQ 上阻塞等待 HAL 唤醒。HAL 彻底死掉且从不唤醒时，`threadLoop` 会卡在那次等待里，
  注入也就无从发生（这种情况平台本身也拿不到任何数据）。
  上面的**运行时投递通道正是为解除这条依赖而设计**的：它由我们自己的时钟驱动，
  与 HAL 是否 poll、是否唤醒无关（该通道尚未接入，见"设计定稿"一节的分级验证）。
* **框架的"最后值缓存"会漏一次**：为新订阅者补发该传感器最近一次值时，框架走的是
  连接缓存而非 HAL 出口，模块挂不到，因此每种传感器在**每次新订阅**时可能漏出
  1 条真实事件（实测：手机静置时步数计数器表现为 1 条真实的当日步数——它会在下一次
  注入的步事件之后被覆盖；加速度计等则被紧随其后的注入值覆盖）。
* **步数不再被两条通道各报一次**（已修）：应用侧 hook 历来会在位置回调里**主动推**步事件
  （因为设备不走路时真机计步器不响）。开启本项后，框架**本身**已经在送步事件 ——
  两条通道各报一步，按事件计步的应用直接翻倍，而且两条通道的时钟会缓慢漂移
  （表现为"步频还在涨"）。实测反馈里的 290+ 步/分（理论 178）就是这个量级。
  现在应用侧 hook**到货即让位**：只要框架在送步事件（含真机计步器），本进程就不再自己推，
  只把到货计数器按合理增量并入自家计数后改写输出（计数器仍连续单调）。
  这条路径在本机**未能装机观测**：作用域内只有微信，而它启动时不注册步数传感器。
  判定方法（用户侧 30 秒即可）：应用侧 hook 让位时会打印
  `步数推送让位：框架正在送步事件`（需开调试日志），此刻步频应回落到 ~178 步/分。
* **与应用侧 hook 并存**：本项不改变既有应用侧传感 hook 的其余行为（"不影响现有功能"）。
  两者口径一致（同一虚拟世界），应用侧 hook 对同一条事件做的改写是幂等的。
  若需要"纯粹只由系统框架模拟"，把目标应用移出 LSPosed 作用域即可——
  定位模拟本身不依赖作用域。
  模块也会尝试通过 LSPosed 的跨进程偏好读取开关并自动让应用侧 hook 让位；
  **本机 LSPosed 2.1.1 未向模块暴露 `XSharedPreferences`**（`ClassNotFound`，
  换三个 classloader 都不行），所以这条自动让位在本机不生效，代码保留（在支持的
  版本上自动生效）。

## 如何验证

```bash
# 纯 NDK 传感器消费者（不经过 Java 层，不装任何 hook）
# 打开开关 + 启动模拟后运行：应看到平坦的加速度（0,0,9.81±噪声）、
# 恒定的虚拟方位、与方位自洽的四元数/地磁矢量
adb shell /data/local/tmp/sensorprobe 4 20000

# 关闭开关后再跑一次：应回到底层真实传感器的数值
```

日志（`logcat -s PortalSensor`）会打印符号解析结果、改写的槽位、
学习到的 `type → handle` 映射，以及注入/压制计数。

## 附：外周传感器模拟**按类别拆成两侧**（2026-09-18）

原来是一个总开关（`Key.BINDER_SENSOR_MOCK` / `Pref.binderSensorMock`，默认开），
现在按**传感器类别**拆成两个独立开关，各自落在对应功能页上：

| 页 | 开关 | 接管 |
| --- | --- | --- |
| 步频 Mock | `cadenceMock`（`Key.CADENCE_MOCK`） | `TYPE_STEP_COUNTER` / `TYPE_STEP_DETECTOR` |
| 角度和指南针 Mock | `orientationMock`（`Key.ORIENTATION_MOCK`） | 加速度（含未校准/线性）、陀螺（含未校准）、磁场（含未校准）、朝向（orientation / rotation vector / 重力） |

两者**都默认开** ⇒ 与拆分前行为逐位一致。**旧键不继承**：读不到新键的各取其默认。

### 门控落在哪（读代码时别找错地方）

**不在这条链的装载路径上，而在 `vw_owns_type()`** —— 它是"这个 type 归不归我们管"的唯一判定，
而投递侧的分叉是：

```
归我们管    ⇒ 发我们生成的事件
不归我们管  ⇒ 真实事件原样放行   ← 关掉的一侧走这里
```

所以"关掉一侧 ⇒ 那一侧一个事件都不会被注入"是**结构保证**，不需要在投递路径上再加一道判断。

⚠️ 但**模块里那几处 `anySensorMockEnabled` 门控是"整体生死"**（装载/卸载、监督线程停摆与唤醒、
泵循环、滴答）—— 它们问的是"**任一侧还开着就该活着**"，与类别无关。只开一侧时整条链仍然活着，
只是另一侧不注入 —— 这两件事别混。

### 验收判据（可测，不是"看起来对"）

`xposed/src/main/cpp/test/vw_class_gate_test.c`（已进 `test/run.sh`）：默认全开 ⇒ 全 1；
只开步频 ⇒ 步数计数器/检测器 = 1 且 加速度/陀螺/磁场/朝向/旋转矢量**必须全 0**；只开角度 ⇒ 反之；
全关 ⇒ 全 0；不归我们管的 type（光照）恒 0。

**真机判据（运行时）**：`portal_sensor.c` 里"归我们管 ⇒ 压制真实事件"这行 `suppressed++` 落在
`vw_owns_type()` 的分支里，于是按类压制计数是一个**可读的仪表**：

* 每次下发开关时模块打一行 `按类开关 cadence=… orientation=… | cadence=N orientation=M`；
* **关掉的那一侧，两次读数之间的增量必须为 0**（该侧不再被接管 ⇒ 不再压制真实事件）；
* 开着的那一侧同期增量必须 > 0（否则说明是"没有订阅者"而非"门控生效"，判据无效）。

⚠️ 读数**只能在下发那一刻取**（那行日志只在 `pushSensorClasses()` 里打）。
"关掉之后等一会儿再读一次"读到的是**同一行旧数据**，差值恒为 0 —— 那是方法错误，不是门控生效。

### 按组波动：波动强度 / 随机区间（两组各两条，默认 15%）

两组各自有两条**独立**参数（步频 Mock 页 / 角度和指南针 Mock 页各一组，文本输入行）：

| 参数 | 含义 | 默认 |
| --- | --- | --- |
| 波动强度 `amp` | **慢漂**半幅：时间常数 1.5s 的一阶低通随机游走在 [-1,1] 上乘 `amp` | 15% |
| 随机区间 `rnd` | **逐条事件**均匀随机半宽：`rnd × U[-1,1]` | 15% |

本事件的总偏差 `dev = amp·s(t) + rnd·U[-1,1]`（约 ±(amp+rnd)），0 表示关闭。

**与噪声档是两层**：噪声档（Calibration 页）是"每条事件的传感器本底噪声"（逐轴 σ + 陀螺零偏），
这里叠在它之上 —— 慢漂让读数在秒级上缓慢来回走，逐条随机让每条事件各抖一下。两者独立可调。

#### 施加口径：`dev × 该类型的参考量`，**不是**逐值百分比

逐值百分比（`value × (1+dev)`）在两类量上会坏掉，这是实测/推导出来的：

* 基准接近 0 的量（陀螺 x/y 静止时只有零偏、线性加速度静止时为 0）永远不抖；
* 角度量会变成"朝向越大抖得越狠"（0° 几乎不抖、350° 抖 50°）—— 与朝向无关的抖动才像真机。

所以统一按**该类型的参考量**加绝对偏差（见 `vw_wobble.c` 的 `vw_wobble_ref`）：

| 类型 | 参考量 | 吃波动的分量 |
| --- | --- | --- |
| 加速度 / 重力 / 线性加速度 | 1g = 9.80665 m/s² | 3（x/y/z） |
| 陀螺（含未校准） | 1 rad/s | 3 |
| 磁场（含未校准） | 50 µT | 3 |
| 方向角 | 180° | 1 |
| 旋转矢量（含 game/geomagnetic） | π | **加在半角 θ 上**（见下） |

旋转矢量**不能**按分量缩放：那会把四元数变成非单位长度，客户端 `getRotationMatrixFromVector`
拿到一个不是旋转的"旋转矢量"，姿态整体跑偏 —— 比不抖更糟。所以它的偏差加在**半角**上，
`(θ + dev·π)/2` ⇒ 四元数仍然是单位四元数。

**步频侧**没有"分量"，作用在**步间隔**上：`interval × (1 + dev)`（间隔抖 = 步频抖，等价且最直接），
每个间隔单独取一次偏差 ⇒ 慢漂让它一段快一段慢、逐条随机让它一步一个样。间隔钳在 ±50%，
避免慢漂与逐条随机叠加后出现 0 间隔或十几秒一步。

#### 0 值是**逐位兼容**的

两条都为 0 时，原生层**在碰随机数之前**就返回 —— 不消耗随机数、不做任何算术。这不是洁癖：
0 值若也抽一次 `vw_rng_unit()`，全局随机流会被挪位，后面所有 σ 噪声都会跟着变，
"参数为 0 ⇒ 输出与从前逐位一致"这条判据就当场失效。`test/vw_wobble_test.c` 用固定种子把
这条钉死（插 500 次 0 值调用后，随机流位置必须原封不动；同时用非 0 值做反向对照，
证明该断言不是空的）。

#### 施加口径的**相干性**（2026-09-21 按真机实测收口）

波动**不能各抽各的** —— 真机上三条物理关系会立刻被打散，且读数会"毛糙"。现在按量类分三档：

**统一口径（2026-09-21 二改，用户裁决"照角度的办法"）**：

| 量类 | 慢漂（旋钮「波动强度」） | 逐条随机（旋钮「随机区间」） |
| --- | --- | --- |
| 角度类：朝向 / 旋转矢量 / 磁场**方向** | 全额：`amp × 180°`（±27° 的秒级游走） | **≤1°** 且 **100ms 采样保持**（同窗内三者同一个值） |
| 加速度计 / 重力 | 全额：`amp × 1g`（±1.47 m/s² 的秒级游走） | **≤ 参考量/180**（≈0.054 m/s²），同样 100ms 保持 |
| 磁场**幅度** | 全额（按 `1+dev/\|H\|` 缩放矢量） | **≤ 参考量/180**（≈0.28 µT） |
| 线性加速度 | — | **不吃偏差**（吃了 `accel = gravity + linear` 就破） |

**为什么逐条随机要有绝对上限**：按百分比全额施加时，逐条就是 ±1.47 m/s²（1g）、±7.5 µT、
±27° 的**台阶**，真机实测加速度计 |Δ| 中位 0.89 m/s²、罗盘角 7.9°（更早还量到 61°，那是探针 bug）。
两个旋钮都还在，只是"逐条"那一路不再制造尖峰。

**慢漂幅度的一个真 bug（同一轮修掉）**：慢漂原来是"每步抽一个新的均匀数再做一阶低通"，
稳态幅度**与调用频率挂钩** —— 真机事件率约 150/s 时稳态 |s| ≈ 0.03，
也就是说「波动强度」这个旋钮**实际只兑现了约 3%**。改成"目标每 3s 换一次 + 以 τ=1.5s 逼近"后，
|s| ≤ 1 且与频率无关，半幅就是旋钮给的 amp。

真机实测（Test 页「IMU 平滑度」，各 25s/35s 一轮）：

| 量 | 最初 | 收口后 \|Δ\|中位（静止 25s） | 判读 |
| --- | --- | --- | --- |
| 加速度计 | 0.89 | **0.0066 m/s²**（最大 0.046；z 值回到 9.815） | 平滑 ✓ |
| 重力 | 0.87 | **0.0045 m/s²** | 平滑 ✓ |
| 线性加速度 | 0.004 | 0.0068 m/s² | 平滑 ✓ |
| 磁场（幅度） | 4.4 | **0.183 µT**（最大 0.96） | 平滑 ✓ |
| 朝向 | 0.14° | 0.143° | 平滑 ✓ |
| 罗盘角 | 7.9°（更早还误报过 61°） | **0.249°**（最大 1.51°） | 平滑 ✓ |
| **恒等式残差** | 1.70 | **0.030 m/s²** | `accel = gravity + linear` 成立 ✓ |

走动 35 秒同口径复测：加速度计 0.010、重力 0.009、线性 0.007 m/s²、磁场 0.29 µT、朝向 0.16° —— 同样平滑。

⚠️ **判读口径**：恒等式按**事件时间戳对齐**后再比（容差 60ms）—— 修复前那一行是被采样错位污染的
（各量"最近一条"差几十毫秒，走路时线性加速度在这段时间里能摆 ±1.5 m/s²）。
对齐后实测配对残差 **0.004~0.022 m/s²**，恒等式成立 ✓（配对率受各通道排定时刻限制，未配对的不计入判读）。

⚠️ **改这里的教训（本轮最贵的一课）**：`virtual_world.c` 里这条链上**四处替换全部静默没命中** ——
取偏差那一行、以及**施加块本身**（磁场只抖幅度、`wdev` 已是绝对单位不再乘 `wref`）。
后果：`dev` 被 `× wref` **乘了两次**（加速度的慢漂被放大到 ±14 m/s²，真机实测 z 值漂到 12.1），
磁场仍是**逐分量加**（方向被噪声主导）。而 **.so 照常编译成功、门禁照常 59/59 全绿**。

暴露它的不是任何检查，而是两个**不可能现象**：① 两轮不同代码给出**同一个测量结果**；
② 加速度计的 z 值是 **12.1**（标称 9.81 + 慢漂）。

⇒ 硬规则：**改了符号或调用点，必须 grep 核验该符号在文件里实际出现**；
`assert 锚点命中` 只保证**那一次**替换成功，不保证下一次的锚点还在（这一行之前被别的补丁改过）。
另外：**"不同代码 → 同一个数"要当成 bug 信号**，不要当成"改动无效"。

#### 落点与下发

* 步频 Mock 页两行 → `Key.CADENCE_WOB_AMP/RND`；角度和指南针 Mock 页两行 → `Key.ORIENTATION_WOB_AMP/RND`；
  值域 0~100(%)，NaN/负值归 0、超上限钳位（App 与原生两侧同一口径）。
* 与噪声档走**同一条命令**（`set_sensor_mock`，见 [ConfigSync]）：这条命令是"传感器侧配置"的载体，
  系统进程重启后靠它恢复；`BinderSensorMock.applyStoredConfig()` 也会把两组波动重放一次。
* 原生层状态串里带 `wob=[cad=A%/B% ori=C%/D%]` —— Test 页"注入计数（原生层）"那一栏直接显示，
  是"页面上的数字真的走到了原生层"的现场证据。

### 权威来源：命令，**不是**偏好（2026-09-19 实测）

开关值在 App 侧落在偏好里（`Pref.CADENCE_MOCK` / `Pref.ORIENTATION_MOCK`），但
**system_server 读不到它的变化**，所以模块侧的唯一权威是 `put_config` 命令：

* libxposed 的 `getRemotePreferences(group)` 返回的是"**构造时拉一次快照**"的对象：
  框架按 group 在进程内缓存实例（`computeIfAbsent`），之后靠**推送增量**刷新；
* 本机（LSPosed 2.2.0 / api 102）实测**推送从未到达**：App 在功能页改开关（写偏好 + 发命令）
  没有推送、用 root 原地改偏好文件（保 inode）也没有推送，监听器一次都没被回调；
* 后果是这条通道给 system_server 的永远是**进程第一次读时的旧值**。

曾试过"模块自轮询偏好、以偏好为唯一真相源"（本文件写作时的下一步计划），**实测证明有害**：
模块按命令把一侧关掉后 1 秒，自轮询拿**旧快照**又把它打开 —— 日志现场

```
BinderSensorMock: onConfigChanged            cadence=true orientation=false   ← 命令：关
BinderSensorMock: 按类开关变化（自轮询）      cadence=true->true orientation=false->true  ← 旧快照把它开回来
```

于是这条读路径被整体删除（`ModulePrefs.cadenceMockEnabled()/orientationMockEnabled()` 一并删除，
连同刚加的偏好变化监听）。**要改开关就发命令** —— 与噪声档、速度、路线等设置同一条通道。

> 未验证的假设：推送可能依赖 LSPosed 管理端进程在跑（本机测试时它没在跑）。
> 这条没有实测，**不要**据此把偏好通道重新当成实时源。

