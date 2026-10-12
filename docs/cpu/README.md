# CPU 频率控制

## 先读

| 任务 | 文档 |
|---|---|
| 判断当前能力、限制和兼容范围 | [facts.md](facts.md) |
| 排查高频区间无法达到 | [CPU-003](facts.md#cpu-003-频率上限由系统温控约束) → [THERM-001](../thermal/facts.md#therm-001-系统默认温控) → [温控频率表](../thermal/configs/README.md) |
| 改 PowerHAL 下发和句柄生命周期 | [protocol.md](protocol.md) + [工程架构](../dev/architecture.md) |
| 查资源 ID | [resources.md](resources.md) |
| 回归已稳定的单值行为 | [known-good.md](known-good.md) |

`facts.md` 是 CPU 当前事实唯一入口。日期调查、旧状态页和被推翻的路线已移入
[历史调查](../archive/cpu/)。历史文件只用于追溯，不能作为当前待办或当前结论。

## 文档归属

- CPU 下限、上限、区间和读回限制：本类。
- 哪份温控策略生效、温度阶梯：见 [温控当前事实](../thermal/facts.md)。
- 具体机型的权限和命令：见 [设备当前事实](../device/facts.md)。
