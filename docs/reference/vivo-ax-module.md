# vivo AxManager 无 Root 优化模块 — 实现记录

> - 分析对象：`../MTK-reference/sources/2026-09-ax-module/`（第三方文件，git-ignored）
> - 分析日期：2026-09-18
> - 材料来源：模块自带 README、全部 shell 源码、WebUI 源码、5 个 jar 的 DEX 反汇编

---

> 本文分析固定版本的第三方源码，不描述本项目当前实现。目标 ROM 的服务、权限和读回范围见 [设备事实](../../docs/device/facts.md)，接入约束见 [工程架构](../../docs/dev/architecture.md)。

## 0. 证据等级

这份文档里的每一句都标了来源。**不要混用**这三类：

| 标记 | 含义 |
|---|---|
| **[源码]** | 直接读 shell / JS 源码得到，可复核 |
| **[字节码]** | 从 `.dex` 反汇编推断，看不到 `.class`，字段名和方法名保留 |
| **[未确定]** | 查不出来。不要当成事实，也不要靠猜补上 |

模块全部代码是 `d8` 编译的 dex，**没有 `.class`**，`min-api` 28/29/31，未混淆。

---

## 1. 这是什么，以及它和本项目的关系

**AxManager** 是一个类似 Magisk 的模块框架，区别是它**基于 Shizuku、无 Root**。这个文件夹是它的一个插件，运行身份是 Android `shell`（uid 2000）。

来源模块面向 vivo 设备；本项目目标机也使用 vivo ROM，但不保证接口与效果相同。

我们的目标机是 **vivo V2430A / MT6989（Dimensity 9300+）** —— 见 [device-vivo-v2430a.md](../../docs/device/facts.md)。它**既是 vivo 的 ROM，也是 MediaTek 的 SoC**。这些 vivo 服务是值得检查的候选；是否存在、接口是否相容，必须以目标手机当前 ROM 的探测为准，不能从品牌和 SoC 推断。

它不是"另一套技术"，是**同一台设备上另一半控制面**：

| | 本项目（MTK God） | vivo 模块 |
|---|---|---|
| 打的层 | MTK PowerHAL（`PERF_RES_*` id）+ 内核 `sysfs` / `proc` | vivo ROM 服务（binder）+ vivo Settings |
| 形态 | Kotlin + Compose 原生 App | shell + 5 个 jar + WebUI |
| 用户 | 自己装 APK、授权 Shizuku | 装在 AxManager 里，浏览器界面 |
| 观测路径 | 部分内核节点与诊断读回，范围受权限影响 | 主要使用 binder 与 Settings |
| 权限 | 同：Shizuku → uid 2000 | 同：uid 2000 |

它提供厂商服务的调用线索。Binder 返回值、Settings 读回和硬件效果是不同层次的证据；当前手机的权限变化统一记录在设备事实页。

它明确**不做**的事（README 原文）：无 Root 不可写的 QCOM/MTK `sysfs`、`zram`、`drop_caches`。也就是说，**它绕开的正是本项目主攻的那部分** —— 两者不重叠。

---

## 2. 功能清单

### 2.1 设备识别 **[源码]**

厂商 / 品牌 / 市场型号 / PD 号 / 固件版本 / OS 名 / Android 版本 / SoC 厂商 / SoC 型号 / 平台，全部走 `getprop` 多候选回退：

```sh
first_prop() { for p in "$@"; do v="$(getprop "$p")"; ... done }   # 跳过空/unknown/null
soc_vendor="$(first_prop ro.soc.manufacturer ro.vivo.product.solution ro.hardware)"
soc_model="$(first_prop ro.soc.model ro.vivo.product.platform ro.board.platform)"
```

SoC 归类已包含 MediaTek：`mediatek|mtk|dimensity|mt[0-9]` → `mediatek`。

### 2.2 云控限制

