# 文档入口

按任务读相关章节；链接用于导航，不要求通读。实现细节查源码，设备能力和非显然的限制查对应事实页。

| 任务 | 入口 |
|---|---|
| 写文档、更新结论、解决问题后的收尾 | [维护流程](CONTRIBUTING.md) |
| 查看仍决定要做的工作 | [待办](dev/todo.md) |
| 分层、新功能、实验退役、日志与导出 | [开发指南](dev/README.md) |
| CPU 控制、句柄和协议 | [CPU](cpu/README.md) |
| 温控策略、配置与解码 | [温控](thermal/README.md) |
| 真机权限、命令和排障 | [设备](device/README.md) |
| 其他功能和名词 | [工具](tools/README.md) |
| 系统升级、机型差异 | [兼容范围](VERSIONS.md) |
| 原始资料在哪里、如何复查 | [来源入口](SOURCES.md) |
| 有复用价值的测试 | [实验](experiments/README.md) |
| 外部实现摘录 | [参考资料](reference/README.md) |
| 回归对照和历史调查 | [归档](archive/README.md) |

`dev/` 是 `docs/` 内的开发指南目录；`cpu/`、`thermal/`、`device/` 按设备主题组织。
同一设备结论在该主题的 `facts.md` 维护，关联主题链接过去；代码中的常量、路由和功能清单以源码为准。

新 agent 的任务入口在仓库 [AGENTS.md](../AGENTS.md)。本地 Skill 提供快捷导航，
维护流程统一在 [CONTRIBUTING.md](CONTRIBUTING.md)，不用再复制一套规则。
