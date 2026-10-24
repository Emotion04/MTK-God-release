# Kbattery 1.2.3 — 固定版本实现摘录

> 分析对象：`D:\CCode\MTK\reference\sources\2026-10-b1\Kbattery1.2.3.apk`
>
只记录该版本的接口、命令与来源，不是本项目的功能计划。源码摘录与推断按条目标识；本机效果需独立核验。

---

## 身份

| 项 | 值 |
|---|---|
| 名称 | Kbattery（蓝厂小护士 / 智能电池优化工具） |
| 包名 / 模块 ID | com.example.kbattery |
| 版本 | versionName 1.2.3 / versionCode 4；minSdk 31，targetSdk 36，compileSdk 36；platformBuildVersion 16 |
| 形态 | APK（34 MB，2 个 dex：classes.dex 21 MB + classes2.dex 4.8 MB；native arm64-v8a 仅 libandroidx.graphics.path.so + libdatastore_shared_counter.so） |
| 提权路径 | Shizuku（moe.shizuku.manager.permission.API_V23，已内置完整 rikka.shizuku API 库与 ShizukuProvider authority=com.example.kbattery.shizuku）+ 清单声明 WRITE_SETTINGS / WRITE_SECURE_SETTINGS + MainActivity 里 `pm grant <self> android.permission.WRITE_SECURE_SETTINGS` 自助授权。无 root、无 Magisk 模块。Shizuku 命令由 shell 身份执行，获授权后的 ContentResolver 则属于 App 进程，两条路径的身份和错误语义不同。 |
| 界面技术栈 | 原生 Kotlin + Jetpack Compose（Material3 + Material Icons Extended + Navigation-Compose + Coil + DataStore Preferences + OkHttp）；启动 Activity com.example.kbattery.MainActivity，Application com.example.kbattery.KbatteryApp；两个 specialUse 前台服务（ForegroundKeepAliveService subtype=keep_alive，VivoRmsMonitorService subtype=rms_monitor）+ 一个 AppWidget（DailyQuoteWidget） |

## 功能清单（31 项）

### 1. 续航优化（Battery Optimization）主执行管线

**类别** 省电主流程 · **证据** [字节码] 反汇编 obf/ih.invokeSuspend、obf/wg.invokeSuspend；[字符串] 键名与命令原文 · **权限** Shizuku(uid 2000) 或 WRITE_SECURE_SETTINGS；sysfs 写需 shell 权限

**做什么**：一键把 ~30 个 vivo/iqoo 私有 settings 键写成省电值、发一批省电广播、并强停 9 个 com.vivo.abe 系服务。UI 提示"此板块会执行循环，可能带来额外耗电请注意"。

**怎么做**：实现类 com.example.kbattery.obf.ih（enable）/ com.example.kbattery.obf.wg（同键集另一路径）。为每个开关构造 Triple(namespace, key, value) 后调用 com.example.kbattery.utils.SettingsWriter.d(...) 批量落库；回滚键统一以 prefs 前缀 `backup_game_` / `backup_battery_opt` / `backup_high_temp_no_dim` / `backup_direct_power` 存原值。附带一条 shell 脚本直接写 CPU sysfs：`for cpu in /sys/devices/system/cpu/cpu*/cpufreq; do echo 0 > $cpu/scaling_min_freq 2>/dev/null; done`（obf/ih S@680）。另有一段 setprop 批次：debug.jpegdec.high.perf=0 / debug.ui.perfmode.enable=0 / debug.sf.hw=0 / debug.egl.hw=0 / debug.sf.auto_latch_unsignaled=0 / debug.sf.disable_client_composition_cache=0 / debug.sf.enable_gl_backpressure=0 / debug.sf.enable_hwc_vds=0 / debug.sf.predict_hwc_composition_strategy=0 / debug.vivo.perftest=false，以及 cmd jobscheduler reset-flex-policy、cmd jobscheduler cache-config-changes off、cmd power set-adaptive-power-saver-enabled true、cmd power set-fixed-performance-mode-enabled false。写入键与命名空间见下方 settings 清单。

### 2. 深度省电（Deep Power Saving）11 项策略

**类别** 省电开关矩阵 · **证据** [源码] ui/power/g 字符串表逐项可读；[字节码] StrategyItem 构造顺序 · **权限** WRITE_SECURE_SETTINGS / Shizuku

**做什么**：一个二级页面 11 个 StrategyItem 开关，每项 = prefKey + namespace + settingsKey + valueOn/valueOff，全部走 SettingsWriter。

**怎么做**：UI 类 com.example.kbattery.ui.power.g（DeepPowerSavingScreenKt，源文件 DeepPowerSavingScreen.kt）。StrategyItem(title, description, icon, prefKey, namespace, settingsKey, valueOn, valueOff)。精确清单：①power_save_ap / power_save_ap_status / 热点省电 / 1↔0；②save_mode_screen_time / save_mode_setting_screen_time / 息屏时长生效 / 1↔0；③power_save_change_screen_time / power_save_change_screen_time / 省电自动改息屏 / 1↔0；④power_lock_time / power_lock_time_in_default / 按键熄屏延时 / 1↔0；⑤power_save_change_rate / power_save_change_screen_rate / 省电自动刷新率 / 1↔0；⑥save_mode_rate / save_mode_setting_screen_rate / 省电锁定刷新率 / 1↔0；⑦light_effect_strategy / light_effect_power_saving_strategy_switch / 灯效省电策略 / 1↔0；⑧face_super_power / face_super_power_mode / 人脸省电管控 / 1↔0；⑨bbk_power_assistant / namespace=secure / settingsKey=bbk_power_assistant / vivo电源底层助手 / 1↔0；⑩wifi_saved_state / wifi_saved_state / WiFi驻留省电关闭 / 关=0。主开关写 power_save_type=2、system_property_power_mode_type、power_save_ap_status=0（ui/power/b）。关闭时按 10 个 prefKey 逐一还原（ui/power/e）。持久化 datastore 键 deep_power_settings、deep_power_main。

### 3. 系统功能（System Functions）批量 settings + 广播 + 服务强停

