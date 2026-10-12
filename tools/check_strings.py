"""检查用户可见文案的存放与引用一致性。

按层的判据见 `docs/dev/wording.md`：应用编写的用户文案进 `res/values/strings.xml`；
设备原文、路径、协议标识、构建标识、报告原始字段和工程日志按数据处理。
**展示这些数据的标题和说明仍使用字符串资源。**

检查五件事：

1. **悬空引用** —— 引用未定义的字符串资源，通常导致编译或资源链接失败。
2. **分层审计** —— 按文件所在层统计代码里的中文，见下。
3. **基线对比** —— 只禁止变多。
4. **文案形态** —— 匹配结果仅提示，由维护者结合展示位置复核。
5. **待迁移的插值串** —— 含变量的中文要先按位置参数写成模板才能搬，单独列一栏提示。

## 为什么改成「全量扫描」而不是继续认几种写法

旧版按形状收：`Text("…")` 加一张具名参数名单。**那是黑名单，只会漏。** 实测在
注释之外、含中文的字符串字面量有 1141 处，旧版只认其中 242。漏掉的形状至少四种：

- 首参不是字面量：`Text(if (a) "…" else "…")`
- 插值里含双引号：`"${x.joinToString("/")}°C 起关部分核心"` —— 内层引号截断匹配
- 枚举常量上的位置参数：`LIGHT("浅色", "…")`
- 属性名不在名单里：`displayName`、`title` 之类

所以改成**先把注释剥掉，再扫每一个含中文的字面量**，按层归类。判据从「长什么样」
变成「在哪一层、干什么用」—— 那才是 wording.md 的真实判据。

## 分层

| 层 | 判据 |
|---|---|
| `domain/` | **零容忍**。那一层与 Android 资源解耦，不该出现任何中文。 |
| 其余层 | 日志与报告原文按数据处理；**其余中文 —— 尤其是返回给上层的说明 —— 按文案算**。 |

日志与报告原文的判定**不分目录**：`feature/` 里的 `AppLog.w(TAG, "…")` 同样是工程日志。
**"是不是日志"看它是谁的实参**，不看附近有没有出现过日志调用。从字面量往前
找最近的未闭合 `(`，取出调用名 —— `AppLog.i(...)`、`emit(...)`、`appendLine(...)`
里的是日志，其余不是。找括号前先把字面量内容掩码掉，否则 `"PowerHAL (${x})"`
这种字符串里的括号会把平衡算错。

旧版按"往上找 4 行里有没有日志调用"判定，实测把 42 处真文案误判成了日志
（`Outcome(true, "无测试句柄")` 因为上面几行有个 `emit(` 就被豁免了）。

确实要展示给用户的设备原文、协议标识，在该行或上面几行写 `ui-check:ignore` 豁免。
这应该是逐条的、有理由的；**没有文件级豁免** —— 曾经加过一个（给"整个文件只进日志"
的情况），后来发现那个文件的消息其实经上层传到了界面，机制连同理由一起撤了。

## 含变量的中文也是文案

曾经把 `Text("频率 ${mhz} MHz")` 排除在统计外，理由是"那是数据格式化"。
**那个理由把格式化和文案当成了一回事。** 资源占位符本来就是为这件事设计的：
`strings.xml` 里写 `频率 %1$s MHz`，展示处 `stringResource(R.string.x, mhz)`。

用法：
    python tools/check_strings.py            # 报告当前状态，并与基线对比
    python tools/check_strings.py --freeze   # 把当前状态存为新基线
"""

from __future__ import annotations

import bisect
import json
import re
import sys
from pathlib import Path

# Windows 控制台默认 GBK，直接打印中文会乱码甚至抛异常。
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "mtk-optimizer" / "app" / "src" / "main"
JAVA = SRC / "java"
PKG = JAVA / "magicau" / "mtkcontroller"
BASELINE = Path(__file__).resolve().parent / ".strings-baseline.json"

