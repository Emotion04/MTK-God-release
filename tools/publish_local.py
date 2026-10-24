#!/usr/bin/env python3
"""本地一键发布：生成 changelog.json / update.json，并上传到七牛云。

前置环境变量（不要写进仓库）：
    QINIU_ACCESS_KEY / QINIU_SECRET_KEY / QINIU_BUCKET
可选：
    QINIU_PREFIX（默认 MTK-God）
    QINIU_URL（上传后刷新 CDN；可只填目录或单个文件）

用法：
    python tools/publish_local.py \
      --apk mtk-optimizer/app/build/outputs/apk/release/app-release.apk \
      --apk-url https://changelog.a0v0a.cn/MTK-God/app-release.apk \
      （--notes 可选，不填时取 CHANGELOG.md 最新一版的前几条）

会生成（每次整份重写，不保留旧版）：
    release/public/changelog.json     ← 解析根目录 CHANGELOG.md
    release/public/update.json        ← 读 build.gradle.kts 的版本号 + 窗口里填的说明
    release/public/remote-config.json ← 去掉 tools/remote-config.jsonc 的注释
并上传到 {prefix}/changelog.json、{prefix}/update.json、{prefix}/remote-config.json、
{prefix}/app-release.apk。
"""
import argparse
import json
import os
import pathlib
import re
import subprocess
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import release_config as cfg  # noqa: E402

# 中文提示统一按 UTF-8 输出；管道或窗口按 UTF-8 读时不会变成乱码。
for _stream in (sys.stdout, sys.stderr):
    _stream.reconfigure(encoding="utf-8", errors="replace")

ROOT = pathlib.Path(__file__).resolve().parent.parent
PUBLIC = ROOT / "release" / "public"


def run(*args, env=None):
    print("+", " ".join(str(a) for a in args))
    subprocess.run(args, cwd=ROOT, check=True, env=env)


def upload(key: str, local: pathlib.Path, prefix: str):
    env = os.environ.copy()
    env["QINIU_PREFIX"] = prefix
    run(sys.executable, "tools/upload_qiniu.py", str(local), key, env=env)


def publish_remote_config(prefix: str):
    """从 tools/remote-config.jsonc 生成并上传 remote-config.json。"""
    from release_updates import build_remote_config

    target = PUBLIC / "remote-config.json"
    build_remote_config()
    print(f"写入 {target}")
    upload("remote-config.json", target, prefix)


def main():
    """可选：把 release/public/ 里的文件传到七牛云（不是默认发布方式）。

    发布文件本身由 tools/release_updates.py 生成（和 GitHub Actions 用同一套），
    这里只负责上传，顺带校验 APK 版本号。
    """
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", type=pathlib.Path, default=ROOT / "mtk-optimizer/app/build/outputs/apk/release/app-release.apk")
    ap.add_argument("--prefix", default=os.environ.get("QINIU_PREFIX", "MTK-God"))
    args = ap.parse_args()

    if not args.apk.is_file():
        raise SystemExit(f"APK 不存在：{args.apk}\n先构建 release，再发布；本次没有改动线上文件")
    code, _ = cfg.gradle_version()
    built = cfg.apk_version_code(args.apk)
    if built != code:
        raise SystemExit(f"版本号不一致：APK 是 {built}，build.gradle.kts 是 {code}。"
                         "旧的 app-release.apk 还在，先重新构建；本次没有改动线上文件")

    run(sys.executable, "tools/release_updates.py", "--apk", str(args.apk))

    # 先上传 APK 再发 update.json：清单一旦更新，点下载必须能拿到新包。
    upload(args.apk.name, args.apk, args.prefix)
    upload("changelog.json", PUBLIC / "changelog.json", args.prefix)
    upload("update.json", PUBLIC / "update.json", args.prefix)
    publish_remote_config(args.prefix)
    print("完成。请确认空间是公开读，下载地址与 update.json 中的 url 一致。")


if __name__ == "__main__":
    main()