- **ABE 云控**：维持 `secure abe_unified_switch=0`；调度模式三选（`off` 限制云控 / `balanced` 官方均衡 / `performance` 仅游戏期间启用官方调度）
- **私人 DNS（DoT）**：可恢复管理。启用前先存 `private_dns_mode` + `private_dns_specifier`，按"主机名 → hostname 模式"顺序写入并回读，再由系统 `LinkProperties` 验证。**失败回滚**，暂停只关模式保留主机，恢复写回安装前快照
- **域名清单三档导出** `compat`(27) / `privacy`(94) / `strict`(115)，生成 AdGuard Home 规则到 `/sdcard/Download/`

README 明确不夸大：**DNS 过滤 ≠ 云控被 100% 移除**（应用可能用 HttpDNS、硬编码 IP、代理）。"无法解析"不等于"已被过滤"。

### 2.3 显示 / 高刷

- RMS FixRate / FramePacing 请求**本机最高物理刷新率**，不硬编码
- 删除 PEM 降亮度输入 + 启用 LCM 策略禁用键 + **持续发布实时零降幅回调**
- 游戏 MEMC 目标选择（**只读观察**官方命令，不自己造，见 §4.6）
- 1.25x 动画

### 2.4 触控

通过 `bbk_touch_screen_service` **事务 16** 读固件 `vts-high-rate-support`，再用**事务 17** 持续请求该机最高合法档。校验方式用 `getevent` **6 秒实测**。

### 2.5 性能会话 / 游戏

- **只在白名单游戏位于前台时**才建会话（`dumpsys activity activities` 查 `topResumedActivity`），后台残留进程不持锁
- vivo perfLock：**30 秒有界租约 + 每 20 秒续期** + 按 handle 释放 + 异常最多 30 秒自动到期。这里是 `VivoPerfManager` 路径，不等于本项目 MTK `power_hal_mgr_service` 已证明支持相同租约语义。
- GameWatch `vsync_imd` **只读**查询 → 自动扩充游戏识别
- GameCube 高报点 / 游戏超控 / VTS 参数官方合法最高档
- 直驱供电开关、游戏快捷启动

### 2.6 预加载 / 编译

- vivo 官方 `ai_turbo_enabled` RMS/SPS 预加载链
- dex profile 编译 + APK 页缓存预热（每分片上限 192 MiB，**只在充电时**触发一次）
- 系统后台编译 / 全量 DEX `speed-profile` 编译 / 单包编译

### 2.7 平台策略 / 增强库

- 骁龙设备启用 IORap 框架策略（`is_qti_device` 门控，MTK 上自动跳过）
- **增强库**：按应用设 `Balanced / Plus / Turbo`，每应用可配编译启动、触控、网络、视效、高温请求、Game Driver

### 2.8 恢复

安装前 Settings/DeviceConfig 快照，卸载时全量还原，常驻 `guard.sh` 守护。

---

## 3. 实现方法

### 3.1 运行形态与目录 **[源码]**

```
AxManager 根/
├── plugins/vivo_ax_noroot_optimizer/   ← 模块本体（只读）
└── state/vivo_ax_noroot_optimizer/     ← 全部可变状态
```

状态与代码分离。`common.sh:8-23` 靠 `readlink -f "$0"` 自定位，匹配 `*/plugins/*` 决定 `STATE_DIR`。

**两个调用者**：`guard.sh`（守护进程）和 `system/bin/vivo-optctl`（CLI，WebUI 的桥）。

一个值得注意的细节 —— `vivo-optctl:441-449`：

```sh
. "$MODDIR/lib/common.sh"
# AxManager WebUI may start with /data/local as cwd, which is not writable by
# shell on vivo firmware. BusyBox ash materializes here-documents in the cwd.
cd "$STATE_DIR" 2>/dev/null || exit 1
TMPDIR="$STATE_DIR"; export TMPDIR
```

**ash 的 here-document 会在当前目录落地临时文件**，而默认 cwd 不可写 → 显式 `cd` 到状态目录。在 vivo 固件上跑 shell 会遇到同样的坑。

### 3.2 四个反射招式 **[字节码]**

下面是该版本使用的反射和接口适配方法。

#### 招式一：反射关掉厂商的权限门

`vivo_perf_session.jar`：

