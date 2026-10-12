# Android 应用模块

此目录包含 MTK God 的 Android 应用工程。项目介绍和面向开发的文档入口见仓库根目录 [README.md](../README.md) 与 [docs/README.md](../docs/README.md)。

## 构建

需要 JDK 17+ 和 Android SDK（`compileSdk 37`）。在本目录运行：

```bash
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk    —— 日常开发
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk —— 交付用
```

release 包开混淆与资源压缩，签名读 `signing/keystore.properties`。
**`signing/` 被 gitignore**，新克隆的仓库里没有它，此时 `assembleRelease`
会因未签名而失败 —— 这是有意的，比静默退回 debug 签名好。密钥不能换：
换了签名的包装不到已装的包上，用户必须先卸载。

## 开发资料

- 当前能力和设备事实：按主题查看 `docs/` 下对应的 `facts.md`。
- 工程分层与不变量：[docs/dev/architecture.md](../docs/dev/architecture.md)。
- 文档目录及维护规范：[docs/README.md](../docs/README.md)、[docs/CONTRIBUTING.md](../docs/CONTRIBUTING.md)。