# 代码里对字符串资源的引用：R.string.name
REF = re.compile(r"R\.string\.([A-Za-z0-9_]+)")
# layouts 里的引用：@string/name
XML_REF = re.compile(r"@string/([A-Za-z0-9_]+)")
# strings.xml 里的定义
DEF = re.compile(r'<string\s+name="([A-Za-z0-9_]+)"')

CJK = re.compile(r"[一-鿿]")

# 工程日志与报告拼装的调用名。这些位置的中文按数据处理。
#
# 按**调用名**判定，不按"附近有没有出现在几行内"—— 旧版那么做会把
# `Outcome(true, "无测试句柄")` 也算成日志，只因为上面几行有个 `emit(`。
# 判据是"这个字面量是谁的实参"，那就得真的找到那个调用。
LOG_CALLS = ("AppLog.", "emit", "appendLine", "append", "record")
# 显式豁免：确实要展示给用户的设备原文、协议标识。
#
# 只在逐条看展示路径后使用，不是通配符 —— 一行豁免意味着"这条中文要么是数据、
# 要么已经由别处供文案"，写的时候心里要有那句话。
IGNORE_MARK = "ui-check:ignore"
IGNORE_WINDOW = 4


def strip_comments(text: str) -> str:
    """去掉注释，**保持行号不变**（注释换成等量换行）。

    不能用 `re` 一把梭：`//` 会出现在字符串里（网址），`/*` 会出现在正则或
    路径说明里。所以按字符扫，跟踪"是否在字符串中"。
    """
    out: list[str] = []
    i, n = 0, len(text)
    in_line = in_block = in_str = in_raw = False
    while i < n:
        c = text[i]
        if in_line:
            if c == "\n":
                in_line = False
                out.append(c)
            i += 1
            continue
        if in_block:
            if text.startswith("*/", i):
                in_block = False
                i += 2
            else:
                out.append("\n" if c == "\n" else " ")
                i += 1
            continue
        if in_raw:
            if text.startswith('"""', i):
                in_raw = False
                out.append('"""')
                i += 3
            else:
                out.append(c)
                i += 1
            continue
        if in_str:
            out.append(c)
            if c == "\\":
                # 转义：把下一个字符也原样带过去（含 `\"`），否则会误判字符串结束。
                if i + 1 < n:
                    out.append(text[i + 1])
                i += 2
                continue
            if c == '"':
                in_str = False
            elif c == "\n":
                in_str = False  # 未闭合，当作结束，避免整份文件被吞
            i += 1
            continue
        if text.startswith('"""', i):
            in_raw = True
            out.append('"""')
            i += 3
            continue
        if text.startswith("//", i):
            in_line = True
            i += 2
            continue
        if text.startswith("/*", i):
            in_block = True
            i += 2
            continue
        out.append(c)
        if c == '"':
            in_str = True
        i += 1
    return "".join(out)


# 字符串字面量。转义引号、行内双引号都要能吃到。
LITERAL = re.compile(r'"((?:[^"\\\n]|\\.)*)"')


def literals(source: str) -> list[tuple[int, int, str]]:
    """(行号, 在整个源里的起始偏移, 字面量内容)，行号从 1 开始。"""
    starts = [0]
    for i, ch in enumerate(source):
        if ch == "\n":
            starts.append(i + 1)
    return [
        (bisect.bisect_right(starts, m.start()), m.start(), m.group(1))
        for m in LITERAL.finditer(source)
    ]


def masked(source: str) -> str:
    """把字面量内部替换成占位字符，长度不变。

    这样往前找调用名时，字符串里的括号不会把平衡算错 ——
    `"PowerHAL (${x})"` 这类就藏着一对。
    """
    return LITERAL.sub(lambda m: '"' + "x" * (len(m.group(0)) - 2) + '"', source)