**类别** 系统功能 · **证据** [字符串] obf/or 全量键名与命令原文；[字节码] 命名空间分支 · **权限** WRITE_SECURE_SETTINGS / Shizuku

**做什么**：30+ 项 settings 写入 + 9 条 dumpsys 强停 com.vivo.abe 服务 + 6 条省电广播。

**怎么做**：实现类 com.example.kbattery.obf.or。键与命名空间（命名空间由 SettingsWriter.m 的 when 分支反推：CryptoUtils.d()=system、b()=secure、a()=global）：secure — adaptive_connectivity_enabled=1, adaptive_connectivity_wifi_enabled=1, adaptive_connectivity_mobile_network_enabled=1, vivo_adaptive_contrast_state=1, adaptive_sleep=1, adaptive_charging_enabled=1, operator_service_improve_plan=0, vivo_smart_recommend_service_status=0, vivo_personalized_recommend_status=0, vivo_recommended_status=0, abe_unified_switch=0, basic_sleep_enabled=0, stay_connected_internet_enable=0；system — low_power_standby_enabled=1, adaptive_sliding_frame_change=1, vivo_ltpo_setting=0, user_experience_improve_plan=0, user_experience_improve_plan_first_close=1, pempsmstate=0, powermonitor=0, pemMonsterState=0, bbk_update_notice=0, big_data_switch=0, battery_life_protect_enabled=0, power_sleep_mode_enabled=1, power_sleep_mode_type=1；global — setupwizard_nps_status=0, preferred_network_mode=9, auto_sync=0, data_roaming=0。广播/命令原文：`am broadcast -a android.os.action.POWER_SAVE_MODE_CHANGED`、`am broadcast -a intent.action.super_power_save`、`am broadcast -a com.iqoo.secure.power_save_mode_changed`、`am broadcast -a android.engineMode.closehighprotect.action --ez highprotect false`、`cmd netpolicy set restrict-background true`、`dumpsys activity kill-all`、`dumpsys activity broadcast -a com.vivo.pem.REFRESH_CONFIG`，以及 9 条 `dumpsys activity stop-service com.vivo.abe/...`：.unifiedconfig.UnifiedConfigService / .unifiedconfig.UnifiedManager / .unifiedconfig.UnifiedConfigProvider / .vivo.vmcs.core.VMCSCoreService / .vivo.core.receivers.PushMessageReceiver / .vivo.push.sdk.service.CommandService / .vivo.core.configlist.AbeConfigDataProvider / .vivo.core.utils.datareport.ABEDataReportService / .vivo.statistics.TimeBasedScheduler.TimeBasedJobServiceAbe / .vivo.statistics.TimeBasedScheduler.TimeBasedJobServiceRms。

### 4. 增强库（Enhancement Library）游戏增强 7 项

**类别** 游戏增强 · **证据** [字节码] obf/wg.invokeSuspend 反汇编；[字符串] 命令原文 · **权限** Shizuku(uid 2000) + sysfs 写权限

**做什么**：按游戏包名导入后逐项开启：Monster Plus/Turbo、游戏驱动切换、编译启动、触控增强、游戏专网加速、视效增强、关闭高温保护。

**怎么做**：UI 类 obf/ri（EnhancementLibraryScreen，源文件 EnhancementScreen.kt）。prefKey→settingsKey 映射：monster_plus→monster_mode_<pkg>；game_driver_switch→updatable_driver_prerelease_opt_in_apps / updatable_driver_production_opt_out_apps / updatable_driver_production_opt_in_apps；compile_boot→`cmd package bg-dexopt-job`；touch_boost→vts_game_para_adjust；game_network_boost→vivo_smart_cloud_acc_enable_state；visual_enhancement→gamecube_hawkeye_effective；thermal_protection→pem_htp_level / pem_htp_thred / HighTemptureProtect / pem_little_window_high_temp_protect / vivo_gamethermal_switch。开启时附带 sysfs：`for cpu in /sys/devices/system/cpu/cpu*/cpufreq; do cat $cpu/scaling_max_freq > $cpu/scaling_min_freq 2>/dev/null; done`（把每簇 min 拉成 max）。另有 setprop debug.*.hw=1、debug.vivo.perftest=true、cmd power set-fixed-performance-mode-enabled true、cmd power set-adaptive-power-saver-enabled false、cmd jobscheduler enable-flex-policy --option battery-not-low --option charging --option connectivity --option idle、cmd shortcut reset-throttling / reset-all-throttling、cmd thermalservice override-status 0、cmd activity force-stop thermal-engine-v2 / android.hardware.thermal@2.0-service.qti-v2、am force-stop thermal-engine-v2、am broadcast -a com.vivo.gamewatch.action.BENCHMARK_START、am broadcast -a vivo.intent.action.COOLER_CONNECTION --ez connected true --ei level 3、am broadcast -a vivo.intent.action.ICON_RADUIS_CHANGE、am broadcast -a intent.action.POWER_MODE_CHANGE_SERVICE -p com.iqoo.powersaving --ei command <> --ei source 23、dumpsys activity broadcast -a vivo.intent.action.APP_TEMP_STATE_CHANGE --es scene rear_flash_temp --ei temperature 20。数值字面量：'1'、'2'、'5'、'7'、'272'、'1036'、'1,7,7,7'、'50-52-58-1000-1000-1000'。

### 5. VIVO-RMS 连写（vivo_rms_active_request_reason 突发写）

**类别** RMS 调度伪造 · **证据** [字节码] obf/xn0.invokeSuspend 反汇编；[源码] RmsWriteConfig.a() 可读 · **权限** WRITE_SECURE_SETTINGS / Shizuku

**做什么**：向 Settings.Secure 的 vivo_rms_active_request_reason 连续写 N 次伪造 reason，让 vivo RMS 调度器以为前台是某个游戏 Activity，从而提帧/提频；写后读回校验。

