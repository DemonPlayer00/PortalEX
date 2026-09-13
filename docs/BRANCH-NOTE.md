# 这条分支是什么

`feature/hal-vcam` = **主分支（app） + 系统层摄像头虚拟化的一整条线**。

主分支 `master` 上**没有**这部分的任何痕迹（文件、提交消息、注释都没有）—— 这是刻意的：
`master` 只承载应用本身（定位/传感器模拟 + 应用侧摄像头模拟），系统层那部分单独留在这里。

## 它包含什么

| 路径 | 内容 |
|---|---|
| `camera-hal/` | 相机 HAL 包装层（camshim）+ 自建虚拟相机的实验件（vcam）+ **VVideo 统一取帧层** |
| `magisk/portalex-camera-hal/` | 部署形态：Magisk/KernelSU 模块（overlay / 单文件 bind + 运行期回滚 watchdog） |
| `docs/camera-mock.md` | 这条线的完整研究记录（布局标定、显示变换、稳定性矩阵、失败与撤回） |
| `docs/vvideo.md` | VVideo 层：形状照 Linux VFS 的统一取帧接口 |
| `docs/camera-hal-compat.md` | 兼容性分析（适用前提 / 两种平台形态 / 移植清单 / 未验证边界） |
| `scripts/…` | 构建与部署模块、生成素材、host 自测、真机回归 |

## 怎么用

```sh
# 切到这条线干活（工作树会带上 camera-hal/ 等文件）
git switch feature/hal-vcam

# 回主分支
git switch master

# 万一 master 被改坏了：这条分支就是原件（master 的原始历史在这里完整保留）
git checkout -B master feature/hal-vcam
```

⚠️ **别把这条分支推到公开远端**，也别在 `master` 上 `merge` 它 —— 两者都会把系统层的东西
带回去。要合的时候应当是"从这条线里挑出与 app 无关的提交"再挑过去（`git cherry-pick`）。
