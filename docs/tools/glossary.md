# 功能与术语

用于区分容易撞名的功能和接口。设备命令与验证结论见 [commands.md](../device/commands.md)，
源码定义当前功能；这里保留理解它们所需的名词与语义边界。

## 性能模式

| 名称 | 控制入口 | 含义 |
|---|---|---|
| 厂商性能档 | `power_save_type`、`system_property_power_mode_type` 等 settings | vivo 私有档位；编号来自固定版本取值，具体效果依赖 ROM |
| AOSP 固定性能模式 | `cmd power set-fixed-performance-mode-enabled` | 系统的固定性能测试模式；命令成功不保证设备实现了对应能力 |
| 跑分通知 | `com.vivo.gamewatch.action.BENCHMARK_START` | 厂商广播动作；广播派发与消费者实际处理分别验证 |
| MTK PERF_MODE | `PERF_RES_CPUFREQ_PERF_MODE`，`0x00414000` | PowerHAL 的资源；与厂商 settings 档位是不同接口 |
| 私有 debug prop | `debug.vivo.*` 等 | 固件私有参数，名字不能替代实现或效果依据 |

同名“性能模式”不能混用；资源编号、settings 档位和广播各有自己的协议。
厂商档位写入后可能归一化，见 [设备命令记录](../device/commands.md#游戏与性能配置)。

## CPU 与调度

| 名称 | 解释 |
|---|---|
| perfLock / handle | 服务保留的性能请求及其标识；句柄生命周期见 [协议](../cpu/protocol.md) |
| MIN / MAX | 请求的下限与上限；合并规则和最终限制由实现及其他请求决定 |
| MIN_HL / MAX_HL | 公开 BSP 的独立硬下限、硬上限资源；不是“传入就必然生效” |
| CPUCORE_MIN/MAX_CLUSTER | 在线核心数量的边界，不指定某个核心 |
| CPUCORE_ONLINE_CPU | 逐核在线控制资源 |
| CPUCORE_FORCE_PAUSE_CPU | 暂停调度的资源名，不能等同于核心下线 |
| cpufreq policy | 共享频率控制的一组 CPU；目录号与 PowerHAL 的簇索引不同 |
| ABE | vivo 私有调度开关 `abe_unified_switch`；没有可靠依据将其全称或机制写成 AOSP 标准 |
| HTP | 项目中高温保护预设的称呼，使用 `pem_htp_level` 等键；配置温度不等于实时传感器值 |
| `sys_uidcpupower` | 来源工具使用的全局键；具体消费者与语义需固件依据，不能仅凭名字认定为公开 AOSP 接口 |

请求返回、policy 限制、负载峰值是不同观察量。当前可用性见 [CPU 事实](../cpu/facts.md)。

## 画面与帧

### MEMC、插帧、超分

MEMC 是 Motion Estimation / Motion Compensation，运动估计与运动补偿。
插帧在已有帧之间生成中间画面；超分辨率提高画面的重建分辨率，两者可组合。

本项目涉及 `gamecube_frame_interpolation` 和 `gamecube_frame_interpolation_for_sr`。
取值是私有的冒号分隔格式；`0:-1:0:0:0` 是来源材料中的关闭哨兵，不可当作开启值。
有效的目标帧率、格式和支持范围需对应固件依据。

### 鹰眼与游戏画质增强

`gamecube_hawkeye_effective` 是厂商画质增强键。
写入/读回与实际画面变化分开确认；来源工具的值不自动适用于所有游戏和 ROM。

### SF 相位偏移与帧提交

SurfaceFlinger 的相位参数调整相对 vsync 的工作时机，例如
`debug.sf.*_phase_offset_ns`；单位 ns。它们影响显示合成时序，不能作为触控上报率的证据。

`auto_latch_unsignaled`、`latch_unsignaled`、`disable_backpressure` 等涉及缓冲提交和背压。
prop 可读写只说明存储状态；消费者可能在启动时读取，也可能根本不读取该键。
调参是否改善帧节奏需要对应观察量。

### 硬件叠加层与 HWC

HWC 是 Hardware Composer，负责安排可由显示硬件处理的合成。
CLIENT 合成通常由 GPU 渲染客户端目标；CPU 仍参与调度和提交。
“停用 HW 叠加层”是合成路径的调试选项，不能理解为更高性能的通用开关。

### 渲染去同步

`debug.hwui.disable_vsync`、`debug.cpurend.vsync` 是来源材料中的参数。
参数存在、可写与框架实际使用它，是不同问题。

## 触控

| 名称 | 解释 |
|---|---|
| 上报率 / 采样率 | 控制器采样与系统收到事件的频率；两者不能靠一次合成滑动直接等同 |
| MTK 触控参数 | 项目这一后端的 `debug.touch.report_rate` 等参数名称，不代表公开 MTK 标准协议 |
| 按下防抖 | `touch_scan_press_time_delay*` 键族的界面称呼；各字段单位、消费者需固件依据 |
| 指针速度 | `system pointer_speed`，影响指针设备的速度，不等于触屏硬件采样率 |
| 固件触控方案 | `TouchBackend.VIVO` 通过 `bbk_touch_screen_service` 请求整数方案，具体内容由固件解释 |

`vts_game_para_adjust` 是私有的逗号分隔键。历史手机记录有 `1,6,6,6`，
来源工具还使用 `1,7,7,7` / `0,3,3,3`；这些不能证明各段可独立调整，也不能仅凭名称
断定它控制触控。没有明显手感差异也不足以证明它没有消费者。
当前对照与测量限制见 [触控记录](../device/commands.md#触控)。

## 编译与维护

- **dex2oat / AOT**：把 dex 字节码编译为机器码。本项目单应用编译使用
  `cmd package compile -m speed-profile`；系统后台编译和手动编译是不同触发路径。
- **预热**：读取 APK 页面填入 page cache，与 AOT 编译不同；缓存可被系统回收，不保证下一次启动更快。
- **私人 DNS**：`private_dns_mode=hostname` 与 `private_dns_specifier` 配置 DoT 主机名，
  连通性需要另行检查。

## 项目内部名称

| 名称 | 职责与边界 |
|---|---|
| `Evidence` | 功能定义对验证程度的标记；它不是一次写入的退出码或实时效果证明 |
| `FeatureGate` | 根据包、prop、键等线索决定产品入口是否可用；通过门禁仍需验证效果 |
| `CommandSet.essential/optional` | 必需命令决定操作成败，可选项失败单独报告；分类应符合功能实际语义 |
| `SettingChannel` | 系统 settings 的统一写入、读回和恢复边界 |
| `DaemonExec` | 在 Shizuku UserService 的身份下执行命令；权限仍受节点与 SELinux 限制 |

键缺失可能是未初始化、按需创建或不支持，需结合消费者与固件证据判断。
特定 ROM 上的 `settings put` 静默失败记录见 [命令表](../device/commands.md#设置写入)；
原因未查明时保留未知，不把它写成所有 ROM 的机制。

相关职责见 [工程架构](../dev/architecture.md)；仍在推进的工作见 [todo.md](../dev/todo.md)。

