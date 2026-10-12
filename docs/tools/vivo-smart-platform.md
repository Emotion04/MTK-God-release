# 智算平台的证据边界

实现入口：`SmartPlatform`、`SmartBackup`、`VivoTweaks`；
界面和当前候选清单查源码，本页保留容易误判的语义。

## 配置与功能效果

系统页面截图可证明开关存在，不证明其 Settings 键。
其他机型分析中的命令使用 root，也不能据此判断当前 ROM 必须 root 或存在对应消费者。

历史定向搜索仅在外部脚本发现 `system ai_turbo_enabled` 的使用；
未覆盖所有系统 APK/native 实现。这个键的读写与官方 AI 开关或服务实际状态的关系尚未确认。
`secure vivo_aie_privacy_switch` 是隐私授权相关候选，与 AI 性能开关分开处理。

Settings 回读只证明存储值；`service check` 和 dumpsys 返回只证明相应可查询性，
不证明 shell 拥有控制接口或引擎正在工作。`null` 也不等于不存在消费者。

## 档案与恢复

首次读取的档案是当时配置，不能追溯为出厂状态；
操作前快照、初始档案和导入档案承担不同职责。
Settings 可能跨重启保留，也可能被系统覆盖；停止授权不保证恢复。

恢复使用对应快照，按设备与 ROM 核对可写范围；其他候选可记录，不因存入档案就获得恢复语义。
诊断报告不是可导入的恢复文件，导出完成也不证明其中候选功能生效。

控制、实验、日志与输出的共用边界见 [工程架构](../dev/architecture.md)；
相关 ROM 接口见 [追踪记录](../reference/ax-rom-trace-2026-09-19.md)。
