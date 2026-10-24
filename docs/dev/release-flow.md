# 提交流程与发版流程

本页是 **git 提交规范**和**从改代码到旧版 App 收到更新提示**的完整流程。
版本号规则见 [release.md](release.md)，字段含义见 [release/config.md](../../release/config.md)。

## 一句话

**改代码 → 规范提交 → 写 `CHANGELOG.md` → 构建自测 → 打 tag → 推送。**
tag 是唯一“发版”动作；没有 tag 的提交，已安装的 App 永远看不到。

## 仓库与上游

| 远端 | 地址 | 用途 |
|---|---|---|
| `origin` | `https://github.com/Emotion04/MTK-God.git` | 默认上游；推这里 |
| `old-origin` | `https://github.com/Emotion04/mtk-controller.git` | 旧仓库，只读保留 |

```bash
git remote -v
cd mtk-optimizer && ./gradlew assembleRelease
```

## 提交规范

格式：`类型(范围): 说明`

- 类型必填，说明写“为什么要有这个改动”，一句话；
- 范围可省略，例如 `cpu`、`lab`、`ui`、`docs`、`release`；
- **不改写已推送的历史**：改写会把所有协作者和 CI 的基线打乱。

| 类型 | 用在 | 例子 |
|---|---|---|
| `feat` | 用户能感知的新功能 | `feat(cpu): 锁屏后可自动重新下发频率范围` |
| `fix` | 修 bug | `fix(thermal): 修 TEMP 表为空时选中 08 导致区间锁死` |
| `perf` | 纯性能/稳定性，无新功能 | `perf: 减少 CPU 页刷新时的节点读取` |
| `refactor` | 不改行为的结构调整 | `refactor(settings): 通知设置拆成独立分区` |
| `docs` | 文档 | `docs: 补发布流程` |
| `build` / `ci` | 构建脚本、工作流 | `ci: 加 tag 发布工作流` |
| `chore` | 杂项（依赖、忽略规则、发布文件） | `chore(release): v0.6.0 的发布文件` |

`make_changelog.py` 能识别这些前缀，`release_updates.py` 会把 tag 范围内的提交补进当前版本。
但**发布给用户看的文字仍以 `CHANGELOG.md` 为准**，提交只需如实分类。

## 新仓库注意

`origin` 已指向 MTK-God。**当前工作区有大量未提交改动**，推之前先：

1. `git status` 逐个确认要提交的内容（不要 `git add -A` 把临时文件带进去）；
2. 按上面的格式分主题提交；
3. `git push -u origin main`（新仓库为空，第一次要把已有历史一起推上去）；
4. 确认 `release/`、`tools/`、`.github/` 都在版本库里 —— CI 要用它们。

## 日常开发（不发版）