def enclosing_call(masked_source: str, offset: int) -> str | None:
    """[offset] 处（字面量起始引号）所属的调用名，例如 `AppLog.i`、`Outcome`。

    从后往前找最近一个未闭合的 `(`。括号平衡在**掩码后**的源上算。
    """
    depth = 0
    i = offset - 1
    while i >= 0:
        c = masked_source[i]
        if c == ")":
            depth += 1
        elif c == "(":
            if depth == 0:
                j = i - 1
                while j >= 0 and (masked_source[j].isalnum() or masked_source[j] in "._"):
                    j -= 1
                return masked_source[j + 1:i]
            depth -= 1
        i -= 1
    return None


def layer_of(path: Path) -> str:
    rel = path.relative_to(JAVA).as_posix()
    if rel.startswith("magicau/mtkcontroller/domain/"):
        return "domain"
    if rel.startswith("magicau/mtkcontroller/data/"):
        return "data"
    return "ui"  # feature/、ui/、根目录下的 MainActivity/MtkApp 等


def is_log_context(masked_source: str, offset: int) -> bool:
    """该字面量是不是日志/报告拼装的实参。"""
    call = enclosing_call(masked_source, offset)
    return call is not None and any(mark in call for mark in LOG_CALLS)


def is_ignored(lines: list[str], line_no: int) -> bool:
    lo = max(0, line_no - 1 - IGNORE_WINDOW)
    return IGNORE_MARK in "\n".join(lines[lo:line_no])


class Hit:
    __slots__ = ("path", "line", "text", "kind")

    def __init__(self, path: str, line: int, text: str, kind: str) -> None:
        self.path, self.line, self.text, self.kind = path, line, text, kind


def audit() -> list[Hit]:
    """全部含中文的字面量，按层与用途分类。

    kind：
      - `domain`  —— domain 层，必须为 0
      - `log`     —— data 层的日志/报告原文，按数据处理
      - `copy`    —— 应当进资源的中文
    """
    hits: list[Hit] = []
    for f in sorted(JAVA.rglob("*.kt")):
        raw = f.read_text(encoding="utf-8")
        clean = strip_comments(raw)
        lines = clean.splitlines()
        masked_src = masked(clean)
        rel = f.relative_to(ROOT).as_posix()
        layer = layer_of(f)
        for line_no, offset, text in literals(clean):
            if not CJK.search(text):
                continue
            if is_ignored(lines, line_no):
                continue
            # 日志与报告原文按数据处理，**不分层** —— `feature/` 里的
            # `AppLog.w(TAG, "…")` 同样是工程日志。这一条原先只在 data/ 生效，
            # 于是 CpuReapplyService 里的四条轮询日志被当成了文案。
            if layer == "domain":
                kind = "domain"
            elif is_log_context(masked_src, offset):
                kind = "log"
            else:
                kind = "copy"
            hits.append(Hit(rel, line_no, text, kind))
    return hits


def defined_keys() -> set[str]:
    keys: set[str] = set()
    for xml in (SRC / "res" / "values").glob("strings*.xml"):
        keys |= set(DEF.findall(xml.read_text(encoding="utf-8")))
    return keys


XML_COMMENT = re.compile(r"<!--.*?-->", re.S)


def referenced_keys() -> dict[str, set[str]]:
    """key -> 引用它的文件，便于定位。

    **先剥注释再扫。** 注释里写 `R.string.xxx` 是在举例或说明，不是引用 ——
    实测被自己文档里的示例绊倒过一次（`Message` 的 KDoc 引了一个还不存在的 key，
    构建直接失败）。注释不是代码，不该让构建挂掉。
    """
    out: dict[str, set[str]] = {}
    files = list((SRC / "java").rglob("*.kt"))
    files += list((SRC / "res").rglob("*.xml"))
    for f in files:
        raw = f.read_text(encoding="utf-8")
        text = strip_comments(raw) if f.suffix == ".kt" else XML_COMMENT.sub("", raw)
        for key in REF.findall(text) + XML_REF.findall(text):
            out.setdefault(key, set()).add(str(f.relative_to(ROOT)))
    return out


def interpolated(hits: list[Hit]) -> list[Hit]:
    """待迁移清单里含 `$` 插值的那部分 —— 要改成资源占位符才能搬。

    单独列出来是因为迁移动作不同：得先在 `strings.xml` 里按位置写 `%1$s`，
    再把调用点改成 `stringResource(id, …)`。只报数，不判失败。
    """
    return [h for h in hits if "$" in h.text]