```java
Class<?> vp = Class.forName("com.vivo.framework.vperf.VPerformance");
Field f = vp.getDeclaredField("sIsPlatformOrPrivApp");
f.setAccessible(true);  f.setBoolean(null, false);   // 清掉"我是平台应用"
Field g = vp.getDeclaredField("sIsUntrustedDomain");
g.setAccessible(true);  g.setBoolean(null, false);   // 清掉"不可信域"
```

清完之后，uid 2000 的进程就能正常调 `VivoPerfManager.perfLockAcquire()`。

**这里只能证明客户端字段被修改，不能由此推断服务端权限与 SELinux 不再检查。** 代价是脆弱 —— ROM 改字段名就失效。它自己显然知道，所以释放路径做了三级降级（`perfLockReleaseHandler` → `perfLockRelease` → 报 `release_api_missing`）。

#### 招式二：把厂商自己的 SDK 从 APK 里加载出来

`gamewatch_query.jar` 从 `pm path com.vivo.gamewatch` 拿 APK 路径后：

```java
PathClassLoader cl = new PathClassLoader(apkPath, ClassLoader.getSystemClassLoader());
Class<?> mgr = cl.loadClass("com.vivo.gamewatch.common.GameWatchManager");
Object svc = mgr.getMethod("getInstance").invoke(null);
Object r = mgr.getMethod("execute", String.class, Object[].class).invoke(svc, "vsync_imd", args);
```

**Binder 通信全部发生在 vivo 自己的类里**，jar 只负责把 SDK 载进来 —— 接口名和事务码都不需要知道。

#### 招式三：通用 binder 工具

`vivo_binder_tool.jar`，接口：

```
VivoBinderTool SERVICE DESCRIPTOR_OR_- CODE REPLY_TYPE [i32 N|i64 N|f N|d N|s16 TEXT|null]...
```

- `DESCRIPTOR` 填 `-` 或 `raw` 就跳过 `writeInterfaceToken`
- `REPLY_TYPE`：`oneway` / `rawvoid` / `void` / `i32` / `bool` / `s16`
- 事务码**从命令行传入**，jar 里不硬编码
- 实现：`ServiceManager.checkService(name)` → 手工 `Parcel` → `IBinder.transact(code, ...)`
- `transact()` 返回 `false` → 抛 `Transaction was not handled: <code>`

**这是一个通用 `service call` 替代品** —— `service call` 只能表达简单类型，这个能构造任意 Parcel。

> **不要用它来试探候选事务码。** `architecture.md` 里那条约束仍然成立：错误的事务码可能是写操作。"`transact()` 返回 false 说明不存在"只在**事务码本身已被证实是只读**时才安全。

#### 招式四：`app_process` 里拿 Context

三个 jar 都用这个起手式：

```java
ActivityThread at = Class.forName("android.app.ActivityThread");
Object sysCtx = at.getMethod("getSystemContext").invoke(at.getMethod("systemMain").invoke(null));
```

### 3.3 命令原语清单 **[源码]**

| 原语 | 用途 | 备注 |
|---|---|---|
| `settings put/get/delete/list` | 写配置 | **必然回读**，见下 |
| `content insert/delete --uri content://settings/<ns>` | `settings` 失败时的兜底 | 权限模型不同，可能成 |
| `device_config put/get/delete` | `activity_manager_native_boot` / `runtime_native_boot` | |
| `/system/bin/service call <svc> <code> <args>` | 简单 binder 调用 | 见 §3.4 |
| `app_process` + `CLASSPATH=<jar>` | 复杂 binder 调用 | 见 §3.2 |
| `cmd display get/set/clear-user-preferred-display-mode` | 显示模式 | |
| `cmd power set-mode 0` | 退出省电 | |
| `cmd thermalservice reset` | 重置框架温控状态 | |
| `cmd activity idle-maintenance` | 空闲维护 | 回退 `am idle-maintenance` |
| `cmd game mode performance <pkg>` / `cmd game reset <pkg>` | Android Game Mode | |
| `cmd package compile -m speed-profile [-a\|-f] <pkg>` | DEX 编译 | 超时 900s / 300s |
| `cmd package bg-dexopt-job` | 系统后台编译 | 900s |
| `am broadcast -a android.engineMode.closehighprotect.action --ez highprotect <b>` | PEM 高温策略 | |
| `am start -W -n <component>` | 启动并等待 | 之后轮询前台确认 |
| `dumpsys display` / `activity activities` / `window` / `battery` | 只读观测 | |
| `getevent -lt <dev>` + awk | 触控实测 | 见 §3.7 |
| `dd if=<apk> of=/dev/null bs=1M count=<n>` | 页缓存预热 | 上限 192 MiB |

