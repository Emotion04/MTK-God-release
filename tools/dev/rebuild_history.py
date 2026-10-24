#!/usr/bin/env python3
"""按保留清单重建一条线性历史：树用原提交的，作者/日期/信息原样沿用。

被跳过的提交不进新历史，但最终树逐字节不变（每个保留节点都直接取原树）。
用法：python tools/rebuild_history.py <ref>   # 只打印新 tip，不移动任何分支
"""
import os
import subprocess
import sys

KEEP = [
    "19f9b2e", "9799d34", "25dba9d", "12d0fa2", "c8f3105", "deda33d", "896e3d9",
    "95ad952", "aafb78f", "ea55069", "971a758", "b0f76de", "5e14dbf",
    "885ed55", "3471a2f", "75542dd", "884c4bc", "8b541ee", "45efad5",
    "f565155", "6df3188",
]
FIELDS = "%an|%ae|%aI|%cn|%ce|%cI"


def git(*args, **kw):
    return subprocess.run(["git", *args], capture_output=True, text=True,
                          encoding="utf-8", check=True, **kw).stdout.strip()


def main():
    tip_ref = sys.argv[1] if len(sys.argv) > 1 else "main"
    tip_tree = git("rev-parse", tip_ref + "^{tree}")
    parent = None
    for h in KEEP:
        full = git("rev-parse", h + "^{commit}")
        tree = git("rev-parse", full + "^{tree}")
        a, ae, ad, c, ce, cd = git("log", "-1", "--format=" + FIELDS, full).split("|")
        msg = git("log", "-1", "--format=%B", full)
        env = dict(os.environ, GIT_AUTHOR_NAME=a, GIT_AUTHOR_EMAIL=ae, GIT_AUTHOR_DATE=ad,
                   GIT_COMMITTER_NAME=c, GIT_COMMITTER_EMAIL=ce, GIT_COMMITTER_DATE=cd)
        args = ["commit-tree", tree] + (["-p", parent] if parent else [])
        parent = git(*args, input=msg, env=env)
    assert git("rev-parse", parent + "^{tree}") == tip_tree, "tip tree mismatch"
    print(parent)


if __name__ == "__main__":
    main()