# --- 文案形态扫描 ---------------------------------------------------------
#
# 正则只发现可能需要复核的字符串，不能据此判断文案是否合适。

# 可能包含多个状态的字符串，按 ui.md「选项状态」复核展示层级。
SEPARATOR = re.compile(r'"[^"\n]*\s·\s[^"\n]*"')

# 可能包含内部字段的字符串；诊断详情可以展示必要的原始字段。
# 含 `|` 的串排除：那是拼出来的 map key（如 "${id}|${key}"），不是文案。
INTERNAL = re.compile(
    r'"(?![^"\n]*\|)[^"\n]*(回读|失败项：|index=|policy[0-9]|\$key\.|\$\{item\.)[^"\n]*"'
)

# 技术词提示；是否适合目标用户由 wording.md 的表达原则判断。
IDENTITY = re.compile(
    r'"[^"\n]*('
    r"ROM|SoC|BSP|SELinux|binder|sysfs"
    r"|模拟器|真机"
    r"|内核|节点"
    r"|PowerHAL|powerhal"
    r"|framework|Framework"
    r')"[^"\n]*"'
)


def shape_hints() -> dict[str, list[tuple[str, str]]]:
    """界面层里的可疑形态 → {文件: [(类别, 原文)]}。

    只覆盖 feature/ 与 ui/ —— data/ 层产出的是数据，渲染责任在界面层。
    """
    out: dict[str, list[tuple[str, str]]] = {}
    for base in ("feature", "ui"):
        for f in (PKG / base).rglob("*.kt"):
            text = strip_comments(f.read_text(encoding="utf-8"))
            masked_src = masked(text)
            lines = text.splitlines()
            hits: list[tuple[str, str]] = []
            for label, pat in (("标点分隔状态", SEPARATOR),
                               ("内部标识符", INTERNAL),
                               ("身份词", IDENTITY)):
                for m in pat.finditer(text):
                    line_no = text.count("\n", 0, m.start()) + 1
                    if is_ignored(lines, line_no) or is_log_context(masked_src, m.start()):
                        continue
                    hits.append((label, m.group(0)))
            if hits:
                out[str(f.relative_to(ROOT))] = hits
    return out