**写入后必然回读，且带兜底** —— `common.sh:562`：

```sh
put_setting() {
  ns="$1"; key="$2"; value="$3"
  /system/bin/settings put "$ns" "$key" "$value" >/dev/null 2>&1 || true
  cur="$(get_setting "$ns" "$key")"
  if [ "$cur" != "$value" ]; then
    /system/bin/content insert --uri "content://settings/$ns" \
      --bind name:s:"$key" --bind value:s:"$value" >/dev/null 2>&1 || true
  fi
  [ "$(get_setting "$ns" "$key")" = "$value" ]
}
```

原因：**shell 身份可能没有 `WRITE_SETTINGS`，`settings put` 会静默失败**；`content insert` 走 ContentProvider，权限模型不同。

### 3.4 确切的服务名与事务码 **[源码]**

#### 触控 `bbk_touch_screen_service`

接口名 `vivo.app.touchscreen.ITouchScreen`。

```sh
# 读取固件报告的最高档位（事务 16，返回 i32）
run_binder_tool bbk_touch_screen_service vivo.app.touchscreen.ITouchScreen 16 i32

# 请求档位（事务 17，void）
run_binder_tool bbk_touch_screen_service vivo.app.touchscreen.ITouchScreen 17 void i32 "$TOUCH_LEVEL"

# 恢复用 17 + 0
run_binder_tool bbk_touch_screen_service vivo.app.touchscreen.ITouchScreen 17 void i32 0
```

**没有 jar 时的兜底**（`common.sh:2056-2065`）—— 手工解析 Parcel 的第二个 32 位字，并做范围校验：

```sh
raw="$(/system/bin/service call bbk_touch_screen_service 16 2>/dev/null | tr '\n' ' ')"
hex="$(printf '%s\n' "$raw" | sed -n 's/.*Parcel([[:space:]]*[0-9A-Fa-f]*[[:space:]]*\([0-9A-Fa-f][0-9A-Fa-f]*\).*/\1/p')"
value="$((0x$hex))"
[ "$value" -ge 0 ] && [ "$value" -le 8 ] || return 1     # ← 越界即失败
```

档位 → 标称 Hz 的映射是硬编码的：`0/1/2` → `120/240/300 Hz`。文档明说**档位号不是采样率本身**，所以 UI 同时显示固件档位、请求档位和 `getevent` 实测值。

#### 亮度 `vivo_lcm_brightness_service`

```sh
/system/bin/service call vivo_lcm_brightness_service 8 f 0.0
/system/bin/service call vivo_lcm_brightness_service 10 i32 100 i32 0
```

守护循环每秒重发事务 10、每 10 秒重发事务 8 —— 持续压制发热降亮度。

#### RMS（FramePacing / FixRate）

```sh
# 释放（事务 24）
run_binder_tool rms - 24 rawvoid i32 0 i64 "$handle"

# 固定刷新率获取（事务 36，11 个 int）
run_binder_tool rms - 36 rawvoid \
  i64 886612001 s16 FixRate s16 "AX_VIVO_Fixed$TARGET_HZ" \
  i32 0 i32 0 i32 "$TARGET_HZ" i32 0 i32 0 i32 0 \
  i32 100 i32 0 i32 0 i32 0 i32 15

# 按游戏获取（事务 36，14 个 int，比上面多 pid / 960 / 30840 / 65537）
run_binder_tool rms - 36 rawvoid \
  i64 886612002 s16 FixRate s16 "AX_VIVO_Game$TARGET_HZ:$pkg" \
  i32 -1 i32 0 i32 "$TARGET_HZ" i32 0 i32 0 i32 0 \
  i32 100 i32 0 i32 0 i32 "$pid" i32 960 \
  i32 30840 i32 65537 i32 0
```

