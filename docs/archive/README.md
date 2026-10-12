# 历史档案 📦

**结论可能已被推翻，只在要查"当时怎么想的"时看。**

| 文档 | 为什么留着 |
|---|---|
| [cpu-probe-guide-before-2026-09-19.md](cpu-probe-guide-before-2026-09-19.md) | 探针指南旧版 |
| [cpu/](cpu/) | CPU 上限、范围、ROM 和探针调查过程 |
| [thermal-investigation-through-2026-09-26.md](thermal-investigation-through-2026-09-26.md) | 温控调查过程；当前结论见 `thermal/facts.md` |
| [evidence/](evidence/) | 按版本号归档的探针原始输出 |
| [reports/](reports/) | 按版本归档的完整报告 |

## CPU 调查导航

| 要追溯 | 档案 |
|---|---|
| 高频上限、温控解码和后续对照 | [截至 2026-09-26 的调查](cpu/frequency-ceiling-investigation-through-2026-09-26.md) |
| 区间控制的早期判断 | [早期区间调查](cpu/range-investigation-early.md) |
| 2026-09-16 的相机、ROM 探针与分析 | [分析](cpu/probes/2026-09-16-analysis.md)、[相机](cpu/probes/2026-09-16-camera.md)、[相机复测](cpu/probes/2026-09-16-camera-v2.md)、[ROM](cpu/probes/2026-09-16-rom.md) |
| 2026-09-18 至 19 的 ROM 配置与实现分歧 | [完整配置](cpu/rom/configs-2026-09-18.md)、[实现](cpu/rom/implementation-2026-09-19.md)、[策略转向](cpu/rom/policy-pivot-2026-09-19.md)、[Vivo 上限](cpu/rom/vivo-limit-2026-09-19.md)、[Vivo 策略](cpu/rom/vivo-policy-2026-09-19.md) |
| 原始日志和旧完整报告 | [探针日志索引](evidence/README.md)、[0.3.3 完整报告](reports/2026-09-12-vivo-v2430a-0.3.3.txt) |

这些是历史观察，遇到冲突先回到对应主题的当前事实和适用 ROM。新增有保留价值的档案时，按调查阶段补到本索引；原始探针仍归 `evidence/README.md`。