def main() -> int:
    defined = defined_keys()
    used = referenced_keys()
    hits = audit()
    copy = [h for h in hits if h.kind == "copy"]
    logs = [h for h in hits if h.kind == "log"]
    domain = [h for h in hits if h.kind == "domain"]
    ui_copy = [h for h in copy if "/domain/" not in h.path and "/data/" not in h.path]
    data_copy = [h for h in copy if "/data/" in h.path]

    dangling = {k: sorted(v) for k, v in used.items() if k not in defined}
    unused = sorted(defined - used.keys())

    print("== 字符串资源 ==")
    print(f"  定义 {len(defined)} 个，被引用 {len(used)} 个")
    print(f"  定义未引用 {len(unused)} 个（仅提示，可能是占位或将来用）")

    print("\n== 悬空引用（引用但未定义，通常导致构建失败）==")
    if not dangling:
        print("  无")
    for key, files in sorted(dangling.items()):
        print(f"  {key}")
        for f in files:
            print(f"      {f}")

    ui_bad = shape_hints()
    print("\n== 文案形态提示（仅提示，按 wording.md 与 ui.md 判断）==")
    if not ui_bad:
        print("  无")
    total_bad = 0
    for f, found in sorted(ui_bad.items(), key=lambda kv: -len(kv[1])):
        print(f"  {f}  （{len(found)} 处）")
        for label, text in found[:4]:
            print(f"      [{label}] {text[:72]}")
        if len(found) > 4:
            print(f"      …还有 {len(found) - 4} 处")
        total_bad += len(found)
    print(f"  合计 {total_bad} 处")

    print("\n== 分层审计（代码里含中文的字符串字面量）==")
    print(f"  domain/  {len(domain)} 处  —— 必须为 0，那一层不带界面文案")
    print(f"  data/ 非日志  {len(data_copy)} 处  —— 控制层应返回状态与错误类型，由展示边界选文案")
    print(f"  feature/ + ui/  {len(ui_copy)} 处  —— 应全部进资源")
    print(f"  data/ 日志与报告原文  {len(logs)} 处  —— 按数据处理，不计入")

    def by_file(items: list[Hit]) -> list[tuple[str, int]]:
        counts: dict[str, int] = {}
        for h in items:
            counts[h.path] = counts.get(h.path, 0) + 1
        return sorted(counts.items(), key=lambda kv: -kv[1])

    if domain:
        print("\n  domain/ 明细（必须清零）：")
        for h in domain:
            print(f"      {h.path}:{h.line}  {h.text[:60]}")
    for title, items in (("feature/ + ui/", ui_copy), ("data/ 非日志", data_copy)):
        if not items:
            continue
        print(f"\n  {title} 明细：")
        for path, n in by_file(items)[:20]:
            print(f"    {n:>4}  {path}")
        if len(by_file(items)) > 20:
            print(f"    …还有 {len(by_file(items)) - 20} 个文件")

    inter = interpolated(copy)
    print(f"\n  其中含变量、要按位置参数改写成模板的 {len(inter)} 处（仅提示，不判失败）：")
    for path, n in by_file(inter)[:8]:
        print(f"    {n:>4}  {path}")

    snapshot = {
        "domain": len(domain),
        "data_copy": len(data_copy),
        "ui_copy": len(ui_copy),
        "logs": len(logs),
        "total_copy": len(copy),
        "total_chars": sum(len(h.text) for h in copy),
        "longest": max((len(h.text) for h in copy), default=0),
        "by_file": {path: n for path, n in by_file(copy)},
    }

    if "--freeze" in sys.argv:
        if dangling or domain:
            print("\n存在悬空引用或 domain 层中文，未写入基线。")
            return 1
        BASELINE.write_text(json.dumps(snapshot, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"\n基线已写入 {BASELINE.relative_to(ROOT)}：")
        print(f"  domain {snapshot['domain']} / data 非日志 {snapshot['data_copy']}"
              f" / feature+ui {snapshot['ui_copy']}")
        return 0

    if BASELINE.exists():
        base = json.loads(BASELINE.read_text(encoding="utf-8"))
        print(f"\n== 与基线对比（domain {base.get('domain', 0)} / "
              f"data 非日志 {base.get('data_copy', 0)} / "
              f"feature+ui {base.get('ui_copy', 0)}）==")
        print("  处数变多即失败；字数与最长值只是参考，不判失败。")

        grew = []
        for key, label in (("domain", "domain/"), ("data_copy", "data/ 非日志"),
                           ("ui_copy", "feature/ + ui/")):
            now, was = snapshot[key], base.get(key, 0)
            if now > was:
                grew.append(f"{label} 增加 {now - was} 处（{was} -> {now}）")
        if grew:
            print("  X " + "；".join(grew))
            for path, n in by_file(copy):
                if n > base.get("by_file", {}).get(path, 0):
                    print(f"      {path}: {base.get('by_file', {}).get(path, 0)} -> {n}")

        if snapshot["total_chars"] > base.get("total_chars", 0):
            print(f"  · 总字数增加 {snapshot['total_chars'] - base.get('total_chars', 0)} 字（仅提示）")
        if snapshot["longest"] > base.get("longest", 0):
            print(f"  · 最长文案变成 {snapshot['longest']} 字（仅提示）。"
                  f"若它出现在主界面而非信息按钮内，按 wording.md 应移进弹层")
        if not grew:
            print("  OK 未恶化")
        return 1 if (dangling or domain or grew) else 0

    print("\n（无基线，用 --freeze 建立）")
    return 1 if (dangling or domain) else 0


if __name__ == "__main__":
    sys.exit(main())
