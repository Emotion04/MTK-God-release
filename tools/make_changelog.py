#!/usr/bin/env python3
"""从 git 提交生成 release/public/changelog.json。

约定式提交前缀映射为徽章类型：feat / fix / perf / refactor / docs / chore / build / ci / test。
没有前缀的提交按普通条目处理。只取标题行，不抓正文。

用法（在仓库根目录）：
    python tools/make_changelog.py                 # 上一个 tag 到 HEAD
    python tools/make_changelog.py --since HEAD~10 # 指定范围
    python tools/make_changelog.py --tag v0.5.1    # 指定范围内所有提交归到这一版

版本号默认从 mtk-optimizer/app/build.gradle.kts 读 versionCode / versionName。
"""
import argparse
import datetime
import json
import pathlib
import re
import subprocess
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import release_config as cfg  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parent.parent
GRADLE = ROOT / "mtk-optimizer" / "app" / "build.gradle.kts"
OUT = ROOT / "release" / "public" / "changelog.json"

TYPES = {
    "feat": "feat",
    "fix": "fix",
    "perf": "perf",
    "refactor": "refactor",
    "docs": "docs",
    "chore": "chore",
    "build": "build",
    "ci": "ci",
    "test": "test",
    "style": "style",
}
PREFIX = re.compile(r"^(\w+)(?:\([^)]*\))?!?\s*[:：]\s*(.+)$")


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True, encoding="utf-8").strip()


def gradle_version():
    text = GRADLE.read_text(encoding="utf-8")
    code = re.search(r"versionCode\s*=\s*(\d+)", text)
    name = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    return int(code.group(1)) if code else 0, name.group(1) if name else ""


SECTION_TYPES = {
    "新增": "feat",
    "修复": "fix",
    "优化": "perf",
    "改进": "perf",
    "重构": "refactor",
    "文档": "docs",
    "杂项": "chore",
    "其他": "",
}
HEADING = re.compile(r"^##\s+\[?([^\]\s]+?)\]?\s*[-—–]\s*(\d{4}-\d{2}-\d{2})")
SECTION = re.compile(r"^###\s+(.+)$")
ITEM = re.compile(r"^-\s+(.+)$")


def parse_markdown(text):
    entries = []
    seen = set()
    section = ""
    for line in text.splitlines():
        m = HEADING.match(line)
        if m:
            seen.add(m.group(1).strip().lstrip("v"))
            entries.append({
                "versionName": m.group(1).strip().lstrip("v"),
                "date": m.group(2),
                "summary": "",
                "notes": [],
            })
            section = ""
            continue
        m = SECTION.match(line)
        if m and entries:
            section = SECTION_TYPES.get(m.group(1).strip(), "")
            continue
        m = ITEM.match(line)
        if m and entries:
            entries[-1]["notes"].append({"type": section, "text": m.group(1).strip()})
        elif line.strip() and entries and not line.startswith("#") and entries[-1]["notes"]:
            # 接在条目后面的续行，合并进上一条
            entries[-1]["notes"][-1]["text"] += line.strip()
    kept = [e for e in entries if e["notes"]]
    for entry in entries:
        if not entry["notes"]:
            print(f"警告：版本 {entry['versionName']} 没有解析到任何条目，已跳过。"
                  "检查标题是否为 `## [版本] - YYYY-MM-DD`，条目是否为 `- ` 开头。")
    for entry in kept:
        for note in entry["notes"]:
            if not note["type"]:
                print(f"警告：{entry['versionName']} 的条目「{note['text']}」所在段落不是"
                      "### 新增 / 修复 / 优化 / 重构 / 文档 / 杂项，徽章会留空。")
                break
    # 形如 0.5 的二级标题不被上面的规则识别，单独提示一次
    for line in text.splitlines():
        m = re.match(r"^#{1,3}\s+\[?v?(\d+\.\d+(?:\.\d+)?)\]?\s*$", line)
        if m and m.group(1) not in seen:
            print(f"警告：标题「{line.strip()}」缺日期，已忽略。写成 `## [{m.group(1)}] - YYYY-MM-DD`。")
    return kept


def last_tag():
    try:
        return git("describe", "--tags", "--abbrev=0")
    except subprocess.CalledProcessError:
        return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--since", help="git 范围起点（默认上一个 tag）")
    ap.add_argument("--until", default="HEAD", help="git 范围终点")
    ap.add_argument("--tag", help="版本名；默认读 build.gradle.kts")
    ap.add_argument("--version-code", type=int)
    ap.add_argument("--out", type=pathlib.Path, default=OUT)
    ap.add_argument("--from-changelog", type=pathlib.Path,
                    help="从根目录 CHANGELOG.md 解析，而不是从 git 提交生成")
    args = ap.parse_args()

    if args.from_changelog:
        entries = parse_markdown(args.from_changelog.read_text(encoding="utf-8"))
        code, name = gradle_version()
        for i, entry in enumerate(entries):
            # 历史版本没有对应构建，只能从当前 code 往下递减估一个，供排序用。
            entry["versionCode"] = max(code - i, 0)
        if entries and entries[0]["versionName"] != name:
            print(f"提示：CHANGELOG.md 最新版本是 {entries[0]['versionName']}，"
                  f"而 build.gradle.kts 是 {name}，记得补最新一版。")
        payload = {"schema": 1, "entries": entries}
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + chr(10), encoding="utf-8")
        print(f"写入 {args.out}（{len(entries)} 个版本，来自 {args.from_changelog}）")
        return

    code, name = gradle_version()
    if args.version_code:
        code = args.version_code
    if args.tag:
        name = args.tag.lstrip("v")

    since = args.since or last_tag()
    rng = f"{since}..{args.until}" if since else args.until
    # 日期取离 HEAD 最近的提交日期；tag 没打时也算一个明确的时间点。
    date = cfg.git("log", "-1", "--format=%cs", args.until) or datetime.date.today().isoformat()
    notes = []
    for subject in cfg.git("log", "--no-merges", "--pretty=format:%s", rng).splitlines():
        subject = subject.strip()
        if not subject:
            continue
        m = PREFIX.match(subject)
        if m and m.group(1).lower() in TYPES:
            notes.append({"type": TYPES[m.group(1).lower()], "text": m.group(2).strip()})
        else:
            notes.append({"type": "", "text": subject})

    if not notes:
        notes = [{"type": "chore", "text": "本次没有记录到提交"}]

    # 同类型相邻，界面才能只显示一次徽章；组内保持提交顺序。
    order = {"feat": 0, "fix": 1, "perf": 2, "refactor": 3, "docs": 4, "chore": 5}
    notes.sort(key=lambda n: order.get(n["type"], 99))

    payload = {
        "schema": 1,
        "entries": [{
            "versionCode": code,
            "versionName": name,
            "date": date,
            "notes": notes,
        }],
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"写入 {args.out}（{len(notes)} 条，范围 {rng}）")


if __name__ == "__main__":
    main()