**两个 handle 是固定常量**：`RMS_FIXED_HANDLE=886612001`、`RMS_GAME_HANDLE=886612002`（`common.sh:63-64`）。这些固定值是该模块的所有权约定；释放能否成功还取决于服务与调用者状态。

成功判定是**退出码 + 字面量 `OK` 双条件**：

```sh
[ "$rc" = "0" ] && [ "$out" = "OK" ]
```

> 事务 36 的两组参数**参数个数不同**（11 vs 14）。尾部那几个数（`960` / `30840` / `65537`）语义未确定 **[未确定]**。

#### 其他探测到的服务名

`vendor.vivoperfservice`、`sps_rms`、`gamewatch_server`、`rms`。

存活探测统一用：

```sh
service_exists() { /system/bin/service check "$1" 2>/dev/null | grep -q 'found'; }
```

### 3.5 perfLock 与会话租约 **[字节码]**

`vivo_perf_session.jar`，由 `lib/perf_session.sh` 长驻启动：

```sh
CLASSPATH="$JAR"; export CLASSPATH
exec /system/bin/app_process /system/bin VivoPerfSession hold "$STATE"
```

| 常量 | 值 |
|---|---|
| `LEASE_MS` | **30000** |
| `RENEW_INTERVAL_MS` | **20000** |
| `POLL_INTERVAL_MS` | 500 |

调用链（全反射）：

```java
Class.forName("com.vivo.framework.vperf.VivoPerfManager")
  .getConstructor(Context.class).newInstance(systemContext);
// 清权限门（§3.2 招式一）
getMethod("perfLockAcquire", Integer.TYPE, int[].class).invoke(mgr, 30000, GAME_HINTS);
```

**停止机制是协作式的**，不是信号：

1. shell `touch "$STATE.stop"`
2. jar 每 500ms 轮询 `File.exists()`，看到就 `release("stop_file")`
3. shell 轮询最多 5 秒等它自己退出
4. 超时才 `kill`

另外注册了 JVM shutdown hook（线程名 `vivo-perf-release`），所以异常退出也能释放。

状态文件（`key=value`）被 shell 读回来判活：

```
status=LOCKED|STARTING|RELEASED|FAILED
pid= / handle= / hint_pairs=22 / lease_ms=30000 / renew_interval_ms=20000
acquired_at_ms= / last_renew_ms= / lease_expires_at_ms= / renew_count=
release_api=perfLockReleaseHandler|perfLockRelease|none
```

**`GAME_HINTS` 是一个静态 `int[22]`**（`fill-array-data`，11 组 `(hint, value)`）：

```
{0x40C00000,2, 0x40800000,0xFFF, 0x40800000,0xFFF, 0x40800000,0xFFF,
 0x40800000,0xFFF, 0x40800000,0xFFF, 0x40800000,0xFFF, 0x40800000,0xFFF,
 0x40800000,0xFFF, 0x40400000,1, 0xF0100000,9}
```

**这 22 个数的语义无法从字节码推出** **[未确定]**。它们是 vivo/MTK 的性能提示常量，含义在 ROM 侧。

> perfLock 的**接口描述符和事务码不在这 5 个 jar 里** —— jar 只调 `VivoPerfManager` 的 Java 方法，Binder 通信在 ROM 的框架实现内 **[未确定]**。

### 3.6 守护进程模型 **[源码]**

`service.sh` → `exec guard.sh`，一个 tick 计数器驱动的循环（`GUARD_FAST_INTERVAL=1`，所以 tick ≈ 秒）：

