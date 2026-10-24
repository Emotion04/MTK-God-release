#!/usr/bin/env python3
"""核对 release/config.jsonc 里的远程地址与 App 里写死的默认值一致。

App 只认 BuildConfig 里的地址；配置改了、build.gradle.kts 没跟上，用户就永远看不到更新。
构建前通过 Gradle 的 checkReleaseConfig 任务调用，也可以单独跑。

用 `-PchangelogUrl=` 等覆盖过的那一项不检查（值是渠道特定的）。
"""
import os
import re
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import release_config as cfg  # noqa: E402

PAIRS = [
    ("changelogUrl", "CHANGELOG_URL"),
    ("updateUrl", "UPDATE_URL"),
    ("remoteConfigUrl", "REMOTE_CONFIG_URL"),
]


def main():
    config = cfg.load_config()
    gradle = cfg.GRADLE.read_text(encoding="utf-8")
    problems = []
    for key, field in PAIRS:
        if os.environ.get(f"GRADLE_PROP_{key}"):
            continue
        index = gradle.find(f'"{field}"')
        match = re.search(r'"(https://[^"]*)"', gradle[index:]) if index >= 0 else None
        default = match.group(1) if match else ""
        configured = config.get(key, "")
        if index < 0:
            problems.append(f"{field} 在 build.gradle.kts 里不存在")
        elif configured and configured != default:
            problems.append(f"{key} = {configured}，但 {field} 默认值是 {default}")
    for line in problems:
        print(line, file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