**怎么做**：写实现类 com.example.kbattery.obf.xn0：构造 `settings put secure vivo_rms_active_request_reason <reason>`（常量串 'settings'、'put'、'secure' 在 pc=47/49/51），在 kotlin.ranges 的 1..times 循环里执行，循环间用 android.os.Handler.postDelayed 间隔；结果 BurstResult(successCount, attemptCount, allOk, lastMessage)，失败 detail='write failed'，成功 detail='ok'；最后用 `android.provider.Settings$Secure.getString(cr, "vivo_rms_active_request_reason")` 读回比对，产出 RmsBurstWriteResult(targetReason, burst, verifyRaw, verifyMatchesTarget)。reason 串由 com.example.kbattery.rms.RmsWriteConfig.a() 拼：`<package>/<activity>:<fps>|gfx_game:99998|Thermal:<temperature>benchMark:<variant>`，variant ∈ {setting, setting_override, setting_vsyncImd, setting_override_vsyncImd, gameSdk, gameSdk_override, gameSdk_vsyncImd, gameSdk_override_vsyncImd}（由 useOverride/useVsync 与 kind 决定）；activity 缺省补 '.MainActivity'，fps 缺省 '120'，temperature 缺省 '38.0'，并对 windowMinFps / windowMaxFps 做替换。kind 枚举 com.example.kbattery.obf.eg0 = {gameSdk/GAME_SDK, setting/SETTING, benchMark/BENCH_MARK, gfx_game/GFX_GAME, Thermal/THERMAL, windowMinFps/WINDOW_MIN, windowMaxFps/WINDOW_MAX}。配置项（datastore kbattery_settings，键由 obf/fo 定义）：rms_target_package、rms_reason_kind、rms_use_override、rms_use_vsync_imd、rms_burst_write_count、rms_poll_interval_ms、rms_thermal_temp、rms_activity、rms_fps、rms_bg_monitor_enabled。解析/展示类 obf/bo0（窗口最低FPS/窗口最高FPS/跑分模式 · /温控 · X℃/GFX游戏 · 魔数/自定义：），类别词表 obf/yn0 = {gfx_game=GFX游戏, Thermal=温控模式, gameSdk=游戏SDK, setting=系统设置, benchMark=跑分模式, windowMinFps=窗口最小帧率, windowMaxFps=窗口最大帧率}。未配包名时报"请先配置包名再开启连写"。

### 6. VivoRmsMonitorService 循环连写前台服务

**类别** RMS 调度伪造 · **证据** [源码] service/VivoRmsMonitorService 可读；[字节码] obf/zn0 · **权限** Shizuku

**做什么**：specialUse(rms_monitor) 前台服务，按 poll_interval_ms 循环 content 连写 vivo_rms_active_request_reason。

**怎么做**：com.example.kbattery.service.VivoRmsMonitorService：onCreate 建通知渠道 vivo_rms_monitor_channel（名称 'VIVO-RMS 连写'，描述 '循环 content 连写 vivo_rms_active_request_reason'）；onStartCommand 处理 action `com.example.kbattery.action.STOP_RMS_MONITOR`；通知文案 'VIVO-RMS · 连写中' / 'VIVO-RMS · 连写生效' / 'VIVO-RMS · 已匹配' / 'VIVO-RMS · 连写 <n>/<total>'；协域字段 obf/xv，实际连写在 obf/zn0（引用 RmsWriteConfig，日志 '连写失败'、' · '、'/'）。无 Shizuku 权限时报 '❌ 监控失败: 无法获取Shizuku权限'。

### 7. GameWatchBinder — gamewatch_server 通用 Binder 执行器（25 个 executor）

**类别** Binder 直调 · **证据** [源码] binder/GameWatchBinder + Executor/ExecutorParam 全可读；[字节码] transact 码由 const/4 推出 · **权限** Shizuku 或 shell（ServiceManager.getService 是隐藏 API，走反射）

**做什么**：反射拿隐藏服务 gamewatch_server 的 IBinder，用固定 transaction code 调 com.vivo.gamewatch.common.IGameWatch 的 25 个 executor。

**怎么做**：com.example.kbattery.binder.GameWatchBinder：getService() = `Class.forName("android.os.ServiceManager").getDeclaredMethod("getService", String.class).invoke(null, "gamewatch_server")`；execute(executorName, args)：data=Parcel.obtain()、reply=Parcel.obtain()、data.writeInterfaceToken("com.vivo.gamewatch.common.IGameWatch")、data.writeString(executorName)、buildArgPack(...) 按 TYPE_MAP 类型写参、`IBinder.transact(5, data, reply, 0)`（EXECUTE_CODE=5、flags=0，均由 invoke-interface 前的 const/4 v5,#5 / const/4 v6,#0 证实），finally 双 Parcel.recycle()。异常路径 Pair(false, e.message ?: "unknown error")；服务缺失 Pair(false, "gamewatch_server not found")；未知 executor Pair(false, "Unknown executor: <name>")。TYPE_MAP：void→-1、null→-1、boolean→Z、byte→B、int→I、long→J、String→Ljava/lang/String;、IBinder→Landroid/os/IBinder;、byte[]/String[]/int[]/long[]/float/float[]→对应数组签名。25 个 executor（name | 中文说明 | 参数）：hang_up_game|游戏挂起管理|pid,state；vgt_server_register|VGT 服务器注册|serverName,binder,isServer,flags；callback_register|多线程回调注册|callbackBinder；sdkinfo_getter_register|SDK 信息获取器注册|；dual_resume|双分辨率恢复|pkgName；notify_render_thread|渲染线程通知|threadName,threadId,width,height；notify_target_fps|目标 FPS 通知|uid,targetFps,minFps,maxFps,refreshRate,mode,thermalLevel；net_opt|网络优化|key,value；game_space|游戏空间查询|type；info_provide|信息提供|action；notify_game_hang|游戏挂起通知|；checkPermission|权限检查|permissionName；turbo|多线程服务|cmd；turbo2|扩展多线程服务|cmd,intArray1,intArray2,strArray1,strArray2；vaptTurbo|VAPT 多线程服务|；netcallback|网络回调|url,responseBody；control_panel|GPU 控制面板|scope(配置键),value(配置值)；vsync_imd|V-Sync 即时模式|；notify_to_app_common|通用应用通知|extras；magt_extension_control|MAGT 扩展控制|targetPid,serviceCode,arg1,arg2；GameLib|游戏库状态|gameId,enable；perf_tool_dump_begin|VCS 性能转储|type,outputPath；is_support_vnss|VNSS 游戏支持检查|；notify_api_type|图形 API 类型通知|apiType；ecosdk|Eco SDK Q3 配置|jsonCmd。buildArgPack 按 String[]/int[]/float/boolean/long/byte/int/long[]/String/IBinder 分型写 Parcel。

