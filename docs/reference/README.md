# 外部实现与 ROM 资料

固定版本的第三方实现摘录与固件接口追踪，全部随仓库提交。
素材本体和来源状态在本地忽略的 [reference/](../../reference/README.md)
（台账：[LEDGER.md](../../reference/LEDGER.md)）；日常开发用不到，查外部实现时再进。

| 要查 | 入口 |
|---|---|
| 固定版本 App / 模块源码摘录 | [apps/](apps/README.md) |
| AX 模块命令与协议 | [vivo-ax-module.md](vivo-ax-module.md) |
| 固件接口追踪 | [ax-rom-trace-2026-09-19.md](ax-rom-trace-2026-09-19.md) |
| 游戏蓝盒 FPS 浮层 | [youxilanhe-fps-module.md](../tools/youxilanhe-fps-module.md) |

采用某个接口后，当前实现查源码，设备判断写回 [主题事实](../README.md)，
跨功能契约写入 [工程架构](../dev/architecture.md)。
