# MOs Premium v13 — 固定版本实现摘录

> 分析对象：`D:\CCode\MTK\reference\sources\2026-10-b1\MOsPremiumV13`
>
只记录该版本的接口、命令与来源，不是本项目的功能计划。源码摘录与推断按条目标识；本机效果需独立核验。

---

## 身份

| 项 | 值 |
|---|---|
| 名称 | MOs Premium v13 — Full-Stack FPS Engine 汉化☠️ (id=MOsPremiumADB, axeronPlugin=14000) |
| 包名 / 模块 ID | MOsPremiumADB / axeronPlugin=14000 |
| 版本 | v13.2 (versionCode 1402), minApi=21 maxApi=99 |
| 形态 | Axeron/AxManager 插件模块（action.sh 脚本 + webroot/ WebUI），非 APK；目录无 AndroidManifest/资源/代码，说明.md 是与模块无关的 RTL 布局遗留文档 |
| 提权路径 | rootless（module.prop 无 root/Magisk 声明）；命令经 WebUI 的 Axeron.exec(cmd, env="{}") 桥执行，action.sh 直接 setprop/settings/echo>；action.sh 用 [ -e ]&&[ -w ] 守门 sysfs，webroot 用 2>/dev/null 静默吞失败；未声明 WRITE_SECURE_SETTINGS，settings put secure 是否能写取决于 Axeron 注入了什么权限 [未确定] |
| 界面技术栈 | webroot/index.html + app.js(约9235行,原生JS) + style.css，由宿主管理器托管；含 Canvas 粒子/3D物理 splash、点击音效、贪吃蛇彩蛋、i18n 多语言 |

## 功能清单（31 项）

### 1. 动作按钮入口 apply_mos_enhancer（action.sh 实际执行体）

**类别** entrypoint · **证据** [源码] action.sh 全文，尤其 922-1777 · **权限** setprop 多数可(debug.*/persist.*/vendor.* shell 可写, ro.* 写不动)；settings put global/system 多数可, secure 需 WRITE_SECURE_SETTINGS [未确定 Axeron 是否注入]；sysfs 走 [ -w ] 守门

**做什么**：action.sh 最后一行(1777)只调用 apply_mos_enhancer；这是点"动作按钮"时唯一真正运行的函数，等于 FPS core + 巨量 settings/setprop 倾倒（约数百条）。

**怎么做**：[源码] action.sh:922-1777。依次 apply：动画倍率(global/system window/transition/animator 0.3)、display/rendering(overlay_display_devices none 等)、GPU/Vulkan(skia_renderer_enabled, game_driver_all_apps 1, updatable_driver_all_apps 1, angle_default_backend 2, angle_gl_driver_selection_pkgs "")、SurfaceFlinger/VRR(sf_frame_rate_override 1, sf_use_content_detection_v2, 各种 sf_early_phase_offset_ns 250000, display_manager_constants refresh_rate_in_zone=165)、input(touch_hovering_enabled 0, minimum/maximum_fling_velocity, scroll_friction 0.008)、battery saver off(low_power 0, battery_saver_constants 一批)、doze 关(dumpsys deviceidle disable, device_idle_constants 全部归零, devicе_idle_enabled 0)、后台/AM 管理(activity_manager_constants max_cached_processes=512 等几十项)、内存/ZRAM(cached_apps_freezer enabled, zram_enabled 1)、job_scheduler_constants、game mode(game_mode_config_fps_unlock 1, game_mode_unlocked_frame_rate 1, game_default_frame_rate 165)、CPU boost(cpu_boost_on_touch 1, cpu_frequency_governor performance, disable_thermal_mitigation 1)、dalvik、network/DNS(几百条 wifi/telephony/socket_buffer_size/captive_portal/dns_resolver)、storage、audio、screen、media、notification/logging(silent)、misc toggles，再跟 setprop 块(HW/rendering、dalvik/vm、system/misc、surfaceflinger、media/gpu/net.net.dns*=8.8.8.8/8.8.4.4/1.1.1.1/1.0.0.1)、kernel/VM tweaks、extra gaming tweaks。

### 2. safe_write / safe_write_glob 的 [ -w ] 写入门

