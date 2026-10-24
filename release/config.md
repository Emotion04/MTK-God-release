# 发布配置与文件字段说明

所有发布相关的地址和取值都从 `release/config.jsonc` 读（可写 `//` 注释）。
默认值是 **GitHub Releases**：不配置也能直接发布。流程见 [发布流程](../docs/dev/release-flow.md)。

## release/config.jsonc

| 字段 | 含义 | 默认值 | 什么时候改 |
|---|---|---|---|
| `apkUrl` | App 内「更新」按钮打开的下载地址 | `https://github.com/Emotion04/MTK-God/releases/latest/download/app-release.apk` | 换 CDN 或别的托管方式 |
| `changelogUrl` | 更新日志 JSON | `https://raw.githubusercontent.com/Emotion04/MTK-God/main/release/public/changelog.json` | 换 CDN / 加镜像 |
| `updateUrl` | 更新清单 JSON | `…/main/release/public/update.json` | 同上 |
| `remoteConfigUrl` | 云控配置 JSON | `…/main/release/public/remote-config.json` | 同上 |
| `changelogUrlBackup` / `updateUrlBackup` | 备用地址，主地址不可达时再试 | 空 | 需要镜像时 |
| `minVersionCode` | 低于它就强制更新 | `0` | 出现必须淘汰的旧版本时 |

**App 里写死的三个地址必须和这里一致**，否则 App 读的是旧位置。对应关系：

| 这里 | `mtk-optimizer/app/build.gradle.kts` 里的 `buildConfigField` |
|---|---|
| `changelogUrl` | `CHANGELOG_URL` |
| `updateUrl` | `UPDATE_URL` |
| `remoteConfigUrl` | `REMOTE_CONFIG_URL` |

备用地址要在构建时显式传入：`-PchangelogUrlBackup=… -PupdateUrlBackup=…`；不一致时构建任务只检查主地址。

不需要手工同步：`./gradlew :app:checkReleaseConfig`（`preBuild` 也会跑）会核对上表三项，
不一致直接构建失败。

## release/public/update.json（构建时生成，不要手改）

| 字段 | 含义 | 来源 |
|---|---|---|
| `schema` | 结构版本，固定 `1`；App 不认识就整份忽略 | 固定 |
| `channel` | `stable` / `canary`；App 只认自己渠道（release 包 = stable，debug 包 = canary） | tag 名带 `-canary` 就是 canary |
| `versionCode` | 版本号，用来判断「是不是新版」 | `build.gradle.kts` |
| `versionName` | 显示用的版本名 | `build.gradle.kts` |
| `buildTime` | 构建时间 | 恒 `0`（APK 里没有可靠构建时间，不冒充） |
| `publishedAt` | 发布时间（毫秒） | tag 指向的提交时间；没 tag 用当前时间 |
| `minVersionCode` | 低于它就强制更新 | `release/config.jsonc` |
| `notes` | 更新弹窗里的一句话 | `CHANGELOG.md` 最新一版前 3 条 |
| `url` | APK 下载地址（必须 https） | `release/config.jsonc` 的 `apkUrl` |
| `sha256` | APK 的校验值 | 构建产物 |

## release/public/changelog.json（构建时生成，不要手改）

| 字段 | 含义 |
|---|---|
| `schema` | 固定 `1` |
| `entries[].versionCode` | 排序用；最新一版是 Gradle 的 code，历史版本递减续上 |
| `entries[].versionName` | 版本名，取 `CHANGELOG.md` 的 `## [0.5.1] - 日期` |
| `entries[].date` | 日期，取 `CHANGELOG.md` |
| `entries[].notes[].type` | 徽章：`feat` 新增 / `fix` 修复 / `perf` 优化 / `refactor` 重构 / `docs` 文档 / `chore` 杂项；空串表示无徽章 |
| `entries[].notes[].text` | 一条更新说明 |

**唯一需要人写的是根目录 `CHANGELOG.md`**，格式：`## [版本] - YYYY-MM-DD`，下面用
`### 新增 / 修复 / 优化 / 重构 / 文档 / 杂项` 分节，条目以 `- ` 开头。写错时生成脚本会打印警告并跳过那一版。

## release/public/remote-config.json（由 `tools/remote-config.jsonc` 生成）

App 端的读取还没接上，字段先保持少。字段说明见 [remote-config.md](remote-config.md)。

## 七牛上传时的范围（可选，不是默认方式）

只有明确要上传到七牛时才需要 `tools/publish_local.py` / 窗口；它上传的是：

- `{prefix}/app-release.apk`
- `{prefix}/changelog.json`
- `{prefix}/update.json`
- `{prefix}/remote-config.json`

流量消耗 = APK 大小 × 下载次数 + JSON 的大小（几 KB，可忽略）。GitHub Releases 走 GitHub 的带宽，不计七牛流量。
