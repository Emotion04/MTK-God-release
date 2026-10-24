# reference/ 规范

这个目录只在「要查别人的实现到底怎么写」时才进，日常开发不需要。规范写在这里，
是因为规则散着放必然漂：谁都想不起来三个月前的自己为什么那样命名。

**一句话**：仓库只放读出来的结论；原件（`sources/`）与解包结果（`derived/`）在仓库外的
`../MTK-reference/`（与仓库同级，不进任何版本库），`LEDGER.md` 记状态。结论缺了，这条来源就算没入库。

| 在哪 | 放什么 |
|---|---|
| `docs/reference/` | `LEDGER.md`、`README.md`、`SPEC.md` 与 `apps/` 下的结论 |
| `../MTK-reference/` | 原件、解包/反编译、ROM、工具链（不进版本库） |

---

## 1. 什么进来，什么不进来

| 进来 | 不进来 |
|---|---|
| 第三方 APK / 模块 / 脚本原件 | 本项目自己的构建产物（`app-debug*.apk` 之类，放构建目录） |
| 从固件里取出的配置、framework 反编译 | 本项目的源码、截图、日志 |
| 公开仓库取回的 HAL 源码 | 需要联网才能复现的一次性下载（记 URL 即可） |
| 对上述材料的分析笔记 | 功能计划、待办（进 `docs/dev/todo.md`） |

判据不是「有没有用」，是「**能不能拿来复核一个已经写下的结论**」。不能的话，它是资料，
不是证据，放别处。

## 2. 目录契约

| 目录 | 内容 | 可变性 | 进 git |
|---|---|---|---|
| `../MTK-reference/sources/<YYYY-MM>-<批次或短名>/` | 一手原件，**只增不改** | 只增 | 否 |
| `../MTK-reference/derived/` | 解包、反编译、dump、中间产物 | 可随时删、可重建 | 否 |
| `../MTK-reference/derived/decompiled/<pkg>/` | 每个被分析 APK 的双树 + `INDEX.md` | 可重建 | 否 |
| `docs/reference/apps/`、`docs/reference/*.md` | 分析结论、摘录、ROM 接口追踪 | 随结论更新 | **是** |
| `../MTK-reference/rom/`、`../MTK-reference/research/` | 长期来源，按主题保留 | 只增 | 否 |
| `../MTK-reference/tools/`、`../MTK-reference/libs/` | 工具链 | 可替换 | 否 |
| `docs/reference/LEDGER.md`、`README.md`、`SPEC.md` | 台账与规范 | 随流程更新 | **是** |

`../MTK-reference/sources/` 里**不许出现解包产物**。解包结果一律进 `derived/`；发现 `sources/<批次>/` 下
有 `AndroidManifest.xml`、`classes*.dex`、`res/` 这类东西，说明有人把 `apktool d -o` 指错了地方。

## 3. 命名

- 批次目录：`<YYYY-MM>-<批次或对象短名>`。批次用 `b1`、`b2`，单个对象用短名（`ax-module`、`lhgjx`）。
- 原件**不改名**。中文名、双点号（`1.3.0..apk`）、大小写都照原样 —— 改名之后 sha256 对不上
  别人手上的那份，台账就失去意义。
- 反编译目录：`derived/decompiled/<短名>/`，短名和台账 ID 的第二段一致（`kbattery`、`metric`、`lanhe`）。

## 4. 入库流程

1. 原件放 `../MTK-reference/sources/<批次>/`，不改名。
2. `sha256sum` 算全量。
3. `LEDGER.md` 加一行：8 位前缀填表格，全量填 §3 清单。
4. 跑 `python ../MTK-reference/tools/check_reference.py`，0 问题才算入库。

缺任一步视为未入库。**空目录不算入库**：建了批次目录没放东西，要么补上，要么删掉。

## 5. 台账怎么记

七个列，缺一列这行就不成立：

| 列 | 写什么 | 常见错法 |
|---|---|---|
| ID | `src-<短名>-<版本>` | 版本变了不改 ID |
| 对象 | 人话名字 + 括号里的别名 | 只写包名 |
| 版本 / sha256 前 8 位 | 版本号 + 8 位十六进制 | 拿文件名当版本 |
| 本地路径 | 反引号包住，相对 `../MTK-reference/` | 写成绝对路径 |
| 状态 | 见下 | 没核实就写 `analyzed` |
| 可复查入口 | 能跳到的具体位置（`derived/...` + INDEX 节号） | **指向结论落点自己** |
| 结论落点 | `docs/reference/` 里的文档 | 留空 |

### 状态机

```
raw ──清点──> surveyed ──读透──> analyzed
 └──────────────解不开──────────> blocked
```

- `raw`：原件在、哈希记了，没打开过。
- `surveyed`：清点过（包名、版本、结构），没深读。
- `analyzed`：**有笔记 + 至少一个能跳过去的位置**。两者缺一不算。
- `blocked`：解不开，**必须写原因**（加固方式、加密区偏移），不能只写 blocked。

功能采纳是另一条线，记在 §2：`adopted` 已落地 / `trial` 试过 / `rejected` 否掉（写理由）/
`pending` 没评估。**没核实一律 `pending`，不许猜 `adopted`。**

### 一条硬规则

「可复查入口」不能是「结论落点」。两者指向同一份文档时，等于文档自己证明自己，
这行 `analyzed` 是虚的 —— 检查脚本会把它报成提醒。

## 6. 要不要拎出源码

**拎，但只在 `derived/`，而且只在会被反复查的时候拎。**

