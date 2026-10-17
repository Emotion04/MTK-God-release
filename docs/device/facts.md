# 设备当前事实

本页记录开发设备的能力与非显然限制，范围见 [VERSIONS.md](../VERSIONS.md)。

<a id="dev-001"></a>
## DEV-001 当前开发基线

vivo V2430A / PD2430，MT6989，Android 16。
完整 ROM 指纹及本次 2026-10-05 只读核验见 [开发基线](../VERSIONS.md#当前开发基线)。

<a id="dev-002"></a>
## DEV-002 权限模型

2026-10-05 `adb shell id`：uid 2000，`u:r:shell:s0`；`getenforce`：Enforcing。
这不能单独证明 App 经 Shizuku 执行时环境、补充组和效果完全相同。
正式功能以实际执行路径的结果为准；退出码 0、Binder 无异常或句柄不能代替效果观察。

<a id="dev-003"></a>
## DEV-003 CPU 频率节点可见性

2026-10-05 本次 ADB 只读核验，policy0/4/7 均为：

| 节点 | 权限与属主 | 观察 |
|---|---|---|
| `scaling_min_freq` / `scaling_max_freq` | `0660 system:system` | 读 max 返回 Permission denied |
| `scaling_cur_freq` | `0444 root:root` | 可读 |

shell 不在 system 组，DAC 权限已足以拒绝；不能由相同 SELinux 标签断言 SELinux 必然允许。
旧测试中的 governor 也为 0660，此次未单独复查。

2026-09-25 曾记录约十分钟的 max 可读窗口；当前代码也保留过 max 为 0664 的观察。
变化机制未确定，不把窗口当成可靠能力。原始过程见
[上限调查](../archive/cpu/frequency-ceiling-investigation-through-2026-09-26.md)。

开发含义：每次按实际读取结果区分数值与未知，不能缓存“以前读得到”的能力判断。

## DEV-004 温度来源

`dumpsys thermalservice` 可读 HAL 命名温度、静态阈值和 framework status；
历史 `/sys/class/thermal/` 访问被拒。
framework 缓存、当前 HAL 读数和内核传感器不是同一来源，可能在同一份输出中不同。
框架状态不是厂商限频策略本身。
依据：2026-10-05 本次 dumpsys；sysfs 拒绝记录见 [commands.md](commands.md)。

<a id="dev-005"></a>
## DEV-005 GPU、DDR 与部分内核节点可见性

2026-10-02 的路径复核中，GPU devfreq、GED、DDR/DVFSRC、zram 统计及部分 MTK 节点
返回访问拒绝或未检出；具体路径见 [commands.md](commands.md#指标读取)。
这限定于已探测的路径和权限，不意味着所有读通道都已穷尽，也不保证 root 后必然可用。

发现目录不等于文件可读；正式控制仍须有取值范围和可用读回，
`GpuInfo.controllable` 的能力判断应保留。此次未重试这组路径。

<a id="dev-006"></a>
## DEV-006 settings 与属性写入

旧对照记录中，`settings put` 存在退出 0 但值未改变的情况；
`content insert` 对已测键可写并读回。不能据此推断所有键或 ROM 的内部拦截机制。

实现通过 `SettingChannel` 选择受控命令并核对结果。
键写入、固件消费该键、用户可见效果是三个不同判据。
依据：历史对照见 [commands.md](commands.md#设置写入)；此次未做写入。

<a id="dev-007-有效频率上限随温度漂移"></a>
<a id="dev-007"></a>
## DEV-007 负载峰值随运行条件变化

2026-10-05 旧 ADB 测试，同次开机四轮“负载 8 秒 + 冷却 12 秒”：

| 轮次 | policy0 峰值 | policy4 峰值 | policy7 峰值 |
|---|---|---|---|
| 1–2 | 1.9 GHz | 2.6 GHz | 2.6 GHz |
| 3–4 | 1.8 GHz | 2.4 GHz | 2.2 GHz |

可确认采样峰值下降；负载分布、cpuset、调度与热状态均会影响结果，
这些数值不是对真实频率上限的直接测量，也不足以单独证明温度是唯一原因。

测效果时通过 App 的 `CpuLoadRunner` 控制负载并记录采样条件；
ADB 的 `yes` 与 App 负载执行环境不同。即使 App 满载，峰值也只证明测试期间达到过该频率。
配置对比应尽量保持热状态、其他请求与负载可比。
关联：[CPU-003](../cpu/facts.md#cpu-003-频率上限由系统温控约束)。

<a id="dev-008"></a>
## DEV-008 构建签名：debug 与 release 是两把不同的密钥

本机 `assembleDebug` 用 `~/.android/debug.keystore`（`CN=Android Debug`）；
`assembleRelease` 用 `signing/canary-release.jks`（已 gitignore）。两者证书不同，
所以 **release 包不能覆盖安装 debug 包**，只能先卸载 —— 而卸载会清掉本地方案、设置和报告。
`signing/` 缺失时 release 构建故意失败，不退回 debug 签名。

debug keystore 的位置由 `ANDROID_USER_HOME` 决定：指到别的目录会换出一把新密钥，
`adb install -r` 报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match`。
这不是版本兼容问题，改成对的 keystore 重新构建即可，**不要去卸载**。
2026-09-25 踩过一次；2026-10-17 复核当前 debug APK 指纹与已装版本一致。

依据：`apksigner verify --print-certs` 与 `keytool -list -v` 对照。
