---
name: "android-dialog-ime-layout"
description: "键盘弹出后对话框被遮挡/按钮被压住:量卡片而非窗口、IME 高度取 Activity、验证复位"
---

# Android 对话框输入法自适应上移

适用：键盘弹出后对话框底部按钮被遮住、对话框不跟随输入法上移。

原理：对话框是独立于 Activity 的悬浮窗口，Activity 的 `windowSoftInputMode` 对它无效；系统默认给它的 `adjust=pan` 只在「焦点视图被键盘盖住」时才平移，而对话框里的输入框通常本来就在键盘上方 → 判定无需动作，对话框不动。`adjustResize` 同理不会移动浮窗。必须自己接管位置。

## 步骤

1. 先复现并量化：`adb shell uiautomator dump /sdcard/d1.xml` → `adb pull` → grep 卡片与按钮的 bounds；`adb shell input tap <输入框中心>` 弹出键盘（用 `adb shell dumpsys input_method | grep mInputShown=` 确认已弹出），再 dump 一次。两次 bounds 相同 = 对话框没动。完成：拿到「按钮 bottom」与「键盘 top」两个数。

2. 定位键盘真实顶边：`adb shell screencap -p /sdcard/s.png` → pull → PIL 从下往上找第一条整行纯色（键盘背景色）的行，即 imeTop。不要用 `dumpsys window` 的 IME 窗口 frame——它是 `fillxfill` 全屏窗口，frame 恒等于屏幕，与键盘高度无关。

3. 实现：给对话框窗口设 `SOFT_INPUT_ADJUST_NOTHING`（关掉系统平移，避免与手动位移叠加），自己监听 IME inset 并改 `window.attributes.y`。`show()` 返回的 AlertDialog 直接接：`dialog.shiftAboveIme(requireActivity().window.decorView)`；在 `setOnDismissListener` 里移除该 inset 监听。

4. IME 高度从 **Activity 的 decorView** 读，别从对话框窗口读：对话框窗口不与键盘重叠，系统可能不向它派发 IME inset。`imeTop = src.getLocationOnScreen()[1] + src.height - insets.getInsets(Type.ime()).bottom`。

5. 量**卡片本体** `decor.findViewById<View>(android.R.id.content)`，不要用 decorView：对话框窗口比可见卡片上下各多约 280px 背景内边距（实测 decorH=1359 而卡片 799），按窗口算会多推 280px。

6. 静止位置只测一次：`window.attributes.y` 赋值立即生效，但窗口真实位置下一帧才更新——每次回调都用 `getLocationOnScreen` 反推静止位置会算错。首次非零 IME 回调时记 `restingTop = cardY - (attributes.y - baseY)`，之后复用；`imeBottom == 0` 时把 `attributes.y` 复位成 `baseY` 并丢弃缓存，下次弹出重新测。

7. 位移 = `restingTop + card.height + bottomMarginPx - imeTop`，再 `coerceIn(0, restingTop)`。同时给窗口加 `addFlags(FLAG_LAYOUT_IN_SCREEN)`：默认对话框窗口的 layout 区域从状态栏底部开始（frame 顶恒为状态栏高如 140），高对话框上移会被钳住、实际间距小于设定值（实测只剩 5px）；LAYOUT_IN_SCREEN 让窗口可以升到屏幕顶部，间距精确达标。代价：无键盘时静止位置整体上移半个状态栏高（居中参照改为全屏），属预期。

8. 内容可滚动 + 按钮固定：把自定义内容（setView 的视图）包进 ScrollView——`decor.findViewById<ViewGroup>(R.id.custom)`（注意用 material 的 R，见坑）取内容容器 → `getChildAt(0)` 取出原内容 → `custom.removeAllViews()` → 塞进 ScrollView 再放回。上限：`addOnLayoutChangeListener` 里 `h = bottom - top`，内容自然高度超过 `屏幕高 × ratio`（默认 0.5）时把 `layoutParams.height = maxH`，未超限保持 wrap_content 外观不变。buttonPanel 在 custom 容器之外，取消/保存按钮永远固定在卡片底部，不随内容滚动。

8. 验证三件事：键盘弹出后按钮 bottom 小于 imeTop；卡片底部与 imeTop 的间距≈bottomMarginDp；`adb shell input keyevent KEYCODE_BACK` 收起键盘后卡片 bounds 完整复位（含 y 偏移归零）。完成：三个数都对。

## 坑

- 把日志变量复用后打印错值：先 `src.getLocationOnScreen(location)` 算 imeTop，又 `card.getLocationOnScreen(location)` 覆盖同一数组，再打印 `location[1]` 得到的是卡片坐标。打印前用不同数组或分变量。
- 临时加 `Log.d` 定位时记得移除：`grep -n "Log\.d"` 确认后再提交。
