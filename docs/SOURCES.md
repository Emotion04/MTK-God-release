# 来源入口

证据在结论旁简写，公共原始资料从这里定位。维护方式见 [CONTRIBUTING.md](CONTRIBUTING.md)。

| 材料 | 位置与复查入口 |
|---|---|
| 当前机型、ROM 和权限身份 | [VERSIONS.md](VERSIONS.md)；现场 `getprop`、`id`、`getenforce` |
| 温控原件与明文 | 本地忽略目录 `.local-baselines/thermal-crack/`；[文件清单、哈希、解码和重取方法](thermal/configs/README.md) |
| 应用与场景选路表 | `.local-baselines/policy-select/`、`.local-baselines/pull/powerscntbl.xml`；[静态映射](thermal/configs/app-whitelist.md) |
| PowerHAL 与资源定义 | [协议](cpu/protocol.md)、[资源表](cpu/resources.md) 各自的一手实现路径 |
| 历史设备输出 | [evidence/README.md](archive/evidence/README.md) 及 [归档索引](archive/README.md) |
| 外部资料 | 被忽略的 `reference/` 原件；[分析入口](reference/README.md) |

代码证明 App 发出什么；配置证明文件写了什么；设备读回证明指定条件下观察到了什么。
它们共同支撑判断，任何一种都不能自动证明另一机型或场景的效果。
源码能直接回答的实现细节无需复制；影响结论的测试保留日期、范围与可复查入口即可。
