#!/usr/bin/env python3
"""发版流水线的第 2 步：根据 tag 重新生成发布用的 JSON（release/public/）。

在“构建 APK”和“创建 GitHub Release / 上传”之间跑：
    python tools/release_updates.py --tag v0.6.0

做的事：
    changelog.json     ← 远端版本由 CHANGELOG.md 全量生成；**再把当前版本置顶**。
                         只从 tag 范围生成的话，没经过发版的提交会一直缺失，
                         等下次发版就永久漏掉。
    update.json        ← versionCode / 说明 / 渠道 / sha256 全部自动取，见 make_update.py
    remote-config.json ← 去掉 tools/remote-config.jsonc 的注释，保持和 App 读到的结构一致

发布期间代码就冻结在这里：改 JSON 时不要同时改 CHANGELOG.md 或 APK。
"""
import argparse
import pathlib
import re
import subprocess
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import release_config as cfg  # noqa: E402

PUBLIC = cfg.ROOT / "release" / "public"


def build_changelog(tag: str) -> dict:
    """CHANGELOG.md 全量 → changelog.json，再把当前版本置顶并补上 tag 范围内的提交。

    不只用 tag 范围生成：没经过发版的提交（直接推 main 的修复）如果只从 tag 里取，
    会永久缺在线上更新日志里。合并后按类型排序，同一版本里徽章更整齐。
    """
    import json

    out = PUBLIC / "changelog.json"
    subprocess.run([sys.executable, "tools/make_changelog.py", "--from-changelog", "CHANGELOG.md",
                    "--out", str(out)], cwd=cfg.ROOT, check=True)
    entries = json.loads(out.read_text(encoding="utf-8"))["entries"]
    code, name = cfg.gradle_version()
    if not entries or entries[0]["versionName"] != name:
        raise SystemExit(f"CHANGELOG.md 最新一版不是 {name}，先补更新日志再发版")

    notes = [dict(n) for n in entries[0]["notes"]]
    seen = {n["text"] for n in notes}
    previous = cfg.git("describe", "--tags", "--abbrev=0", f"{tag}^") if tag else ""
    if previous:
        for subject in cfg.git("log", "--no-merges", "--pretty=format:%s", f"{previous}..{tag}").splitlines():
            subject = subject.strip()
            m = re.match(r"^(\w+)(?:\([^)]*\))?!?\s*[:：]\s*(.+)$", subject)
            text = m.group(2).strip() if m else subject
            if text and text not in seen:
                notes.append({"type": _type(subject), "text": text})
                seen.add(text)
    order = {"feat": 0, "fix": 1, "perf": 2, "refactor": 3, "docs": 4, "chore": 5}
    notes.sort(key=lambda n: order.get(n["type"], 99))
    entries[0]["notes"] = notes
    cfg.write_json(out, {"schema": 1, "entries": entries})
    return {"schema": 1, "entries": entries}


_ALIAS = {"新增": "feat", "修复": "fix", "优化": "perf", "重构": "refactor", "文档": "docs", "杂项": "chore"}
_KNOWN = {"feat", "fix", "perf", "refactor", "docs", "chore", "build", "ci", "test", "style"}


def _type(subject: str) -> str:
    head = subject.split(":")[0].split("(")[0].strip().lower()
    head = _ALIAS.get(head, head)
    return head if head in _KNOWN else ""


def build_remote_config() -> dict:
    """tools/remote-config.jsonc（带注释的源文件）→ release/public/remote-config.json，并做基本校验。"""
    import json

    source = cfg.ROOT / "tools" / "remote-config.jsonc"
    data = json.loads(cfg.strip_jsonc(source.read_text(encoding="utf-8")))
    if data.get("schema") != 1:
        raise SystemExit("remote-config schema 必须是 1")
    if data.get("channel") not in ("stable", "canary"):
        raise SystemExit("remote-config channel 只能是 stable 或 canary")
    if data.get("downloadUrl") and not data["downloadUrl"].startswith("https://"):
        raise SystemExit("downloadUrl 必须是 https")
    cfg.write_json(PUBLIC / "remote-config.json", data)
    return data


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag", default="", help="本次发布的 tag，例如 v0.6.0；CI 里由 tag 触发时自动传入")
    ap.add_argument("--apk", type=pathlib.Path, default=cfg.DEFAULT_APK)
    args = ap.parse_args()

    if not args.apk.is_file():
        raise SystemExit(f"找不到 APK：{args.apk}。先构建，再生成发布文件")
    built = cfg.apk_version_code(args.apk)
    code, name = cfg.gradle_version()
    if built >= 0 and built != code:
        raise SystemExit(f"APK 版本 {built} 与 build.gradle.kts 的 {code} 不一致：旧的 app-release.apk 还在，"
                         "先重新构建（或删掉它），否则这次发布的内容和版本号对不上")
    config = cfg.load_config()
    changelog = build_changelog(args.tag)
    update = cfg.build_update_info(apk_url=config["apkUrl"], apk=args.apk, channel="",
                                   min_version_code=config["minVersionCode"])
    cfg.write_json(PUBLIC / "update.json", update)
    remote = build_remote_config()
    print(f"changelog：{len(changelog['entries'])} 个版本；"
          f"update：{update['versionName']} ({update['versionCode']}) {update['channel']}；"
          f"remote-config：v{remote.get('version')}")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    main()
