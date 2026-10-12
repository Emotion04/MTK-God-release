# CPU 单值已知可用基线：0.3.8 / build 11

用户在 vivo V2430A / MT6989、Android 16、Shizuku ADB uid 2000 下确认：
单值频率可正常改变，包括高于范围模式动态边界的频率；绑定与释放正常。
范围上限低于约 1800/2400/2300 MHz 时有效，跨边界仍受限制。
这些是用户观察，不保证所有温度、负载、ROM 或长期运行条件下都成立。

## 单值必须保留的形状

每簇同一 kHz 值 v：MIN=v、MAX=v、MIN_HL=v、MAX_HL=v。
对应 base 为 0x00400000、0x00404000、0x00408000、0x0040C000；
资源 ID = base + 扫描索引 * 0x100。policy0/4/7 对应 index 0/1/2。
不能将 policy 数字直接当索引。此页保存单值回归与恢复副本；当前区间请求以
`CpuControl.applyLocked` 为准，协议说明见 [protocol.md](protocol.md)。

**这个四值形状只在频率区间模式为「上下限都发」时成立。** 模式是可调的
（设置 → 通用 → 频率区间模式，见 `TweakCommands.RangeMode`）：

| 模式 | 实际下发 |
|---|---|
| 上下限都发（默认） | MIN、MAX、MIN_HL、MAX_HL —— 上面那个形状 |
| 只发下限 | 只有 MIN、MIN_HL |
| 只压上限 | 只有 MAX、MAX_HL |

所以**跑回归前先确认模式**：在"只发下限"下测会看到两个值，那不是形状坏了。
`applyLocked` 按 `rangeMode()` 跳过对应的键，见 `CpuControl.kt` 的区间构造。

## 请求路径

**正式功能的 CPU 请求都通过 `CpuControl`**；稳定 `RuntimeUserService` 是实际 Binder 调用者。
替换前释放旧句柄；释放调用失败不获取新句柄。操作互斥包含持久化和验证。
获取句柄与保存关键段不因协程取消中断。轮询在锁内读取最新设置。
oneway 释放是提交确认，200 ms 等待不是完成屏障。句柄成功不是频率生效证明。

**例外是实验室的逐 ID 原始测试**：它直接经 `PerfLockController` 下发四个 base，
不经过这条协调路径。这类请求的所有权和效果都未经验证，不能当作正式能力
（架构上的区分见 [工程架构](../dev/architecture.md) 的"CPU 请求所有权"）。

## 实际恢复副本

已在本机保存（不纳入 Git）：
- `.local-baselines/build11-source.zip`：app/src、app 构建脚本、根构建脚本、
  settings、Gradle 属性、版本目录与 wrapper 目录。
  SHA256：AB74775C2A46AA344A076E31F6D623FE3C38B1411F707AA9C15C3EC881DA0684
- `.local-baselines/build11.apk`：修改探测器前 APK。
  SHA256：E63649718B05172AE07599BAA1526837BD2357D6153B1A9D5749E5E9B02C36FD

推荐恢复副本：`.local-baselines/build11-restorable.zip`，保留项目目录结构与启动脚本。
SHA256：5D245BF4E85F38C6EE59DA7D5C763E1F60A4FBBC18CF1F569BB26050C948AEBC。
解压后的 build11-tree 是项目根目录，补上本机 local.properties 指向 Android SDK 后构建。
恢复时先解压到新目录核对，
不要覆盖当前未提交工作。安装旧 APK 可能需要卸载或允许降级；优先以原源码重新构建
并递增版本号。卸载前释放请求、导出配置。不要以删除应用代替释放 HAL 请求。

回归：低单值→高单值→释放、重复操作、应用重启、轮询期间手动应用与释放、
低范围与跨边界范围。回归时比较相同 ROM、权限、温控与负载条件，分别记录单值与区间结果。
