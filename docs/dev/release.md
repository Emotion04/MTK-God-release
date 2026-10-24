# 发布、版本与更新日志

本页只保留**版本号**与**更新日志**的规则。提交规范、打 tag、CI 发版的完整流程
见 [release-flow.md](release-flow.md)；开发约定见 [architecture.md](architecture.md)，
文案见 [wording.md](wording.md)。

> 流程与提交规范已移到 [release-flow.md](release-flow.md)；本页只保留版本号与更新日志的规则。

## versionCode

| 场景 | versionCode | versionName |
|---|---|---|
| 交付给用户的 APK | **必须递增** | 发布时手动改成正式版本号 |
| 手动下载 APK 到手机安装 | **每次递增** | 可以暂时不变 |
| 本地构建、`adb install -r` 自测 | 可以不递增 | 可以不变 |
| 只改文档、重新提供同一个 APK | 不递增 | 不变 |

**versionName 不参与安装判断**，只用于显示和发布标签；没敲定版本前沿用旧名字没问题。
**versionCode 才是安装身份**：手动下载安装时，同号的包可能被当成“已经装过”。

**为什么交付包必须递增**：安装器会把同 versionCode 的包当成“已经装过”。在真机上
交付时这可能表现为**静默不装**，用户看到的是旧代码，而所有人以为更新成功了。
拿错构建调试已经浪费过一整晚。

本地测试不同：签名一致时 `adb install -r` 会直接覆盖，不需要卸载；同 versionCode
可以反复装。**不要把同一个 versionCode 的两个不同构建发给别人** —— 对方无法判断
手机上到底是哪一版。

日常开发不因为“改了几行代码”就 bump；只在敲定一个要发布/交付的版本时递增。

## 更新日志

**唯一来源是仓库根目录的 `CHANGELOG.md`。** 不手工维护
`app/src/main/assets/changelog.json`，也不直接改云端文件。

格式：

```md
## [0.5.1] - 2026-10-20

### 新增
- 一条用户能感知的变化

### 修复
- ...

### 优化
- ...
```

- 一个版本内每种标签只出现一次，条目合并；
- 开发期间用 `## [开发中] - 日期` 累计改动，不每次构建都开新版本；
- 敲定发布时把「开发中」改成正式版本号和日期，再新开一条「开发中」。

`### 新增 / 修复 / 优化` 在 App 里映射成 `feat / fix / perf` 徽章。没有归类的条目
也可以写，显示时不带徽章。

### 经常微调，但没有新版本

`CHANGELOG.md` 是**手工维护的源文件**；APK 内置的那份是构建时由它生成的，不单独维护。
改措辞、补条目、调整分组都不需要动版本号。

发布方式：

1. **默认**：改 `CHANGELOG.md` → 随下一次打 tag 由 CI 发布（GitHub Releases + `main` 上的 JSON）。
   只改措辞不想等下次发版时，跑 `python tools/release_updates.py` 提交 `release/public/` 即可；
2. **七牛云（可选）**：本地改完直接跑生成器 + 上传脚本，云端立即更新；
   源文件仍是本地的，之后记得补一次提交，避免源和云端分叉；
3. **GitHub 网页手动触发**：Actions 里的 `workflow_dispatch` 按钮可以手动跑一次部署。

“只让 push 触发”不是限制，而是最少操作的路径；手动触发和本地脚本都是补充。

### 运行时从哪里读

| 来源 | 作用 |
|---|---|
| 云端静态 JSON | **运行时真源**，每次打开页面拉一次 |
| APK 内置 JSON | 断网或远端失败时的保底；构建时从 `CHANGELOG.md` 生成 |

所以改了 `CHANGELOG.md` 之后**不需要发新 APK**：推送后云端更新，所有已安装版本
下次打开页面就能看到。改任何一天的措辞或补内容也一样。

**更新日志不影响版本检测。** 更新检测读的是单独的更新清单；日志没来得及写，
只会让页面显示旧内容，不会影响“有没有新版本”的判断。

### 提交信息和更新日志的关系

**不会自动同步。** 两者分工：

- `CHANGELOG.md` 是**发布给用户的**内容，由人写、人改；
- git 提交是开发记录，适合用 `feat:` / `fix:` 前缀，方便以后回看；
- `make_changelog.py` 不带参数时可以从提交生成一份**草稿**，只用于参考/粘贴，
  不会被 CI 自动发布。

推荐流程：平时按习惯写提交；准备发版时把这一段的提交草稿过一遍，挑用户能感知的
写进 `CHANGELOG.md`。不要把提交原文直接当发布说明。

### `tools/make_changelog.py` 是什么

