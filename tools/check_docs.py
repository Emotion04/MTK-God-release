"""检查 docs/ 的链接、锚点、总索引可达性、待办位置，以及文档里点名的代码标识符是否存在。

内容正确性由维护流程复核；这里只做**机械能判定的那部分**。
"""

from __future__ import annotations

import re
import sys
from collections import defaultdict
from pathlib import Path
from urllib.parse import unquote


ROOT = Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"
SOURCE = ROOT / "mtk-optimizer" / "app" / "src" / "main"
LINK = re.compile(r"(?<!!)\[[^\]\r\n]+\]\(([^)\r\n]+)\)")
HEADING = re.compile(r"^\s{0,3}#{1,6}\s+(.+?)\s*#*\s*$")
EXPLICIT_ANCHOR = re.compile(r"<a\s+(?:name|id)=[\"']([^\"']+)[\"']", re.IGNORECASE)

#: 待办写法。**必须长得像一条待办条目**，不能只是句子里提到这几个词 ——
#: 否则规范文档里"待办写法只允许……"这一句自己就会被判违规。
#: 只认三种形状：未勾选复选框、行首加粗的「待做」、行首的 TODO 条目。
TASK_PATTERNS = (
    re.compile(r"^\s*[-*+]\s+\[ \]"),          # - [ ] 未勾选
    re.compile(r"^\s*(?:[-*+]\s+)?\*\*待做\*\*"),  # **待做**
    re.compile(r"^\s*[-*+]\s+TODO\b", re.IGNORECASE),  # - TODO
)
#: 除归档外，唯一允许写待办的地方。归档是历史，不在此列。
TASK_ALLOWED = {"dev/todo.md"}

# --- 文档里点名的代码标识符必须在源码里存在 -----------------------------------
#
# 防的是一类具体的漂移：文档写"子页按 `SubScreen` 的布局处理"，而代码里
# 根本没有 `SubScreen`（实际叫 `SubScreenScaffold`）。这种错**没人会去核**，
# 通过窄范围扫描提示可能的改名漂移，语义仍需对照源码复核。
#
# 只认**看起来像本项目类型/函数名**的形状：首字母大写、含至少一个小写字母、长度 ≥ 4。
# 这样能自动排除：
#   - 全大写缩写（`MEMC`、`ABE`、`HTP`）
#   - 设备侧名字（`scaling_max_freq`、`PERF_RES_CPUFREQ_*`、`content insert`）
#   - 短名字（`Yes`、`No`、`SKIN`）
IDENTIFIER = re.compile(r"`([A-Z][A-Za-z0-9]{3,})`")
#: 这些目录写的是外部材料或历史，里面的符号不属于本项目。
SYMBOL_SCAN_SKIP = {"reference", "archive"}
#: 有些页面**通篇在讲外部材料**（厂商配置里的应用名、第三方模块的类名），
#: 里面的符号本来就不该在源码里。这类页面自己声明一下，比在脚本里堆一堆
#: 例外名单清楚 —— 例外名单会让同一个名字在**任何**页面都被放行。
SYMBOL_EXTERNAL_MARK = "<!-- external-symbols -->"
SYMBOL_MARK_WINDOW = 20


def markdown_files() -> list[Path]:
    return sorted(DOCS.rglob("*.md"))


def visible_lines(path: Path):
    in_fence = False
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if line.lstrip().startswith("```"):
            in_fence = not in_fence
            continue
        if not in_fence:
            yield number, line


def markdown_anchors(path: Path) -> set[str]:
    anchors: set[str] = set()
    duplicate_counts: dict[str, int] = defaultdict(int)

    for _, line in visible_lines(path):
        anchors.update(EXPLICIT_ANCHOR.findall(line))
        heading = HEADING.match(line)
        if not heading:
            continue

        title = heading.group(1)
        title = re.sub(r"\[([^\]]+)\]\([^)]*\)", r"\1", title)
        title = title.replace("`", "").lower()
        slug = re.sub(r"[^\w-]", "", re.sub(r"\s+", "-", title), flags=re.UNICODE)
        slug = re.sub(r"-+", "-", slug).strip("-")
        suffix = duplicate_counts[slug]
        duplicate_counts[slug] += 1
        anchors.add(slug if suffix == 0 else f"{slug}-{suffix}")

    return anchors


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")

    errors: list[str] = []
    anchor_cache: dict[Path, set[str]] = {}

    for path in markdown_files():
        for number, line in visible_lines(path):
            for target in LINK.findall(line):
                target = target.strip("<>")
                target_path, separator, fragment = target.partition("#")
                target = target_path
                if (not target and not separator) or re.match(
                    r"^(?:https?:|mailto:)", target
                ):
                    continue
                resolved = path.resolve() if not target else (path.parent / target).resolve()
                if not resolved.exists():
                    errors.append(
                        f"断链 {path.relative_to(DOCS)}:{number} -> {target}"
                    )
                elif separator and fragment and resolved.is_file() and resolved.suffix == ".md":
                    if resolved not in anchor_cache:
                        anchor_cache[resolved] = markdown_anchors(resolved)
                    if unquote(fragment) not in anchor_cache[resolved]:
                        errors.append(
                            f"片段断链 {path.relative_to(DOCS)}:{number} -> "
                            f"{target}#{fragment}"
                        )

    errors.extend(check_task_language())
    errors.extend(check_index_reachability())
    errors.extend(check_identifiers_exist())

    if errors:
        print("文档检查失败：")
        for error in errors:
            print(f"- {error}")
        return 1

    print(f"文档检查通过：{len(markdown_files())} 个 Markdown —— 链接、锚点、总索引可达性、"
          f"待办位置、代码标识符都存在。")
    return 0


