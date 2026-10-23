# PortalEX

> 秋夜长，殊未央，月明白露澄清光，层城绮阁遥相望。
>
> —— Portal 的后继者，**新时代**的 Portal。

Telegram: <https://t.me/portal_fuqiuluo>（原 Portal 项目频道，历史与社区仍在）

基于 LSPosed 的虚拟定位模块：只通过 Hook 系统服务实现虚拟定位，**不修改、不集成进目标 APP**。
安装并启动后自动生效，无需对目标应用做任何操作。

---

## 新时代 · New Era

Portal 曾经是一位作者（[ella8192](https://github.com/ella8192)）的优秀作品。在作者停更之后，
我们接手了这个项目，继续维护并让它**焕然一新**：

- **全面现代化的界面**：迁移到 Material 3 设计语言、适配深浅色主题、悬浮胶囊快捷操作栏
  （单实例、按页面切换功能集、路径回放一键入口）；
- **路线模拟焕新**：折线平滑转向（限角速度、自然转弯）、路线速度与摇杆完全对齐、
  路线完成提示音与振动，历史路线管理；
- **更完善的模拟能力**：外周传感器模拟**拆成步频侧 / 角度与指南针侧两个独立开关**（各自带
  波动强度与随机区间），噪声档可在 Calibration 页一键校准；GNSS 模拟（卫星状态回调、
  NMEA 数据流软复位）；新增**体力模拟**（跑动消耗 / 疲劳降速 / 恢复）与**使用教程**
  [`docs/guide.md`](docs/guide.md)；
- **持续修复与打磨**：深浅主题切换的各类显示缺陷、摇杆悬浮窗样式锁定、
  摇杆状态跨页统一、图标与配色一致性、开关样式统一为设置项那套（主题默认）。

我们保留了 Portal 的初心——面向开发调试的定位模拟工具，同时把它带进了新时代。

> [!note]
>
> 中文地区特供：
>
> 1. 本项目根据 GPL-3.0 许可证开放（详见 [License](#-license)），可用于任何符合法律的目的
>    （包括学习和研究）。使用者应遵守相关法律法规，**禁止用于任何违法行为**。
> 2. 依 GPL-3.0 您可以自由使用、修改、分发本代码及创建衍生作品，但需遵守以下条件：
>    - 保留原始版权声明与许可证副本；
>    - 衍生作品同样以 GPL-3.0 发布；
>    - 说明您所做的重大修改。
> 3. 因使用本软件导致的任何后果由使用者自行承担，与本项目开发者无关。
> 4. 开发者保留在使用者违反许可证条款时追究法律责任的权利。

# Warning

- 如发现任何人利用 PortalEX 进行违法活动，请收集证据并向有关部门举报。
- 使用者应遵守所有适用的法律法规。任何企业/组织/个人对因违法使用 PortalEX
  而产生的后果需自行承担责任。
- PortalEX 开发者对任何因使用本软件而导致的法律纠纷不承担责任。
- 若有企业/组织/个人因使用 PortalEX 遇到技术问题导致损失或业务中断，
  开发团队将在合理范围内提供技术支持和协助。
- 开发团队保留对本软件技术实现细节的最终解释权。

# Features

> 面向使用者的**使用教程与功能说明**（怎么用、每个功能是什么）见 [`docs/guide.md`](docs/guide.md)；
> 实现细节见 [`docs/`](docs/) 下的专题文档。

- [x] 运行高亮通知：模拟运行时创建常驻通知，便于检测状态
- [x] 无指纹注入：不写入 `portal.enable` / `is_mock` 等特征标记，`isMock` 恒为 `false`，检测只能依赖数据合理性
- [x] 任意位置模拟：百度地图选点（单击落点 / 长按详情）、历史位置管理、搜索定位
- [x] 移动摇杆：悬浮摇杆手动控制移动方向与转向，实时生效（单实例、跨页状态一致）
- [x] 路线模拟：沿历史路线自动行驶，平滑转向、速度对齐，完成提示（音 + 振动）
- [x] 速度 / 海拔 / 精度 / 位置上报间隔设置：模拟参数可调
- [x] 移动中方位角实时变更（bearing 跟随移动方向）
- [x] 体力模拟：跑动消耗、空闲恢复、疲劳降速与冷却，参数可调，附「理论 / 生成」双曲线与参数体检
- [x] 传感器模拟（外周）：**步频侧**与**角度与指南针侧**各一个开关（**默认都开**），
      分别落在 `Cadence & Acceleration Mock` 与 `Angle & Compass Mock` 页；
      每侧另有 **波动强度 / 随机区间**（默认各 15%，0 = 关闭）控制慢漂与逐条抖动。
      在**系统框架层**伪造：步数计数器 / 检测器、加速度（含未校准 / 线性）、陀螺（含未校准）、
      磁场（含未校准）、朝向 / 旋转矢量 / 重力。只 hook 系统框架、**不 hook 目标应用**，
      因此作用域外的应用与走 NDK `ASensorEventQueue` 的原生消费者同样拿到模拟数据；
      事件由模块自产，不依赖底层传感器是否在工作。见
      [`docs/binder-sensor-mock.md`](docs/binder-sensor-mock.md)
- [x] 传感器噪声档与一键校准（Calibration 页）：逐轴 σ + 陀螺零偏，可按本机实测噪声自动填充
- [x] GNSS 模拟：卫星状态回调、星图与信号强度、AGPS / NMEA / 地理围栏开关、数据流复位
- [x] 融合定位处置：伪装 / 放行 / 拒绝三选一
- [x] 抗位置拉回：反定位拉回、禁用扫描 Wi-Fi 列表、网络降级
- [x] 后台保活与电池优化白名单
- [x] 诊断页（Test）：模拟状态、注入与压制计数、当前档位、普通应用视角的真实订阅读数
- [ ] GPS 状态码模拟
- [ ] Cell 基站信息模拟
- [ ] Wi-Fi 信息模拟

## 如何检测 PortalEX / How to detect?

- PortalEX 运行时创建常驻通知，查看通知即可确认是否在运行。
- PortalEX 不向 `Location` 注入任何模块自有标记：`extras` 只透传原始数据，
  `isMock` 恒为 `false`。**不存在可枚举的特征字段**，无法通过「检查注入标记」检测。
- 只能从数据合理性入手，例如：
  - 传感器模拟的两个开关打开后，被接管类型的**全部**数据都来自模块自产的虚拟世界：
    设备的真实晃动不会出现在加速度计上（这是「完全隔离」的代价，也是最直接的检测面）；
    各传感器之间仍然自洽。「波动强度 / 随机区间」只是给这些**自产数值**再加一层慢漂与逐条抖动，
    并不让它与物理世界产生关联 —— 晃动设备、比对真实地磁/重力方向即可识别；
  - 路线播放的轨迹与速度高度贴合预设，抖动幅度受配置控制，与真实路况噪声存在差异；
  - **固定间隔的配速尖峰**：长时间跑动时，目标应用的配速曲线会出现**间隔近似固定**的尖峰 / 台阶，
    而不是真实路况那种随机分布的起伏。成因都在注入侧 —— 位置按固定拍长出帧
    （`Setting → 位置上报间隔`）、速度是低通慢游走而非真机那种宽带噪声、
    开启体力模拟后速度还会按**疲劳周期**规律起伏（周期可在体力页直接读到）；
    自动播放起步那一次「路线起点对齐」的跳变（实测 145 / 170 m）还会额外贡献一根夸张尖峰
    —— 目标应用据此算出的「最快配速」出现过 **0'35"/km** 这种物理上不可能的值。
    把配速曲线拉长做**周期拟合**，这类规整的间隔本身就是判据。

# Build & Releases —— 为什么不发布 APK？

经过慎重考虑，开发者（们）认为：此类软件需要设立一定使用门槛，以防止滥用、降低影响。
因此 **PortalEX 不发布任何可直接使用的安装包（.apk）**：

- 您必须自行使用 Android Studio 或 Gradle 等工具编译（`./gradlew :app:assemble<Flavor>Debug`，
  支持 `arm64`、`x64` 等构建变体）；
- 项目不提供与编译过程本身无关的教程；
- 不发布 APK 是刻意的设计，而非疏漏——门槛让每一位使用者都清楚自己编译、
  自己安装、自己负责。

After careful consideration, the developer(s) believe that such software requires a certain
usage threshold to prevent abuse and reduce impact. PortalEX will not release any directly
usable installation packages (.apk). You must compile it yourself using tools such as
Android Studio or Gradle. PortalEX will not provide tutorials unrelated to the project itself.

## 用你自己的 fork 跑 CI（可选）

本仓库不携带任何私有数据：没有签名证书，也没有百度 AK。fork 之后你跑的工作流会自己
生成一把签名证书（存在该 fork 的 Actions 缓存里），并把申请 AK 需要的两行信息打印在
Job Summary 里：

| 项 | 来自哪里 |
|:--|:--|
| 应用包名 | `moe.fuqiuluo.portalex`（固定） |
| 签名 SHA1 | 首次运行后出现在 Job Summary |

流程：

1. 在 fork 里跑一次 **Build Apks**（百度 AK 输入框留空）
2. 打开该次运行的 **Job Summary**，抄下包名与签名 SHA1
3. 到 [百度地图开放平台](https://lbsyun.baidu.com/) 建一个 **Android SDK** 应用，
   填上面两行，勾选「地点检索」，申请 AK
4. 再跑一次，把 AK 填进手动触发时的 `baidu_map_ak` 输入框
5. 下载 Artifact 安装

不给 AK 也能编译（地图会正常显示，因为瓦片走 CDN 不校验 AK），但**地点检索与逆地理
会失效**。签名证书由工作流管理，缓存被清除后会重新生成，SHA1 随之改变，需要重新登记。

> 本地直接用 `./gradlew :app:assembleArm64Debug` 编译是另一条路：用的是你机器上
> `~/.android/debug.keystore` 的 SHA1，同样拿它去申请 AK 即可，全程不需要任何 Secret。
> 详细说明见 [`docs/baidu-map-sdk.md`](docs/baidu-map-sdk.md)。

# Thanks

- [GoGoGo](https://github.com/ZCShou/GoGoGo)
- [Baidu Map SDK](https://lbsyun.baidu.com/faq/api?title=androidsdk)
- [ella8192/Portal](https://github.com/ella8192/Portal) —— 本项目的起点
- [LSPosed](https://github.com/LSPosed/LSPosed)

# License

本仓库为 [Portal](https://github.com/ella8192/Portal) 的 fork。原项目的代码可在
Apache License 2.0 或 GNU General Public License v3.0（或更高版本）中选择使用。

**本 fork（PortalEX）整体以 GNU General Public License v3.0 或更高版本
（GPL-3.0-or-later）授权。**

- 原 Apache License 2.0 副本保留在 `LICENSE.Apache-2.0` 文件中；
- GPL v3.0 许可证副本见 `LICENSE` 文件；
