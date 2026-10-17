# 智算平台的证据边界

实现入口：`SmartPlatform`、`SmartBackup`、`VivoTweaks`；
界面和当前候选清单查源码，本页保留容易误判的语义。

## 这是厂商特供功能，名字就该带着厂商

智算平台**是 vivo ROM 自带的**，不是通用能力。凭据是候选清单里的键与服务名：
`vivo_aie_privacy_switch`、`vivo_gamethermal_switch`、`vivo_qs_center_theme_style`、
`vendor.vivoperfservice`、`vivo_gfx_display_service`……换个厂商的 ROM，这些全都不存在，
`SmartPlatform.parse` 读回空值，界面上就是"读不到"。

**所以界面写「vivo 智算平台」不是违规。** `AGENTS.md` 那条
"不出现其他 App 或第三方模块名称及其指代"，禁的是**别的调优 App 的名字**
（拿别人做的东西当自己的卖点）；厂商名是**平台的自我标识**，和 MTK、PowerHAL 同一类 ——
本 App 就叫 MTK Controller，全篇都在写 MTK 的资源号，没有理由单独把 vivo 抹掉。
抹掉反而更糟：用户看到"智算平台"不知道说的是什么，也不知道换台机器为什么没了。

判断口径：**这个名字属于设备还是属于别人做的软件？** 属于设备 → 照实写；
属于另一个 App / 第三方模块 → 换成中性说法。判不准就查这个键是不是该厂商专有。

（横屏/小米/OPPO 同理。真遇到非 vivo 机器上这个入口有实际影响，再按机型决定是隐藏
还是标注"本机无此功能" —— 现在不预判，没有证据。）

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
