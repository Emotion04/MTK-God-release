#!/usr/bin/env python3
"""生成公开的 update.json（App 更新检测清单）。

默认值全部来自仓库当前状态，不用手填：
    versionCode / versionName  ← build.gradle.kts
    notes                      ← CHANGELOG.md 最新一版的前几条
    channel                    ← tag 带 -canary 就是 canary，否则 stable
    sha256                     ← APK 文件
    apk-url / 其他地址          ← release/config.jsonc（不写配置时用 GitHub Releases）

用法：
    python tools/make_update.py                       # 写完 release/public/update.json
    python tools/make_update.py --apk-url https://... # 临时换下载地址

纪律：**versionCode 变了不等于发版**。只有打了 tag（v0.6.0）的构建才会被发布，
没打 tag 的提交，App 永远看不到。见 docs/dev/release-flow.md。
"""
import argparse
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import release_config as cfg  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk-url", default="", help="覆盖 release/config.jsonc 里的 apkUrl")
    ap.add_argument("--apk", type=pathlib.Path, default=cfg.DEFAULT_APK)
    ap.add_argument("--out", type=pathlib.Path, default=cfg.ROOT / "release" / "public" / "update.json")
    ap.add_argument("--changelog", type=pathlib.Path, default=cfg.ROOT / "CHANGELOG.md")
    ap.add_argument("--channel", default="", choices=["", "stable", "canary"])
    ap.add_argument("--min-version-code", type=int, default=-1)
    ap.add_argument("--notes", default="", help="不填时取 CHANGELOG.md 最新一版的前几条")
    args = ap.parse_args()

    config = cfg.load_config()
    notes = args.notes.strip() or cfg.notes_from_changelog(args.changelog)
    if len(notes) > 200:
        notes = notes[:200].rstrip("；;，, ") + "…"
    payload = cfg.build_update_info(
        apk_url=args.apk_url or config["apkUrl"],
        apk=args.apk,
        notes=notes,
        channel=args.channel,
        min_version_code=config["minVersionCode"] if args.min_version_code < 0 else args.min_version_code,
    )
    cfg.write_json(args.out, payload)
    print(f"写入 {args.out}：{payload['versionName']} ({payload['versionCode']}) "
          f"{payload['channel']} -> {payload['url']}")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    main()