### 8. GameWatchCommands — gamewatch 广播命令集

**类别** 广播 · **证据** [源码] 命令模板原文 · **权限** shell / Shizuku

**做什么**：5 条 am broadcast 触发 gamewatch 侧行为。

**怎么做**：com.example.kbattery.binder.GameWatchCommands：CONFIG_UPDATE = `am broadcast -a com.vivo.daemonService.unifiedconfig.update_finish_broadcast_GameWatch`；quality(pkg,pid,q) = `am broadcast -a com.vivo.gamewatch.game.quality --es pkgName <p> --ei pid <n> --ei quality <q>`；sceneStatus(pkg,st) = `am broadcast -a com.vivo.gamewatch.game.scene.status --es packageName <p> --ei status <n>`；swapModel(mode,list) = `am broadcast -a com.vivo.gamewatch.ACTION_OPEN_SWAP_MODEL --es key_swap_model_name <m> --esal key_swap_model_game_list <l> --receiver-permission com.vivo.gamewatch.permission.SWAPMODEL`；targetFps(pkg,pid,fps) = `am broadcast -a com.vivo.gamewatch.game.targetfps --es pkgName <p> --ei pid <n> --ei targetFps <n>`。

### 9. ShellObfTable — 命令混淆表（60+ 条）

**类别** 命令分发 · **证据** [字节码] ShellObfTable.<clinit> 完整反推 · **权限** —（表本身无需权限）

**做什么**：把短 token 映射成真实 shell 命令，规避字符串扫描。

**怎么做**：com.example.kbattery.shell.ShellObfTable.table: Map<String,String>，ShellObfTable.get(token) 取命令，ShellObf.s(String) 再包一层。完整表（token → 命令）：token.sh→sh；token.dash_c→-c；token.pidof→pidof；token.pm→pm；token.grant→grant；token.logcat→logcat；token.adb_shell_pm_grant→`adb shell pm grant %s android.permission.WRITE_SECURE_SETTINGS`；perf.power_1011→`settings put secure perf_power_test_item 1011`；perf.power_1004→`settings put secure perf_power_test_item 1004`；perf.set_game_plus→`settings put secure game_plus_value `；perf.set_pwr_secure→`settings put secure power_save_type `；perf.set_pwr_system→`settings put system power_save_type `；perf.set_monster→`settings put system vgame_agreement_consent_state `；perf.icon_radius→`am broadcast -a com.iqoo.secure.ICON_RADIUS_CHANGED`；perf.set_gamecube_version→`settings put secure gamecube_version`；pem.refresh→`am broadcast -a com.vivo.pem.REFRESH_CONFIG`；pem.upgrade_check→`am broadcast -a com.vivo.pem.SYSTEM_UPGRADE_CHECK`；pem.cloud→`am broadcast -a com.vivo.pem.CLOUD_ACTION`；pem.temp_state→`am broadcast -a com.vivo.pem.TEMP_STATE_CHANGE`；bg.iqoo_power→`am broadcast -a com.iqoo.secure.power_save_mode_changed`；bg.pemr_update→`am broadcast -a com.vivo.daemonService.unifiedconfig.update_finish_broadcast_pemr`；bg.restore.pem→`dumpsys activity startservice com.vivo.pem/.PemService`；bg.restore.power→`dumpsys activity startservice -e command 1006 -e source 23 com.iqoo.powersaving/.PowerModeChangeService`；superres.startservice→`am startservice -n com.vivo.gamecube/.GameCubeGameEcosystemService`；opt.dumpsys_prefix→dumpsys；opt.surface_flinger→SurfaceFlinger；opt.latency_clear→latency_clear；svc.start→`am startservice`；svc.stop→`am stopservice`；act.grep_filter→grep；act.start_activity→`am start`；act.dumpsys_activity→`dumpsys activity`；act.start_service→act.start_service；act.stop_service→act.stop_service；act.kill_pkg→`am force-stop`；act.am_crash→`am crash`；act.kill_all→killall；net.restrict_bg→`cmd netpolicy set restrict-background`；net.true→true；net.false→false；idle.whitelist_add→`cmd deviceidle whitelist +`；idle.whitelist_remove→`cmd deviceidle whitelist -`；term.pm_list→`pm list packages`；term.battery→`dumpsys battery`；term.getprop→getprop；term.logcat_tail→`logcat -t 50`；term.ps_a→`ps -A`；term.meminfo→`cat /proc/meminfo`；hp.close_prefix→`settings put secure high_temp_protection_enable 0`；se.no_allow_prefix→`settings put secure smart_engine_no_allow_list `；se.no_allow_suffix→''（空串，dex string id 0）；se.update_config→`am broadcast -a com.vivo.smartengine.UPDATE_CONFIG`；se.exception_prefix→`settings put secure smart_engine_exception_prefix `；se.exception_pid / se.exception_name→值在别处动态构造；se.user_kill→`am force-stop %s`。另有 shell/BatteryGuardCommands（bg.iqoo_power / bg.pemr_update / bg.restore.pem / bg.restore.power）、shell/PemCommands（pem.cloud / pem.refresh / pem.upgrade_check / pem.temp_state）、shell/VivoPerfCommands（perf.set_game_plus / set_monster_key / set_pwr_secure / set_pwr_system / gamePlusOn / gamePlusOff / monsterKeyOn / monsterKeyOff / powerSaveSecure / powerSaveSystem / getICON_RADIUS_BROADCAST）。

### 10. SettingsWriter — 三态写入 + 分层还原

**类别** 写入引擎 · **证据** [字节码] SettingsWriter.m/r/q/o/d/c 反汇编 · **权限** WRITE_SECURE_SETTINGS / Shizuku

