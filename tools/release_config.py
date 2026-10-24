#!/usr/bin/env python3
"""发布相关的共享逻辑：配置、远程地址、JSONC、更新清单生成。

`make_update.py`（构建 / CI 用）和 `publish_gui.py`（本地可选工具）都从这里取，
两边规则只有一份，不会各说各话。

配置文件：`release/config.jsonc`（可写 `//` 注释），字段说明见 `release/config.md`。
不写配置时的默认值 = 用 GitHub Releases 发布（仓库 MTK-God）。
"""
import json
import os
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
CONFIG = ROOT / "release" / "config.jsonc"
GRADLE = ROOT / "mtk-optimizer" / "app" / "build.gradle.kts"
DEFAULT_APK = ROOT / "mtk-optimizer" / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"

# 没写配置文件时的默认值。改默认发布方式只改这里。
DEFAULT_CONFIG = {
    "apkUrl": "https://github.com/Emotion04/MTK-God-release/releases/latest/download/app-release.apk",
    "changelogUrl": "https://raw.githubusercontent.com/Emotion04/MTK-God-release/main/changelog.json",
    "updateUrl": "https://raw.githubusercontent.com/Emotion04/MTK-God-release/main/update.json",
    "remoteConfigUrl": "https://raw.githubusercontent.com/Emotion04/MTK-God-release/main/remote-config.json",
    "minVersionCode": 0,
}


def strip_jsonc(text: str) -> str:
    """去掉 // 和 /* */ 注释，并把是否带注释的决定权留给源文件本身。"""
    out, i, n = [], 0, len(text)
    in_str = False
    while i < n:
        c = text[i]
        if in_str:
            out.append(c)
            if c == "\\":
                out.append(text[i + 1] if i + 1 < n else "")
                i += 2
                continue
            if c == '"':
                in_str = False
            i += 1
            continue
        if c == '"':
            in_str = True
            out.append(c)
            i += 1
        elif text.startswith("//", i):
            j = text.find("\n", i)
            i = n if j < 0 else j
        elif text.startswith("/*", i):
            j = text.find("*/", i + 2)
            i = n if j < 0 else j + 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def load_config(path: pathlib.Path = CONFIG) -> dict:
    data = dict(DEFAULT_CONFIG)
    if path.is_file():
        data.update(json.loads(strip_jsonc(path.read_text(encoding="utf-8"))))
    return data


def gradle_version():
    text = GRADLE.read_text(encoding="utf-8")
    code = re.search(r"versionCode\s*=\s*(\d+)", text)
    name = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    return int(code.group(1)) if code else 0, name.group(1) if name else ""


def apk_version_code(apk: pathlib.Path) -> int:
    """用 aapt 读 APK 真实版本号；找不到 aapt 时返回 -1（跳过校验）。"""
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        props = ROOT / "mtk-optimizer" / "local.properties"
        m = re.search(r"^sdk\.dir=(.+)$", props.read_text(encoding="utf-8"), re.M) if props.is_file() else None
        sdk = m.group(1).strip().replace("\\", "/") if m else None
    if not sdk:
        return -1
    tools = sorted(pathlib.Path(sdk).glob("build-tools/*/aapt.exe")) +         sorted(pathlib.Path(sdk).glob("build-tools/*/aapt"))
    for aapt in tools:
        out = subprocess.run([str(aapt), "dump", "badging", str(apk)], capture_output=True,
                             text=True, encoding="utf-8", errors="replace").stdout
        m = re.search(r"versionCode='(\d+)'", out)
        if m:
            return int(m.group(1))
    return -1


def git(*args):
    try:
        return subprocess.check_output(["git", *args], cwd=ROOT, text=True,
                                       encoding="utf-8", stderr=subprocess.DEVNULL).strip()
    except (subprocess.CalledProcessError, FileNotFoundError):
        return ""


def notes_from_changelog(path: pathlib.Path = ROOT / "CHANGELOG.md", limit: int = 200) -> str:
    """更新日志最新一版的前几条，拼成一句话。读文件而不是收命令行参数，免得受控制台编码影响。"""
    if not path.is_file():
        return ""
    text = path.read_text(encoding="utf-8")
    head = re.search(r"^##\s+\[?[^\]\s]+\]?\s*[-—–]\s*\d{4}-\d{2}-\d{2}\s*$\n(.*?)(?=^##\s|\Z)",
                     text, re.S | re.M)
    if not head:
        return ""
    items = [m.group(1).strip() for m in re.finditer(r"^-\s+(.+)$", head.group(1), re.M)]
    joined = "；".join(items)
    if len(items) > 3:
        joined += " 等"
    if len(joined) > limit:
        joined = joined[:limit].rstrip("；;，, ") + "…"
    return joined


def build_update_info(*, apk_url: str, apk: pathlib.Path, notes: str = "", channel: str = "",
                      min_version_code: int = 0, published_at: int | None = None) -> dict:
    """生成 update.json 内容。版本号取 build.gradle.kts，渠道默认按 tag 判断。"""
    import datetime
    import hashlib

    if not apk_url.startswith("https://"):
        raise SystemExit("APK 下载地址必须是 https")
    code, name = gradle_version()
    tag = f"v{name}"
    has_tag = bool(git("rev-parse", "-q", "--verify", f"refs/tags/{tag}"))
    if published_at is None:
        stamp = git("log", "-1", "--format=%ct", tag) if has_tag else ""
        published_at = int(stamp) * 1000 if stamp.isdigit() else int(datetime.datetime.now().timestamp() * 1000)
    return {
        "schema": 1,
        "channel": channel or ("canary" if "-canary" in tag else "stable"),
        "versionCode": code,
        "versionName": name,
        # APK 里没有可靠的构建时间（Gradle 统一写 1981），这里不拿文件修改时间冒充，记 0 表示未知。
        "buildTime": 0,
        "publishedAt": published_at,
        "minVersionCode": min_version_code,
        "notes": notes,
        "url": apk_url,
        "sha256": hashlib.sha256(apk.read_bytes()).hexdigest() if apk.is_file() else "",
        # 包体大小：App 用它显示大小、给下载画进度条，也是判断“能不能直接下载”的依据。
        "size": apk_size(apk),
    }


def apk_size(apk: pathlib.Path) -> int:
    return apk.stat().st_size if apk.is_file() else 0


def write_json(path: pathlib.Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    print(json.dumps(load_config(), ensure_ascii=False, indent=2))
