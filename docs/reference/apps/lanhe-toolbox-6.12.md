# 蓝河工具箱 6.12 — 固定版本实现摘录

> 分析对象：`D:\CCode\MTK\reference\sources\2026-10-b1\蓝河工具箱6.12.apk`
>
只记录该版本的接口、命令与来源，不是本项目的功能计划。源码摘录与推断按条目标识；本机效果需独立核验。

---

## 身份

| 项 | 值 |
|---|---|
| 名称 | 蓝河工具箱 (BlueRiver / BlueToolbox) |
| 包名 / 模块 ID | com.bluestudiocjd.app |
| 版本 | 6.12 (versionCode 72), minSdk 24 / target 34 |
| 形态 | APK（单 classes.dex 3.67 MB，armeabi-v8a only） |
| 提权路径 | Shizuku（直连 IShizukuService binder，未用 SDK newProcess）+ 自研无线 ADB（libadb.so SPAKE2 配对 + Kotlin ADB 协议客户端）；同时声明 WRITE_SECURE_SETTINGS 但未作为主路径。执行模式 execution_mode：A 或 SETTINGS_PUT = shell settings put；B = content insert（默认） |
| 界面技术栈 | 原生 Kotlin + Jetpack Compose(material3) + Hilt + Room + Navigation-Compose + WorkManager，无 Flutter/WebView |

## 功能清单（47 项）

### 1. 双提权通道调度（AUTO/SHIZUKU/WIRELESS_DEBUG）

**类别** 基础设施/执行层 · **证据** [字节码] LL1/T.l / LL1/c.a / LM1/a 反汇编 + [字符串] ADB_EXIT_CODE · **权限** 无线调试配对 或 Shizuku

**做什么**：所有系统修改收敛到一个 shell 执行器；优先自研无线 ADB，退化到 Shizuku。枚举 LK1/b={SHIZUKU,WIRELESS_DEBUG,AUTO}，默认 AUTO

**怎么做**：入口 LL1/T.l(String command) -> LC2/g(exitCode, output)。读 activation_preference/preferred_method；执行 command + "; echo ADB_EXIT_CODE=$?"；用正则 ADB_EXIT_CODE=(\d+)\s*$ 抽退出码并从 stdout 尾部切掉。底层 LL1/c.a(String)->LL1/l 走 ADB shell: 流。ADB 未连抛 IllegalStateException("ADB not connected")。R8 混淆后的真实类名：LL1/T=com.bluestudiocjd.app.adb.WirelessDebuggingManager，LL1/c=shell session，LL1/l=result，LM1/a=ADB protocol connection。

### 2. Shizuku 直连 binder（绕开 SDK 封装）

**类别** 基础设施/执行层 · **证据** [字节码] Lc2/e 反汇编 · **权限** Shizuku

**做什么**：不用 Shizuku SDK 的 Shizuku.newProcess()，自己 queryLocalInterface 拿 IShizukuService 直接 AIDL 调用 newProcess

