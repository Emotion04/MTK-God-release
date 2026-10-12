# CPU 频率区间早期调查档案

> **历史档案。** 结论以 [cpu-freq-ceiling.md](frequency-ceiling-investigation-through-2026-09-26.md) 为准 ——
> 本文里"区间不带硬下限"等判断已在 2026-09-24 被实测推翻，保留是为了记录推理过程。

## 后续：build 11 / 0.3.8

用户新报告 `mtk-god-report-20711.txt` 确认 build 10 与有效的 MTK Binder 描述符。
用户确认**天花板低于实测厂商边界**的区间现在可以工作。跨越该边界仍然会收窄有效区间；
把整个请求放到边界之上，频率会低于所请求的下限。

具体证据：

- 17:16:16：请求 policy4 1500-2850，读回 1500-2400；policy7 2000-3400 读回 2000-2100 MHz。
  这些是**有效上下限被收窄**，不是界面区间写反了。
- 17:18:14-17：请求 2800-2850 与 2900-3400，读回 2400-2400 与 2100-2100。
- 17:30:55-58：policy4 2200-2400 完全一致，而 policy7 2500-3100 读回 2300-2300。
- 17:34:08：那条未改动的 policy7 请求读回 2600-2600。**所以 2100/2300 不是不可变的硬件上限。**
  报告的 NONE 温控快照不能证明不存在厂商或按簇的温控约束。
- 17:15:38 还有一次低区间短暂塌陷。用户观察到的动态成功与某一瞬时的钉住采样可以并存；
  单个采样不能证明持续时长。

工作假设仍是厂商仲裁 / HAL 簇表钳制，具体写入方不明。日志揭示不了当前 ROM 的 HAL 派发
或在效请求。**不要承诺硬 MIN 能恢复动态高区间**：它改变优先级，可能只是把某个频率钉住。
build 11 保留现在已经能用的资源形状。下一个能区分假设的证据是当前 ROM 的 PowerHAL
资源/上限表，或与 apply/release 相关联的只读厂商日志 —— **不是猜频率 ID**。

build 11 区分了"有效上限低于所请求下限"与"区间内塌陷"这两种情况。即便 min/max 节点读不到，
也会报告当前频率越界 —— 那证明的是一次采样违规，不证明存在固定上限或其成因。

启动时现在会检查 Shizuku，并弹应用内对话框，提供授权/跳过。跳过不请求权限。设置页保留
可点击的未授权横幅；权限状态在 resume 和 Binder 到达时刷新。本修订不引入温控改动，
也不引入新的 CPU 资源 ID。

build 11 验证：宿主机 JVM 测试套件通过（11 个测试，零失败零错误），`assembleDebug`
产出 0.3.8 / versionCode 11。授权界面只验证了编译，未在手机上跑过；
**不声称已修复高区间硬件问题**。

## 当前检查点

MTK God 0.3.7 / versionCode 10。包 `magicau.mtkcontroller`，Kotlin/Compose，
minSdk 33，target/compileSdk 37。目标机型 vivo V2430A / MT6989 / Android 16，
ROM `PD2430D_A_16.1.12.0.W10`，Shizuku ADB uid 2000 并已绑定 daemon UserService。
用户确认定点控制稳定、主应用 bind/release 正常。新的区间路径是修复候选；
**0.3.7 的硬件有效性仍未验证**。

本文取代 handoff、设备记录、架构与协议文档中相互冲突的历史说法。工作区里无关的既有编辑保留。

## 证据

用户提供的 `mtk-god-report-20710.txt` 是 build 9，生成于 2026-09-14。日志时间是 UTC，
报告头部是本地时间；0.3.7 修正了这个不一致。

- 09:50:32：policy4 1100-1400 与 policy7 1300-1700 MHz 三次采样全部一致。
  这证明在当时，软上限/下限确实能影响这些簇。
- 09:53:03：请求 2500-2850 / 3250-3400 MHz，读回 2400-2400 / 2300-2300。
  之后更高的请求重复出现塌陷。有一次采样两个簇都是 1500-1500。
- policy0 的上下限读不到，但当前频率可读。policy4/policy7 的 min/max 与硬件上限可读。
  **"完全读不到"的旧说法是错的。**
- 09:58:54-57：手动核对与轮询核对在时间上重叠。原报告只确认了 PerfLockController
  被锁，没有确认 CpuControl 的完整操作。
- 快照里的 LIGHT 温控状态和 37% 电量，既不能把早前事件归因于温度，
  也不能排除厂商按簇设的上限。

用户给出的约 1800/2400/2300 MHz 边界是观察，不是已证实的官方 OTA 限制。不为它们引入设备常量。

## 假设排序

1. **厂商仲裁或 HAL 簇表钳制**：预言低区间能用、高区间塌陷、系统加速能压过软上限。
2. **应用并发**：预言旧请求重放和来自另一请求的回读。已在代码与日志中确认，用完整操作互斥锁解决。
3. **ID 用错**：但"软 MAX 全局无效"与低区间完全一致矛盾。当前 ROM 是否支持硬资源仍未定论。

