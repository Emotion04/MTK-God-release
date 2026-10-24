# Android 应用模块

此目录包含 MTK God 的 Android 应用工程。项目介绍见上一级 [README.md](../README.md)。
公开仓库只放源码快照；完整开发文档在私有仓库。

## 构建

需要 JDK 17+ 和 Android SDK（`compileSdk 37`）。在本目录运行：

```bash
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk    —— 日常开发
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk —— 交付用
```

release 包开混淆与资源压缩，签名读 `signing/keystore.properties`。
**`signing/` 被 gitignore**，新克隆的仓库里没有它，此时 `assembleRelease`
出的是未签名包，不能覆盖安装已发布的版本 —— 这是有意的，比静默退回 debug 签名好。
密钥不能换：换了签名的包装不到已装的包上，用户必须先卸载。

## 开发资料

源码即文档：分层入口在 `app/src/main/java/magicau/mtkcontroller/`（`data/` 控制层、
`feature/` 界面、`ui/` 组件）。设备相关结论以代码里的探测逻辑为准 —— 应用运行时
枚举 cpufreq policy、频率表与 governor，不依赖预置的设备表。