**做什么**：settings 写入/读取/回滚核心，含 ContentResolver 与 shell 两条路径、批量读、三层还原。

**怎么做**：com.example.kbattery.utils.SettingsWriter。m(context,namespace,key,value)：when(namespace){ CryptoUtils.d()→"system"; CryptoUtils.b()→"secure"; CryptoUtils.a()→"global" } → `Uri.parse("content://settings/"+ns)` → ContentValues{name=key, value=value} → contentResolver.insert(uri, values)。r(...)：shell 路径，把 value 里的 `\:` 转 `:`、换行转空格，脚本体 `settings put <ns> <key> '<value>'` + `content insert --uri content://settings/<ns> --bind name:s:<key> --bind 'value:s:<value>'` + `settings get <ns> <key>`（验证），一次 write+flush+close+waitFor，再读 inputStream / errorStream（InputStreamReader + BufferedReader，8192 缓冲）；创建进程失败返回 '无法创建Shizuku进程'。q(key)：`settings get <ns> <key>` 走 `sh -c`。o(...)：批量读，`settings get <ns> <key>; echo ___BATCH_SEP___\n` 循环后用 ___BATCH_SEP___ 切分。d(...)/c(...)：LayeredRestore —— Layer1 还原 → Layer2 重试 → Layer3 delete keys，并逐项读回比对（日志 'LayeredRestore verify failed: <k> expected=... actual=...'），产出 LayeredRestoreResult(errors, mismatched)。写失败日志 ' put fail: ' / ' exception: '；未知命名空间抛 '未知命名空间: <ns>'。执行模式枚举 obf/yi = {content/CONTENT_INSERT, settings/SETTINGS_PUT}，存 SharedPreferences execution_mode_prefs 键 execution_mode。

### 11. VivoPerfManager 反射持锁（Plus/Turbo 场景）

**类别** Perf lock · **证据** [字节码] obf/wn0.<clinit> 反汇编 + fill-array-data 载荷提取 · **权限** 系统/签名级（VivoPerfManager 通常需 system uid）；失败路径已容错

**做什么**：反射调用 framework 隐藏类 com.vivo.framework.vperf.VivoPerfManager 的 perfLockAcquire / perfLockRelease，为 Monster Plus/Turbo 会话持有 perf lock 至会话结束。

**怎么做**：实现类 com.example.kbattery.obf.wn0。<clinit>：`Class.forName("com.vivo.framework.vperf.VivoPerfManager")` → `getConstructor()`（无参）→ `newInstance()` 存字段 a；`getMethod("perfLockAcquire", int[].class, Integer.TYPE)` 存字段 b；`getMethod("perfLockRelease", int[].class, Integer.TYPE)` 存字段 c。a(hints) 方法：obf/tv.az(hints) 判空 → b() 拿 Method → Method.invoke(instance, Integer.valueOf(...))，失败 detail='fail: '+msg，unsupported 时返回 'unsupported'。hint 数组（int[]，成对 (opcode, value)）：字段 d（12 元素）=[1086324736, 2, 1082130432, 4095, 1082130432, 4095, 1082130432, 4095, 1077936128, 1, -267386880, 9]；字段 e（22 元素）= 同上前 18 元素重复一次后再接 [1077936128, 1, -267386880, 9]；字段 f（18 元素）=[1086324736, 2, 1082130432, 4095 ×8 组]。clinit 另定义常量 9、61456(0xF010)、4095(0xFFF)、16512(0x4080)、0L（疑为 duration）。

### 12. 全局插帧（Global Frame Interpolation）

**类别** 显示 · **证据** [字节码] ForegroundKeepAliveService.applyFrameInterpolation 反汇编 · **权限** WRITE_SECURE_SETTINGS / Shizuku

**做什么**：写 gamecube_frame_interpolation 让系统级插帧到 120/144Hz 或关闭。

**怎么做**：com.example.kbattery.ForegroundKeepAliveService.applyFrameInterpolation(value)：144 → `1:1:0:72:144`；120 → `1:1:0:60:120`；关闭 → `0:-1:0:0:0`；写入 Settings 的 gamecube_frame_interpolation（namespace secure）。由 library_settings prefs 的 global_frame_interpolation 开关驱动，在 ForegroundKeepAliveService 的循环里反复执行。

### 13. 直驱供电（Direct Power / 充电直驱）

**类别** 充电 · **证据** [字节码] ForegroundKeepAliveService.applyDirectPowerSettings 与 obf/s20.invokeSuspend · **权限** WRITE_SECURE_SETTINGS / Shizuku

**做什么**：改一组 vivo 充电/键锁 settings 开启充电直驱（绕过电池直接给系统供电）。

**怎么做**：开启：com.example.kbattery.ForegroundKeepAliveService.applyDirectPowerSettings() —— 先读 secure 的 smart_charge_upper_value 做备份，然后写 power_engine_and_plugged_state_and_charging_aod=`1,1,true`、settings_battery_charge_director=`1`、settings_battery_charge_director_game_cube、vivo_keyguard_super_speed_charge_switch=`0`、balance_charge_switch、vivo_keyguard_show_charge_mode_switch；并从 system 取 batterymanager 转 BatteryManager。还原：obf/s20，按 backup_direct_power 前缀回写 balance_charge_switch / vivo_keyguard_super_speed_charge_switch / settings_battery_charge_director_game_cube / settings_battery_charge_director / power_engine_and_plugged_state_and_charging_aod（值 '1'/'0'）。

### 14. 高温不降亮度（High Temp No Dim）

**类别** 温控/显示 · **证据** [字节码] obf/n20.invokeSuspend 反汇编 · **权限** WRITE_SECURE_SETTINGS / Shizuku

**做什么**：关掉高温降亮度，并强开 HBM 高亮模式。

**怎么做**：实现类 com.example.kbattery.obf.n20：备份键 backup_high_temp_no_dim；写 vivo_display_color_mode、gamecube_hawkeye_effective、pem_htp_thred、pem_htp_level、lock_screen_bright_on、brightness_for_pem；另执行 `content insert --uri content://settings/secure --bind name:s:com.android.server.display.HBM_SETTING_KEY --bind value:i:1`（注意 value:i:1 是 int 绑定）。另有 high_temp_protect_level 挡位（低/中/高，字符串 '中' 为默认）与 ShellObfTable 的 hp.close_prefix = `settings put secure high_temp_protection_enable 0`。

