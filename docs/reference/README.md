# 外部实现与 ROM 资料

用于查接口、命令及一手实现位置。静态代码、配置、推断和设备效果按具体条目区分，
不把整个目录统一标成“已验证”或“推断”。

| 要查 | 入口 |
|---|---|
| 四份固定版本 App / 模块源码摘录 | [apps/README.md](apps/README.md) |
| AX 模块命令与协议 | [vivo-ax-module.md](vivo-ax-module.md) |
| 固件接口追踪 | [ax-rom-trace-2026-09-19.md](ax-rom-trace-2026-09-19.md) |

原始第三方资料留在本地忽略的 `reference/`；它与这里的分析文档是不同目录。
采用某个接口后，当前实现查源码，设备判断写回 [主题事实](../README.md)，
跨功能契约写入 [工程架构](../dev/architecture.md)。旧移植建议不作为当前任务。
