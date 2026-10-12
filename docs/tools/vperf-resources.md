# vivo 资源映射摘录

来源：2026-09-24 从开发设备拉取的 vendor 配置，本地 `.local-baselines/pull/`：
`powercontable.xml` 为资源名 → opcode，`powerscntbl.xml` 为场景 → 参数，
`vperfmaps.xml` 为 vivo boost 映射。ROM 范围见 [开发基线](../VERSIONS.md)。

## 场景 3160

注释为 `benchmark mode package switch boost`，以下是文件中配置，不是本项目实测效果：

| 资源 | 配置值 |
|---|---:|
| `PERF_RES_CPUFREQ_MIN_CLUSTER_0` | 1800000 |
| `PERF_RES_CPUFREQ_MIN_CLUSTER_1` | 2850000 |
| `PERF_RES_CPUFREQ_MIN_CLUSTER_2` | 3250000 |
| `PERF_RES_KICK_IDLE_SPEED_UP` | 1 |
| `PERF_RES_SCHED_PACKING_CPU` | 0 |
| `PERF_RES_SCHED_ADAPTIVE_MARGIN` | 0 |
| `PERF_RES_SCHED_CLOSE_PELT_ENABLE` | 0 |

同组出现在 3161 / 3163。当前 cluster0/1/2 对应 policy0/4/7。
配置频率不能证明运行中达到该值，更不能据此认定高频受限是缺少 PELT 配合。

## 调度资源

| 资源 | opcode |
|---|---|
| `PERF_RES_SCHED_CLOSE_PELT_ENABLE` | `0x1000c900` |
| `PERF_RES_SCHED_ADAPTIVE_MARGIN` | `0x1000c200` |
| `PERF_RES_SCHED_PACKING_CPU` | `0x1000c500` |
| `PERF_RES_KICK_IDLE_SPEED_UP` | `0x1000c800` |
| `PERF_RES_SCHED_UCLAMP_MIN_TA` | `0x01408300` |
| `PERF_RES_SCHED_UCLAMP_MIN_FG` | `0x01408100` |
| `PERF_RES_SCHED_UCLAMP_MAX_TA` | `0x01408900` |

名称和静态映射提供接口线索，不单独证明调用权限、消费者或运行效果。
完整资源命名空间见 [CPU 资源参考](../cpu/resources.md)。

已检查的相机场景主要含 FPS/FBT/CFP 配置，未见直接 CPU 频率命令；
这不足以排除其他 hint、间接调度或策略选路，也不能确认相机高频来自哪个机制。
当前 CPU/温控判断见 [CPU-003](../cpu/facts.md#cpu-003-频率上限由系统温控约束)。