| 周期 | 做什么 |
|---|---|
| 每 tick | 重发 `vivo_lcm_brightness_service` 事务 10 |
| `% 5` | 写心跳文件（存活窗口 45 秒） |
| `% 10` | `apply_abe_scheduler` / `apply_direct_charge_mode` / `request_touch_high` / LCM 事务 8 / `update_game_runtime` |
| `% 30` | `apply_core_settings` 全量重申；然后**只在** `get_active_hz` 偏离目标时才重做显示策略和 RMS |
| `% 60` | 刷新状态缓存 |
| `>= 180` 且充电中 | 一次性预加载（用 `preload_scheduled.<boot_id>` 防重） |
| `>= 3600` | 打一条脉冲日志并归零 |

`trap cleanup_guard EXIT` 会在退出时释放两个 RMS handle、停 perf 会话、清游戏温控。

**`update_game_runtime`** 是每 tick 的游戏状态机，把 `(pid, pkg, profile, config)` 写进 `$STATE_DIR/current_game` 做快照比较。**元组没变就走快路径，只重申不重设** —— 这是它避免反复读写的关键。

### 3.7 状态、缓存与快照 **[源码]**

#### 锁：mkdir + 自己的 pid 戳 + 超龄夺取

```sh
state_mutex_acquire() {
  while [ "$try" -lt 100 ]; do
    if runtime_create_lock_dir "$dir"; then printf '%s\n' "$$" > "$dir/pid"; return 0; fi
    age="$(runtime_lock_age_seconds "$dir")"
    [ "$age" -ge 5 ] && rm -rf "$dir"          # 超龄夺取
    try=$((try+1)); sleep 0.02
  done
  return 1
}
```

年龄优先读锁目录里的 `created_at`，回退 `stat -c %Y`，还有个 `.recovery_seen_at` 标记防止**时钟跳变让新锁看起来超龄**。

#### 原子替换

所有状态文件都是 `写入 .tmp.$$` → `mv`。配置批量更新（`runtime_config_set_batch`）在同一把锁里逐键 `grep -v` 重建再整体 `mv`。

#### 安装前快照 `restore.tsv`

TSV，四列 `kind / ns / key / value`，`kind ∈ {setting, device_config}`。

- **首写者胜**：`[ -s "$RESTORE_FILE" ] && return 0`，锁内二次确认
- 不存在的键编码成字面量 `__NULL__`，还原时翻译成 `delete_setting` / `device_config delete`
- 安装后新增的键由 `ensure_snapshot_setting` 用 awk 按 `kind/ns/key` 去重后补录 —— **所以升级到新版本时，老快照仍能拿到基线**
- 显示模式单独存文件（`cmd display` 不是 Settings 键）

#### 结果按 boot_id 失效

```sh
record_result()  { printf '%s\t%s\t%s\n' "$(current_boot_id)" "$(now_epoch)" "$3" > "$STATE_DIR/$1"; }
result_current_value() {
  boot="$(awk -F '\t' 'NR==1{print $1}' "$file")"
  [ "$boot" = "$(current_boot_id)" ] || return 1     # ← 跨重启直接失效
  awk -F '\t' 'NR==1{print $3}' "$file"
}
```

**一条 `OK` 跨重启自动作废，不需要手动清缓存。** 这也是为什么过期状态在 UI 上显示灰色而不是绿色。

能力探测缓存同理：`schema` 或 `boot_id` 不匹配就重扫。

#### 还原顺序是刻意的 **[源码]**

`restore_all` 的顺序不能改：

```
停 guard → 释放两个 RMS handle → 停 perf 会话 → 清游戏温控
→ 触控归零(事务17) → cmd thermalservice reset → 释放性能会话
→ 写回快照 → 还原显示模式 → 还原 Android Game Mode → 删结果文件
```

**框架层重置必须在快照写回之前**，否则会被快照覆盖回去。

`--uninstall` 只多一件事：最后 `rm -rf "$STATE_DIR"`，然后**刻意不输出任何东西**。

### 3.8 任务系统 **[源码]**

**分组并发：不同组可同时跑，同组互斥。**

```sh
apply|restore → global        compile-app → compile     abe-profile → abe
htp-set → htp                 library-apply → library   direct-charge
memc-set → memc               animation-set → animation  memory-set → memory
dns-set|dns-test|export-cloud → dns   game-launch → launch
touch-test → touch            preload → preload          diagnostics / verify
```