## 一手资料核对

2026-09-15 取到公开 BSP 的
[perfservice.cpp](https://github.com/PotatoDevices/vendor_mediatek_opensource/blob/dumaloo-release/hardware/power/lib/powerhal/perfservice.cpp)。
相关函数：`cmdSetting`、`perfScnEnable`、`perfScnUpdate`、`setClusterFreq`、`setClusterHardFreq`。

软约束与硬约束**独立累加**。每一族都可以把自己的上限抬到自己最强的下限。
在若干后端上，软写入还会把"达到或超过 HAL 表 `freqMax`"的上限翻译成一个被忽略的帽值。
这份源码支持某个机制存在，但指认不出 vivo 的私有实现。

~~单独一个硬上限不等于定点锁。这推动了新的区间形状：MIN=lo、MAX=hi、MAX_HL=hi。
区间请求不带 MIN_HL；定点请求保留四项相等资源。~~
**2026-09-24 已被真机实测推翻**：硬上下限在系统包络内**能**做区间
（policy7 上 800000-1600000 完全符合预期），只有超出包络后才退化成钉死。
区间请求现在带硬下限，见 [cpu-freq-ceiling.md](frequency-ceiling-investigation-through-2026-09-26.md) §13。

当前 ROM 是否支持某个资源，
必须依据回读判断，不能只凭拿到了 handle。公开 BSP 的 legacy proc 路径不能证明现代 ROM 的底层路径。
App 读失败不能证明厂商服务也访问不了该资源。

## 应用侧改动

- CPU 互斥锁覆盖请求替换、持久化与核对。轮询在锁内读 settings，
  所以旧的重放不能在释放之后让设置复活。
- 变更记账一旦开始就不可取消。取消可能打断后续采样；此时状态保持"未验证"，
  并记录一个可释放的 handle。
- 持久化归一化后的设置；拒绝重复/未知的 policy 与非法值。
- 最后一次缺失的采样仍记为未知，不拿早前的成功充数。
- 批量读取包含 min/max/current、每个 policy 以及提权后的兜底读取。
- `Outcome.success` 描述的是请求操作本身。`Status.verification` 记录已验证、被收窄、
  冲突或读不到。单次采样永远不能证明持续的动态行为。平台给出的更低上限不被覆盖。
- 轮询先核对，可读边界已满足就跳过替换。读不到时保留尽力而为的重放。轮询不是持续强制。
- 三种资源配对都是合法的：早前"奇数配对一律拒绝"的判断是错的。
- 描述符回复里直接是字符串，不是异常头。
- 命名 handle 的写入移除了旧的 legacy 键，防止已释放的 handle 复活。
- 释放成功但替换失败时，清掉旧的重放设置；释放失败则保留旧 handle 与设置，并阻止重新获取。
- DEBUG 记录命令数组与实际经过的采样时间；INFO 记录变化的观测；WARN 记录变化的冲突。
  报告带最后一次请求/状态，即便常规日志被过滤。日志 ID 在缓冲区锁下分配。
- 诊断读到的 null 表示读不到或不存在，不再断言"不存在"。

oneway 释放只确认提交成功；保留的 200 ms 延迟是个启发式，不是顺序屏障。
取消安全性并不能让 Binder 与磁盘写入在进程死亡或存储故障时保持原子。

## 构建与验证

在 `mtk-optimizer` 下运行 `gradlew.bat testDebugUnitTest assembleDebug`。
工作站 JDK：`D:/ProgramCS/Android/Android Studio/jbr`。本地缓存与 API 37 SDK
在已忽略的 `.gradle-user`、`.android-user`、`.android-sdk` 里。
相应设置 `JAVA_HOME`、`GRADLE_USER_HOME`、`ANDROID_USER_HOME`；
`local.properties` 里是已忽略的机器本地 SDK 路径。

JVM 测试通过注入读取、存储、延迟与传输，跑的是生产代码 CpuControl 与 PerfLockController。
覆盖观测到的塌陷、缺失采样、命令归一化、apply/release/轮询重叠以及取消记账。
**不模拟联发科芯片，也不模拟私有 HAL。**

最终本地验证：`testDebugUnitTest assembleDebug --offline --no-configuration-cache`
于 2026-09-15 通过。九个测试，零失败零错误。
APK：`mtk-optimizer/app/build/outputs/apk/debug/app-debug.apk`，0.3.7（build 10）。
**未做真机测试。** 空白检查通过，Windows CRLF 已识别。

待办真机 A/B：稳定的单点请求/释放、一个低区间、前台加速加 30-60 秒负载、
一个跨边界的区间，然后释放。若观察到陈旧状态，干净的基线有用；本地开发不需要重启。
若硬 MAX 被忽略，在猜其它 ID 之前先查当前 ROM 的资源派发与厂商日志。**不要关闭温控保护。**

保留 daemon 请求 owner 与独立的 lab handle。GPU/lab 仍未验证。
参考资料与 APK 保持忽略。为产物可追溯性递增 versionCode ——
Android 可以替换同版本 APK，与旧注释里的说法相反。