def is_archived(path: Path) -> bool:
    return "archive" in path.relative_to(DOCS).parts


def check_task_language() -> list[str]:
    """检查常见的显式待办格式，不判断任务状态或自然语言语义。"""
    problems = []
    for path in markdown_files():
        relative = path.relative_to(DOCS).as_posix()
        if is_archived(path) or relative in TASK_ALLOWED:
            continue
        for number, line in visible_lines(path):
            if any(pattern.search(line) for pattern in TASK_PATTERNS):
                problems.append(
                    f"待办散落 {relative}:{number} —— 待办只写进 "
                    f"dev/todo.md；这一页应该只描述当前状态"
                )
    return problems


def local_markdown_links(path: Path) -> list[Path]:
    """Return Markdown targets reachable directly from one Markdown file."""
    targets: list[Path] = []
    for _, line in visible_lines(path):
        for raw_target in LINK.findall(line):
            target = raw_target.strip("<>")
            target_path, separator, _ = target.partition("#")
            if not target_path or re.match(r"^(?:https?:|mailto:)", target_path):
                continue
            resolved = (path.parent / unquote(target_path)).resolve()
            if resolved.is_dir():
                resolved = resolved / "README.md"
            if resolved.suffix.lower() == ".md" and resolved.exists():
                targets.append(resolved)
    return targets


def source_words() -> set[str]:
    """源码里出现过的所有标识符片段。

    **文件名也算。** 只扫内容会漏掉那些只在文件名里出现的符号 ——
    `DataStores.kt` 就是个例子：它是个扩展属性持有者，全仓库没有任何一处
    写着 `DataStores` 这个词，只有文件名。不加这一条就会把它误报成"不存在"。
    """
    words: set[str] = set()
    for path in SOURCE.rglob("*"):
        if path.suffix in {".kt", ".xml", ".aidl"}:
            words.add(path.stem)
            words |= set(
                re.findall(r"[A-Za-z_][A-Za-z0-9_]*", path.read_text(encoding="utf-8", errors="replace"))
            )
    return words


def declares_external_symbols(text: str) -> bool:
    head = "\n".join(text.splitlines()[:SYMBOL_MARK_WINDOW])
    return SYMBOL_EXTERNAL_MARK in head


def check_identifiers_exist() -> list[str]:
    """文档反引号里的库/类/函数名必须在源码里出现。

    漏检是可能的（名字被拆行、或写在代码块里），也可能因文档语境产生噪音；
    形状限定只让它适合作为窄范围的漂移提示，不是事实正确性证明。
    """
    words = source_words()
    problems = []
    for path in markdown_files():
        if is_archived(path) or SYMBOL_SCAN_SKIP & set(path.relative_to(DOCS).parts):
            continue
        text = path.read_text(encoding="utf-8")
        if declares_external_symbols(text):
            continue
        relative = path.relative_to(DOCS).as_posix()
        for number, line in visible_lines(path):
            for name in IDENTIFIER.findall(line):
                if name in words:
                    continue
                # 全大写缩写不作为标识符：`MEMC` 这类是术语表里的名词。
                if name.isupper():
                    continue
                problems.append(
                    f"文档点名了不存在的标识符 {relative}:{number} `{name}` —— "
                    f"要么源码里改了名，要么这里写错了"
                )
    return problems


def check_index_reachability() -> list[str]:
    """Require every docs Markdown page to be reachable from docs/README.md."""
    root = (DOCS / "README.md").resolve()
    reachable: set[Path] = set()
    pending = [root]
    while pending:
        path = pending.pop()
        if path in reachable or not path.exists():
            continue
        reachable.add(path)
        pending.extend(local_markdown_links(path))

    orphaned = [
        path.relative_to(DOCS).as_posix()
        for path in markdown_files()
        if path.resolve() not in reachable
    ]
    return [f"未从 docs/README.md 可达：{relative}" for relative in orphaned]


if __name__ == "__main__":
    sys.exit(main())
