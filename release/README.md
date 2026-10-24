# 发布目录

发布只认三样东西：**`CHANGELOG.md`（人写）**、**`release/config.jsonc`（地址和策略）**、
**tag（什么时候发）**。其余 JSON 全部自动生成，不要手改。

- 完整流程：[docs/dev/release-flow.md](../docs/dev/release-flow.md)
- 配置与每个字段的含义：[config.md](config.md)
- 云控文件字段：[remote-config.md](remote-config.md)

## 文件一览

| 文件 | 谁写 | 说明 |
|---|---|---|
| `config.jsonc` | 人 | 下载地址、JSON 地址、强制更新版本；可写 `//` 注释 |
| `public/changelog.json` | 构建生成 | 由 `release_updates.py` 生成，CI 提交回 main |
| `public/update.json` | 构建生成 | 版本号、说明、渠道、sha256 自动取 |
| `public/remote-config.json` | 构建生成 | 由 `tools/remote-config.jsonc` 去掉注释得到 |
| `*.example.json` | 参考 | 不带注释的样例，不参与构建和发布 |
| `publish-qiniu.yml` / `publish-changelog.yml` | 可选 | 七牛 / Cloudflare Pages 的旧方案，复制到 `.github/workflows/` 才生效 |

## 两种发布方式

**1. GitHub Releases（默认，不需要任何密钥服务）**

推送 tag（如 `v0.6.0`）→ `.github/workflows/release.yml` 构建签名 APK、生成 JSON、
创建 Release、把 JSON 提交回 `main`。App 读：

- `https://github.com/Emotion04/MTK-God/releases/latest/download/app-release.apk`
- `https://raw.githubusercontent.com/Emotion04/MTK-God/main/release/public/update.json`

**2. 七牛云（可选）**

只在需要国内 CDN 或 GitHub 不好访问时用。改了 `release/config.jsonc` 后，下载地址、
JSON 地址都要换成七牛域名，同时改 `mtk-optimizer/app/build.gradle.kts` 里的
`CHANGELOG_URL` / `UPDATE_URL` / `REMOTE_CONFIG_URL` —— 构建前的 `checkReleaseConfig`
不一致会直接让构建失败。

本地双击 `tools/发布.bat` 或 `tools/publish.bat` 打开上传窗口（两者相同），也可以直接跑：

```bash
python tools/publish_local.py
```

它先生成同样的 JSON，再上传 APK → changelog.json → update.json → remote-config.json。
凭据存 `.cyh/publish.json`（已 gitignore）。

流量：只有选七牛时才消耗七牛流量，= APK 大小 × 下载次数 + 几 KB 的 JSON。GitHub
Releases 走 GitHub 的带宽，不计七牛流量。

## 两种方式会冲突吗

不会同时生效，但**以 App 里写死的地址为准**。App 只读 `build.gradle.kts` 里那三个
地址；哪个托管在提供服务，就决定用户收到谁的内容。所以：

- 切换方式时，配置和 BuildConfig 一起改（构建会检查）；
- 不要一边改七牛、一边指望 GitHub 上的文件同步 —— 两边是两份独立副本。

## 更新日志

唯一来源是根目录 `CHANGELOG.md`，格式：`## [版本] - YYYY-MM-DD` + `### 新增 / 修复 / 优化…` +
`- 条目`。写错时生成脚本会打印警告并跳过那一版。`changelog.json` 每次从它全量生成，
历史不会丢；没经过发版的提交会在下次发版时补进当前版本。
