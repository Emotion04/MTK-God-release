# remote-config.json 字段说明

App 读取的公开只读配置。**源文件是 `tools/remote-config.jsonc`（可写注释）**，
发布时由 `tools/release_updates.py` 去掉注释、校验后生成 `release/public/remote-config.json`。

地址由 `release/config.jsonc` 的 `remoteConfigUrl` 决定，默认：
`https://raw.githubusercontent.com/Emotion04/MTK-God/main/release/public/remote-config.json`

> App 端目前只预留了地址，读取/验签/UI 接线还没做（见 `docs/dev/todo.md`）。先保持字段少。

## 最小文件

```json
{
  "schema": 1,
  "version": 1,
  "channel": "stable",
  "texts": { "notice": "", "download_button": "下载新版", "download_hint": "" },
  "flags": { "enable_update_check": true, "show_lab": true },
  "downloadUrl": "https://github.com/Emotion04/MTK-God/releases/latest/download/app-release.apk"
}
```

## 字段

### 顶层：版本与渠道

| 字段 | 作用 | 建议 |
|---|---|---|
| `schema` | 配置结构版本 | 固定 `1`；App 不认识就忽略整份配置 |
| `version` | 配置版本号 | 每次修改递增；App 只接受比缓存更高的版本，便于回滚 |
| `channel` | 渠道 | `stable` / `canary`；App 只应用自己渠道 |

### `texts`：远程文案

只放白名单 key；空字符串表示用 App 内置文案。

| 字段 | 显示在哪 |
|---|---|
| `notice` | 首页/设置公告，空则不显示 |
| `download_button` | 更新弹窗的下载按钮 |
| `download_hint` | 下载按钮下方的说明 |

### `flags`：功能开关

只控制实验/新入口。**核心释放、恢复、导出不要远程关。**

| 字段 | 作用 |
|---|---|
| `enable_update_check` | 是否启用更新检查 |
| `show_lab` | 实验入口是否显示 |

### 下载

| 字段 | 作用 | 建议 |
|---|---|---|
| `downloadUrl` | 下载地址 | 必须 https；与 update.json 的 `url` 保持一致，可独立改动 |

以后真需要再加：`minVersionCode` / `maxVersionCode`（只对某些版本生效）、
`expiresAt`（过期回退默认值）、`signature` / `keyId`（内容签名，防被篡改）。

## 发布

1. 改 `tools/remote-config.jsonc`，递增 `version`；
2. 随发版自动上线（tag 触发 CI）；想立即生效就跑 `python tools/release_updates.py`
   再提交 `release/public/remote-config.json`；
3. 用 `tools/发布.bat` 上传到七牛时也会一起传。

不要在这里放密钥、用户标识或任意代码。`release/remote-config.example.json` 只是不带注释的参考副本。
