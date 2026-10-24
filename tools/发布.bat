@echo off
rem 双击启动发布窗口（无黑框）。
rem 不要写 `2> log`：pythonw 是 GUI 程序，cmd 不会打开重定向的文件，会报「系统找不到指定的路径」。
rem 保持 pythonw（没有可见控制台）。启动阶段的错误写在窗口的日志区里。
rem 脚本如果有语法错误，窗口会一闪而过：用 `python tools\publish_gui.py` 在终端里跑可看到堆栈。
chcp 65001 >nul
setlocal
set "PYW="
for /f "delims=" %%P in ('where pythonw 2^>nul') do if not defined PYW set "PYW=%%P"
if not defined PYW (
  echo 未找到 Python。请安装 Python 3，或直接运行 tools\publish_local.py
  pause
  exit /b 1
)
"%PYW%" "%~dp0publish_gui.py"
