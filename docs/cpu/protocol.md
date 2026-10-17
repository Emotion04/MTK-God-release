# MediaTek PowerHAL 协议参考

这里解释调用格式和公开 BSP 中的资源语义。具体 ROM 是否支持、请求是否有效，见 [CPU 当前事实](facts.md)；完整资源与原始出处见 [resources.md](resources.md)。公开 BSP 的节点、合并规则和资源名不能直接当作当前手机的实现。

## 1. 服务与调用者

服务名是 `power_hal_mgr_service`，接口 token 是 `com.mediatek.powerhalmgr.IPowerHalMgr`。
当前 App 的 acquire/release 由 Shizuku `RuntimeUserService` 实际发起，保持稳定调用者身份与句柄所有权；`PowerHal` 封装命令和结果。

<a name="2"></a>
## 2. 事务格式

```text
perfLockAcquire(handle, duration, int[] list) -> handle
perfLockRelease(handle)                     // oneway
querySysInfo(type, param) -> int
```

```text
acquire:
  writeInterfaceToken(IFACE)
  writeInt(0)                              // 新请求
  writeInt(duration)
  writeIntArray([cmdId, value, cmdId, value, ...])
  transact(ACQUIRE, data, reply, 0)
  reply.readException()
  handle = reply.readInt()

release:
  writeInterfaceToken(IFACE)
  writeInt(handle)
  transact(RELEASE, data, null, FLAG_ONEWAY)

query:
  writeInterfaceToken(IFACE)
  writeInt(type)
  writeInt(param)
  transact(QUERY, data, reply, 0)
  reply.readException()
  value = reply.readInt()
```

数组长度必须为偶数。正句柄表示服务接受了请求；效果需要另外读回。
oneway 的返回值只说明提交情况，等待固定毫秒数也不是服务端完成屏障。

公开实现把 `duration=0` 定义为不限时，当前正式 CPU 请求使用此值并主动释放。

**非零期限会在本机自动到期（2026-10-15 真机实测）。** 发一个 `duration=20000ms` 的软上限
请求（policy7 → 1400000），**不发任何释放命令**，读回随时间变化：

| 时刻 | `policy7/scaling_max_freq` |
|---|---|
| T+3s / T+8s / T+13s / T+18s | 1400000（请求仍然生效） |
| T+22s / T+26s / T+30s | **2300000**（自己回到了请求前的值） |

另一次用 `duration=8000ms` 测的也在 10 秒内回到原值。到期时刻和请求的 duration 对得上。

**这条承重于实验室的安全网**：`LabController.TEST_DURATION_MS = 20_000` 之外的一切
短时测试都指望固件自己收尾，界面上也因此对用户承诺"就算 App 崩了、句柄丢了，也不会留下
卡死的频率"。实测成立，所以那个承诺是兑现得了的。

vivo 那个性能管理接口的租约证据不适用于这个服务 —— 这条结论来自本服务自己的实测。

<a name="transaction-codes-are-version-dependent-"></a>
### 事务码取决于接口版本

| 方法 | 目标 ROM 的已核验布局 | 另一份 vendor framework |
|---|---:|---:|
| acquire | 22 | 23 |
| release | 23 | 24 |
| query | 24 | 25 |

