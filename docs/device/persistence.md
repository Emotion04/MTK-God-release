# 本软件写入的系统改动：活多久

本页回答一个问题：App 写进系统的东西，在**重启手机、强停 App、停 Shizuku、卸载 App**
之后还在不在。界面开关、恢复原值、升级迁移都依赖这个答案。

记录格式：只写**实测过**的内容，写清日期、命令、前后读数。没有测过的格子写“未测”，
不按常识推断 —— 这一页存在的意义就是不让“按理说应该”变成“事实”。

## 当前结论

| 改动类型 | 重启手机 | 强停 App | 停 Shizuku | 卸载 App |
|---|---|---|---|---|
| `settings` 键（`content insert` 写入） | **保留** | 保留 | 保留（未单独测） | **保留**（2026-10-18 实测） |
| `setprop` 的非 `persist.*` 属性 | **丢失** | 保留 | 保留（未单独测） | **保留直到重启**（2026-10-18 实测） |
| PowerHAL 频率请求 | 丢失（句柄随进程消亡） | **保留** | 未测 | 历史观察：保留，重启才清（无读数，见 [pitfalls.md](pitfalls.md#13) 第 13 条） |
| 温控策略档位 `0x03000000` | 丢失（2026-09-26 干净重启对照） | **保留**（`am force-stop` 后仍生效） | 未测 | 未测 |
| 固件触控方案（`bbk_touch_screen_service`） | 未测 | 20 秒租约到期自动恢复（设计，未单独观察） | 未测 | 未测 |
| App 自己的本地记录（DataStore / SharedPreferences） | 保留 | 保留 | 保留 | **丢失**（2026-10-18 卸载后确认包已不在） |

**本地记录不随系统值走。** 卸载或清除数据会带走 App 自己的快照、开关偏好和备份引用，
但系统里的 `settings` 值还在 —— 所以"卸载后重新安装"看到的是一台已经被改过的机器，
而 App 以为它是第一次运行。这就是"原值快照必须持久保存、而且要能跨重装找回"的原因。

**关键机制**：频率请求和温控档位的句柄不在 App 进程里，而在 Shizuku 的用户服务进程
（`magicau.mtkcontroller:runtime`，PPID 1）。强停 App 不会结束它，所以“强停 App”和
“停 Shizuku”是两件不同的事，不能互相代替。

## 2026-10-18 实测：设置键与属性跨重启

方法：ADB 用 `content insert` / `setprop` 写四个键，读回；强停 App 后读回；重启手机后读回。

| 键 | 写入前 | 写入后 | 强停 App 后 | 重启后 |
|---|---|---|---|---|
| `settings system touch_scan_press_time_delay` | 300 | 250 | 250 | **250** |
| `settings system pointer_speed` | 7 | 5 | 5 | **5** |
| `debug.touch.report_rate`（prop） | 360 | 240 | 240 | **空** |
| `debug.hwui.disable_vsync`（prop） | true | false | false | **空** |

命令与当时的输出：

```text
content insert --uri content://settings/system --bind name:s:touch_scan_press_time_delay --bind value:s:250
content insert --uri content://settings/system --bind name:s:pointer_speed --bind value:s:5
setprop debug.touch.report_rate 240
setprop debug.hwui.disable_vsync false
settings get system <键> / getprop <键>        # 每步之后读一次
am force-stop magicau.mtkcontroller
```

强停 App 后 `ps -A` 仍能看到 `19138 1 magicau.mtkcontroller:runtime` —— 用户服务没有跟着停。

**结论与适用范围**：`settings` 键跨重启是持久值；`debug.*` 属性不跨重启（只有
`persist.*` 才会）。这一条与 [commands.md](commands.md) 里“重启后 Settings 仍可能是此前
写入的持久值”一致，但那次只有文字没有读数，本次是带前后读数的复核。
**卸载测试（2026-10-18 第二次，成功）**：先用 `content insert` 写
`touch_scan_press_time_delay=250`、`pointer_speed=5`，并 `setprop debug.touch.report_rate 240`；
随后用户卸载 App，`pm list packages magicau.mtkcontroller` 返回空。读回仍是 `250 / 5 / 240`。

结论：**`settings` 键和 prop 都不随 App 卸载消失**；prop 与重启无关的那条限制仍然成立，
只是这次没有重启。App 自己的 DataStore 随卸载丢失，重装后是一台"已经被改过、但 App 不知道"的机器。
测试后已把两个键改回 300 / 7，prop 清空。

## 用途

- **升级与卸载**：`settings` 键不会因为重装而消失，所以“原值”必须能跨重装找回，
  或者明确告诉用户找不回。
- **开关状态**：重启后 `debug.*` 类开关在设备上已经不再生效，而本地记录还写着“开” ——
  这种开关必须从设备读，不能只信本地。
- **重新应用**：重启后需要重放的只有句柄类（频率请求）和属性类；`settings` 键本来就是持久的，
  再写一次是无意义的写操作。
