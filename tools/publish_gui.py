#!/usr/bin/env python3
"""本地发布的小窗口：填 APK、下载地址和七牛参数，点一下生成并上传。

第一次点「生成并上传」后，AK/SK 存到 .cyh/publish.json（已被 gitignore）；
也可以放进环境变量，窗口会自动带出。
依赖：Python 3、tkinter（Windows 官方 Python 自带）、qiniu。
"""
import os
import pathlib
import subprocess
import sys
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import release_config  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parent.parent
SAVED = ROOT / ".cyh" / "publish.json"
DEFAULT_APK = ROOT / "mtk-optimizer" / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"


def _load_saved() -> dict:
    try:
        import json
        return json.loads(SAVED.read_text(encoding="utf-8"))
    except Exception:
        return {}


def _save(ak: str, sk: str, bucket: str, cdn: str = "") -> None:
    import json
    try:
        SAVED.parent.mkdir(parents=True, exist_ok=True)
        SAVED.write_text(json.dumps({"ak": ak, "sk": sk, "bucket": bucket, "cdn": cdn}, ensure_ascii=False, indent=2),
                         encoding="utf-8")
    except Exception as e:
        print(f"保存失败：{e}")


class PublishApp(tk.Tk):
    def __init__(self):
        super().__init__()
        saved = _load_saved()
        self.title("MTK God 发布")
        self.geometry("720x620")
        self.minsize(620, 520)

        # 版本号、下载地址都从仓库当前状态读出，不用手抄。
        self.config = release_config.load_config()
        code, name = release_config.gradle_version()
        self.apk = tk.StringVar(value=str(DEFAULT_APK))
        self.apk_url = tk.StringVar(value=self.config["apkUrl"])
        self.version_text = tk.StringVar(value=f"当前版本：{name}（versionCode {code}）" if code else "")
        self.prefix = tk.StringVar(value=os.environ.get("QINIU_PREFIX", "MTK-God"))
        self.ak = tk.StringVar(value=os.environ.get("QINIU_ACCESS_KEY", saved.get("ak", "")))
        self.sk = tk.StringVar(value=os.environ.get("QINIU_SECRET_KEY", saved.get("sk", "")))
        self.bucket = tk.StringVar(value=os.environ.get("QINIU_BUCKET", saved.get("bucket", "app-changelog")))
        self.cdn_url = tk.StringVar(value=os.environ.get("QINIU_URL") or saved.get("cdn", ""))

        self._build()
        self._check_python_deps()
        if not DEFAULT_APK.is_file():
            self._append(f"注意：还没找到 {DEFAULT_APK}\n先构建 release，或点「选择」指定 APK。\n")

    def _row(self, parent, row, label, var, browse=False, secret=False):
        ttk.Label(parent, text=label).grid(row=row, column=0, sticky="w", padx=8, pady=5)
        entry = ttk.Entry(parent, textvariable=var, show="*" if secret else "")
        entry.grid(row=row, column=1, sticky="ew", padx=4, pady=5)
        if browse:
            ttk.Button(parent, text="选择", command=self._browse).grid(row=row, column=2, padx=4)
        return entry

    def _build(self):
        form = ttk.Frame(self)
        form.pack(fill="x", padx=10, pady=10)
        form.columnconfigure(1, weight=1)

        ttk.Label(form, textvariable=self.version_text).grid(row=0, column=0, columnspan=3, sticky="w", padx=8)
        self._row(form, 1, "Release APK", self.apk, browse=True)
        self._row(form, 2, "公开下载地址", self.apk_url)
        self._row(form, 3, "七牛前缀", self.prefix)
        self._row(form, 4, "AccessKey", self.ak)
        self._row(form, 5, "SecretKey", self.sk, secret=True)
        self._row(form, 6, "Bucket（空间名，如 app-changelog）", self.bucket)
        self._row(form, 7, "CDN 刷新 URL（可选）", self.cdn_url)

        actions = ttk.Frame(self)
        actions.pack(fill="x", padx=10)
        self.button = ttk.Button(actions, text="生成并上传", command=self._start)
        self.button.pack(side="left")
        ttk.Button(actions, text="打开输出目录", command=self._open_output).pack(side="left", padx=8)

        ttk.Label(self, text="日志").pack(anchor="w", padx=12, pady=(10, 0))
        self.log = tk.Text(self, height=10, width=60, wrap="word", state="disabled")
        self.log.pack(fill="both", expand=True, padx=10, pady=6)

    def _browse(self):
        # 默认目录不存在时（还没构建过 release）退回仓库根目录，否则 Tk 会报找不到路径。
        start = DEFAULT_APK.parent
        while not start.is_dir() and start != ROOT.parent:
            start = start.parent
        path = filedialog.askopenfilename(
            title="选择 release APK",
            initialdir=str(start),
            filetypes=[("APK", "*.apk"), ("所有文件", "*.*")],
        )
        if path:
            self.apk.set(path)

    def _open_output(self):
        path = ROOT / "release" / "public"
        path.mkdir(parents=True, exist_ok=True)
        if sys.platform.startswith("win"):
            os.startfile(path)
        else:
            subprocess.Popen(["xdg-open", str(path)])

    def _append(self, text):
        self.log.configure(state="normal")
        self.log.insert("end", text)
        self.log.see("end")
        self.log.configure(state="disabled")

    def _check_python_deps(self):
        def check():
            try:
                import qiniu  # noqa: F401
                self._append("依赖检查通过。\n")
            except Exception as e:
                self._append(f"缺少 qiniu：{e}\n请先运行：pip install qiniu\n")
        threading.Thread(target=check, daemon=True).start()

    def _start(self):
        if not self.ak.get().strip() or not self.sk.get().strip() or not self.bucket.get().strip():
            messagebox.showerror("缺少参数", "AccessKey / SecretKey / Bucket 都要填。")
            return
        if not self.apk.get().strip():
            messagebox.showerror("缺少 APK", "先选择 release APK。")
            return
        if not self.apk_url.get().strip().startswith("https://"):
            messagebox.showerror("地址不对", "公开下载地址必须是 https://")
            return
        self.button.configure(state="disabled")
        self._append("\n=== 开始 ===\n")
        threading.Thread(target=self._run, daemon=True).start()

    def _run(self):
        _save(self.ak.get().strip(), self.sk.get().strip(), self.bucket.get().strip(), self.cdn_url.get().strip())
        env = os.environ.copy()
        env.update({
            "QINIU_ACCESS_KEY": self.ak.get().strip(),
            "QINIU_SECRET_KEY": self.sk.get().strip(),
            "QINIU_BUCKET": self.bucket.get().strip(),
            "QINIU_PREFIX": self.prefix.get().strip(),
        })
        if self.cdn_url.get().strip():
            env["QINIU_URL"] = self.cdn_url.get().strip()
        else:
            env.pop("QINIU_URL", None)
        # 七牛 SDK 的 put_file 还带 DeprecationWarning；日志区只需要结果。
        env["PYTHONWARNINGS"] = "ignore::DeprecationWarning"

        cmd = [
            sys.executable, "tools/publish_local.py",
            "--apk", self.apk.get().strip(),
            "--prefix", self.prefix.get().strip(),
        ]
        try:
            # 子进程输出按 UTF-8 读：按控制台默认编码（GBK）读会把中文变成乱码。
            env["PYTHONIOENCODING"] = "utf-8"
            proc = subprocess.run(cmd, cwd=ROOT, env=env, text=True, encoding="utf-8",
                                  errors="replace", capture_output=True)
            self._append(proc.stdout)
            if proc.stderr:
                self._append(proc.stderr)
            if proc.returncode == 0:
                self._append("=== 上传完成 ===\n")
                self.after(0, lambda: messagebox.showinfo("完成", "JSON 和 APK 已上传。"))
            else:
                self._append("=== 上传失败 ===\n")
                self.after(0, lambda: messagebox.showerror("失败", "看下面日志。"))
        except Exception as e:
            self._append(f"执行失败：{e}\n")
        finally:
            self.after(0, lambda: self.button.configure(state="normal"))


if __name__ == "__main__":
    PublishApp().mainloop()