**怎么做**：Lc2/e：Shizuku.getBinder() -> IBinder.queryLocalInterface("moe.shizuku.server.IShizukuService") -> Lmoe/shizuku/server/g;.k([Ljava/lang/String; [Ljava/lang/String; Ljava/lang/String;)（即 newProcess(String[] cmd,String[] envp,String dir)）。取返回对象 stdin/stdout/stderr 三个 ParcelFileDescriptor，写 command + "\nexit\n"，BufferedReader(8192) 读 stdout/stderr，exitValue() 取退出码。权限：Shizuku.requestPermission(1001) + addRequestPermissionResultListener/addBinderReceivedListener/addBinderDeadListener。

### 3. libadb.so 自研无线调试配对（SPAKE2）

**类别** 基础设施/执行层 · **证据** [字节码] PairingContext native 方法表 + [字符串] libadb.so 符号 · **权限** 需要系统「无线调试」里使用配对码配对设备

**做什么**：用原生代码实现 Android 11+ 无线调试的配对协议，与 Shizuku 无关，是自研而非复用

**怎么做**：lib/arm64-v8a/libadb.so，90 KB，自带 BoringSSL。JNI 绑定 com.bluestudiocjd.app.adb.PairingContext，native 方法：nativeConstructor(J[B)J、nativeMsg(J)[B、nativeInitCipher(J[B)Z、nativeEncrypt(J[B)[B、nativeDecrypt(J[B)[B、nativeDestroy(J)V。内部符号 AdbPairClient/adb pair client/adb pair server，SPAKE2 + HKDF + info 字符串 "adb pairing_auth aes-128-gcm key"，EVP_AEAD_CTX（AES-128-GCM），随机数走 /dev/urandom 与 getrandom。

### 4. ADB 协议客户端（Kotlin 自研）

**类别** 基础设施/执行层 · **证据** [字节码] LL1/V / LL1/g / LL1/e / LL1/k / LL1/i / LM1/a · **权限** 配对过的无线调试连接

**做什么**：不依赖 adb 可执行文件，自己跑 ADB wire protocol 拿 shell

**怎么做**：LL1/V（PairingClient）：javax.net.ssl.SSLSocket + TLSv1.3，发 "host:" 头，用 SSLSession.exportKeyingMaterial 导出密钥给 SPAKE2。LL1/g 生成 RSA 密钥（AndroidKeyStore，alias "adbkey"），私钥用 AES/GCM/NoPadding（key alias "_adbkey_encryption_key_"）加密后存 SharedPreferences(adb_keys)，公钥 base64 存 adbkey；X509 自签证书 alias "adb-key"（LL1/e 实现 X509ExtendedKeyManager）。LL1/k 封装 NsdManager，服务类型 "_adb-tls-connect._tcp"，LL1/i 做 NsdServiceInfo.resolveService。LM1/a 实现完整 ADB 协议：A_CNXN(host:: + features)、A_AUTH(RSA/ECB/NoPadding 签名 token)、A_STLS 升级 TLS、A_OPEN("shell:")、A_WRTE/A_OKAY/A_CLSE，Conscrypt 提供 TLSv1.2/1.3。

### 5. 两套 settings 写入通道 + 命令拼装

**类别** 基础设施/执行层 · **证据** [字节码] LK1/W1.d/e/f/g/A0/z 反汇编 · **权限** shell（Shizuku 或无线 ADB）

**做什么**：A 方案=shell settings put；B 方案（默认，UI 叫「绕封杀方案」）= content insert 走 SettingsProvider.insert，绕过部分 ROM 对 settings 二进制的拦截

**怎么做**：A0(String) 做 POSIX 单引号转义：' + s.replace("'", "'\\''") + '。f(ns,key,value) -> "settings put " + ns + " " + key + " " + value（拼空格）。g(ns,key,value) -> "content insert --uri content://settings/" + ns + " --bind name:s:" + key + " --bind value:s:" + value（"\"->\\\" 与 ":"->"\\:" 额外转义）。z(List) 用 " && " 连接多条命令。h(ns,key) -> "settings get " + ns + " " + key。模式由 Lr3/k.Y(Context) 读 activation_preference/execution_mode（默认 "B"）决定：值为 "A" 或 "SETTINGS_PUT" 走 settings put，否则走 content insert。

### 6. 写后回读验证 + 删除重写

**类别** 基础设施/执行层 · **证据** [字节码] LK1/W1.B/Y/a0/Z/b0/C0 反汇编 · **权限** shell

**做什么**：每次写入后回读比对，失败就换通道重试；UI 称之为「功能开启验证」

**怎么做**：LK1/W1.B(ns,key,value) 是带校验的重写器：按模式调 Y(...)（settings put 批）或 a0(...)（content insert 批）→ delay(150 ms) → Z(ns,key)/b0(ns,key) 回读比对 → 不符则 C0(ns) 删键后换通道重试（循环上限 8）。LK1/W1.C(ns,key,value,flag) 是无校验直写版。读值有两种：t(ns,key) 走 settings get；u(ns,key) 走 content call --uri content://settings/<ns> --method GET_<Table> --arg <key>，再从结果里截 value= 到 }] 之间。

### 7. 批量读取（单次 echo 子 shell）

**类别** 基础设施/执行层 · **证据** [字节码] LK1/W1.P/echo 反汇编 · **权限** shell

**做什么**：把多条 settings get 合成一条 echo $(...) 命令一次执行，避免 N 次 shell 往返

**怎么做**：P() 拼一条 shell："echo $(" + "settings get system <k1>" + "),$(" + "settings get system <k2>" + "),$(" + "settings get secure <k3>" ... 一次 shell 调用读回所有值，再按 "," split。用于「保存（分享）数据」导出当前所有状态。

### 8. libintegrity.so 签名与 dex 哈希校验

**类别** 基础设施/安全 · **证据** [字节码] NativeIntegrity 反汇编 + [字符串] libintegrity.so 符号 · **权限** 无

**做什么**：校验 APK 签名证书 SHA-256 与 dex SHA-256，不通过就 finishAndRemoveTask()

**怎么做**：NativeIntegrity.isTrustedDexHash()：算 classes.dex 的 SHA-256，去掉":"并 toUpperCase 后传给 native b(String)Z 与内置期望值比；isTrustedSignature() 同套路用证书 SHA-256。libintegrity.so 只有 4.9 KB，JNI 类 com/bluestudiocjd/app/security/NativeIntegrity，方法签名 (Ljava/lang/String;)Z，期望值以乱码常量打散在 .rodata。

### 9. Root / Xposed / Frida / Substrate 环境检测

**类别** 基础设施/安全 · **证据** [字节码] BlueStudioCJDApplication onCreate + [字符串] 路径列表 · **权限** 无

**做什么**：反调试/反 hook，命中任意一项就弹「应用环境异常，请使用官方安装包」并自杀

**怎么做**：在 Application.onCreate 里读 /proc/self/maps 与 /proc/self/status 的 TracerPid:，并检查这些路径/类是否存在：/data/adb/lspd、/data/adb/magisk、/data/adb/modules/zygisk_lsposed、/system/framework/XposedBridge.jar、/sbin/su、/system/bin/su、/system/xbin/su、/data/local/tmp/frida-server、/data/local/tmp/re.frida.server，以及 com.topjohnwu.magisk、me.weishu.kernelsu、eu.chainfire.supersu、org.lsposed.lspd.impl.LSPosedBridge、de.robv.android.xposed.XposedBridge、com.saurik.substrate.MS$2、re.frida.server.Frida。命中即判定环境异常。

### 10. Monster+（复刻Monster+模式）

**类别** 增强功能 · **证据** [字节码] LK1/W1.m0/n0 + LK1/I0（运行时解密） · **权限** shell

**做什么**：复刻 vivo/iQOO 的 Monster+ 性能模式；UI 建议 iQOO 用户用 M+

**怎么做**：LK1/I0 用 int[]+xor 55 的方式在 <clinit> 里现场解密出三个键名（我从 dex 字节直接解出）：I0.a = system_property_power_mode_type（secure 表）、I0.b = game_plus_mode_key（system 表）、I0.c = power_save_type（system+secure 两表都写）。开启时 B("1","8","5")：secure system_property_power_mode_type=1；system game_plus_mode_key=8；system power_save_type=5；secure power_save_type=5。关闭时 B("0","1","1")。写入/回读都走 B() 的八次重试通道。

### 11. BOOST+（BOOST+ 模式）

**类别** 增强功能 · **证据** [字节码] LK1/W1.q0 + LK1/I0 · **权限** shell

**做什么**：Monster+ 的更强档；UI 建议 vivo 用户用 B+

**怎么做**：同一个 B(ns,key,value) 写四条，唯一差别是 game_plus_mode_key=7：secure system_property_power_mode_type=1；system game_plus_mode_key=7；system power_save_type=5；secure power_save_type=5。关闭同样 B("0","1","1")。

### 12. 全局插帧 / 高刷固定（vivo MEMC）

**类别** 增强功能 · **证据** [字节码] LK1/W1.A/h0/LK1/C1 反汇编 · **权限** shell

**做什么**：打开 iQOO/vivo 游戏魔盒的 MEMC 帧率倍增；可选 120/144 Hz 两档，另有常驻的「高刷固定」入口

**怎么做**：h0(enabled, refreshRate) 组装三个 LC2/k 三元组交给批量写 D()：(system, gamecube_frame_interpolation, X)、(system, nova_memc_status, Y)、(global, game_memc_request_touch_rate, Z)。X：关闭="0:-1:0:0:0"；开启且 rate==144 -> "1:1:0:48:144"；否则（含 120 与其它值）-> "1:1:0:30:120"。Y = enabled?"1":"0"。Z = enabled?"180":"0"。回读只校验 gamecube_frame_interpolation，启用标志 = X != "0:-1:0:0:0"。

### 13. 视效增强 / 视效增强+

**类别** 增强功能 · **证据** [字节码] LK1/W1.k0 反汇编 · **权限** shell

**做什么**：全局画质超分+色彩增强；会先把原值存 secure/system 备份再写

**怎么做**：写 (system, gamecube_hawkeye_effective, "1") 与 (system, vivo_display_color_mode, "271")；关闭/恢复时从 SharedPreferences(advanced_features) 取出备份 global_visual_enhancement_hawkeye_original 与 global_visual_enhancement_color_mode_original 写回（原值为 0 与空/271 对比）。

### 14. 触控拉满

**类别** 增强功能 · **证据** [字节码] LK1/W1.y0/U 反汇编 · **权限** shell

**做什么**：提高触控采样率档位。一条 settings 就够，比用户现在的 bbk_touch_screen_service 事务 17 + UserService 续期简单得多，但生命周期/持久化大概率不如用户的方案

**怎么做**：写 (system, vts_game_para_adjust, "1,7,7,7")，关闭写 "0,3,3,3"。原值先存 SharedPreferences(advanced_features) 的 touch_enhancement_backup_value（可恢复）。

### 15. 直驱供电 / 直驱供电+

**类别** 增强功能 · **证据** [字节码] LK1/W1.g0 反汇编 · **权限** shell

**做什么**：直驱供电（绕过电池直供主板）。作者注明代码来自「可白」

**怎么做**：g0(enabled) 批量写：(secure, settings_battery_charge_director, "1,1,true"/"0,0,false")、(secure, power_engine_and_plugged_state_and_charging_aod, "1"/"0")、(secure, settings_battery_charge_director_game_cube, "1"/"0")（第三项仅当该键当前非空才写）。另外会跑 dumpsys battery 抓 level:\s*(\d+) 判定电量，并涉及 system vivo_keyguard_super_speed_charge_switch 与 smart_charge_upper_value=100 / vivo_keyguard_show_charge_mode_switch 的恢复。G() 是纯校验函数：读 secure settings_battery_charge_director 含 "1,1,true" 且 secure power_engine_and_plugged_state_and_charging_aod=="1" 才算开。

### 16. 阅读模式

**类别** 增强功能 · **证据** [字节码] LK1/W1.t0 反汇编 · **权限** shell（WRITE_SECURE_SETTINGS 也可以）

**做什么**：全局灰度护眼

**怎么做**：先读原值 t("secure","accessibility_display_daltonizer") 与 t("secure","accessibility_display_daltonizer_enabled")，存进 SharedPreferences(advanced_features) 的 reading_mode_backup / has_valid_backup；然后写 (secure, accessibility_display_daltonizer_enabled, "1")、(secure, accessibility_display_daltonizer, "0")（daltonizer=0 即灰度）。关闭时按 backup 还原。

### 17. 极暗模式

**类别** 增强功能 · **证据** [字节码] LK1/W1.z0/V 反汇编 · **权限** shell

**做什么**：进一步压暗最低亮度（系统「降低亮度」开关）

**怎么做**：写 (secure, reduce_bright_colors_activated, "1")，关闭写 "0"；z0(Z) 是开关入口，V() 是回读校验。

### 18. 不锁屏（插电时常亮）

**类别** 增强功能 · **证据** [字节码] Lf2/h2 / LK1/W1.C/u0/s 反汇编 · **权限** shell

**做什么**：屏幕永不锁定；充电时保持常亮

**怎么做**：开：(global, stay_on_while_plugged_in, "15") + (system, screen_off_timeout, <UI 传入毫秒>)；关：(global, stay_on_while_plugged_in, "0") + 恢复 system screen_off_timeout 原值。u0(long ms, boolean) 内部是 String.valueOf((int)ms) 写 system screen_off_timeout；s() 负责读当前值保存。

### 19. 屏幕锁定时间 / 屏幕超时设置

**类别** 增强功能 · **证据** [字节码] Lf2/e2 / LK1/W1.C 反汇编 · **权限** shell

**做什么**：任意指定息屏时间

**怎么做**：C("system","screen_off_timeout", String.valueOf(seconds*1000))；小于 10 秒直接拒绝（"不支持10秒以下的锁定时间"）。

### 20. 动画微操大师（三档动画倍率）

**类别** 增强功能 · **证据** [字节码] LK1/W1.m0/n0/q0 + Lf2/K0/O0/P0 反汇编 · **权限** shell

**做什么**：精细调节窗口/过渡/Animator 动画速度，滑动停留 1 秒自动应用，双击「当前速度」恢复 1

**怎么做**：Lf2/K0/O0/P0 用 t("global","animator_duration_scale")、t("global","window_animation_scale")、t("global","transition_animation_scale") 读当前三个 scale，UI 是「慢←滑条→快」；写入同样是这三个 global 键。注意 m0/n0/q0 这三个 VM 方法在字节码里其实是同一个 B() 包装（值 "1"/"0"、"8"/"1"、"5"/"1"），动画 scale 的真实下发走 Lf2/O0/P0 的 C() 通道。

### 21. 彩色充电动画

**类别** 增强功能 · **证据** [字节码] LK1/W1.e0 反汇编 · **权限** shell

**做什么**：开启彩色充电动画，关闭时恢复原值

**怎么做**：写 (system, flash_charge_animation_style, "10")；关闭时把 SharedPreferences(advanced_features) 里 colorful_charging_animation_original_value 读出来写回。

### 22. Scene 激活（帧率测试工具联动）

**类别** 增强功能 · **证据** [字节码] LK1/W1.c + Lf2/b/a 反汇编 · **权限** shell + 已装 Scene(com.omarea.vtools)

**做什么**：拉起 Scene 的高权限服务以便做帧率测试

**怎么做**：c() 检查 Shizuku 就绪（"服务未准备就绪"）后执行单条命令：sh /storage/emulated/0/Android/data/com.omarea.vtools/up.sh，成功弹 Scene激活成功/失败/出错。

### 23. BMW控制中心主题

**类别** 外观/主题 · **证据** [字节码] LK1/W1.d0/E 反汇编 · **权限** shell

**做什么**：把控制中心主题改成 BMW 样式

**怎么做**：写 (system, vivo_qs_center_theme_style, "2")；恢复时用 SharedPreferences(advanced_features).bwm_qs_center_theme_backup_value。

### 24. 灰底主题

**类别** 外观/主题 · **证据** [字节码] LK1/W1.l0/J 反汇编 · **权限** shell

**做什么**：把自带应用设置页底色改成深灰

**怎么做**：l0(bool) 在 "whole" 与 "general" 之间切换后写 (system, theme_style, <值>)；J() 回读 system theme_style 用 equalsIgnoreCase("whole") 校验。

### 25. 状态栏显示秒

**类别** 外观/状态栏 · **证据** [字节码] LK1/W1.v0/R 反汇编 · **权限** shell 或 WRITE_SECURE_SETTINGS

**做什么**：状态栏时钟精确到秒

**怎么做**：写 (secure, clock_seconds, "1")，关闭 "0"；R() 回读校验。

### 26. 隐藏状态栏图标

**类别** 外观/状态栏 · **证据** [字节码] LK1/W1.w0/S + Lm2/m.N/y/j 反汇编 · **权限** shell

**做什么**：精确隐藏指定状态栏图标（含 Monster+ 图标）

**怎么做**：w0(iconName, hide) 先 t("secure","icon_blacklist") 读当前逗号分隔列表，split(",")→trim→LinkedHashSet 增删目标项，再 join(",") 写回 secure icon_blacklist（字符串 "null" 当空值哨兵）。可隐藏的 9 个图标：monster_mode、mute、volume、alarm_clock、wifi、vpn、vivo_headset、no_sim、zen；每项状态另存 SharedPreferences(advanced_features) 的 status_bar_icon_hidden_<name>。

### 27. 锁屏指纹图标隐藏

**类别** 外观/锁屏 · **证据** [字节码] Lf2/J0 反汇编 + [字符串] 键名 · **权限** shell

**做什么**：隐藏锁屏上的指纹图标

**怎么做**：三个 system 键：fingerprint_icon_style_resource_a、udfp_fingerprint_icon_style、light_cover_customize_fp_icon。开启前把原值存 SharedPreferences 的 bule_fingerprint_icon_style_resource_a_backup 与 bule_udfp_fingerprint_icon_style_backup（用零宽空格 + "#split#" 分隔），关闭时还原。

### 28. 关闭旋转建议

**类别** 外观 · **证据** [字节码] Lf2/i0 反汇编 + [字符串] 键名 · **权限** shell 或跳转系统设置

**做什么**：关掉旋转屏幕时左下角弹出的旋转提示图标

**怎么做**：SharedPreferences(rotation_suggestions_prefs) 的 rotation_suggestions_disabled_enabled 开关；实际下发是系统「显示旋转建议」开关（通过跳转 or 通道写入）。

### 29. 隐藏后台（最近任务隐藏）

**类别** 外观 · **证据** [字节码] MainActivity.onResume/i + LK1/c0 反汇编 · **权限** 无（本地 API）

**做什么**：在最近任务里隐藏蓝河工具箱自身

**怎么做**：MainActivity 里 ActivityManager.getAppTasks() → AppTask.setExcludeFromRecents(background_hidden_enabled)，纯本地 API 无需 shell；未激活时 UI 会先触发 LL1/T.x(ctx,flag) 拉起配对。

### 30. 禁止软件联网

**类别** 应用/网络 · **证据** [字节码] LK1/W1.s0/W/x0 反汇编 · **权限** shell

**做什么**：按包名切断应用网络（重启失效，需重新设置）

**怎么做**：s0(pkg, enabled) 拼 "cmd connectivity set-package-networking-enabled " + pkg + " " + (enabled?"true":"false") 执行；选包列表来自 PackageManager getInstalledPackages(GET_PERMISSIONS) 过滤含 android.permission.INTERNET 的应用；已选包存 SharedPreferences(app_network_block_prefs).selected_packages / active_packages / enabled / enabled_timestamp。

### 31. 禁止系统升级联网

**类别** 应用/网络 · **证据** [字节码] LK1/W1.x0/T 反汇编 · **权限** shell

**做什么**：阻止系统后台自动下载更新；chain3 = 系统联网总闸 + com.bbk.updater 单独断网

**怎么做**：x0(enabled) 一次执行两条："cmd connectivity set-chain3-enabled " + bool 与 "cmd connectivity set-package-networking-enabled com.bbk.updater " + bool。T() 是状态回读：跑 cmd connectivity get-chain3-enabled 与 cmd connectivity get-package-networking-enabled com.bbk.updater，从输出找 "enabled"/"allow"/"1"/"true" 判定。

### 32. 暂停（冻结）应用

**类别** 应用管理 · **证据** [字节码] LK1/W1.B0/l/m 反汇编 · **权限** shell

**做什么**：冻结/解冻用户应用与系统应用

**怎么做**：B0(pkg, suspend)："cmd package suspend --user 0 " + pkg / "cmd package unsuspend --user 0 " + pkg。另有 l(pkg)="pm disable-user --user 0 "+pkg、m(pkg)="pm enable --user 0 "+pkg、q(pkg)="am force-stop "+pkg、j(pkg)="pm list packages | grep "+pkg、x(pkg)="pm path "+pkg。系统应用暂停前有二次确认对话框（黑屏/死机警告）。

### 33. 原子随身听Max（白名单加应用）

**类别** 应用管理 · **证据** [字节码] Lf2/S1/Lf2/O1 反汇编 · **权限** shell

**做什么**：把应用加进 vivo 原子随身听后台白名单（重启后需重设）

**怎么做**：写 (system, musicwidget_list_pkg_type_key, <由选中包名拼成的值>)；读取用 t("system","musicwidget_list_pkg_type_key")，遇 "null" 视为空后重建列表。具体 value 拼接格式在字节码里由多个 StringBuilder 分支拼出，[未确定] 精确分隔符。

### 34. 手机改名（无需验证）

**类别** 应用管理 · **证据** [字节码] LK1/W1.i + Lf2/t0/v/w 反汇编 · **权限** shell

**做什么**：修改蓝牙/热点显示的设备名

**怎么做**：写 (global, vivo_user_define_device_name, <新名称>)；读取 t 同样的键，Lf2/t0 用 substring + 去 " 显示当前名。无需联网验证。

### 35. 假装电量 / 假装充电 / 假装没充电

**类别** 系统工具 · **证据** [字节码] Lf2/T0/U0/V0/W0/W 反汇编 · **权限** shell

**做什么**：伪造电量百分比与充电状态，可一键恢复

**怎么做**：T0(level)："dumpsys battery set level " + level（UI 1%~100% 滑条）；U0()："dumpsys battery set status 2" + "dumpsys battery set usb 1"；V0()："dumpsys battery set status 4" + "dumpsys battery set usb 0"；W0()："dumpsys battery reset"。全部走 X(cmd) 执行器。

### 36. 电池状态分析（bugreport 解析）

**类别** 系统工具 · **证据** [字节码] LN1/i/LN1/e/LN1/c + Lj2/D 反汇编 + [字符串] 正则 · **权限** 无（纯本地文件解析）

**做什么**：解析 bugreport zip 得出双电芯容量、循环次数、满电容量、学习容量

**怎么做**：解压用户选的 vivo/iQOO bugreport zip，按 entry 名分流（LN1/c 枚举：MainEntry/Bugreport/LastKmsg/AtCmd/HistoryCache/Version）。核心正则（US_ASCII 逐行扫）：\bbattery0\s*:\s*avg\s*=(\d+).*?\bbattery1\s*:\s*avg\s*=(\d+)、\bhealthd:\s*battery\b.*\bcc=(\d+)、.*\bfc=(\d+)、\bvbat\s*=\s*(-?\d+).*?\bvcell1\s*=\s*(-?\d+).*?\bvcell2\s*=\s*(-?\d+)、\bval\s*=\s*(0x[0-9a-f]+).*?\bindex\s*=\s*42\s*\(\s*battery_capacity_learning_value_avg__battery0\s*\)（index 50 对应 battery1）。另抓 "vivo dual battery learning avg="、"charge_full_design="、"default_capacity_mah="、"capacity_mah_from_battery_health="、"power_bigdata_capacity_max="、"Estimated/Last/Max/Min learned battery capacity:"、ro.vivo.internet.name / ro.vivo.market.name。结果存 Room 表 battery_health_report_history，字段 designCapacity/fullChargeCapacity/lastLearnedCapacity/minLearnedCapacity/maxLearnedCapacity/estimatedCapacity/cycleCount，出厂容量纠正存本地 SharedPreferences battery_design_capacity_overrides。

### 37. 拨号暗码（9 项）

**类别** 系统工具 · **证据** [字节码] Lj2/V.c packed-switch payload + LK1.Y 索引 · **权限** 无（ACTION_DIAL 即可）

**做什么**：一键跳拨号盘输入暗码进隐藏工程菜单

**怎么做**：点击后 startActivity(Intent.ACTION_DIAL, Uri.parse("tel:"+code))，自动补 *# 前缀（UI 提示「会自动键入 *#*#112#*#*，请进入日志信息采集页面后点更多→生成错误报告」）。我直接从 dex 解出 packed-switch 的 index→code 映射（用 *#*#112#*#* 与 *#06# 两个已知点交叉验证过）：0 网络设置=*#*#2288#*#*、1 视频彩铃=*#*#86583#*#*、2 日志信息采集=*#*#112#*#*、3 高刷固定=*#*#4244#*#*、4 快捷查看iemi=*#06#、5 设备信息=*#09#、6 版本测试=*#225#、7 工程菜单=*#558#、8 手机温度查看=*#*#113#*#*。

### 38. 隐藏系统通知（系统通知管理）

**类别** 系统工具 · **证据** [字节码] NotificationHiderService onNotificationPosted 反汇编 · **权限** 通知访问权限（人为授权）

**做什么**：隐藏系统关不掉的通知，可查看历史通知、可恢复

**怎么做**：BIND_NOTIFICATION_LISTENER_SERVICE 的 NotificationListenerService；onNotificationPosted 拿 sbn.getKey()/getPackageName()/getApplicationInfo()，读通知 extras 的 android.title/android.text/android.bigText，按 key 维护隐藏集合；通过通知访问权限授权后由服务在后台 cancel 通知。

### 39. 悬浮窗引导 + 系统广告关闭引导

**类别** 系统工具 · **证据** [字节码] FloatingWindowService / Lj2.d / LP1.b 反汇编 + [字符串] 长文案 · **权限** SYSTEM_ALERT_WINDOW

**做什么**：悬浮窗步骤提示，引导用户逐项关闭 OriginOS 各处广告/推荐

**怎么做**：FloatingWindowService（foregroundServiceType SPECIAL_USE，floating_window_channel）用 WindowManager TYPE_APPLICATION_OVERLAY 加可拖动悬浮窗；LP1/b 内置 11 个系统应用的逐级关闭广告路径长文案（信息/天气/应用商店/全局搜索/文件管理/日历/智慧桌面/浏览器/相册/设置/钱包），Lj2/d 按包名+类名 startActivity 跳过去同时悬浮窗提示步骤。

### 40. 原生/系统设置页跳转（省电管理、内存管理、开发者选项、通知管理、网络设置、运营商视频通话、使用统计、WiFi 密码、智慧桌面、深空音效、遥控器等）

**类别** 系统工具 · **证据** [字节码] LP1/b / LP1/d / LK1/N 反汇编 + [字符串] 组件名 · **权限** 无

**做什么**：调起系统隐藏/原生设置页与 vivo 系 App 页面

**怎么做**：LP1/b 内置包名+类名表：com.android.filemanager.FileManagerActivity、com.android.mms、com.bbk.appstore.ui.AppStore、com.bbk.calendar.MainActivity、com.bbk.launcher2.Launcher、com.vivo.browser.BrowserActivity、com.vivo.gallery、com.vivo.globalsearch.SearchActivity、com.vivo.wallet.StartPageActivity、com.vivo.weather.WeatherMain、com.android.settings.Settings$*。LP1/d 是原生设置页映射（com.android.settings.Settings 的 WifiSettingsActivity/BatterySaverSettingsActivity/AppMemoryUsageActivity/DevelopmentSettingsDashboardActivity/UsageStatsActivity、ConfigureNotificationSettingsActivity、MobileNetworkActivity）。LK1/N：深空音效=com.vivo.audiofx.controlpanel.MainActivity；设备控制器=com.android.systemui.controls.ui.ControlsActivity；另有 com.vivo.settings.VivoSubSettings、com.vivo.vhome.ui。

### 41. 应用图标显隐

**类别** 系统工具 · **证据** [字节码] Lf2/k2 + setComponentEnabledSetting 字符串 · **权限** 无

**做什么**：切换桌面图标别名显示/隐藏

**怎么做**：Lf2/k2 调 LK1/W1.w0(String,Z)（同一个 icon_blacklist 写入口）+ PackageManager.setComponentEnabledSetting/getComponentEnabledSetting 切换自身组件别名图标；成功后 Toast「图标已显示/已隐藏/图标设置失败」。

### 42. 临时脚本执行器

**类别** 系统工具 · **证据** [字节码] LK1/W1.p + rm/echo/chmod/sh 字符串 · **权限** shell

**做什么**：落一个临时 sh 脚本再 chmod+执行+删除

**怎么做**：p(String scriptBody)：写 /data/local/tmp/bluestudio_temp_script_<n>.sh（内容 "#!/system/bin/sh\n" + 逐行 echo '<line>' >>），chmod +x 后 sh 执行，最后 rm -f 删除。用于跑一段复杂脚本而不是单条命令。

### 43. 清除系统更新提醒

**类别** 系统工具 · **证据** [字节码] Lf2/a0/b0/R1 反汇编 · **权限** shell

**做什么**：去掉已出现的系统更新红点/提醒

**怎么做**：清除系统更新提醒：C("system","bbk_update_notice","-1",...)。

### 44. 功能守护者（后台保活+自动恢复）

**类别** 守护 · **证据** [字节码] FunctionGuardianService / Lb2/r / Lm2/m 反汇编 · **权限** shell

**做什么**：后台周期性检查六个功能状态，被系统覆盖就重新下发

**怎么做**：FunctionGuardianService（foregroundServiceType SPECIAL_USE，property 描述 "Monitor and restore system function states (Monster+, Frame Interpolation) in background"）用 AlarmManager.setAlarmClock(AlarmClockInfo, PendingIntent) 做最抗 Doze 的定时唤醒，配 PowerManager.newWakeLock(...,"BlueRiver:FunctionGuardianWakeLock")；intent action 有 com.bluestudiocjd.app.service.ACTION_CHECK_NOW / ACTION_STOP_GUARDIAN / ACTION_USER_STOP_GUARDIAN。频率存 function_guardian_interval_minutes / _seconds，统计存 total_checks / restore_count / last_check_time。恢复逻辑在 Lm2/m：按 guard_monster_plus / guard_monster_plus_max / guard_frame_interpolation / guard_global_visual_enhancement / guard_touch_enhancement / guard_direct_charging 逐个调回 LK1/W1 的 K/M/H/I/U/G 及 m0/q0/h0/k0/y0/g0/p0/r0。WorkManager 侧有 GuardianStartWorker、WirelessReconnectWorker（开机/网络恢复自动重连无线调试，读 activation_preference/preferred_method=AUTO）。

### 45. 更新检查（主源+备用源）

**类别** 联网/更新 · **证据** [字节码] LQ1/l / UpdateConfig / LK1/o0 反汇编 + [字符串] URL · **权限** 无

**做什么**：Gitee/CNB 双源拉 version.json 检查新版并弹更新日志

**怎么做**：主源 https://gitee.com/cjd_plus/bule/raw/master/version.json，备用源 https://cnb.cool/cjd_plus/lanhe/-/git/raw/main/version.json（Retrofit UpdateApiService.getVersionInfo/testConnection）。UpdateConfig 字段 updateUrl/configVersion/autoCheck/checkInterval/ignoredVersions；本地状态存 update_config 的 dialog_shown_count/dialog_shown_version/has_update/ignored_versions/last_check_time/last_sync_time/pending_update_version。

### 46. 口令联网校验

**类别** 联网/激活 · **证据** [字节码] LK1/o0 / MainActivity 反汇编 · **权限** 无

**做什么**：首次使用要求输入抖音群口令做在线校验

**怎么做**：首进需联网核对「免费口令」（口令在抖音群）；LK1/o0 走网络请求拿验证信息，成功「验证成功，欢迎使用蓝河工具箱」，失败「口令错误，请获取正确口令后重试」。Hook 点：MainActivity.onCreate 先跑 NativeIntegrity + 环境检测，再读 advanced_features.background_hidden_enabled 调 setExcludeFromRecents，再读 theme_prefs.theme_mode / rage_mode_enabled。

### 47. 控制中心 QS Tile ×9 + 桌面 Widget + ShortcutToggleActivity

**类别** 快捷入口 · **证据** [字节码] AndroidManifest + 各 TileService onStartListening/onClick · **权限** shell（tile 点击后同样走执行器）

**做什么**：把主要开关做成磁贴/桌面快捷方式/桌面小部件

**怎么做**：9 个 QS TileService（均 BIND_QUICK_SETTINGS_TILE）：DeviceControlTileService(设备控制器)、DirectChargingTileService(直驱供电+)、FrameInterpolationTileService(全局插帧)、MonsterPlusModeTileService(Monster+)、MonsterUltraModeTileService(BOOST+)、ReadingModeTileService(阅读模式)、UltraDarkModeTileService(极暗模式)、KeepScreenOnTileService(不锁屏)、RemoteControlTileService(遥控器)、GlobalVisualEnhancementTileService(视效增强+)、TouchEnhancementTileService(触控拉满)。另有桌面 AppWidget QuickFunctionWidgetAdaptiveProvider（action com.bluestudiocjd.app.widget.adaptive.DIRECT_CHARGING / FRAME_INTERPOLATION / MONSTER_ULTRA）和 exported 的 ShortcutToggleActivity（excludeFromRecents，切换 BOOST+/Monster+/全局插帧/直驱供电 并 Toast 已开启/已关闭）。

## 明确查不出的东西（11 条）

- 「原子随身听Max」写入 system musicwidget_list_pkg_type_key 时 value 的精确拼接格式没完全解出（字节码里是多个 StringBuilder 分支拼出的），需要实测 settings get system musicwidget_list_pkg_type_key 对比前后值才能确定。
- 直驱供电 g0() 里 smart_charge_upper_value=100、vivo_keyguard_show_charge_mode_switch、system vivo_keyguard_super_speed_charge_switch、secure power_engine_and_plugged_state_and_charging_aod 这几个键的具体生效条件/先后顺序只读到调用关系，没逐个确认写入值（部分值来自运行时变量）。
- 「不锁屏」开启时 system screen_off_timeout 具体写多少毫秒是从 UI（Lf2/f2 屏幕超时设置对话框）传进来的运行时值，dex 里没有硬编码常数；屏幕锁定时间同理。
- 「动画微操大师」慢/快滑条具体把 animator/window/transition_animation_scale 写成的数值集合没逐个解出（VM 方法 m0/n0/q0 在字节码里实际包装的是 Monster+/BOOST+ 的 B() 写四键调用，动画 scale 的真实下发在 Lf2/K0/O0/P0 的读与 C() 里，具体滑条→数值映射是 UI 侧计算）。
- R8 混淆后所有业务类都是 a/a、b1/c 这类短名，我是靠 const-string + 调用关系 + SharedPreferences 键名反推类职责的；类名到源码文件名的对应关系无法还原（.dex 里没有 source_file 之外的映射，dexdump 只给 SourceFile 字符串）。
- 「高刷固定」这个卡片本身只是拨号暗码入口（*#*#4244#*#*），与「全局插帧」的 120/144Hz 选择是两个不同东西——我没有找到任何 min_refresh_rate / peak_refresh_rate / rms 服务事务的写入痕迹，确认本 App 不写刷新率锁。
- 蓝河工具箱没有任何亮度直写（screen_brightness / screen_brightness_mode / vivo_lcm_brightness_policy_disable 均未出现），它的「极暗模式」只是 secure reduce_bright_colors_activated=1。
- 「触发/HTP/温控」相关（pem_htp_level / pem_htp_thred / gamethermal）在本 dex 里完全不存在，确认未实现。
- 「禁止系统升级联网」的 chain3 具体是哪个网络通道没查（只是照抄 cmd connectivity set-chain3-enabled 的行为）。
- libadb.so 是否 100% 自研无法 100% 断言；但其 JNI 类名是 com.bluestudiocjd/app/adb/PairingContext、内部符号是 AdbPairClient，且 Shizuku 生态无此库，判定为自研/移植 AOSP pairing_auth.cc。
- 更新/口令校验服务端接口的完整请求响应格式没有抓包，只从 UpdateConfig/UpdateInfo 字段与 URL 反推。
