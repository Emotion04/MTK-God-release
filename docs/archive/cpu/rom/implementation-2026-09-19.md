# normal / boost 实现包对比与分析

## 完整性与 diff

用户提供 build19 的 `rom-configs-normal/` 和 `rom-configs-boost/`。
两包各 171 项采集文件全部通过 manifest 的长度和 SHA256 校验；含 manifest 共 **172 个文件逐字节相同**，无新增、缺失或内容差异。
这仅说明静态导出一致，不能解释成 boost 没生效；本包没有运行时频率约束、活跃句柄或模式切换轨迹。

原件复制并逐项核验于忽略目录 `reference/rom/PD2430D_A_16.1.12.0.W10/implementation-build19/{normal,boost}/`。
反编译位于 `reference/derived/rom-analysis/{framework,services}/sources/`，均不提交公开仓库。
源文件 `70-vivo-framework.jar` SHA256=`846fa4f89c2b1f2f4dfba947466738c507012fd900f5e842358b0a81d0edc8c3`；
服务来源 `78-vivo-services.jar`，对应哈希见原 manifest。
JADX 完成输出但分别报 9/21 个反编译错误；不得把全部输出当源码真值。以下关注的策略方法没有反编译失败占位，涉及复杂控制流的后续修改仍需对照 DEX。

## 有用的新发现

1. **存在独立的 Vivo CPU 限制资源。** `com/vivo/services/qos/QoSConstants.java:124` 定义 `VPERF_VIVO_CPU_LIMIT_CONTROL=-268432384`，即 `0xF0000C00`。`qos/policy/list/CPULimitPolicy.java:106` 经 Vivo `perfLockAcquire` 提交该资源和值 `88000003`，有场景请求管理和释放逻辑。数值不是频率，不能推断为解锁等级。
2. **对外 CPU 限制功能有权限和限时。** `vboost/function/lists/CpuLimitFunction.java` 的 set 分支要求 timeout 为 1..10000 ms、level 为 1..6，并按权限限制最高 level；资源参数为 `88000009+level`。注册核对进程包名和配置权限。不能靠伪造包名调用，也不能由此认定 shell 可用或必被拒绝：直接 Vivo Binder 服务有自己的权限边界。
3. **MTK6989 有具体场景上限代码。** `qos/policy/list/CPUFreqPolicy.java:67–80` 将 MTK6989 纳入 Activity 切换限频（排除部分其他机型）；实际触发还要求 `sAppStartFreqSupport`、sceneId/type 等条件。参数组中存在 2000/2400/2400 和 2000/2700/2900；这是场景策略存在的证据，尚不能认定为持续的 1800/2400/2300 来源。
4. **上层资源命名空间与本应用不同。** 上述 Vivo 路径使用 `0x40804100/0x40804000/0x40804200`，参数 2000/2400/2900；本应用直连 MTK 用 `0x0040xxxx` 和 kHz。不能把 Vivo 参数照抄到 MTK Binder，也不能按数字顺序猜簇映射。
5. **热策略线索得到加强。** `MultiTaskPerformancePolicy.java:57` 将 `0xF0100000=9` 命名为 `THERMAL_TYPE_PARAM`，支持它与热策略有关；尚无证据说明相机的 71 如何处理，更不是“71=关闭温控”。
6. **当前 ROM 的 Vivo Binder 协议已获得。** `com/vivo/framework/vperf/IVivoPerfManager.java` 中 acquire=4（duration、int[]，同步 int 返回），按句柄 release=2，setClientBinder=6。`VPerformance.java:173` 对平台/特权应用走 native，对其他调用走 Binder。客户端有请求通道不代表服务端授权成功。

7. **三簇 MAX 数组是待验证线索。** `CPUFreqPolicy.java` 的 `LIMIT_PARA_ALL_CORES` 是 `MAX_CLUSTER_1=2400`、`MAX_CLUSTER_0=2000`、`MAX_CLUSTER_2=2400`；`LIMIT_PARA_ALL_CORES_27_29G` 是 `2700/2000/2900`。对应资源为 `0x40804000/0x40804100/0x40804200`。这些场景数组尚未证明是持续上限的来源，不能据此解释单值与范围的差异或 boost 行为。
8. **9/71 未验证为解锁资源。** `vperfmaps.xml` 把它们作为场景参数传入，但 `CPUFreqPolicy` 的限频数组另外携带三簇 MAX。已有试验未观察到单独提交 `0xF0100000=9/71` 放宽可读上限；执行端的完整语义仍未知。
9. **终止性候选已实测失败。** ROM 的 `BOOST_CPU_MAX_FREQ` 使用三簇 MAX=4095；0.3.20 复用这三对资源做 5 秒回读。报告中整组请求返回句柄（`ACCEPTED`），但 policy4/7 上限全程 2400/2300 MHz，释放后仍相同，仅证明本次 MAX=4095 请求没有放宽可读上限，应停止同类参数试错。

## 边界与下一步

这次得到真实 ROM 的接口和 CPU 场景策略，调查已从猜参数推进到具体调用链；但 **没有证明解除限制或高频动态范围成功**。
包内有 init 文件指向 `/system/bin/vivoperfservice`，却没有该执行文件；也没有实际 `libpowerhal.so`。当前枚举器会跳过 `isFile=false` 项而未逐路径解释，因此 171/171 只代表选中项完整，不能说执行端全齐或文件不存在。

先用已有 JAR/原生客户端继续核对协议、资源与调用者限制。后续有依据的最小实验应走独立 Vivo 请求与对应释放，和原 MTK 范围组合对照、有限时并回读；不能直接替换稳定单值路径，不能把所有系统场景一并开启。无需用户再做 normal/boost 静态重采集。

0.3.20/build23 已加入上述一次性候选并通过本地单元测试；真机结果已归档，证明按钮的 `ACCEPTED` 不代表效果。

