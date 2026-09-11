# virtual_world.c 的 host 回归测试

不需要设备、不需要 NDK：虚拟世界本身没有 Android 依赖，`stub/android/log.h` 只是
把 `__android_log_print` 与日志优先级常量补上。

```sh
cd xposed/src/main/cpp
cc -D_GNU_SOURCE -I test/stub -I . test/vw_consumer_split_test.c virtual_world.c \
   -lpthread -lm -o /tmp/vwtest && /tmp/vwtest
```

覆盖两个已修缺陷（都是"数据悄悄消失"那一类，Test 页看不出来）：

1. **步事件静默丢失**：两个消费者（poll 出口 / 运行时泵）共用一条时间轴，不属于本次请求的
   那一对步事件必须**留在队列里**等对方来取。旧实现有两处会把它吃掉：
   归属判定之前就 `used = 0`，以及函数末尾"按 due 清扫"。本测试断言
   poll 侧一条都不拿、运行时侧成对拿到且计数器逐步 +1。
   验证方法：把 `virtual_world.c` 对应改动还原到临时副本再编一遍，测试必须失败。
2. **未激活早退的记账**：关掉总开关后，从延迟队列取出来的事件不许发出去，但必须计入
   `dropped`（旧实现直接 `return 0`，事件既不发也不计 ⇒ 账实不符）。

注意 harness 的时间记账：`vw_generate` 的内部 `g_last_tick` 只前进不回退，
若中途用一次"要全都要"的调用把时间推到更晚，后面的用例必须从**更晚**的时刻继续，
否则 `now < g_last_tick` 会让 tick 循环整个不跑（这个坑我自己踩过一次）。