**类别** mechanism · **证据** [源码] action.sh:27-43 · **权限** 节点需对执行 uid 有 w 权限；非 root shell 下 /proc/sys/kernel/*、/sys cpufreq 多数 root-only 会被 SKIP，/proc/sys/vm|net 视内核 sysctl 权限

**做什么**：判断一个节点在当前 uid 下是否真的可写，可写才写、否则记 SKIP。这是"哪些真的无 root 可写"的唯一守门点。

**怎么做**：[源码] action.sh:27-43。safe_write(){ [ -e "$2" ] && [ -w "$2" ] && echo "$1" > "$2" || log SKIP; }；safe_write_glob(){ for PATH_GLOB; do for f in $PATH_GLOB; do [ -f "$f" ] && [ -w "$f" ] && echo "$VALUE" > "$f"; done; done; }。webroot 侧【没有】这道 [ -w ] 门，直接 echo>path 2>/dev/null，权限不足静默失败。

### 3. 状态与日志目录

**类别** mechanism · **证据** [源码] action.sh:9-15 · **权限** 可写 /data/local/tmp

**做什么**：写入运行状态与日志。

**怎么做**：[源码] action.sh:9-15。STATE_DIR=/data/local/tmp/mos；STATE_FILE=$STATE_DIR/current_profile；LOG_FILE=$STATE_DIR/engine.log；log_msg 写 "[HH:MM:SS] msg"。各 profile 结束时 echo <NAME> > STATE_FILE（增强器 apply_mos_enhancer 不写 STATE_FILE）。

### 4. 设备分级探测 cpu_cores/device_tier/soc/max_refresh

**类别** mechanism · **证据** [源码] action.sh:49-113 · **权限** 只读

**做什么**：自适应堆参数、dex2oat 线程、SOC/刷新率分支。

**怎么做**：[源码] action.sh:49-113。detect_cpu_cores 用 nproc(空则4)；detect_device_tier 读 grep MemTotal /proc/meminfo (HIGH≥5500000KB≈5.5G, MID≥2800000, 否则LOW)；detect_soc 读 ro.board.platform/ro.product.board/ro.hardware, grep kalama|sun|pineapple|SM8750|qcom8gen4→SD8ELITE 否则 OTHER；detect_max_refresh_rate 读 settings get system peak_refresh_rate (≥60 否则默认120) → MAX_HZ。

### 5. ART / dex2oat 调优

**类别** dex2oat · **证据** [源码] action.sh:171-183; app.js 208+, pm_am 桶 · **权限** setprop persist.sys.dalvik.vm.* / dalvik.vm.*(重启 zygote 才生效)；pm compile 需 shell；dex2oat-* 属性仅影响新编译, 已装应用需 pm compile

**做什么**：把系统 dexopt/JIT 切到激进 speed 档，缩短游戏 JIT 卡顿。

**怎么做**：[源码] action.sh:171-183。setprop dalvik.vm.dex2oat-filter speed-profile；dalvik.vm.dex2oat-cpu-set <cpu_set_list>；dalvik.vm.dex2oat-threads <nproc>；dalvik.vm.jitinitialsize 64m/jitmaxsize 256m/jitthreshold 500/usejit true；pm.dexopt.first-boot speed；pm.dexopt.install speed-profile。device_config put runtime_native usap_refill_threshold 2、usap_pool_enabled true、usap_pool_size 5。webroot 另按包 pm compile -m speed-profile -f <pkg> 与 pm compile -m speed-profile -a。

### 6. ART 大堆 + Phantom 进程解锁

**类别** dex2oat · **证据** [源码] action.sh:186-210 · **权限** setprop(dalvik.vm.* 需重启生效); device_config/settings shell

**做什么**：按设备档设置 dalvik 堆，解除 Android12+ 对游戏子进程(phantom)的配额。

**怎么做**：[源码] action.sh:186-210。HIGH/SD8ELITE: dalvik.vm.heapsize 2g / heapgrowthlimit 512m / heapstartsize 32m / heapminfree 8m / heapmaxfree 32m / heaptargetutilization 0.85；MID:1g/256m/16m/4m/16m/0.80；LOW:512m/128m/8m/2m/8m/0.75。device_config put activity_manager max_phantom_processes 2147483647；settings put global settings_enable_monitor_phantom_procs false。webroot 另有 heapsize 768m/1g/2g、heapgrowthlimit 1g 等变体。

### 7. HWUI Vulkan (skiavk) 渲染器

**类别** RENDERER · **证据** [源码] action.sh:213-244; app.js setprop 桶 · **权限** setprop debug.* shell 可；但 GPU 驱动须支持 Vulkan(MTK 部分机型无 skiavk 会回退或崩溃)

**做什么**：强制 HWUI/RenderEngine 走 Vulkan/Skia，加各级 cache。

**怎么做**：[源码] action.sh:213-244。setprop debug.hwui.renderer skiavk；debug.renderengine.backend skiavk；debug.hwui.disable_buffer_age false；disable_partial_updates true；use_buffer_age false；vk_force_present_fifo true；debug.vulkan.layers ""；debug.vk.enable_validation false；render_dirty_regions false；use_async_texture_upload true。cache 按档(HIGH texture 192/layer 96/gradient 12/path 64/shape 12/drop_shadow 12)。webroot 还有 skiavk_threaded / skiagl / vulkan 及 render_thread/use_gpu_pixel_buffer/use_vulkan_prerotation 等大量 tf.

### 8. SurfaceFlinger 三缓冲 + 相位偏移 + 帧率覆盖

**类别** RENDERER · **证据** [源码] action.sh:246-272; app.js setprop/device_config 桶 · **权限** setprop debug.sf.* shell 可; device_config surface_flinger shell

**做什么**：抬合成相位、关 backpressure、三缓冲、强制帧率 API。

**怎么做**：[源码] action.sh:246-262。ro.surface_flinger.max_frame_buffer_acquired_buffers 3；debug.sf.latch_unsignaled 1；disable_backpressure 1；enable_gl_backpressure 0；use_phase_offsets_as_durations 1；predict_hwc_composition_strategy 1；hw_vsync_via_callback 1；device_config surface_flinger use_frame_rate_api true / enable_frame_rate_override true。相位偏移 debug.sf.early_phase_offset_ns/early_app_phase_offset_ns/early_gl_phase_offset_ns 按 profile 100000~5000000。webroot 有 SF Early Phase 250µs/500µs、SF Early SF/App/High-FPS Phase、debug.sf.high_fps_early_phase_offset_ns 2000000/6100000 等几十个独立 tweak。

### 9. 120Hz 刷新率锁定

**类别** display · **证据** [源码] action.sh 各 profile; app.js settings/device_config 桶 · **权限** settings system/global shell 可; device_config shell

**做什么**：把屏幕锁到最高刷新率, 避免 content-detection 降频。

**怎么做**：[源码] action.sh:441-483 等。settings put system peak_refresh_rate 120 / min_refresh_rate 120(或 MAX_HZ/60/90) / user_preferred_refresh_rate 120 / global display_peak_refresh_rate 120；ro.surface_flinger.use_content_detection_for_refresh_rate false；set_idle_timer_ms 0 / set_touch_timer_ms 0 / set_display_power_timer_ms 0。webroot device_config surface_flinger max_frame_rate 120/144、refresh_max/refresh_reset、game_default_frame_rate 165；settings global game_mode_config_game_default_frame_rate 165。thermal_balanced 90, battery 60。

### 10. GPU Adreno(kgsl)节点直写

**类别** GPU · **证据** [源码] action.sh:283-295; app.js sysfs_proc 桶 · **权限** kgsl 节点通常 root-only; 非 root 下被 [ -w ] SKIP(action.sh) 或 2>/dev/null 失败(webroot)

**做什么**：锁 GPU 常开、最大化带宽、关热限。

**怎么做**：[源码] action.sh:283-295,407-408。经 safe_write_glob 写 /sys/class/kgsl/kgsl-3d0/: force_clk_on=1, idle_timer=0, devfreq/governor=high_performance, min_pwrlevel=0, max_pwrlevel=0, bus_split=0, force_bus_on=1, force_rail_on=1, thermal_pwrlevel=0, devfreq/min_freq=700000000。webroot 同样 echo>上述节点(部分带 || true), 另有 default_pwrlevel=0。

### 11. GPU min_freq 频率地板(按 profile)

**类别** GPU · **证据** [源码] action.sh 各 profile; app.js sysfs_proc 桶 · **权限** kgsl devfreq min_freq 通常 root-only

**做什么**：给 Adreno GPU 设最低频率, 保帧。

**怎么做**：[源码] action.sh。fps_core=700000000; thermal_balanced=400000000(641);battery_gaming=300000000(764);low_end=200000000(831);emulator=500000000(897)。均写 /sys/class/kgsl/kgsl-3d0/devfreq/min_freq。webroot 用 setprop vendor.gpu.min_freq_lock max + /sys/kernel/gpu/gpu_min_clock 0/gpu_max_clock 1。

### 12. 通用 GPU(Mali/PowerVR/Xclipse/Exynos/Tensor/Unisoc)

**类别** GPU · **证据** [源码] action.sh:297-315; app.js setprop 桶 · **权限** devfreq governor/max_freq 多数 root-only; setprop vendor.* shell

**做什么**：覆盖非 Adreno 芯片的 GPU devfreq 最大频率锁。

**怎么做**：[源码] action.sh:297-315。safe_write_glob performance /sys/class/devfreq/*/governor；for /sys/devices/platform/*/devfreq/*/governor …=performance；对 /sys/class/devfreq/*/max_freq 读同目录 available_frequencies 取 sort -rn head -1 写入 max_freq；/sys/class/misc/mali0/device/dvfs_period=0；/sys/kernel/gpu/gpu_governor=1；dvfs_governor=1。webroot 追加 setprop vendor.gpu.governor performance/simple_ondemand、vendor.mali.dvfs_governor performance、vendor.mali.power_policy always_on、vendor.gpu.boost 2/3、vendor.gpu.force_on/rail_on/bus_on。

### 13. CPU 调度器 /proc/sys/kernel/sched_*

**类别** scheduler · **证据** [源码] action.sh:317-332,各profile; app.js sysfs_proc 桶 · **权限** /proc/sys/kernel/* 通常 root-only, 非 root SKIP

**做什么**：调 CFS 参数减少掉帧与迁移抖动。

**怎么做**：[源码] action.sh:317-332。perf_cpu_time_max_percent=0, sched_migration_cost_ns=950000, sched_child_runs_first=1, sched_latency_ns=8000000, sched_min_granularity_ns=1000000, sched_nr_migrate=16, sched_wakeup_granularity_ns=500000, sched_rt_runtime_us=-1(关RT限流)。profile 变体: emulator migration 1500000/latency 14000000/nr_migrate 4; low_end migration 200000/latency 5000000。webroot 另有 sched_energy_aware 0/sched_mc_power_savings 0(echo)。

### 14. ★ CPU cpufreq policy 触碰(与 PowerHAL 直接冲突点)

**类别** CPU · **证据** [源码] action.sh:330,1728,1761-1764; app.js:2446,3807 及 sysfs_proc 桶 / setprop 桶 · **权限** cpufreq scaling_governor/scaling_min_freq 通常 root-only; 若 Axeron 给了 root 或 CAP, 这些会成功

**做什么**：写 CPU governor、iowait_boost、cpu online；webroot 侧重还把 scaling_min_freq 钉到 max_freq。这是我方需重点关注的冲突源。

**怎么做**：[源码] action.sh:330=`/sys/devices/system/cpu/cpufreq/policy*/schedutil/iowait_boost_enable`=1；1728=`safe_write_glob performance /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor`；1761-1764=先 echo 0 到 cpu*/online 再 for i=1..7 echo 1 回 cpu{i}/online。注意 action.sh 【不写】 scaling_min_freq/scaling_max_freq。webroot(app.js)则大量触碰：scaling_governor=performance(多处, 也有=schedutil 反向)、`for f in /sys/devices/system/cpu/cpu*/cpufreq/scaling_min_freq; do cat ${f%min_freq}max_freq | xargs -I{} echo {} > $f; done`(把 min 钉=max, 见 app.js:2446 APEX_GAMING、3807 PHANTOM)、cpufreq/policy*/schedutil/{up,down}_rate_limit_us[=0/3000]、rate_limit_us=0、policy0/4/schedutil/up_rate_limit_us=0、cpu0/cpu4/cpu6 scaling_governor=performance/schedutil；setprop persist.vendor.qti.cpufreq.min_freq max、persist.vendor.qti.cpufreq.governor performance/schedutil、persist.vendor.qti.cpufreq.{up,down}_rate_limit 100/0、persist.cpu.min_freq_little 300000、persist.cpu.max_freq_little 1000000、persist.sys.cpu_gov powersave/conservative/schedutil/interactive/ondemand。

### 15. BBR2 / TCP 拥塞控制(带 bbr 回退)

**类别** NETWORK · **证据** [源码] action.sh:357-362; app.js sysfs_proc 桶 · **权限** /proc/sys/net/ipv4/tcp_congestion_control 常需 root; tcp_rmem/wmem 写需 root

**做什么**：切拥塞控制算法为 BBR2 降低网游延迟，失败回退 BBR。

**怎么做**：[源码] action.sh:357-362。safe_write bbr2 /proc/sys/net/ipv4/tcp_congestion_control || safe_write bbr …。tcp_rmem="4096 262144 67108864"(67MB max); tcp_wmem="4096 131072 67108864"; rmem_max/wmem_max=67108864。webroot 同样 echo bbr2>bbr>cubic 链, 另加 tcp_fastopen 3/tcp_low_latency 1/tcp_nodelay 1/tcp_mtu_probing 1/tcp_window_scaling/netdev_max_backlog 20000/somaxconn 131072, setprop net.tcp.buffersize.{wifi,lte,default}=4096,{..},16777216,{..}。

### 16. VM / 内存(swappiness 等)

**类别** VM · **证据** [源码] action.sh 各 profile/334-375/1707-1721; app.js sysfs_proc 桶 · **权限** /proc/sys/vm/*、/sys/kernel/mm/*、zram 节点多需 root

**做什么**：按 profile 调 swappiness/脏页/水位/ZRAM/KSM/THP。

**怎么做**：[源码] action.sh。swappiness: S25/ZALITH=0, COMPETITIVE=3, ULTRA_120=10, THERMAL=20, TOUCH=5, BATTERY=45, LOW_END=55, EMULATOR=10, Enhancer=1/50/95/100(不同 tweak)。vfs_cache_pressure、dirty_ratio/dirty_background_ratio、dirty_writeback/expire_centisecs、compaction_proactiveness=0、watermark_boost_factor=0、page-cluster=0。webroot 加 min_free_kbytes 65536/131072/49152/256、extra_free_kbytes、watermark_scale_factor 150/200/300、overcommit_memory 1、zram comp_algorithm zstd/lz4、/sys/kernel/mm/transparent_hugepage/enabled never、/sys/kernel/mm/ksm/run 0、/sys/module/lowmemorykiller/parameters/minfree "0,0,0,0,0,0"。

### 17. 存储 I/O 调优

**类别** storage · **证据** [源码] action.sh:364-375; app.js sysfs_proc/cmd 桶 · **权限** /sys/block/*/queue/* 通常 root-only

**做什么**：flash 队列调度器切 noop/none/deadline、关 iostats、调 readahead。

**怎么做**：[源码] action.sh:364-375,1734-1738。/sys/block/*/queue/scheduler=none||noop(webroot 另 deadline||mq-deadline||kyber); iostats=0; read_ahead_kb=128(webroot 另 64/256); add_random 0; rotational 0; rq_affinity 2; nr_requests 1024(webroot 另 64/512); max_sectors_kb 4096; wbt_lat_usec 0。cmd storaged force-gc / sm fstrim; cmd package trim-caches 128M/256M。

### 18. 重度游戏自动 boost(白名单多管齐下)

**类别** gaming · **证据** [源码] action.sh:120-154; app.js:1867-1907 · **权限** cmd/pm/dumpsys/appops shell 可; 但设备需实现了 game mode/deviceidle 白名单接口

**做什么**：对 20 款重载游戏同时施加性能模式/加速编译/免杀后台/帧率提示。

**怎么做**：[源码] action.sh:120-154 与 app.js:1867-1907。列表 HEAVY_GAMES/HEAVY_GAMES_PKGS(com.miHoYo.GenshinImpact, bh3.global, com.kurogame.wutheringwaves.global, com.tencent.ig, com.activision.callofduty.shooter, com.garena.game.codm, com.dts.freefireth, com.mobile.legends, com.epicgames.fortnite, com.roblox.client, com.mojang.minecraftpe, com.ea.gp.apexlegendsmobilefps, com.gameloft.android.ANMP.GloftA9HM, com.ea.games.r3_row, com.supercell.clashofclans, com.ngame.allstar.eu, com.nexon.bluearchive, com.blizzard.diabloimmortal, com.netease.lztgglobal 等20个)。对每个 pkg: `cmd game mode performance ${pkg}`; `pm compile -m speed-profile -f ${pkg}`; `cmd activity set-standby-bucket ${pkg} active`; `dumpsys deviceidle whitelist +${pkg}`; `appops set ${pkg} RUN_IN_BACKGROUND allow`; `RUN_ANY_IN_BACKGROUND allow`; `device_config put game_overlay ${pkg}_game_default_frame_rate 120`; webroot 另 `settings put global game_driver_opt_in_apps ${pkg}`。

### 19. 电源/温控服务覆写

**类别** power · **证据** [源码] action.sh 各 profile; app.js cmd 桶 · **权限** cmd power/thermalservice shell 可(实现与否看 ROM)

**做什么**：开 fixed-performance 模式、set-mode 0/1、override 温控服务。

**怎么做**：[源码] action.sh: `cmd power set-fixed-performance-mode-enabled true`(S25/competitive/touch/low_end/emulator) 或 false(thermal/battery); `cmd power set-mode 0`(性能) 或 `set-mode 1`(省电)。app.js: `cmd power set-fixed-performance-mode-enabled true/false`、`set-adaptive-power-saver-enabled true/false`、`set-mode 0/1`; `cmd thermalservice override-status 0`(关热缓解) / `cmd thermalservice reset`(恢复); `dumpsys deviceidle disable/force-idle/unforce`。

### 20. FPS 解锁/去帧率限制设置

**类别** gaming · **证据** [源码] action.sh:380-411 · **权限** settings/setprop shell

**做什么**：清掉 OEM/省电视图的帧率上限与后台限帧。

**怎么做**：[源码] action.sh:380-411。settings global fps_divisor 1; min_refresh_rate 60; low_power 0; low_power_sticky 0; device_config game_overlay game_mode_intervention_enabled false; activity_manager default_background_activity_starts_enabled true; debug.hwui.target_cpu_time_percent 80/target_gpu_time_percent 90; debug.sf.nobootanimation 1; persist.sys.perf.topAppRenderThreadBoost.enable true; persist.sys.perf.awaremod.enable 1; 各 OEM 专键: persist.sys.miui_booster.fps_limit 0, debug.game.fps.limit 0, persist.sys.oppo.game_mode 2, persist.sys.oplus.game_fps_limit 0, persist.sys.game.fps.limit 0, debug.samsung.fps.limit 0; debug.egl.swapinterval -1。

### 21. premiumNewModules 千余条 tweak 目录(WebUI 主菜)

**类别** catalog · **证据** [源码] app.js:386-1637 · **权限** 逐条同上; sysfs 依赖 Axeron 权限

**做什么**：WebUI 里可逐条点选的调优目录, 每条即一条 setprop/settings/sysfs 命令, 由 Axeron.exec 执行。

**怎么做**：[源码] app.js:550 起, premiumNewModules=[{name,desc,cmd,category}], 共约 1075 条(实测分类计数 GAMING 447 / PERFORMANCE 226 / RENDERER 101 / BATTERY 138 / BALANCED 163)。每条 cmd 形如 "setprop debug.sf.latch_unsignaled 1"、"device_config put surface_flinger set_idle_timer_ms 80"、"echo 0 > /sys/…/ksm/run 2>/dev/null"。UI 分页 50/页(ITEMS_PER_PAGE=50)可搜索, 单点即 execQueue([cmd])。categorizeModule(a:1601) 归类, runtimeModuleRegistry 生成 generatedDatabase。

### 22. 24 个 WebUI VIP 预设(applyPreset)

**类别** preset · **证据** [源码] app.js:1910-4832 · **权限** 同上; 预设含 CPU scaling_min/governor 直写

**做什么**：一键下发一大批量预设命令(已内联 action.sh 的 FPS core)。

**怎么做**：[源码] app.js:1910 起。presetName ∈ {TURBO, ALL_IN, APEX_GAMING, ESPORTS, GOD_MODE, PHANTOM, STEALTH_MODE, S25_ZALITH_ULTRA, COMPETITIVE_EXTREME, ULTRA_120, THERMAL_BALANCED, THERMAL_STABLE_120, TOUCH_BOOST, BATTERY_GAMING, LOW_END_FPS, EMULATOR_OPTIMIZED, BALANCED, MOS_GAMING, MOS_BATTERY, MEMORY_VAULT, NETWORK_ULTRA, CINEMATIC, DEEP_CLEAN, RESCUE}。每个把几十~上百条命令装入 cmds[] 分批 execQueue(PRESET_BATCH 分批)。注释里可见 "── ACTION.SH FPS CORE (v13) ───" 表明 webroot 内联复制了 action.sh 的 FPS core(两份独立实现, app.js 不调用 action.sh)。APEX_GAMING(app.js:2446)与 PHANTOM(3807)含 scaling_min_freq=max 锁。

### 23. Inject All Safe(安全全量注入)

**类别** catalog · **证据** [源码] app.js:1743-1815 · **权限** 同上

**做什么**：把当前分类的 tweak 全量注入, 但用正则剔除会破坏 UI/网络的破坏性命令。

**怎么做**：[源码] app.js:1743-1815。injectAllSafe 取 generatedDatabase(或按分类过滤), SKIP_CMD_PATTERN=/font_scale|wm\s+(size|density)|display_density_forced|display_size|ui_night_mode|uimode\s+night|night_display|display_color_mode|display_inversion_enabled|high_text_contrast_enabled|hdr.*|captive_portal|private_dns|net\.dns[12]|wallpaper/i 过滤, 剩余 split(/&&|;/) 后 BATCH=20 分批 execQueue。

### 24. 设备伪装 applySpoof

**类别** spoof · **证据** [源码] app.js:4833-4852 · **权限** ro.* 是只读属性, 非 root setprop 一般失败(静默)

**做什么**：改 ro.product.* 伪装机型骗游戏画质检测。

**怎么做**：[源码] app.js:4833。setprop ro.product.model/manufacturer/brand [+ ro.product.name/device]; reset 分支设 ""。

### 25. UI 调校 applyUI

**类别** ui · **证据** [源码] app.js:4854-5150 · **权限** settings/wm/cmd shell

**做什么**：动画倍率、刷新率、分辨率、DPI、字体、深浅色、颜色、导航栏、状态栏等。

**怎么做**：[源码] app.js:4854。refresh_max/refresh_reset(settings peak/min_refresh_rate); res_qhd/hd/fwd→`wm size 1440x3200/1080x2400/720x1600/reset`; dpiLow/High/Reset→`wm density 360/480/reset`; font_scale_small/large/reset→settings system font_scale 0.85/1.2/reset, font_weight_adjustment 300; dark_force/dark_reset→`cmd uimode night yes/no`; navbar_gesture/3btn/reset→`cmd overlay enable|disable com.android.internal.systemui.navbar.gestural|threebutton` + policy_control null; statusbar_*(clock_center/battery_pct/hide_notif_count); color_saturate/vivid/warm→`cmd SurfaceFlinger set-color-saturation 1.4` 或 setprop persist.sys.sf.color_saturation; brightness_max→settings system screen_brightness 255; haptic_on/off; pointer_speed_max→settings system pointer_speed 7; iphone_fluidity.

### 26. 趣味/工具动作 applyFun

**类别** fun · **证据** [源码] app.js:5150-5423 · **权限** settings/dumpsys/am shell

**做什么**：假电量、DPI、清后台、显示点击、网络刷新、FreeFire 灵敏度等。

**怎么做**：[源码] app.js:5150。battery100→`dumpsys battery set level 100`; batteryReset→`dumpsys battery reset`; battery save/balance→settings low_power/adaptive_battery 等; netFlush→`setprop service.adb.tcp.port 5555 && stop adbd && start adbd`类或 dns flush 键; netLowPing→网络键; netDoH→private_dns/specifier; sysKillBg→`am kill-all`+`am send-trim-memory all …`; sysDexopt→`pm compile -m speed-profile -a`; sysAdbWifi→service.adb.tcp.port 5555; show_taps→settings system show_touches 1/0; screenshot_sound_off; ffSensLow/Med/High/Reset/Headshot→FreeFire 触摸相关 settings/setprop。

### 27. 私人 DNS / DoH 配置

**类别** NETWORK · **证据** [源码] action.sh:1688-1701; app.js:4382-4383 · **权限** setprop net.* 非 root 常失败; settings global private_dns_* 需 WRITE_SECURE_SETTINGS/权限

**做什么**：设 DNS 服务器与 opportunistic/指定 DoT 主机。

**怎么做**：[源码] action.sh:1688-1701 setprop net.dns1/2/3/4=8.8.8.8/8.8.4.4/1.1.1.1/1.0.0.1 及 net.rmnet0/wifi/eth0/ppp0/gprs.dns*; app.js preset: `settings put global private_dns_mode opportunistic` + `settings put global private_dns_specifier dns.nextdns.io`, `settings put global ntp_server time.cloudflare.com`。

### 28. 恢复 resetAllProps(部分恢复)

**类别** restore · **证据** [源码] app.js:5424-5448 · **权限** cmd/settings shell

**做什么**：把少量关键项恢复默认/空(不是全量回滚)。

**怎么做**：[源码] app.js:5424。仅 reset: debug.hwui.renderer skiagl, render_thread false, app_memory_policy balanced, debug.sf.early_phase_offset_ns 0; `cmd thermalservice override-status 0`; `cmd power set-mode 0`; 动画 scale 1.0/1.0/1.0; game_driver_all_apps 0; policy_control null; `settings delete secure multi_press_timeout|long_press_timeout`; `settings delete system peak_refresh_rate|min_refresh_rate`; `dumpsys battery reset`; `wm density reset`; `dumpsys deviceidle unforce`。

### 29. restoreSavedPreset(仅恢复选中态)

**类别** restore · **证据** [源码] app.js:1818-1860 · **权限** 无

**做什么**：页面加载时恢复上次选中预设的高亮, 不重新下发命令。

**怎么做**：[源码] app.js:1835-1860, localStorage key mos.vip.selectedPreset, 注释明确"Only restore visual/active state — do NOT re-execute commands on page load."

### 30. 贪吃蛇彩蛋游戏 + 音效/粒子

**类别** misc · **证据** [源码] app.js:8462+; webroot 资源列表 · **权限** 无

**做什么**：WebUI 内嵌 canvas 贪吃蛇(本地最高分)与点击音/背景乐。

**怎么做**：[源码] app.js:8462+ window.snakeStart/snakeDpad, localStorage 'snakeBest'; webroot/paradise_resort.mp3, snake_icon.jpg; playTick(音频); initParticles/init3DPhysics。

### 31. i18n 多语言字符串表

**类别** misc · **证据** [源码] app.js:5453+ · **权限** 无

**做什么**：UI 文案多语言(en/pt/zh 等)。

**怎么做**：[源码] app.js:5453 i18nStrings{en:{…}}, currentLang 切换。

## 明确查不出的东西（8 条）

- Axeron(axeronPlugin=14000) 到底给 exec 注入多大的权限：是否 root、是否 WRITE_SECURE_SETTINGS——直接决定 sysfs/cpufreq/secure settings 是成功还是被 SKIP/静默失败。仓库内看不到 Axeron 运行时，无法确定 [未确定]
- action.sh 的 8 个 profile 函数(apply_s25_zalith_ultra/competitive_extreme/ultra_120/thermal_balanced/touch_boost/battery_gaming/low_end_fps/emulator_optimized)在脚本末尾只定义了、由入口调用的是 apply_mos_enhancer；脚本无参数解析，所以走正常动作按钮时这些 profile 是死代码。若宿主另有方式 source 并调用则另说 [源码推断，宿主行为未确定]
- module.prop 宣传的"APEX perf governor"名不副实：代码里没有 Android APEX 模块 governor，只有名为 APEX_GAMING 的游戏预设(针对 Apex Legends Mobile，性能 governor+相位偏移)。别把 APEX_GAMING 当系统级 APEX [源码]
- 说明.md 与模块无关：它是一篇讲 Android RTL 布局锁定(supportsRtl=false / layoutDirection LTR)的遗留文档，不是 MOs 模块的使用说明 [源码]
- private_dns_mode=opportunistic 与 private_dns_specifier 并列：标准 Android 只有 private_dns_mode=hostname 才消费 specifier，opportunistic 下 specifier 可能无效(疑似 bug) [源码]，真机行为未验证 [未确定]
- webroot 如何被 Axeron 托管(是否有本地 http server、如何暴露 exec 桥)取决于宿主管理器，仓库内无 manifest/服务声明，无法从代码确认 [未确定]
- 众多 setprop 键在本机(vivo V2430A/MT6989)是否被 ROM 实际消费：大量 debug.*/vendor.*/persist.* 是硬编码的全家桶写法，本机消费者和效果未逐一核验 [未确定]
- sysfs 节点在该机型是否存在(如 /sys/kernel/gpu/*、/sys/class/misc/mali0/*、/sys/module/lowmemorykiller/*)未在本机核对——action.sh 用 [ -w ]/webroot 用 2>/dev/null 都会因节点不存在而静默跳过 [未确定]
