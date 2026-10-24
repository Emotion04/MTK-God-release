# reference/ — 外部实现与 ROM 资料的结论

第三方实现摘录与固件接口追踪。**这里放结论，不放原件**：原件、解包与反编译树都在
仓库外的 `../MTK-reference/`（与本仓库同级，不进任何版本库），来源状态见 [来源台账](LEDGER.md)。
写法见 [规范](SPEC.md)，游戏蓝盒分析放在 [apps/](apps/) 下。

日常开发用不到这些，要查别人的实现时再进。

| 要查 | 入口 |
|---|---|
| 固定版本 App / 模块源码摘录 | [apps/](apps/README.md) |
| AX 模块命令与协议 | [vivo-ax-module.md](vivo-ax-module.md) |
| 固件接口追踪 | [ax-rom-trace-2026-09-19.md](ax-rom-trace-2026-09-19.md) |
| 游戏蓝盒 FPS 浮层 | [apps/youxilanhe-fps-module.md](apps/youxilanhe-fps-module.md) |
| 某条命令/事务码在源码第几行 | 仓库外 `../MTK-reference/derived/decompiled/INDEX.md` |

采用某个接口后：当前实现查源码，设备判断写回 [主题事实](../README.md)，
跨功能契约写入 [工程架构](../dev/architecture.md)。**这里只描述别人怎么做的，
不描述本项目怎么做。**
