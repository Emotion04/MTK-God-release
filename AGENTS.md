# AGENTS.md

本仓库是 Kotlin / Compose Android 应用，通过 Shizuku 调用 MTK PowerHAL。
文档与新增注释用中文；代码标识符、命令和路径保持原样。

## 按任务取上下文

- 不确定入口时看 [docs/README.md](docs/README.md)，已有信息足够就开始工作，链接按需阅读。
- 改文档、解决问题或完成计划时，按 [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md) 同次更新当前说明和引用，删掉已完成、取消或被替代的待办。任务集中在 [docs/dev/todo.md](docs/dev/todo.md)。
- 读 `/sys`、`/proc`、`dumpsys` 或写 `settings`、`setprop` 前，先读 [设备入口](docs/device/README.md)。
  解释 vivo V2430A 数据时查当前设备事实与适用 ROM；缺少节点或事务码依据时用 adb 核实。
- 实际改变 CPU 控制行为时读 [CPU 事实](docs/cpu/facts.md)，协议和历史调查按具体问题选择。
- 开发契约见 [architecture.md](docs/dev/architecture.md)，UI 与文案见 [ui.md](docs/dev/ui.md)、[wording.md](docs/dev/wording.md)。
  不认识的功能名和缩写查 [glossary.md](docs/tools/glossary.md)。
- 设备结论统一写回 `docs/` 的主题事实页；本地 `.claude/skills/` 提供导航。
  比较新旧证据时核对机型、ROM、身份与前置状态，可比条件下采用最新有效核验。
- 并行重构文档时先收敛规范再写内容，按 [维护流程](docs/CONTRIBUTING.md) 分配文件维护者；日常开发同次更新相关说明。
  不在不同主题页复制同一当前结论。`docs/dev/wording.md` 与 `tools/check_strings.py` 改动必须一起核对。

## 工程边界

- CPU 操作由 `CpuControl` 协调，保留请求所有权、串行 apply/release 和释放失败时的句柄。
- 系统设置写入经 `SettingChannel` → `DaemonExec` → Shizuku UserService；
  新后端在该边界适配，保持统一身份、结果和读回。恢复打开前保存的原值及原键不存在的状态。
- 系统控制区分请求接受与实际效果。未知读数明确为未知；实验有限时、可清理，
  退役时处理请求和持久恢复状态。具体要求见架构指南。
- 用户可见文案（含短按钮、通知和无障碍描述）进 `res/values/strings.xml`，
  通过 `stringResource()` / `getString()` 展示，变量用资源占位符。
  新增或修改文案时读 [文案规范](docs/dev/wording.md)，按最终展示位置复核。
  设备原文、协议标识和工程日志按数据处理。
- 软件端代码、字符串和注释使用中性命名，不出现其他 App 或第三方模块名称及其指代；
  开发分析文档可以引用。`reference/` 是被忽略的第三方原始资料，分析放进 `docs/reference/`。
- 新 APK 的 `versionCode` 递增；只改文档、仅本地测试或重新提供同一 APK 不递增。
  **不递增的代价不是"版本号难看"**：安装会**静默什么都不做**，设备上继续跑旧代码 ——
  拿错构建调试已经浪费过一整个晚上。

## 验证与构建

验证匹配变更：文档检查链接和机械漂移，UI 检查编译和受影响交互，CPU 控制检查相关逻辑与恢复。
桌面构建不能代替设备效果验证；新假设或证据缺口会改变判断时再安排对应真机测试。

文档改动运行 `python tools/check_docs.py`（链接、锚点、总索引可达性、显式待办位置、
反引号里点名的代码标识符是否存在）；
文案改动运行 `python tools/check_strings.py`（资源引用、硬编码基线、特定字符串形态）。
脚本只做机械检查，语义对照代码和证据判断。

需要 JDK 17+、Android SDK（compileSdk 37）和 **Python**（构建前会跑
`tools/check_docs.py` 与 `tools/check_strings.py`；没装 Python 的机器用
`-PskipChecks` 跳过，正常开发不要用）。在 `mtk-optimizer` 下运行：

- `./gradlew assembleDebug` —— 日常开发，输出 `app/build/outputs/apk/debug/app-debug.apk`，用 debug 签名。
- `./gradlew assembleRelease` —— **交付给用户的就是这个**，输出
  `app/build/outputs/apk/release/app-release.apk`。它开了混淆与资源压缩，
  并从 `mtk-optimizer/signing/keystore.properties` 读签名。

**签名目录已被 gitignore，新克隆的仓库里没有它**，此时 `assembleRelease` 会因为未签名而失败——
这是有意为之（比静默退回 debug 签名好）。拿到密钥后才能出交付包。

**签名密钥不能换。** 换了签名的 APK 装不到已装的包上，用户必须卸载重装，
本地方案、设置和报告一起丢。密钥要离线备份，也别提交进版本库。

环境正常时不重复做安装或迁移检查。混淆只在 release 生效，改过 `proguard-rules.pro`
才需要重跑序列化与提权链路；具体清单见 `app/proguard-rules.pro` 末尾。

已知摩擦（都在这台 Windows 上遇到过）：

- 构建偶发失败，报 `Permission denied` / `Could not add entry … to cache fileHashes.bin` /
  `Failed to clean up output files …` —— 这是文件锁（Defender、搜索索引一类），**不是代码问题**，
  Kotlin 编译其实已经过了。**重跑一次就好**；持续出现就 `./gradlew --stop`，
  删掉 `mtk-optimizer/.gradle/9.4.1/fileHashes` 再构建。**不要用 `--rerun-tasks`**，会让锁更严重。
- `.gradle/`、`build/`、`.kotlin/` 都被 gitignore。拉取后构建异常时，删 `mtk-optimizer/.gradle` 是安全的。
- `libs.versions.toml` 里声明了 `hilt` 和 `ksp`，但**两个都没有启用** —— 依赖注入是手写的
  `AppContainer`。除非你打算正式迁移，否则忽略它们。
