# AX 与本机 ROM 追踪结果

## 材料核实

实际存在 `../MTK-reference/rom/PD2430D_A_16.1.12.0.W10/implementation-build19/normal/`，不是只有配置。重新校验 manifest 中 171 个文件的长度与 SHA256，全部一致；包含 framework/vivo-framework/vivo-services JAR、Vivo/MTK 原生客户端与接口库。AX 的六个 jar 也在本地。未重新采集手机，未执行控制命令。

## CPU 私有参数：当前受阻

已追到 `VivoPerfManager → VPerformance`：平台/特权路径使用 native，其他路径连接 `vendor.vivoperfservice`，经 `IVivoPerfManager` 提交参数。AX 修改客户端字段后走 Binder，并不赋予平台身份。

检查 32/64 位 `libvivo-vperfd-client_system.so` 与 `libmtkperf_client.so` 的 ELF 动态符号：存在 `perf_lock_acq` 定义，导入 Vivo IVPerf、MTK IMtkPowerService/IMtkPerf 的服务连接接口。这些材料提供客户端传输线索，未定位到 9/71、88000003 的执行分支或最终约束合并算法。不能把字符串或导出函数名当成反汇编已证实的执行语义。

init 明确指向以下服务，但本地材料中没有对应执行文件：

- `/system/bin/vivoperfservice`
- `/vendor/bin/hw/vendor.vivo.hardware.vperf@1.0-service`
- `/vendor/bin/hw/vendor.mediatek.hardware.mtkpower-service.mediatek`

也没有 `libpowerhal.so`。旧枚举器没有逐文件记录 stat/open 失败，故只能说本地缺失，不能说手机不存在或必然不可读。本次未取得新手机文件。

当前几条 CPU 路线的结论：

| 路线 | 当前障碍 |
|---|---|
| `0xF0000C00=88000003` | 已有正常边界下实验未改变可读上限；缺底层资源语义 |
| `0xF0100000=9/71` | 已有对照未放宽上限；下限变化受背景混杂，恢复未完全确认 |
| Vivo 三簇 MAX=4095 | 已有 5 秒实验未放宽 policy4/7 上限 |
| VBoost CPU limit API | Java 端检查真实进程包与配置权限，level 1..6、timeout ≤10秒；没有 shell 可用授权证据，不伪造调用者 |
| 热配置／活动请求查询 | 已有报告中 thermal 文件和 Vivo dump 拒绝 uid2000；QoS 帮助未列入口，旧采集器没有实际执行 QoS 查询，不能称其被拒 |
| native 执行端静态分析 | 核心服务文件缺失，当前无法解释单值与范围的不同合并路径 |

**当前这些 CPU 高频范围解锁道路都难以继续落地，没有获得可交付的解锁实现。** 停止同参数盲试；只有取得匹配 ROM 的执行端文件或新的真实约束证据，才重新评估。不是所有 Shizuku 方法被证明不可能。

## 高刷：有实现依据，不能照搬参数

已找到本机链路 `RMNative → RMServer → RMServerExtension → RefreshRateAdjuster`。`services/sources/com/vivo/services/rms/sdk/RMNative.java` 中事务 36 读取 long、两个 string、14 个 int；事务 24 释放。`RefreshRateAdjuster.java` 的参数有名称，包括 display、resolution、fps、priority、duration、caller、client、flags、arg1..3，且检查功能支持和调用方/目标进程是否存在。

AX `common.sh:2283` 全局请求只有 11 个 int，游戏请求有 14 个。与本机完整布局存在差异，缺尾部字段的具体运行结果未实测，不将返回 OK 解释成有效高刷请求。后续应按本机布局构造、使用自己的 handle/期限，检查实际刷新率并验证释放。该路线没有被本次调查否定。

## 亮度：接口语义已进一步明确

从 `28-framework.jar` 用 JADX 单类提取 `vivo.common.ILcmBrightnessInterface`，本地结果 `../MTK-reference/derived/rom-analysis/lcm-interface.java`：

- 事务 8：`setBrightnessPolicyLimitPercent(float)`，返回 boolean。
- 事务 10：`getBrightnessPolicyPercent(int brightnessNit, int envLux)`，返回 int。

`VivoBrightnessPolicyService.java:571` 对策略关闭、TemperatureV3 支持等情况返回 false；命令没有异常不代表业务成功。`:1258` 的 get 方法更新内部亮度/环境输入，并通过消息或统一亮度映射更新处理结果，存在副作用。

所以 AX 的 `10 i32 100 i32 0` 是周期传入 100 nit / 0 lux 的策略查询，有更新副作用；不是“客户端注册回调”。后续仍需分析策略内部回调与真实生效条件、停止后系统接管方式，但此前将模块回调注册/注销视为缺失项不准确。高刷、亮度与 CPU 解锁不能混为同一失败结论。
