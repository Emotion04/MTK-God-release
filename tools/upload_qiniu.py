#!/usr/bin/env python3
"""把生成好的 JSON 上传到七牛云 Kodo，并刷新 CDN。

用法：
    QINIU_ACCESS_KEY=... QINIU_SECRET_KEY=... QINIU_BUCKET=... \
    python tools/upload_qiniu.py release/public/changelog.json changelog.json

可选：
    QINIU_URL=https://你的域名/changelog.json  上传后刷新这个地址
    QINIU_PREFIX=mtk-god                        可选，多 App 共用一个空间时用文件夹
    python tools/upload_qiniu.py <本地文件> <对象 key>
环境变量没设时，读 .cyh/publish.json（由发布窗口保存，已被 gitignore）。
依赖：pip install qiniu
"""
import os
import sys

# 中文提示统一按 UTF-8 输出；管道或窗口按 UTF-8 读时不会变成乱码。
for _stream in (sys.stdout, sys.stderr):
    _stream.reconfigure(encoding="utf-8", errors="replace")

import mimetypes
from qiniu import Auth, CdnManager, put_file

LOCAL = sys.argv[1] if len(sys.argv) > 1 else "release/public/changelog.json"
KEY = sys.argv[2] if len(sys.argv) > 2 else "changelog.json"
PREFIX = os.environ.get("QINIU_PREFIX", "").strip("/")
if PREFIX:
    KEY = f"{PREFIX}/{KEY}"

def saved(key):
    try:
        import json
        import pathlib
        path = pathlib.Path(__file__).resolve().parent.parent / ".cyh" / "publish.json"
        return json.loads(path.read_text(encoding="utf-8")).get(key, "")
    except Exception:
        return ""


def need(env, saved_key):
    value = os.environ.get(env) or saved(saved_key)
    if not value:
        raise SystemExit(f"缺少 {env}：设置环境变量，或在发布窗口里填一次（存在 .cyh/publish.json）")
    return value


ak = need("QINIU_ACCESS_KEY", "ak")
sk = need("QINIU_SECRET_KEY", "sk")
bucket = need("QINIU_BUCKET", "bucket")

auth = Auth(ak, sk)
token = auth.upload_token(bucket, KEY, 3600)
mime = mimetypes.guess_type(LOCAL)[0] or "application/octet-stream"
if LOCAL.lower().endswith(".apk"):
    mime = "application/vnd.android.package-archive"
ret, info = put_file(token, KEY, LOCAL, mime_type=mime)
if info.status_code != 200:
    raise SystemExit(f"上传失败：{info.status_code} {info.text_body}")

url = os.environ.get("QINIU_URL")
if url:
    manager = CdnManager(auth)
    _, refresh = manager.refresh_urls([url])
    if refresh.status_code != 200:
        raise SystemExit(f"CDN 刷新失败：{refresh.status_code} {refresh.text_body}")

print(f"已上传 {LOCAL} -> {bucket}/{KEY}")