### 15. 蔡司显示（Zeiss Display）

**类别** 显示 · **证据** [字符串] obf/a00.invokeSuspend · **权限** WRITE_SECURE_SETTINGS / Shizuku

**做什么**：开/关 vivo 蔡司自然显示。

**怎么做**：实现类 com.example.kbattery.obf.a00：写 zeiss_display_enabled，值 '3'（开）/ '0'（关）；同类还处理 high_temp_no_dim。

### 16. 动画优化（Animation Optimization）

**类别** 系统 · **证据** [字符串] 相关类 + classmap · **权限** WRITE_SECURE_SETTINGS（animator_duration_scale 在 global 或 secure，视 ROM）

**做什么**：调节 Android 动画时长倍率。

**怎么做**：实现类 obf/c2 / obf/q00 / obf/s00 / obf/c30：键 animator_duration_scale（备份键 backup_animation_opt），另有 datastore 键 animation_optimization 与 is_first_launch。

### 17. PEM HTP / 温控挡位（pem_htp_level / pem_htp_thred）

**类别** 温控 · **证据** [字节码] obf/m10 + obf/ih 命名空间配对 · **权限** WRITE_SECURE_SETTINGS / Shizuku

**做什么**：写 vivo PEM 的 HTP 等级与阈值，提供低/中/高三挡。

**怎么做**：实现类 com.example.kbattery.obf.m10：写 system 命名空间的 pem_htp_level 与 pem_htp_thred（原始值从 prefs 读，缺失时用默认）。挡位语义字符串：低挡=温控限制最少性能释放最好、中挡=均衡、高挡=温控最严格保护机身。同一对键在 obf/ih（增强）与 obf/n20（高温不降亮）里也被写。

### 18. Monster / Monster Plus / Turbo 模式切换

**类别** 性能模式 · **证据** [字符串] obf/qg + ShellObfTable；[字节码] obf/wg 中 monster_mode_ → global · **权限** WRITE_SECURE_SETTINGS / Shizuku + VivoPerfManager

**做什么**：切换 vivo  Monster 性能模式（Plus 带散热器 / Turbo 无散热器）。

**怎么做**：实现类 obf/qg（EnhancementLibraryScreen 内）：字符串 'monster_plus'、'plus'、'turbo'、'Turbo'、'Plus (带散热器) / Turbo (无散热器)'、'关闭'；monster_plus 开 → 写 monster_mode_ 前缀键（namespace global）+ 调 obf/wn0 的 perfLockAcquire。另有 ShellObfTable perf.set_monster = `settings put system vgame_agreement_consent_state <v>` 与 perf.set_game_plus = `settings put secure game_plus_value <v>`、perf.set_pwr_secure/system = `settings put secure|system power_save_type <v>`。数值字面量 '7'、'5'、'2'、'1'。

### 19. 编译应用（DEX 预编译）

**类别** 编译 · **证据** [字符串] obf/d20 + 编译监控脚本 · **权限** shell / Shizuku

**做什么**：对第三方应用逐个 speed-profile 编译，并监控 dex2oat 进度。

**怎么做**：实现类 com.example.kbattery.obf.d20：先 `pm list packages -3 | sed 's/package://'` 取三方包列表，再逐个 `cmd package compile -m speed-profile -f <pkg>`（120 秒超时逻辑同用户 App）。进度轮询脚本：`echo "COUNT:$(ps -A | grep -E 'dex2oat|dex2oatd' | grep -v grep | wc -l)"`、`echo "PKG:$(ps -A | grep -E 'dex2oat|dex2oatd' | grep -v grep | head -1)"`、`echo "ODEX:$(find /data/dalvik-cache /data/misc/profiles/cur -name '*.odex' 2>/dev/null | wc -l)"`、`pm list packages -3 | wc -l`。增强库里另有 compile_boot → `cmd package bg-dexopt-job`。

### 20. 系统编译（借 system_server fd 读编译输出）

**类别** 编译 · **证据** [字符串] obf/uy.run · **权限** shell / Shizuku（读 /proc/<pid>/fd 需同 uid 或 root）

**做什么**：一个取巧手段：读 /proc/<system_server pid>/fd/1 来拿系统侧编译日志。

**怎么做**：实现类 com.example.kbattery.obf.uy.run()：`while IFS= read -r line; do echo "$line"; done < /proc/$(pidof system_server)/fd/1 2>/dev/null || echo "无法读取系统输出"`，成功提示 '✅ 系统编译任务已提交'，失败 '❌ 执行失败: ' + '未知错误'。

### 21. 模块制作 / 导入 / 导出（Module 系统）

**类别** 模块化 · **证据** [字节码] utils/a 反汇编 · **权限** 执行模块脚本时需 Shizuku

**做什么**：把"开启脚本 + 关闭脚本 + prop 元数据"打成 ZIP 分享，也能导入他人模块并执行。

**怎么做**：实现类 com.example.kbattery.utils.a：ZIP 内固定包含 start.sh（开启）、close.sh（关闭）、<id>.prop；prop 元数据键 name= / miaoshu= / id= / author= / tags=（逗号分隔），'#' 为注释，解析失败 '无法解析模块 ID，请检查 prop 文件格式'。模块信息类 utils/ModuleImporter$ModuleInfo(name, description, id, author, tags)。持久化 data/ModulePreferences（module_preferences / modules）、data/ModuleImportResult(+Error)。导出 zip 名 module_<id>.zip，打不开报 '无法打开文件'。UI：制作模块 / 导入模块 / 分享 Kbattery 模块（经 FileProvider com.example.kbattery.fileprovider）。

### 22. 内存优化（Memory Optimization）

**类别** 内存 · **证据** [字符串] 相关类 + 中文文案 · **权限** Shizuku

**做什么**：执行一段脚本清理内存。