1. 改代码，构建 debug 自测：`./gradlew assembleDebug`（versionCode 不必递增）；
2. 改 `CHANGELOG.md` 的「开发中」一节（格式见 [release.md](release.md#更新日志)）；
3. 按提交规范提交、推送。

此时**不会**产生更新提示：App 只在 `update.json` 的 `versionCode` 大于自身时提示，
而 `update.json` 只在发版时重新生成。

## 发版（让旧 App 收到更新提示）

### 1. 定版本号

- `mtk-optimizer/app/build.gradle.kts`：`versionCode` 递增，`versionName` 改成正式版本号（如 `0.6.0`）；
- `CHANGELOG.md`：把「开发中」改成 `## [0.6.0] - 2026-10-28`，再新开一条「开发中」；
- 两者版本名不一致时，发版脚本会拒绝执行。

### 2. 构建并自测

```bash
cd mtk-optimizer
./gradlew assembleRelease
```

签名不可用时会直接失败（不会退回 debug 签名）。装到真机上至少确认：能启动、能提权、
新功能正常。**交付包的 versionCode 必须递增**，否则装不上或静默不装。

### 3. 发版前检查

| 检查 | 方法 |
|---|---|
| CHANGELOG 最新一版名字等于 versionName | `python tools/release_updates.py` 会校验 |
| APK 实际 versionCode 等于 Gradle | 同上，用 aapt 读 APK |
| 配置地址与 BuildConfig 一致 | `./gradlew :app:checkReleaseConfig`（构建时自动跑） |
| 图标、文案、字符串检查 | 构建时自动跑 `check_docs.py` / `check_strings.py` |

### 4. 提交并发版

```bash
git add mtk-optimizer/app/build.gradle.kts CHANGELOG.md
git commit -m "chore(release): 准备 0.6.0"
git push origin main
git tag v0.6.0
git push origin v0.6.0
```

推送 tag 后，`.github/workflows/release.yml` 自动：

1. 用 Secrets 里的签名密钥构建 release APK；
2. `tools/release_updates.py --tag v0.6.0` 生成 `changelog.json` / `update.json` / `remote-config.json`；
3. 创建 GitHub Release，资产固定命名为 `app-release.apk`；
4. 把三个 JSON 提交回 `main`（提交信息 `chore(release): v0.6.0 的发布文件`）。

### 5. 旧 App 怎么知道

```
App 启动 → 24 小时节流 → GET release/public/update.json
        → versionCode > 自己的 → 弹窗（minVersionCode 更高则强制）
        → 下载 app-release.apk（latest/download 固定链接，永远指向最新 Release）
```

因此 **versionCode 变了但没打 tag，不会触发更新提示** —— 这就是故意的：日常构建
不会打扰用户，只有正式发布才会。

## 不用 GitHub Actions 时（手动发版）

一台有签名文件的机器上：

```bash
cd mtk-optimizer && ./gradlew assembleRelease && cd ..
python tools/release_updates.py --tag v0.6.0
git add release/public && git commit -m "chore(release): v0.6.0 的发布文件" && git push
# 手动机器上创建的 Release：GitHub 网页 New release → 选 tag → 上传 app-release.apk
```

注意 Release 的资产名必须是 `app-release.apk`，否则固定下载链接会 404。

## 可选：改用七牛云分发

GitHub Releases 走 GitHub 带宽、不花七牛流量；但由于网络原因国内访问可能不稳。
要换七牛时：

1. 改 `release/config.jsonc` 的 `apkUrl` 与三个 JSON 地址；
2. 同时改 `mtk-optimizer/app/build.gradle.kts` 里对应 `buildConfigField` 的默认值
   （`checkReleaseConfig` 会核对，不一致构建失败）；
3. 用 `tools/发布.bat` 或 `python tools/publish_local.py` 上传。

流量 = APK 大小 × 下载次数 + 几 KB 的 JSON。GitHub 与七牛两套是**两份独立副本**，
App 只读 BuildConfig 里的地址，切换时要一起改，否则旧版 App 仍读旧地址。

## 源码私有、公开发 Release（当前方案）

| 仓库 | 可见性 | 放什么 |
|---|---|---|
| `Emotion04/MTK-God` | **私有** | 全部源码、文档、`reference/` 分析资料、Actions |
| `Emotion04/MTK-God-release` | **公开** | 只有三个 JSON（`main` 分支根目录）+ Release 里的 `app-release.apk` |

为什么必须拆：私有仓库的 Release 资产要登录才能下载、raw 链接也不公开，App 拿不到。

App 读的地址（`release/config.jsonc` 与 `build.gradle.kts` 里一致）：
- `https://github.com/Emotion04/MTK-God-release/releases/latest/download/app-release.apk`
- `https://raw.githubusercontent.com/Emotion04/MTK-God-release/main/{update,changelog,remote-config}.json`

### 一次性配置

1. 在 `MTK-God-release` 建一个空仓库（勾选 README），默认分支 `main`；
2. 建 **Fine-grained PAT**，只授权这一个仓库，权限：`Contents: Read and write`；
3. 在私有仓库 Secrets 里加 `RELEASE_REPO_TOKEN`（上面那个 PAT）和签名四项；
4. 私有仓库 Actions 默认可用，不需要额外开关。

### 发布时

`.github/workflows/release.yml` 在私有仓库里：
1. 构建签名 APK；
2. `gh release create` 到 **公开仓库**（用 `RELEASE_REPO_TOKEN`），资产固定名 `app-release.apk`；
3. 把 `update/changelog/remote-config.json` 推到公开仓库 `main` 根目录；
4. 在私有仓库提交一份 `release/public/` 快照，方便以后对比。

公开仓库里不要 manually 改这三份 JSON：下次发版会被整体覆盖。

### 两个仓库都要推吗

- 源码、文档、CHANGELOG：只推私有 `MTK-God`；
- 公开仓库只放产物，由 CI 推送，你不需要手动碰。

### 版本号与渠道

App 用 **versionCode** 判断新旧（同渠道内单调递增，`0.5.10` 这类名字按字符串比会排错）。
渠道由清单的 `channel` 决定：release 包只认 `stable`，debug 包只认 `canary`。
tag 名带 `-canary` 时生成的清单就是 canary，两个渠道用同一个公开仓库、同一个 `update.json`，
所以**同一时间只能有一个渠道在线**；要同时灰度，需要把 canary 清单放到另一个文件名并改 debug 包的 `UPDATE_URL`。