事务码由 AIDL 方法顺序决定。当前源码仍使用目标布局的常量，运行时识别尚未全部落地；
换 ROM 时先核对 framework 中的 `TRANSACTION_*` 和接口格式，见 [适配边界](../dev/architecture.md#transaction-code-detection)。

## 3. 资源 ID 与簇索引

公开资源表及本地 `COMMAND,MAJOR,MINOR,GROUP` 表采用：

```text
id = (MAJOR << 22) | (MINOR << 14) | (GROUP << 8)
CPU 簇资源 = base + index * 0x100
```

在目标机中，扫描得到的 `policy0/4/7` 对应 index `0/1/2`。
policy 目录数字不是资源索引；新平台需核对扫描顺序与厂商资源表。
已经是完整 opcode 的资源不再加簇偏移。

## 4. CPU 频率资源

| 基址 | 公开 BSP 名称 | 单位与含义 |
|---|---|---|
| `0x00400000` | `CPUFREQ_MIN_CLUSTER_n` | kHz，软下限 |
| `0x00404000` | `CPUFREQ_MAX_CLUSTER_n` | kHz，软上限 |
| `0x00408000` | `CPUFREQ_MIN_HL_CLUSTER_n` | kHz，独立硬下限 |
| `0x0040C000` | `CPUFREQ_MAX_HL_CLUSTER_n` | kHz，独立硬上限 |

`HL` 在这些公开头文件中是 hard limit。部分厂商表还使用 `MAX_THERMAL` 等名称，
名称相似不足以证明两者在所有平台上是同一个资源或同一个内核落点。

公开单元测试与 powerhint XML 有非对称 MIN/MAX 请求，说明 API 能表达区间。
`CpuControl.applyLocked` 的普通区间**默认只发软的一组**，单值把它们设为同一值；
只要控上限或只要控下限的模式由该函数选择对应资源。硬的一组由设置项
「附带硬上下限」控制，默认关 —— 本机实测软硬等效，见 [facts.md](facts.md) CPU-006。
请求形状以源码为准，不在多篇文档维护副本。
硬下限在包络外可能表现为钉频，不能由此推断“硬上下限必然只能单值”。

## 5. 其他资源的单位与适用范围

公开 BSP 中，CPU 频率通常取 kHz，GPU/DRAM 常取 OPP index，核心资源取在线核数。
具体单位、枚举和取值范围需要对应版本的表或实现；正句柄不能用于反推这些语义。

`CPUFREQ_PERF_MODE` 在部分公开实现中会请求各簇最高频，与区间请求可能竞争。
目标 ROM 的实际效果见事实页，不把这一实现泛化到全部设备。
OEM 的 `0x10000000+` 扩展按本机资源表解释。

## 6. 内核落点与请求竞争

旧 BSP 曾使用 `/proc/ppm/policy/hard_userlimit_cpu_freq`、
`userlimit_min_cpu_freq`、`userlimit_max_cpu_freq` 等节点。
新平台可能有不同实现；节点不存在或不可读不能证明对应 HAL 资源无效。
当前可读范围见 [设备事实](../device/facts.md)。

部分公开 `perfservice.cpp` 按以下方式合并 scenario：

```c
floor = max(all floors);
ceiling = min(all ceilings);
if (ceiling < floor) ceiling = floor;
```

这是特定实现的规则。区间塌缩还可能涉及系统 boost、温控或其他请求；
只有对照请求所有权、释放结果和运行状态，才能归因。
保存、替换与释放的工程约束见 [CPU 请求所有权](../dev/architecture.md#cpu-请求所有权)。

## 7. Binder 身份与适配

`INTERFACE_TRANSACTION = 0x5F4E5446` 用于查询 descriptor，标准 Binder 路径使用空 Parcel。
本项目通过 `IBinder.transact()` 查询，而不是把 wrapper 的 `getInterfaceDescriptor()` 当成有效结果。
descriptor 符合 MTK token 只确认接口身份，不确认事务布局、资源支持或权限。

适配优先读取 ROM 接口和常量，再调用格式已知的只读查询。
向未知事务码发送空 Parcel 不能普遍保证无副作用，不作为默认扫描方法。
身份或布局不符时，应报告不支持或未确认，不能把 acquire 成功作为试探接口的方法。

`0x40804000`、`0x40804100` 等是另一套 Qualcomm MPCTLV3 资源命名空间，
不是 MTK `0x0040xxxx` 的偏移版本；同一厂商 framework 可能同时包含两种平台变体。

<a name="11-id-drift-between-bsp-revisions"></a>
## 8. BSP 之间的资源漂移

同一个逻辑资源在不同表中可能有不同 ID。例如部分 scheduler 表的
`0x0143C100` / `0x0143C200` 存在名称与位置分歧。
新平台先按对应表核对，再决定是否适配；当前完整 opcode 不自动成为跨机型接口。

<a name="sources"></a>
## 来源

- `mtkperf_resource.h`：资源编码与 CPU 四组资源名。
- `perfservice_types.h`、公开 `perfservice.cpp`：scenario 字段与特定 BSP 的合并实现。
- perfservice 单元测试、vendor `powerhint.xml`：非对称 MIN/MAX 请求。
- vendor framework 的 `IPowerHalMgr`：接口 token、方法顺序与 Parcel 格式。
- 本项目 `RuntimeUserService.kt`、`PowerHal.kt`、`CpuControl.kt`：当前调用与编排。

原始公开链接集中在 [资源表来源](resources.md#sources)；真机验证集中在对应事实页。

