# 排错经验

保留仍能改变判断的经验；当前设备能力见 [facts.md](facts.md)，工程约束见 [架构](../dev/architecture.md)。

## 1. 协议摘要曾漏掉释放语义

旧说明漏掉 release flags 和 release-before-acquire 顺序，忠实照抄仍产生了错误。
修改协议或遇到证据冲突时，核对一手实现的 flags、顺序和错误分支，见 [协议](../cpu/protocol.md)。

<a id="2"></a>
## 2. 过早回读会看到半批状态

历史调用后 3–7 ms 的回读曾显示 `min == max == 请求的 min`；稳定后的状态不同。
按当前控制代码等待稳定并比较多个样本，不从单次过早回读判定 API 不能表达区间。

<a id="3"></a>
## 3. 新请求不会自动替换旧请求

当前 MTK 路径使用 `duration=0`。服务合并活跃请求时，下限取最大、上限取最小；
冲突时上限还可能被抬到下限。新增宽范围请求不能撤销旧请求，必须释放对应 handle。
所有权、串行和释放失败保留句柄由 `CpuControl` 负责；丢句柄后的恢复边界见 §13。

## 4. 资源名不能代替区间验证

软限制、hard-limit 与系统限制共同作用，旧请求组合和单值成功不能直接证明新范围有效。
请求组合以 `CpuControl` 源码为准；设备回归比较 [单值基线](../cpu/known-good.md) 和实际读回，
不把某一版组合写成永久禁用某资源的规则。

## 5. oneway 返回不等于释放完成

release 的 Binder 返回只说明提交情况，不证明服务已撤销。
核对协议后，用可用回读与操作前基线比较；读不到时保留未验证状态。
其他请求也可能阻止回落，不能只凭频率未回落断言本次 release 失败。

## 6. 权限位不足以证明可写

可见性还受所有者、实际补充组、SELinux 域等影响。只读排查先保留这些信息；
需要写入测试时确认节点语义、原值和恢复条件。某 governor 的拒绝不能推广到全部 cpufreq 节点。

## 7. 阻塞 IPC 会卡住主线程

远端执行命令并等待返回的 AIDL 调用可能阻塞。设备 IPC、文件和子进程工作在 IO 层执行；
屏幕工作按可见性启停，见 [架构](../dev/architecture.md)。

<a id="8"></a>
## 8. 深色界面需要正确的内容颜色

`MaterialTheme` 本身不负责为每个容器提供 `LocalContentColor`。
根内容使用提供背景和内容颜色的 `Surface`，检查浅色与深色，避免未指定颜色的文字变黑。

## 9. 同一 DataStore 文件共享一个实例

重复创建相同文件的 delegate 会在运行时报错。复用现有 `DataStores` 定义。

<a id="10"></a>
## 10. 反编译 Java 可能缺少真实逻辑

Kotlin suspend 状态机和内联 lambda 的重建可能有损。Java 搜不到调用时，
继续核对 smali 或原始字节码，再判断实现是否不存在。

## 11. ViewModel 存活不等于页面可见

导航保留和 Pager 预加载会让后台页面继续采样。生命周期之外还需考虑当前选中页，
具体边界见 [UI](../dev/ui.md)；有限任务与持续采样分别设计。

<a id="12"></a>
## 12. ignore 规则可能隐藏新源码

历史根目录临时项 `src/` 也匹配了 Android 的源码目录，已跟踪文件正常而新文件被忽略。
仅要匹配仓库根目录时使用 `/src/`；异常时用 `git check-ignore -v <path>` 定位。
通配 `*.jar` 还可能误排除 Gradle wrapper，按实际需要保留例外。

<a id="13"></a>
## 13. 丢失持久请求的 handle 后恢复困难

当前设备曾在 App 卸载、Shizuku 停止后仍保留限制，设备重启后恢复。
这是目标设备的历史观察，不是所有 ROM 的普遍结论，也不证明请求一定由内核持有。
未验证非零期限的 MTK 请求语义，不能照搬另一服务的租约。

`emergencyRestore` 的直接节点写入和平台重算只是补救尝试：
权限可能拒绝，配置回读变化也不等于所有簇恢复到硬件范围。
可靠的常规退出仍是保留并释放自己的 handle，见 [CPU-002](../cpu/facts.md#cpu-002)。

<a id="14"></a>
## 14. 安装记录与运行中的构建分别核对

相同 versionCode 可能允许覆盖安装，不能据此认定安装没做任何事。
交付新 APK 递增版本便于辨认；排查时同时检查包版本和本次启动日志。
历史 logcat 缓冲可能混入旧启动记录，按本次时间和进程过滤；必要时明确重启 App 再核对。

## 15. 方法名不能证明控制语义

`setPriorityByUid`、`flushPriorityRules` 等名称不足以确定作用范围和恢复方式。
正式控件需要可说明的语义与设备支持；可恢复的受控实验另行编排。

## 16. 父目录不可搜索时，里层不存在尚未证明

历史 `/proc/perfmgr` 返回 Permission denied，里层 `thermal_policy` 返回 No such file；
不能仅凭后者断言路径不存在，更不能仅凭该输出确定是哪种权限机制。

2026-10-11 在 V2430A 上复核（`u:object_r:proc_perfmgr:s0`，`dr-xr-xr-x`）：

| 探法 | 结果 |
|---|---|
| App uid `test -r /proc/perfmgr` | 不可读 |
| App uid `test -x /proc/perfmgr` | **可搜索** |
| App uid `cat /proc/perfmgr` | Permission denied |
| App uid `cat /proc/perfmgr/thermal_policy` | No such file or directory |
| shell uid `cat /proc/perfmgr` | Permission denied |

所以这个目录是**搜得动、读不动**。`cat` 对目录要的是读权限，它失败只说明不能列出内容，
不说明不能进 —— 拿 `cat` 的 Permission denied 判"父目录不可搜索"偏保守，
里层那条 ENOENT 在这里其实是真的。判成 UNREADABLE 是安全方向，但要知道它保守在哪儿。

`stat` 成功不等于可列出目录。`Sysfs.states()` 区分 PRESENT / ABSENT / UNREADABLE，
判 ABSENT 前核验父目录可搜索；权限或陌生错误保留 UNREADABLE。
**佐证父目录的那次判词必须和产生 ENOENT 的同源**（都是提权 shell），
不能拿 App 进程的 `File.canRead()` —— SELinux 按域授权，两边可以完全不同。
“未知”也不自动证明控件可用，支持判断还需实际接口证据。