互斥用 mkdir 锁，**存活性靠读 `/proc/<pid>/cmdline` 确认里面有 `job-worker <id>`** —— 不是看 pid 存在，是看 pid 确实是那个 worker。

状态机：`queued → running → done|failed`；崩溃的 worker 被回收成 `{"state":"failed","message":"worker_lost"}`，不会永远卡 running。

所有 JSON 带 `"schema":1` 和 `operation` 判别字段。

#### 触控实测的写法 **[源码]**

```sh
/system/bin/timeout "$seconds" /system/bin/getevent -lt "$dev" > "$raw"
awk '/SYN_REPORT/ { t=$2; gsub(/\]/,"",t); t=t+0
  if (last>0) { d=(t-last)*1000; if (d>=1.0 && d<=20.0) printf "%.6f\n", d }
  last=t }' "$raw" | sort -n
```

取 `SYN_REPORT` 时间戳差值，**只保留 1–20ms 区间**（滤掉停顿和抖动），再取中位数。设备发现靠 `getevent -pl` 找 `vivo_ts`（排除 `_fp`），回退 `touchscreen|goodix_ts|fts_ts|synaptics`。

有效性门槛：**样本 ≥ 150 个**且实测 Hz 落在 `[0.75×, 1.30×]` 标称档位内。

### 3.9 输出协议与状态语义 **[源码]**

五个状态，颜色由前端渲染：

| state | 颜色 | 文档定义 |
|---|---|---|
| `pass` | 绿 | 当前开机实时确认 |
| `partial` | 黄 | 配置/请求已提交但证据不足 |
| `fail` | 红 | 回读不符 |
| `unknown` | 灰 | 待验证 |
| `na` | 灰 | 不支持或无权限 |

**注意**：README 说的「已写入 / 已请求 / 已读回 / 已实测」**不是四档颜色**，是每张卡片里一个 `evidence` 字典的键（`requested` / `readback` / `effective` / `measured_hz` / `median_ms` / `samples` / `temperature_c`）。颜色回答"结论是什么"，evidence 回答"凭什么"。

WebUI 是**纯渲染器**：状态全部由 shell 给出，JS 只做白名单校验，不认识的值降级成 `unknown`。**它不自己推断状态** —— 诚实性是后端强制的。

几个正直的实例（原文）：
- `htp-set` 返回 `"effective":false,"verification":"storage_readback_only"`
- `memc-set` 返回 `"verification":"official_command_observation_only"`
- `direct-charge` 返回 `"verification":"settings_readback_only"`
- `dns-set` 返回 `"filtering_verified":false`
- `dns-test` 返回 `"interpretation":"blocked_or_unresolved_is_not_proof_of_filtering"`

**`guard.state` 单独一个字段**：`pass` 需要心跳新（≤45 秒）+ pid 的 cmdline 匹配 + boot_id 一致。

green 的条目**要求 guard 存活** —— 比如 `active_mode_target` 要 peak/min/active 都在 120 的 1.2Hz 内、`rms_fixed=OK`、**且 guard pass**。

### 3.10 WebUI 的桥 **[源码]**

没有固定注入的全局对象，**探测两个候选**：

1. **`window.ksu`**（KernelSU 风格，优先）—— `ksu.exec(cmd, optionsJson, callbackName)`，**异步回调**：原生侧调用动态命名的全局函数 `vivo_exec_<ts>_<seq>(errno, stdout, stderr)`
2. **`window.Axeron`** —— `Axeron.exec(cmd, optionsJson)` **同步**，返回 JSON 字符串解析成 `{code, out, err}`。有个 `setTimeout(…, 0)` 让 UI 先画一帧再阻塞，**且不强制超时**

两者都没有 → 直接 reject，**无兜底**。字段名不统一，bridge.js 归一化。

**每次调用都传 `env: { LC_ALL: 'C' }`** —— 强制 shell 输出英文 JSON，避免本地化文本污染解析。这是个值得抄的细节。

轮询固定 **650ms**，任务超时 180s（编译类 360s）。结果读取有 6 次 × 180ms 重试，专门处理 `job_not_finished`（worker 标记完成与结果原子重命名之间的竞态）。