| 情况 | 做法 |
|---|---|
| 要反复查命令、事务码、表结构、类关系 | 建**双树** `derived/decompiled/<pkg>/{java,java-jadx}/` |
| 只想知道包名、版本、用了什么框架 | 不建树，`apktool d -s` 看一眼 manifest 就够 |
| 加固、dex 被加密 | 不建树，记 `blocked` + 字节偏移（参考 startravel） |
| 源码本身就是明文（shell 脚本、WebUI） | 直接读 `sources/` 里的原件，不复制 |

### 为什么是双树

CFR 走 `dex → dex2jar → jar`，遇到「try 块上回跳」的字节码会把整个方法体替换成
`throw new IllegalStateException("Decompilation failed")`；jadx 在这些地方往往能解出来，
但它自己会崩在另一些方法上。**两棵树的失败集合不重合**，所以索引会同时引用两棵树。
只建一棵，就等于默认「查不到的地方不存在」。

### 树的规矩

- 树**不进 git**（第三方代码 + 体量），靠命令重建；重建命令写进 `INDEX.md` 对应节。
- 树**不能单独信任**：反编译器重建不了 Kotlin 挂起函数状态机和内联 lambda，会留派发桩。
  Java 里找不到的调用点，先去 `java-jadx/` 和首批 `smali/` 里查，再下结论。
- 应用代码**不一定在 `com/<厂商>/` 下**。混淆会把它们摊成单字母根包（`p.java`、`w42.java`），
  别因为不在包名目录里就当成库。
- 建完树就在 `INDEX.md` 加一节：统计、重建命令、关键类位置、和笔记的核对结果。

## 7. 索引 INDEX.md

`derived/decompiled/INDEX.md` 是「从命令 / 键名 / 事务码跳回源码行」的检索表。

- 路径一律写成**能被机器解析**的形式：`` `metric/java/p.java:41` ``。
  短路径（只写 `p.java`）会让 `checkrefs.js` 判成缺失 —— 宁可啰嗦。
- 行号必须真实。给不出行号的地方标字节偏移或写「无源码」，**不编**。
- 每节的结构固定：统计表 → 重建命令 → 关键位置表 → 与笔记的核对结果。

## 8. 结论（docs/reference/）

读出来的结论放在 `docs/reference/`（`apps/` 与两个 ROM 追踪页），是仓库里唯一的分析资料。

- 一个对象一份文档，命名 `<对象>-<版本>.md`；同对象的后续版本另起一份，不改旧的。
- 结构固定：身份（包名/版本/形态/提权路径）→ 功能清单（每条带证据标签和位置）→
  **明确查不出的东西**（写清楚为什么查不出）。
- 证据标签只有三种：`[字节码]`、`[字符串]`、`[文档]`。三者都要能落到具体位置。
- 查不出的条目**保留**，标 `[未确定]`，别删 —— 下一个人会重复踩同一个坑。
- 结论不能写进 `docs/reference/` 就算完：跨功能契约仍要写 `docs/dev/architecture.md`，
  设备侧判断写主题事实页。结论只描述**别人怎么做的**，不描述本项目怎么做。

## 9. 校验

三套，改完就跑：

| 命令 | 管什么 | 什么时候跑 |
|---|---|---|
| `python ../MTK-reference/tools/check_reference.py` | 台账七列、哈希、状态词、漏登记、断链、自证循环、结论链接 | 改台账或结论后 |
| `python tools/check_docs.py` | `docs/` 的链接、锚点、总索引可达性 | 改 `docs/` 后 |
| `cd ../MTK-reference/derived && node .local-temp/checkrefs.js` | `INDEX.md` 里每条 `.java:行号` 真实存在 | 改 `INDEX.md` 后 |

退出码 0 才算过。检查器只判**能机械判定的部分**，结论对不对仍要人核。

## 10. 清理与再生成

删 `derived/` 下任何东西之前，先确认 `INDEX.md` 里有重建命令；没有就先补，再删。

- 可以随时删：`tools/jadx-cache/`、`derived/.local-temp/<pkg>/` 里的中间 jar、空的 `jadx-tmp/`。
- 不要删：`derived/decompiled/` 的双树（重建要几分钟到几十分钟）、`derived/rom-analysis/`。
- `derived/.local-temp/` **保留原名**：`checkrefs.js` 按这个前缀找文件，且必须在
  `../MTK-reference/derived/` 下执行，换 cwd 就找不到。

## 11. git 边界

- 仓库里没有 `reference/` 目录；第三方原件在仓库外，`.gitignore` 里的 `/reference/` 只是防止副本被误提交。
- 素材本体、反编译树、工具链**永不提交**。
- 新增需要 review 的文档，先在 `.gitignore` 加例外 —— 加之前问。
- `git status` 里除上述例外外，不该出现任何 `reference/` 内容。

## 12. 与 docs/ 的分工

| 想知道 | 去哪 |
|---|---|
| 本项目现在怎么做、为什么这么定 | `docs/`（架构、事实页、待办） |
| 别人的 App / 固件怎么实现的 | `docs/reference/` |
| 那条结论的原始证据 | `../MTK-reference/sources/`、`../MTK-reference/rom/` |
| 某条命令/事务码在源码第几行 | `../MTK-reference/derived/decompiled/INDEX.md` |

`docs/` 只留日常开发要用的：架构、设备事实、协议、待办、实验。**外部实现的分析不进 `docs/`** ——
它们只在查别人的时候才需要，放进去会把每天要翻的目录撑大。
