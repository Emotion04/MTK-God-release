# V2430A 命令与观测

记录非显然的命令行为，供排错和选择判据；实际命令组以源码为准。历史实验主要来自 2026-09-23，后续核验时间单列；适用环境见 [设备基线](../VERSIONS.md)。ADB shell 的结果不能代替 App 经 UserService 的验证。

## 只读诊断

| 要核对 | 命令 | 如何解释 |
|---|---|---|
| ROM | `getprop ro.vivo.product.version`、`getprop ro.build.fingerprint` | 先比较适用条件 |
| 执行身份 | `id`、`id -Z`、`getenforce` | UID 相同仍需比较域、补充组与环境 |
| CPU | `ls -lZ /sys/devices/system/cpu/cpufreq/policy*/scaling_*_freq` | 权限、硬件能力、当前值分开判断，见 [CPU-004](../cpu/facts.md#cpu-004) |
| 温度 / HAL 阈值 | `dumpsys thermalservice` | 缓存与 HAL 当前读数分开；框架热状态不等于厂商限频状态 |
| 配置原件 | `sha256sum /vendor/etc/thermal/*.conf` | 与 [解码基线](../thermal/configs/README.md) 比较，摘要相同只确认配置 |
| Settings | `settings get <namespace> <key>` | `null` 说明未存储，不证明没有消费者 |
| 属性 | `getprop <key>` | 读到目标字符串不证明功能生效 |

依据：2026-10-05 ADB 只读复核 ROM、身份、CPU 节点权限、温控配置摘要和 HAL；本次没有重跑下文的写入实验。

## 设置写入

依据：2026-09-23，`settings put system pointer_speed 7` 曾退出 0 而回读仍为 0；
同一 ADB 环境改用下列命令后回读为 7：

```text
content insert --uri content://settings/system --bind name:s:pointer_speed --bind value:i:7
```

这确认了当时的配置写入差异，不能推广为所有 Settings 键、ROM 或 App 执行器的固定行为。
项目的受控写入和回读统一由 `SettingChannel` 处理，见 [架构](../dev/architecture.md)。
数值等价性按具体键判断；字符串 `1` 与 `1.0` 并非所有场合都可互换。

原键缺失、属性为空和数值 0 是不同状态。恢复使用操作前快照；
重启后的 Settings 仍可能是此前写入的持久值，不能称为出厂值。
2026-10-18 带前后读数的复核：`settings` 键跨重启保留，`debug.*` 属性重启即清，
强停 App 两者都不影响 —— 见 [persistence.md](persistence.md)。

依据：2026-09-23，给从未设置的属性写空串曾非零退出。
目标已经为空时可以跳过写入并核对读回；需要清空已设置的属性时保留空字符串参数：
`setprop <key> ""`。支持情况仍以执行器回读为准。

## 电池优化白名单

依据：2026-10-14，uid 2000（ADB shell，与 Shizuku 同一身份）**可以直接改** Doze 白名单，
不需要 root，也不弹系统对话框：

```text
$ adb shell "dumpsys deviceidle whitelist" | grep -c magicau.mtkcontroller
0
$ adb shell "dumpsys deviceidle whitelist +magicau.mtkcontroller"
Added: magicau.mtkcontroller
$ adb shell "dumpsys deviceidle whitelist" | grep -c magicau.mtkcontroller
1
```

读和写都通，`-` 号移出。所以 `BatteryWhitelist` 的直写路径是可用的，
开关显示的状态与系统事实一致。

**但厂商 ROM 的后台管理不止这一项。** 本机上
`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` 落到的是
`com.iqoo.powersaving/.BackgroundHighUsageActivity`，管的范围比 Doze 宽；
安卓原生的授权弹窗是 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + `package:` 数据，
本机解析到 `com.android.settings/.fuelgauge.RequestIgnoreBatteryOptimizations`。
要用户自己确认全貌，走后者。

## 触控

依据：2026-09-23，在实体触屏上采集 `SYN_REPORT`：

| 三个上报率属性的值 | 事件数 | 中位间隔 | 折算频率 |
|---|---:|---:|---:|
| 180 | 62 | 7.72 ms | 129.5 Hz |
| 0（对照） | 555 | 7.72 ms | 129.5 Hz |

属性为 `debug.touch.report_rate`、`debug.touchscreen.report_rate`、`debug.game_touch_sampling_rate`。
写入、读回成功，但该测试条件下没有观察到上报率变化；不能据此认定所有场景或所有档位永久无效。

采样前用 `getevent -lp` 识别当前触屏设备；历史路径是 `/dev/input/event4`，不固定复用。
用有限时间的 `getevent -lt <device>` 配合实体触摸记录间隔。`input swipe` 是框架注入事件，
历史测得约 51 Hz，不能用来测控制器上报率。显示刷新率也不能代替触控上报率。

同次观测：`touch_scan_press_time_delay` / `_double` / `_single` / `_grade` 已为
300 / 200 / 300 / 2；再次写相同值不能验证延迟改善。记录这是观测值，不是恢复默认值。

`device_config put activity_manager_native_boot ...` 的相关候选曾被 allowlist 拒绝，退出 255。
这暴露了命令组的边界：辅助项失败应单独报告，不能让不相关的必需项无法清理。

同次 `bbk_touch_screen_service` 事务 16 返回 `Parcel(0, 0)`，
事务 17 写档位 5 后未观察到读数变化。服务存在、描述符匹配和调用成功不足以证明效果；
后续接入情况查源码，当前待核验问题见 [待办](../dev/todo.md)。

## 游戏与性能配置

依据：2026-09-23，用户在系统页面切换增强档后逐表核对：

| 键 | 打开 / 关闭 |
|---|---|
| `system power_save_type` | 1 → 6 / 6 → 1 |
| `secure power_save_type` | 1 → 6 / 6 → 1 |
| `secure system_property_power_mode_type` | 1 → 6 / 6 → 1 |

这是该 ROM 的系统开关联动证据，不能推导所有性能档位含义。
只看经过过滤的 `settings list` 差异曾漏掉 secure 表，后续核验应逐命名空间读取。

同日 App 曾报告 requested=8、readback=6，而 ADB 未复现；原因未确定。
这类冲突要比较实际执行路径、并发写入和系统联动，不能直接归因于 App 并发或 ROM 拦截。

`system vts_game_para_adjust` 当时为 `1,6,6,6`，外部实现使用的 `1,7,7,7` /
`0,3,3,3` 没有在本机确认含义。这是一个全局存储键，与 `monster_mode_<包名>` 不同；
存储作用域也不等于所有应用必然使用该值。

`gamecube_frame_interpolation` 的外部写入位置为 system；键回读不能确认画面插帧。
`vivo_smart_cloud_acc_enable_state` 当时在三张表都为 null，不据此认定服务不存在。
具体消费者与接口见 [ROM 追踪](../reference/ax-rom-trace-2026-09-19.md)。

## 其他历史边界

| 观测 | 能支持的结论 |
|---|---|
| `cmd jobscheduler enable-flex-policy ...` 回读 268435463，disable 回读 0 | 配置变化；不是性能收益，也不是任意环境的原值 |
| `cmd power set-fixed-performance-mode-enabled` / `set-adaptive-power-saver-enabled` 退出 0，未取得效果读回 | 当时效果未验证 |
| `am broadcast` 返回 `result=0` | 派发返回；不证明接收方存在或完成处理 |
| `dumpsys gpu` 显示 Stable / Pre-release Game Driver unsupported | 当时本机驱动机制不支持；不推广到所有 Mali 机型 |
| framework 热状态 override / reset 改变覆盖标记 | 框架状态改变，不等于解除厂商温控，见 [历史调查](../archive/thermal-investigation-through-2026-09-26.md) |
| `dumpsys battery set` 改变模拟状态 | 模拟值不证明实际供电或充电变化，退出模拟需核对 reset 后状态 |

依据：2026-09-23 历史命令测试。未观测到效果的命令不在此改写为永久空操作。

## 指标读取

已知 GPU 候选 `/sys/class/devfreq/13000000.mali/cur_freq`、`/sys/kernel/ged/hal/`、
`/proc/gpufreqv2/`，DDR 候选 `/sys/class/devfreq/mtk-dvfsrc-devfreq/cur_freq`。
历史 shell 测试受到权限拒绝；父目录不可搜索时不能把里层路径判为不存在。
换权限后仍需重新核验，不能承诺 root 必然可读。

`/proc/meminfo` 是已验证的内存读取来源；Zram 与更多指标的能力见
[DEV-005](facts.md#dev-005)，执行身份见 [DEV-002](facts.md#dev-002)。

原始拒绝信息、执行身份和采样条件比“成功 / 无效”标签更有用。
只保留会影响实现或判据的差异；完整命令目录由源码维护。