---

## 4. 应用到本项目的边界

这里只保存来源实现的调用与恢复方法。当前功能、缺陷和工作计划分别由源码、主题事实与
[待办](../../docs/dev/todo.md) 维护。

- vivo 的 30 秒租约属于 `VivoPerfManager`；不能据此改变 MTK duration=0 请求。
- 系统设置后端的命令选择放在 `SettingChannel`，统一身份、读回和恢复。
- 快照保留键不存在的状态；恢复顺序按本项目的请求所有权设计。
- 固件参数、事务码、反射字段和不透明 hint 列表都需要目标版本依据。

---

## 6. 厂商与版本依赖

以下来自该 vivo 模块的目标环境，不能泛化到所有 MTK 设备：

- 服务名：`bbk_touch_screen_service`、`vivo_lcm_brightness_service`、`rms`、`gamewatch_server`、`vendor.vivoperfservice`、`sps_rms`
- 事务码：触控 16/17、LCM 8/10、RMS 24/36
- Settings 键：`pem_down_brightness_percent`、`pem_brightness_rate`、`vivo_lcm_brightness_policy_disable`、`vivo_gamethermal_switch`、`vivo_screen_refresh_rate_mode`、`abe_unified_switch`、`gm...` 等
- 反射字段：`VPerformance.sIsPlatformOrPrivApp` / `sIsUntrustedDomain`
- GameWatch SDK 类：`com.vivo.gamewatch.common.GameWatchManager`
- `GAME_HINTS` 那 22 个整数

服务名可作为查找线索；服务存在还需要接口、权限和效果验证，不能从品牌推断。

**明确不适用：**

- 它的 `is_qti_device` 分支（骁龙专用 IORap 策略）在 MTK 上是死代码
- 它**不碰任何 MTK sysfs / PowerHAL 节点** —— 那正是本项目的地盘

---

## 7. 已知缺陷与版本错位 **[源码]**

写文档或参考时别把这些当真：

- `app.js` 的 `JOB_LABELS` 里声明了 **`network-filter` 和 `library-scan`**，但 shell 分发器里**没有对应的 `case`** —— 废弃别名。实际走 `dns-set`/`dns-test` 和 `app-catalog`
- `app.js:2414-2428` 有个 `data-local-select` 处理器，**没有任何渲染器会产出它**
- `data-library-remove` 同样没有生产者
- `vivo_binder_tool.jar` 里有个 `VivoSystemTool` 类（写 Settings），**从未被 shell 调用**

这些是 UI 与后端版本不一致的痕迹，说明模块在快速迭代中没有清理死代码。

---

## 8. 原始材料未解释的部分

以下限制属于这一份来源分析，新的目标 ROM 证据记录在设备事实页：

1. **perfLock 的接口描述符和事务码** —— 不在 5 个 jar 里，在 ROM 的 `VivoPerfManager`/`VPerformance` 实现内
2. **`GAME_HINTS` 22 个整数的语义** —— 不透明常量
3. **GameWatch 的 Binder 细节** —— 在 vivo 自己的 APK 里
4. **RMS 事务 36 尾部参数**（`960` / `30840` / `65537`）—— 两组调用的参数个数不同（11 vs 14），差异原因不明
5. **`vivo_lcm_brightness_service` 事务 8 与 10 的参数语义** —— `f 0.0` 和 `i32 100 i32 0` 的字面含义未从源码或注释得到解释
6. **README 列出的"能力边界"是否全部经过真机验证** —— 文档自称"已在 X100 Ultra 真机验证"，但本仓库没有对应报告

---

## 9. 复现方式

```bash
# 模块本体
../MTK-reference/sources/2026-09-ax-module/

# 5 个 jar 是 DEX-only，没有 .class。用 JDK 的 javap 看不到，
# 需要直接解析 classes.dex（每个 jar 里就是 classes.dex）
unzip -l ../MTK-reference/sources/2026-09-ax-module/lib/vivo_binder_tool.jar
```

解析工具按本地环境选择；这份来源材料不维护机器上的 JDK 安装路径。


