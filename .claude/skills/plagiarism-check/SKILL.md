---
name: plagiarism-check
description: Use when reviewing this project's source, comments, UI strings, identifiers, or config/asset values for plagiarism or over-similarity against the decompiled third-party reference apps under D:\CCode\MTK\reference (Kbattery, 蓝河工具箱, vivo AxManager, MOsPremium, StarTravel, etc.). Scrubs blatant borrowings (identical naming, magic values, verbatim logic+comments, source-naming prose) while keeping official system interface names and allowing convergent logic.
---

# plagiarism-check

把本工程与 `D:\CCode\MTK\reference` 里那批反编译第三方 App 的"抄袭/同质化"风险
审查与清理，固化成可复用流程。目标：**在保留功能的前提下，把"可指认的抄袭痕迹"清零**，
同时不把正常的接口收敛当成问题。

## 分桶规则（先分类，再动手）
每处重合必须先归入且仅归入一桶：

1. **KEEP — 官方系统接口（共用词汇，必须保留）。**
   `Settings.System/Secure/Global` 设置键、`getprop` 属性名、PowerHAL 接口 token 与事务码、
   `/sys` `/proc` 节点路径。全行业同名、改名功能即废 → 不标记、不改名。
2. **DIFFERENTIATE — 变量命名与代码习惯（自取之物，须区别开）。**
   标识符起名风格、结构性惯用法。与参考**相同命名/习惯是最赤裸的抄袭痕迹** → 换成我们自己的
   约定。这类是自取的，避让免费；但**绝不影响运行时行为**：系统键名不动，只动包装它的
   局部变量 / 类名 / 函数名 / 内部结构。改完确认功能仍正常。
3. **CONVERGENT — 逻辑 / 算法 / 门禁判断（解数学题，可接受）。**
   同一个 undocumented 问题，不同人常收敛到同一思路。同一思路本身**不算抄袭、不处理**。
   仅当三者同时成立才升级为 SCRUB：**结构逐字 + 注释逐字 + 常量排布逐字**。
4. **SCRUB — 逐字非接口字面量 + 点名来源（必须清）。**
   别的 App 调出来的魔取值串；点名出处的注释或 UI 文案（"来源XX工具箱"）。
   魔取值 → 运行时读本机现值 / 实测推导；点名来源 → 删除。

## 流程
1. **建参照集**：列出 `D:\CCode\MTK\reference` 下各 App 的位置与语言（java 树不可全信，
   反编译 Kotlin 只吐桩；以 smali / strings 为准 —— 见 reference/README.md）。
2. **全面比对**：在本工程 `mtk-optimizer/app/src` 范围，比对代码文本、KDoc/注释、
   `res/values/strings.xml`、硬编码 UI、assets、配置字面量、**标识符命名**、门禁逻辑。
   用带证据的 grep，不用印象。
3. **逐条分桶**成 `KEEP / DIFFERENTIATE / CONVERGENT / SCRUB`。
4. **按桶处置**：
   - KEEP → 原样保留；
   - DIFFERENTIATE → 标识符/写法换成我们自己的约定（不改运行时行为），改完自测功能仍正常；
   - CONVERGENT → 保留，除非触发 SCRUB 的三重逐字条件；
   - SCRUB → 魔取值改本机现值/推导；赤裸复刻的代码用自有结构重写；点名来源文字删除。
5. **写诚实注释**：只描述"这个键/节点在本机实测是什么行为"——真实、不点名来源。
   **不编造假出处。**
6. **产出证据表**：`位置 | reference 位置 | 桶 | 处置 | 状态`。
7. **复扫确认**：官方接口名仍在；与 reference 无相同命名/习惯；无点名来源文字；
   无逐字非接口字面量；行为未变（单测 / 只读探针）。再收尾。
8. **上报**：遇到确实复杂或模棱两可的（如某值既是接口常量、又疑似某 App 的调优配置），
   交给维护者判断，**不自行拍板**。

## 输出
- 按桶排列的证据表（风险从高到低）。
- 结论声明：官方接口名已保留；命名/习惯已区别化；逻辑按收敛保留（除三重逐字）；
  逐字非接口字面量已清除；未造假出处；已验证行为未变。
