# 剪锅准星（游戏蓝盒）FPS 浮层 — 第三方实现记录

<!-- external-symbols -->
本页记录外部模块实现，其中的类名属于该模块，不在本项目源码里。

> - 分析对象：`reference/26.10参考/游戏蓝盒1.1.4.apk`（第三方文件，git-ignored）
> - sha256：`038993f1399a16acf8eb0380e8dada29914086cc5b402788d51589f2d3358dea`（4 034 162 字节）
> - 分析日期：2026-09-21
> - 材料来源：`AndroidManifest.xml`（二进制 AXML 字符串池）、`classes.dex` 字符串池、4 个 arm64 ELF 的可打印字符串与符号表

---

## 0. 证据等级

| 标记 | 含义 |
|---|---|
| **[清单]** | 二进制 `AndroidManifest.xml` 字符串池解析得到 |
| **[字节码]** | 从 `classes.dex` 字符串池得到；**没有 smali 层面的方法体**，字段名、类名、常量字符串保留，控制流是推断 |
| **[ELF]** | 从 arm64 ELF 的可打印字符串 / 符号得到；三个 daemon 未 strip 且带 `debug_info`，源码路径与 C++ 符号完整保留 |
| **[未确定]** | 查不出来，不要当成事实 |

本次分析只到字符串与符号层，未解析方法体；尤其不能确认 FPS 与 jank 的计算细节。

模块本体是单 `classes.dex`（2.47 MB），Kotlin + Compose + Hilt + Material3 + Navigation，`kotlin-tooling-metadata.json` 显示 Gradle 8.11.1 / Kotlin 2.0.21，未混淆。

---

## 1. 这是什么，以及它和本项目的关系

**它不是系统优化器，是游戏浮层辅助工具箱。**

| | |
|---|---|
| 包名 | `com.youxilanhe.cjd` **[清单]** |
| 版本 | `versionName` 1.1.4，`versionCode` 15 **[清单]** |
| Application | `com.youxilanhe.cjd.JianGuoZhunXingApplication`（剪锅准星） **[清单]** |
| 入口 | `com.youxilanhe.cjd.MainActivity` **[清单]** |
| 原生 | 仅 `arm64-v8a`，NDK r27 / clang 18，开发机 macOS（`/Users/chenjindong/Documents/jianguozhunxing`） **[ELF]** |
| 提权 | **仅 Shizuku，无 root** |

可复用线索是通过 Shizuku UserService 获取 SurfaceFlinger 的帧时间戳；这是观测接口，不是控制接口。
目标 ROM 的输出与权限仍需现场确认。

---

## 2. 功能清单

清单里 6 个 service（5 个浮层 + 1 个 Shizuku UserService）+ 5 个 QS Tile + 1 个 Application + 1 个 Activity **[清单]**：

| 组件 | 作用 | 需要 Shizuku |
|---|---|---|
| `fps.FpsDisplayService` + `tiles.FpsDisplayTileService` | 帧率浮窗，可附带温度（`show_temp`） **[字节码]** | **是** |
| `crosshair.CrosshairOverlayService` + `tiles.CrosshairAssistantTileService` | 游戏准星 | 否 |
| `circleguide.CircleGuideOverlayService` + `tiles.CircleGuideTileService` | 圆圈准线 | 否 |
| `lineguide.LineGuideOverlayService` + `tiles.LineGuideTileService` | 直线准线 | 否 |
| `skilltimer.FloatingTimerService` + `tiles.SkillTimerTileService` | 技能计时浮窗，带预设（`preset_skill` / `default_presets_version`） **[字节码]** | 否 |
| `shizuku.ShizukuCommandRunnerService` | uid 2000 侧的 UserService，见 §3.1 | — |
| `ui.update.*` + `data.source.remote.UpdateApiService` | 自更新：拉 `version.json` | 否 |

声明的权限里 `DUMP`、`INTERACT_ACROSS_USERS_FULL` 是 signature|privileged，普通 App 装上也拿不到——它们是**装饰性声明**，真正的 dumpsys 能力来自 Shizuku 进程的 uid 2000。见 §7。

浮窗文案：`'帧率 999.9  |  温度 100°C'` / `'FPS 999.9  |  100°C'` **[字节码]**。

---

## 3. 实现方法

### 3.1 运行形态：Shizuku UserService **[字节码]**

