# 历史探针日志

这些输出描述采集时的状态。当前结论见 [CPU 事实](../../cpu/facts.md)、[设备事实](../../device/facts.md)；
调查阶段见 [归档索引](../README.md)。这里按时间定位原件，不维护开发任务。

## 已保留原件

| 日志 | 用途与限制 | 原文件名或分析 |
|---|---|---|
| [0.3.21 策略快照](2026-09-19_133924-v0.3.21-policy-snapshot.txt) | 热配置和 Vivo dump 拒绝 shell；QoS 查询被跳过 | [转向分析](../cpu/rom/policy-pivot-2026-09-19.md) |
| [0.3.20 MAX=4095](2026-09-19_045235-v0.3.20-vivo-source-max.txt) | 请求被接受，policy4/7 仍为 2400/2300 MHz；不证明所有接口都无效 | [转向分析](../cpu/rom/policy-pivot-2026-09-19.md) |
| [0.3.19 策略 9→71→9](2026-09-19_040234-v0.3.19-vivo-policy-9-71-9.txt) | 上限未放开，下限上升且释放后未完全回落；归因与恢复均未确认 | [分析](../cpu/rom/vivo-policy-2026-09-19.md) |
| [0.3.18 日常上限下的 Vivo 请求](2026-09-19_035035-v0.3.18-vivo-limit-normal-cap.txt) | 88000003 未改变 2400/2300 MHz 上限 | [分析](../cpu/rom/vivo-limit-2026-09-19.md) |
| [0.3.17 Vivo 请求](2026-09-19_033740-v0.3.17-vivo-limit-api.txt) | uid2000 取得句柄，未观察到上限放开 | [分析](../cpu/rom/vivo-limit-2026-09-19.md) |
| [0.3.14 ROM 快照](2026-09-16_231546-v0.3.14-rom-control-snapshot.txt) | 接口与权限背景；六份配置截断 | cpu-probe-系统接口快照.txt；[分析](../cpu/probes/2026-09-16-rom.md) |
| [0.3.13 连拍](2026-09-16_202234-v0.3.13-camera-burst-readonly.txt) | 活跃相机与高上限同时出现，未识别内部控制协议 | cpu-probe-相机探测v2.txt；[分析](../cpu/probes/2026-09-16-camera-v2.md) |
| [0.3.12 相机](2026-09-16_185221-v0.3.12-camera-readonly.txt) | 捕捉到 3.25/3.4 GHz 端点；dumpsys 正文缺失，不能归因 | cpu−相机探测.txt |
| [0.3.11 六阶段负载](2026-09-16_001859-v0.3.11-policy7-load-comparison.txt) | 2500–3100；原配置空、完整结束，未证明范围可用 | cpu-probe-1789489339005.txt |
| [0.3.9 手动分项](2026-09-15_225348-v0.3.9-manual-probe-partial.txt) | 部分提前停止；原配置含三簇单值，非干净基线 | 2026-09-15 225348 MTK God 0.3.9 ROM=PD2430D_A_16.txt |
| [0.3.7 系统边界](2026-09-15_173410-v0.3.7-vendor-ceiling.txt) | 低范围改善后跨边界仍受限 | mtk-god-report-20711.txt |
| [0.3.6 范围异常](2026-09-14_175910-v0.3.6-range-failure.txt) | 早期写入与设备诊断 | mtk-god-report-20710.txt |
| [0.3.3 设备诊断](../reports/2026-09-12-vivo-v2430a-0.3.3.txt) | 最初设备与权限背景 | 既有归档名不变 |

原件按日志时间命名，内容保持原样；SHA-256 见 [sha256.json](sha256.json)。
早期文件来自用户指定的桌面/下载目录，后续来自 APK 输出目录；此处副本不受构建清理影响。
源码与 APK 恢复副本见 [单值基线](../../cpu/known-good.md)。

完整 ROM 配置原件在本地忽略目录
`reference/rom/PD2430D_A_16.1.12.0.W10/rom-configs-1789740529639/`；
[配置分析](../cpu/rom/configs-2026-09-18.md) 记录哈希。

## 仅剩分析的缺失原件

旧索引记载以下文件，但当时搜索项目及 build 目录未找到副本。
现有分析见 [旧探针指南](../cpu-probe-guide-before-2026-09-19.md)，证据不完整。

- `cpu-probe-1789484916067.md`：较早完整探测，含原单值配置。
- `cpu-probe-early−主动打开相机.txt`：23:20 相机对照。
- `cpu-probe-later−静置.txt`：23:23 静置对照。

