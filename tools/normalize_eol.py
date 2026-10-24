#!/usr/bin/env python3
"""把仓库里被编辑器改回 CRLF 的文件统一成 LF（与 .gitattributes 一致）。

只在工作区转换，不改内容：去掉 \r 后与原文逐字相同才算“纯换行差异”，
否则跳过并报告，避免吞掉真正的修改。
"""
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent


def main():
    out = subprocess.run(["git", "ls-files", "--eol"], cwd=ROOT, capture_output=True,
                         text=True, encoding="utf-8").stdout
    changed = []
    for line in out.splitlines():
        if "w/crlf" not in line and "w/mixed" not in line:
            continue
        rel = line.split("\t", 1)[1]
        path = ROOT / rel
        raw = path.read_bytes()
        if b"\x00" in raw[:8000]:
            continue  # 二进制
        if b"\r\n" not in raw:
            continue
        lf = raw.replace(b"\r\n", b"\n")
        old = subprocess.run(["git", "show", f"HEAD:{rel}"], cwd=ROOT, capture_output=True).stdout
        if old.replace(b"\r\n", b"\n") == lf:
            path.write_bytes(lf)
            changed.append(rel)
        else:
            print("跳过（有真实改动）：", rel, file=sys.stderr)
    print(f"转换 {len(changed)} 个文件")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    main()