**怎么做**：classmap 中 KEYS: memory_optimization 的类执行 `cat /proc/meminfo` 等（term.meminfo token）；UI 文案 '内存优化执行成功' / '内存优化执行完成，退出代码: ' / '内存优化执行异常: ' / '开始内存优化...'。

### 23. AdbInstaller — 检测"电池"App 并安装

**类别** 辅助 · **证据** [字节码] obf/dd 反汇编 · **权限** Shizuku

**做什么**：枚举已安装应用找 label 含"电池"的 App（配套 App 探测）。

**怎么做**：实现类 com.example.kbattery.obf.dd：b(context) → PackageManager.getInstalledApplications(...) 过滤 label 含 '电池'，返回 AdbInstaller$BatteryAppInfo(packageName=...)；a(...) 用 `pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS` 之类命令（含 ' ' 与 " '" 引号转义、正则 `([^\n]+)` 解析输出）。AppVersionInfo 记录版本。

### 24. Shizuku 权限与远程 Shell 生命周期管理

**类别** 提权 · **证据** [字节码] obf/oy.invoke / obf/ti0.a 反汇编 + MainActivity invoke 目标 · **权限** 用户需先激活 Shizuku Manager

**做什么**：申请 Shizuku 权限，并用它创建 uid 2000 的远程进程执行所有系统命令。

**怎么做**：com.example.kbattery.MainActivity：Shizuku.pingBinder()、Shizuku.checkSelfPermission()、Shizuku.requestPermission()、Shizuku.newProcess(...) → ShizukuRemoteProcess（getOutputStream / waitFor）。com.example.kbattery.utils.SettingsWriter 与 obf/dd 直接调 Shizuku.newProcess + getInputStream + waitFor。反射兜底：obf/oy.invoke() = `Class.forName("rikka.shizuku.Shizuku").getDeclaredMethod("newProcess", String[].class, String[].class, String.class)` + AccessibleObject.setAccessible(true)；obf/ti0.a(cmd: Array<String>) 拿该 Method、构造 Object[] 后 Method.invoke，返回 java.lang.Process。状态管理层：obf/e50（getShizukuActivated）、obf/hv / MainActivity（updateShizukuState 统一回调）、obf/e60（pingBinder）、obf/pi0 / obf/ri0（addBinderReceivedListener / addBinderDeadListener）。Shizuku AIDL 代理类 obf/xs（IShizukuService，含 asBinder/newProcess 相关）、obf/qs（IRemoteProcess，destroy/waitFor/exitCode）。文案：'请先激活 Shizuku'、'无法获取Shizuku权限'、'无法创建Shizuku进程'、'需要 Shizuku 权限执行系统级优化'。未使用 SystemServiceHelper / ShizukuBinderWrapper / UserServiceArgs（dex 里有类型但无 App 侧引用）。

### 25. ForegroundKeepAliveService — 循环重放已开启功能

**类别** 保活 · **证据** [源码] ForegroundKeepAliveService 可读 · **权限** Shizuku

**做什么**：specialUse(keep_alive) 前台服务，按间隔反复执行 library_settings 里开着的功能（直驱供电 / 全局插帧）。

**怎么做**：com.example.kbattery.ForegroundKeepAliveService：PREFS_NAME=library_settings；executeEnabledFeatures() 读 direct_power / global_frame_interpolation，文案 '正在执行: '、'直驱供电'、'全局插帧'、'无启用功能'、'关闭'；createNotificationChannel() 渠道 keep_alive_service_channel（'系统功能服务' / '循环执行已开启的系统功能'），通知 '系统功能运行中' / '正在执行系统功能...'；startForegroundLoop() / fallbackLoop() 双循环。

### 26. 反调试 / 反 Frida 检测

**类别** 自保护 · **证据** [字符串] obf/oo / obf/h7 · **权限** 无

**做什么**：检测 frida-server、frida-agent、gum 线程、TracerPid。

**怎么做**：obf/oo.<clinit> 探测文件 /data/local/tmp/frida-server、/data/local/tmp/re.frida.server、/data/local/tmp/frida-agent-32.so、/data/local/tmp/frida-agent-64.so，以及进程名 'linjector'、'frida'、'gum'。obf/h7：扫 /data/local/tmp、连 127.0.0.1、读 /proc/self/maps、找线程名 'gum-js-loop'、查 'gadget'、列 /proc/self/fd、读 /proc/self/status 并用正则 `TracerPid:\s*(\d+)` 判断是否被 trace。

### 27. 内置 Frida Gadget（assets/frida_gadget.xz + inject.js）

**类别** 自保护/调试 · **证据** [字符串] assets/inject.js 原文（明文 JS）+ frida-gadget.config.json 原文 · **权限** 运行时需先解压 xz 并 System.load（加载代码未在可读类中定位）[未确定]

**做什么**：APK 里自带 frida-gadget（xz 压缩）和一段 inject.js，配置为监听 127.0.0.1:27042。

**怎么做**：assets/frida-gadget.config.json = {"interaction":{"type":"listen","address":"127.0.0.1:27042"}}。assets/inject.js：`Java.use("com.vivo.gameassistant.AssistantUIService").f.value` 偷 Context，然后读 /data/data/com.example.kbattery/files/frida_cmd.txt，按 `key|value` 分割，`Settings$System.putString(ctx.getContentResolver(), key, value)` —— 即"用游戏助手服务的 Context 写 Settings.System"这条备用通道。assets/frida_gadget.xz 为压缩的 gadget so（未解压分析）。

### 28. 远程更新检查

**类别** 其他 · **证据** [字节码] MainActivity 反汇编 · **权限** INTERNET

**做什么**：启动时检查新版本并弹窗，支持 HTML 简易渲染。

**怎么做**：MainActivity：OkHttp GET（User-Agent: Mozilla/5.0 (Linux; Android 12) ... Chrome/120.0.0.0 Mobile Safari/537.36；Accept: text/html,...；Accept-Language: zh-CN,zh;q=0.9,en;q=0.8），分享链 https://sharechain.qq.com/ca3a9cdfc255301103afe5deb332af16；版本号字面量 'v1.27' / '1.27'；HTML 处理正则 `<br\s*/?>`→\n、`<p[^>]*>`→\n、`</p>`→\n、`<[^>]*>`→删除、`&nbsp;`→空格；标题用 【】《》 包裹；文案 '已是最新版本' / '有新版本可用，建议更新' / '获取更新信息为空' / '检查更新失败: '。