```
com.youxilanhe.cjd.shizuku.ICommandRunner                 # AIDL 接口
com.youxilanhe.cjd.shizuku.ShizukuCommandRunnerService    # UserService 实现
com.youxilanhe.cjd.shizuku.ShizukuCommandRunnerService$NativeFpsDaemonBackend
```

事务常量 `TRANSACTION_executeCommand`，方法 `executeCommand` / `executeShell`。后端标识 `backend=shizuku_runner`。

**这和本项目的 [RuntimeUserService.kt](../../mtk-optimizer/app/src/main/java/magicau/mtkcontroller/data/privilege/RuntimeUserService.kt) 是同一个模式**：跑在 Shizuku 拉起的进程里，adb 授权时 uid 2000（shell），所有 shell 操作在这里发生以继承该身份。

### 3.2 二进制落盘与启动 **[字节码] + [ELF]**

安装目录 `/data/local/tmp/com.youxilanhe.cjd-fps`，安装命令（asset 字节走 `getOutputStream()` 喂给 `cat`）：

```sh
mkdir -p /data/local/tmp/com.youxilanhe.cjd-fps && cat > <file>.tmp && chmod 700 <file>.tmp && mv <file>.tmp <file>
```

启动 / 停止：

```sh
/data/local/tmp/com.youxilanhe.cjd-fps/cjd-fps-daemon-v10 >/dev/null 2>&1 &
pkill -f cjd-fps-daemon-v10 || killall cjd-fps-daemon-v10 || true
```

资源名到文件名的映射 **[字节码]**：

| asset | 落盘文件名 | 体积 | 实际被引用 |
|---|---|---|---|
| `fps_daemon` | `cjd-fps-daemon-v10`（`version=fps-daemon-0.10`） | 1 788 704 | 是 |
| `fps_binder_helper` | `cjd-fps-binder-helper-v6` | 1 912 256 | 是 |
| `fps_scene_daemon` | `fps-scene-daemon-0.4`，端口 18767 | 1 777 192 | **否，死重** |
| `lib/arm64-v8a/libfps_frame_sampler.so` | JNI，见 §3.5 | 898 896 | 是 |

### 3.3 `fps_daemon`：本地 TCP 服务 + 行协议 **[ELF]**

`fps_daemon.cpp`，绑定 `bind 127.0.0.1:%d`，端口常量 `kPort` → **18773**（`port=18773`）。监听本地回环，App 侧用 TCP 连上去。

命令：`get-accurate-fps`、`daemon-info`、`daemon-version`、`exit`；未知命令回 `fps_daemon_unknown_command`。

响应是 `key=value` 文本行 **[ELF]**：

```
fps=  frames=  frameTimeMs=  avgFrameMs=  jank=  bigJank=
lastFrame=  lastLayer=  lastSnapshotMs=  historySize=  sampleAgeMs=
helperDurationMs=  helperReady=  latencyKey=  daemonStarted=
```

内部结构（C++ 符号）**[ELF]**：

- `BinderFrameSource`：`collectLoop` / `readSnapshot` / `recordSnapshot` / `sampleFromHistory` / `runHelper` / `shellQuote` / `setTarget`
- `FrameSequenceCollector`：`std::deque<FrameSnapshot>` 滑动窗口
- `FrameSample` / `FrameSnapshot`
- 诊断串：`collector frame source unavailable: history=%zu layer=%s firstFrame=%lld lastFrame=%lld windowMs=%lld target=%s`、`collector warming up: ...`、`frame_source_unavailable`、`collector_history_short`

### 3.4 `fps_binder_helper`：真正的取数者 **[ELF]**

`fps_binder_helper.cpp`，由 daemon 通过 `runHelper` / `shellQuote` 拉起（`backend=binder_helper`）。执行：

```sh
/system/bin/dumpsys SurfaceFlinger --list 2>/dev/null
/system/bin/dumpsys SurfaceFlinger --latency <layer>
/system/bin/dumpsys SurfaceFlinger 2>/dev/null
/system/bin/dumpsys window 2>/dev/null
```

解析产物是 `LayerFrame{ frameNumber, ... }`，行终止哨兵 `__FRAME_END__`。