- `--from-changelog CHANGELOG.md`：把手工写好的更新日志**转换**成 JSON，构建和 CI 用；
- 不带参数：从 git 提交生成一份**草稿**，只作参考，不自动发布。

它的产物是格式转换结果或草稿，不是“最终更新日志”；内容由人决定。

## CI/CD 与托管（七牛 / Cloudflare 备选）

### 私有仓库可以部署吗

GitHub Actions 在私有仓库里也能运行，但私有仓库的 Release 资产需要登录才能下载。
要让 App 直接下载，要么公开仓库（现在的做法），要么另建一个只放产物的公开仓库，见
[release-flow.md](release-flow.md#源码私有公开发-release当前方案)。Cloudflare Pages 是可选的托管方式之一。

### 同步方式：push/CI 优于镜像回源

- **推荐**：改 `CHANGELOG.md` → push → CI 生成并上传。只传变化的文件，内容与源文件一致；
- **镜像回源**：首次访问时由七牛去源站拉取并缓存。适合迁移或源站在别处，不适合日常更新 ——
  源站不可达时它也只能返回旧缓存；
- **手动重传整个空间**：没必要，容易覆盖无关文件，也容易忘记。

### 触发方式

`release/publish-changelog.yml` 在 **`CHANGELOG.md` 被 push** 时触发，不依赖 tag：

```yaml
on:
  push:
    paths: ["CHANGELOG.md"]
  workflow_dispatch: {}
```

- 改日志 → 提交给自己仓库 → Actions 生成 JSON 并部署；
- 其他代码 push 不会触发；
- 打 tag/建 Release 才发布 APK，那是另一条流程。

### tag、Release、Canary

| 概念 | 含义 |
|---|---|
| tag | 指向某个提交的名字（如 `v0.6.0`），本身不发布任何东西 |
| GitHub Release | 挂在 tag 上的页面，可写说明、附 APK；可标记 pre-release |
| Canary | 预发布渠道；可用 `v0.6.0-canary.1` 或直接发 debug 包 |
| CI | 每次 push/PR 自动检查、构建 |
| CD | 通过后自动部署或发布 |

日常开发不需要 tag 或 Release；更新日志的部署与版本发布是分开的。

### 七牛的权限与配置

- 一个七牛账号的一对 AccessKey/SecretKey 可以管理账号下所有空间；
  建议为 CI 单独建子账号，只给目标空间的写权限，不要把主账号密钥放出去；
- 代码里只需要**公开访问域名**（例如 `https://cdn.example.com/changelog.json`），
  AccessKey/SecretKey 只放在 CI Secrets 或本地环境变量里；
- **镜像回源**：访问不存在的文件时由七牛回源站拉取并缓存。适用于迁移或“源站在别处”的
  场景；我们现在是 CI 直接上传，不需要开；
- **跨域设置（CORS）**：只有浏览器里的 JS 跨域读取才需要。Android 原生 HTTP 不受
  同源策略限制，不需要开。
- **私有空间的外链带 `?e=…&token=…`**：那是七牛给私有 bucket 生成的临时下载签名，
  `e` 是过期时间。带签名的地址会过期，App 不能长期使用。更新日志、更新清单、
  公开 APK 应该放在**公开读**的空间（或单独建一个公开 bucket），链接就是干净的
  `https://域名/changelog.json`；
- 传给 App 的应该是**生成后的 `changelog.json`**，不是 `CHANGELOG.md`。Markdown
  需要先由 `tools/make_changelog.py --from-changelog` 转换。

### GitHub Release 与 APK 分发

- GitHub Release 适合有公开仓库时附 APK；私有仓库的 Release 资产需要登录，App 不能直接下；
- 自己分发可以把 APK 放七牛 Kodo 的公开地址，更新清单的 `url` 指向它；
- 两者可以同时存在：Release 给人看，Kodo 给 App 下载；更新清单只认 `url`。

### 中国用户能访问吗

GitHub 与 Cloudflare Pages 在国内都不是稳定保证，可能限速或被运营商干扰。
做法：

- 主地址用 Cloudflare Pages（最好绑自己的域名）；
- 备用地址用国内可达的静态托管（七牛云 Kodo、Gitee Pages、对象存储等），构建时传入：

```bash
./gradlew assembleRelease \
  -PchangelogUrl=https://主地址/changelog.json \
  -PchangelogUrlBackup=https://备用地址/changelog.json
```

App 会先试主地址，失败再试备用；都失败才回退内置版本。

七牛云的自动化：`release/publish-qiniu.yml` 在 `CHANGELOG.md` push 时生成 JSON、
用 `tools/upload_qiniu.py` 上传 Kodo，并按 `QINIU_URL` 刷新 CDN。不需要进控制台。