### 29. 主题 / 背景 / 毛玻璃个性化

**类别** UI · **证据** [源码] data/* 可读 · **权限** 无

**做什么**：多主题（默认/浅色/深色/赛博朋克/清新蓝白粉少女风/现代简约）、自定义背景图、毛玻璃与液态玻璃效果、动态取色。

**怎么做**：data/ThemeSettings（theme_settings / theme_type）、data/BackgroundSettings（background_settings / background_uri / glass_effect_enabled / primary_color / secondary_color / tertiary_color / is_dark_theme / is_enabled）、data/ExtractedColors、data/MaterialColorScheme；图片经 FileProvider + READ_MEDIA_IMAGES。

### 30. Onboarding 引导 + 每日一句 Widget

**类别** UI · **证据** [源码] DailyQuoteWidget.<clinit> 字符串表 · **权限** 无

**做什么**：首次启动引导页（介绍主要功能），桌面 Widget 每天轮换一句励志/电影台词。

**怎么做**：data/OnboardingPreferences（onboarding_prefs / is_first_launch）、data/OnboardingPage(title, description)、ui/onboarding/OnboardingFeature。com.example.kbattery.DailyQuoteWidget：内置 ~25 条 Quote(text, author)，author 有 '可白'、'阿甘正传'、'千与千寻'、'肖申克的救赎'、'教父'、'这个杀手不太冷' 等；SharedPreferences widget_prefs，键 quote_index；APPWIDGET_UPDATE。

### 31. CryptoUtils 字符串加密（分析障碍）

**类别** 混淆 · **证据** [字节码] CryptoUtils.e / .m 反汇编 + 载荷提取 · **权限** —

**做什么**：把 "system"/"secure"/"global" 等短串加密成 int[]，运行时用滚动密钥解码。

**怎么做**：com.example.kbattery.utils.CryptoUtils：~200 个 static int[] 字段（A,B,C,D,E,F,G,t..z,aa..cz）+ 4 个 lazy 字段 a,b,c,d,e,f,g..s（obf/zx 委托，实参是 obf/c / obf/f7 / obf/g7 / obf/h7 lambda，返回 String[]）。解码器 e(encoded: IntArray): String —— ByteArray(encoded.size)，用 Integer.rotateLeft(0x03C3262B, 7) + 0x6D2B79F5 推导初始密钥，循环内 aget-int 取值、与 668265261(0x27D42DEB) 和索引运算组合、两次 and 0xFF 截断、aput-byte 落字节，密钥再以 Integer.rotateLeft(k, 3) + 374761393(0x5616B167) 滚动，最后 new String(bytes, StandardCharsets.UTF_8)。int[] 常量以 fill-array-data 载荷形式存在（共 90 组，载荷头 ident=0x0300, element_width=4）。命名空间映射已由 SettingsWriter.m 的 when 分支确定：d()="system"、b()="secure"、a()="global"。

## 明确查不出的东西（8 条）

- CryptoUtils 的完整字符串表未解出。e(IntArray) 是滚动密钥解码（初始密钥 = Integer.rotateLeft(0x03C3262B,7)+0x6D2B79F5，迭代 = Integer.rotateLeft(k,3)+374761393），我提取了全部 90 组 fill-array-data 载荷，但没有写 Dalvik 解释器去跑循环体，因此除"system/secure/global"三元组（由 SettingsWriter.m 的 when 分支间接确定）外的加密串（尤其是各处 settings 的 value 值，如 monster_mode_ 的具体档位数字）无法确认。已确认的只有散落在可读类里的明文字面量：'1','2','5','7','272','1036','1,7,7,7','50-52-58-1000-1000-1000','1800','1,1,true','0:-1:0:0:0','1:1:0:60:120','1:1:0:72:144'。
- VivoPerfManager perfLockAcquire 的 duration 参数值未确定。clinit 里出现常量 9、61456(0xF010)、4095(0xFFF)、16512(0x4080)、0L，但没能把它们与调用点的寄存器稳定对应起来，因此 perf lock 的持有时长 [未确定]。
- VivoRmsManager 这个类在 dex 里不存在（只有嵌套的 rms/VivoRmsManager$PutResult），真正的写入实现在 obf/xn0、解析在 obf/bo0、连写协程在 obf/zn0。因此"VivoRmsManager" 的公开方法签名 [未确定]。
- GameWatchBinder.buildArgPack 的确切字节布局只恢复到类型分派级别（String[]/int[]/float/boolean/long/byte/int/long[]/String/IBinder，数组用 ',' 分隔），每个 executor 对应的 Parcel 字段顺序 [未确定]。
- assets/frida_gadget.xz 未解压（34 MB APK 里的压缩 blob），gadget 的加载代码（System.load / 解压 xz 的位置）在可读类里没定位到 [未确定]。inject.js 本身是明文，内容确定。
- 关于 CPU 频率：只找到两条 sysfs 命令（续航优化把 scaling_min_freq 写 0；增强库把 scaling_max_freq 抄给 scaling_min_freq），没有找到任何对 scaling_max_freq 的上限压制、也没有 PowerHAL/perfhal 直调（dex 里无 vendor.powerhal / IPowerHAL 引用）。所以"Kbattery 是否限制最高频率"答案是：只通过 min=max 变相锁频，不动 max。
- 是否走 root / su：dex 中没有 'su'、'magisk'、'su -c' 字样，提权仅靠 Shizuku + 声明权限 + pm grant 自助授权 [未确定是否有未出现的 root 路径，倾向没有]。
- com.example.kbattery.obf.* 约 1486 个类名不可读（a/a0/a00/b/b0/b1/... 单双字母），其中相当一部分是 androidx 兼容 shim（如 obf/os 是 INotificationSideChannel 的 AIDL Default）。我只分析了含业务字符串/引用的那部分，纯 UI 装饰类未逐一看。