**这就是经典的 `--latency` 帧时间戳法**：第一行是刷新周期（ns），随后每行三个 ns 时间戳（期望呈现 / 实际呈现 / 帧就绪），`0` 行结束；相邻实际呈现时间戳之差即帧间隔，倒数得瞬时帧率，超过阈值的算 jank。`--list` 用来枚举 layer 名，`dumpsys window` 用来定位前台游戏窗口对应的 layer。

### 3.5 JNI 封装 **[ELF]**

`libfps_frame_sampler.so`（stripped）导出三个 JNI 方法，宿主类是 `ShizukuCommandRunnerService$NativeFpsDaemonBackend`：

```
Java_com_youxilanhe_cjd_shizuku_ShizukuCommandRunnerService_00024NativeFpsDaemonBackend_nativeStartDaemon
Java_..._nativeExecuteDaemonCommand
Java_..._nativeStopDaemon
```

即 daemon 的起停与命令收发由原生代码在 uid 2000 进程内完成。错误串：`FPS daemon is not reachable on 127.0.0.1:%d`、`fps_daemon_empty` / `fps_daemon_unavailable` / `fps_daemon_write_failed` / `fps_daemon_stopped`。

### 3.6 温度来源 **[字节码]**

四个原生二进制里**没有任何 thermal / `/sys/class/thermal` 字符串**。温度走标准 Android API：`registerReceiver` 粘性广播 + `getIntExtra`，以及 `BatteryManager.getProperty`。也就是 `EXTRA_TEMPERATURE`（十分之一摄氏度）。**本项目已经在用同一个源**，见 [CpuViewModel.kt](../../mtk-optimizer/app/src/main/java/magicau/mtkcontroller/feature/cpu/CpuViewModel.kt) 的 `temperatureTenthsC`。

---

## 4. 观测边界

- 来源材料显示 `dumpsys SurfaceFlinger --list`、`--latency <layer>` 和 `dumpsys window`；
  具体 layer 选择与统计方法未取得方法体级证据。
- 帧呈现不是游戏逻辑帧、GPU 利用率或 CPU 限制本身。层名、缓冲长度、输出格式和权限按目标 ROM 确认。
- 轮询会增加 CPU 与 Binder 负载，控制对照需记录是否同时运行采样。
- 若接入本项目，沿 [UserService](../../mtk-optimizer/app/src/main/java/magicau/mtkcontroller/data/privilege/RuntimeUserService.kt)
  的执行身份采集；当前实现和生命周期由 [工程架构](../dev/architecture.md) 与源码维护。

---

## 7. 已知缺陷与版本错位 **[字节码] [ELF]**

1. **`fps_scene_daemon` 是死重**：asset 1 777 192 字节，dex 里只出现 `FPS_DAEMON_*` / `FPS_BINDER_HELPER_*` 常量，没有任何 scene daemon 的文件名或端口引用。白占 1.78 MB。
2. **装饰性权限**：`DUMP` 与 `INTERACT_ACROSS_USERS_FULL` 都是 signature|privileged，普通安装拿不到。真实能力来自 Shizuku 的 uid 2000。照抄这两个声明只会造成"我有权限"的错觉。
3. **停止用模式匹配**：`pkill -f cjd-fps-daemon-v10 || killall cjd-fps-daemon-v10 || true`，`-f` 匹配整条命令行，名字撞上就会误杀。
4. **端口硬编码**：`kPort` 18773 / 18767，无协商、无回退。
5. **三套后端并存**：`backend=binder_helper`（现行）、`backend=surfaceflinger_latency_probe`（scene daemon，未引用）、`backend=shizuku_runner`。历史包袱明显。

---

## 8. 未确定的事实

- `fps=` / `jank=` / `bigJank=` 的**具体计算公式与阈值**：符号与输出字段可见，算术细节在方法体里，没有 smali 无法确认。
- **目标 layer 的选择策略**：`dumpsys SurfaceFlinger --list` + `dumpsys window` 都在跑，但具体怎么挑（焦点窗口？最大面积？排除系统层？）未知。
- `fps_daemon` 的轮询间隔与 `windowMs` 默认值。
- 目标机的 `dumpsys SurfaceFlinger --latency` 可用性、参数与输出完整性：原分析未核验。

---

## 9. 原始材料

原 APK、哈希和分析层次见文件开头；重新分析时解包对应 APK，
读取 AXML、DEX 与 `assets/fps_daemon` 等 ELF。
字符串搜索只能定位候选路径；控制流、调用关系和公式需方法体或运行时证据。

