# 温控

## 先读

| 任务 | 文档 |
|---|---|
| 判断当前默认策略、手动策略和已知边界 | [facts.md](facts.md) |
| 查策略文件和字段 | [configs/README.md](configs/README.md) |
| 查应用与策略索引的固件配置 | [configs/app-whitelist.md](configs/app-whitelist.md) |
| 查字段含义 | [configs/field-reference.md](configs/field-reference.md) |

当前事实只在 `facts.md` 维护。2026-09-26 以前把推测、实验和修正混写的长篇调查已移入
[历史调查](../archive/thermal-investigation-through-2026-09-26.md)。

温控策略文件能够在运行时读取时，以设备现场文件为准；文档中的配置快照用于理解和回归，
不能代替新 ROM 上的能力探测。
