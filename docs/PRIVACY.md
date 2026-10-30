# 隐私说明

一句话：**App 没有作者服务器，不收集任何遥测。** 你填了什么 Key、聊了什么，只会去你指定的那些服务。

## 一、数据存在哪里

全部在 App 私有目录 `/data/data/com.qqbot.app/files/` 下，只有本 App（和 root / adb 用户）能读：

| 路径 | 内容 |
|---|---|
| `files/bot/.env` | 你填的所有配置：模型地址 / Key、TTS / ASR 凭证、搜索 Key、管理员 ID、人设参数等（**明文**） |
| `files/bot/prompt.txt` | 当前人设提示词 |
| `files/bot/data/` | 对话记忆、长期画像、联系人列表、素材标签、聊天统计 |
| `files/bot/emoji/`、`files/bot/image/` | 你添加的表情包 / 图片 |
| `files/bot/skills/` | 你导入的技能（JS 或进程技能） |
| `files/runtime/` | 解压出来的 Node 运行时 |
| `files/linux/` | （可选）proot + Ubuntu rootfs + 协议端，约 1.5–1.8 GB |
| `files/logs/`、`files/bot/logs/` | 运行日志 |

**卸载 App 会删掉上述全部内容。** 想彻底清干净就是卸载（或「设置 → 应用 → 存储 → 清除数据」）。

### 关于 Android 自动备份

`AndroidManifest.xml` 里 `allowBackup="true"`，意味着系统可能把 App 私有数据备份到你自己的 Google/厂商账号云备份里（这不是作者上传的，而是 Android 系统行为）。介意的话：系统设置里关掉「自动备份」，或者自行改成 `allowBackup="false"` 后重编。

## 二、什么会离开手机

只有下面几类，且目的地址都是**你自己配置的**：

| 去向 | 内容 | 触发时机 |
|---|---|---|
| 模型 API（你自己填的地址） | 聊天上下文、人设提示、抓到的链接正文、图片（若开了看图） | 每次回复 / 主动消息 |
| TTS 服务（你选的：云端或手机本地） | 要合成的文本 | 发语音时 |
| ASR 服务 | 收到的语音（解码后） | 对方发语音且开了语音识别 |
| 搜索 API（Tavily / Serper / Bing / Bocha，可选） | 检索关键词 | 模型判断需要联网时 |
| QQ 官方接口 / 你的协议端 | 消息内容（这是它的本职工作） | 收发消息 |
| 下载源（清华 / 中科大 / 腾讯 CDN / GitHub / hf-mirror 等） | 只下载安装包与模型文件，不上传任何东西 | 安装环境 / 语音模型时 |

除此之外**没有第三方统计、没有崩溃上报、没有广告 SDK、没有作者服务器**。

## 三、权限

| 权限 | 为什么需要 |
|---|---|
| `INTERNET` | 聊天、下载环境、连协议端 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` | 让机器人在后台常驻（按「数据同步」类型声明，Android 14+ 要求） |
| `POST_NOTIFICATIONS` | 常驻通知（保活 + 显示状态） |
| `WAKE_LOCK` | 下载 / 安装时不休眠 |
| `RECEIVE_BOOT_COMPLETED` | 开机自启（可关） |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 引导你关掉省电限制，减少被杀 |

没有申请通讯录、短信、位置、相机、麦克风、无障碍、设备标识等敏感权限。

## 四、安全提示

- **`.env` 是明文**，Key 会直接写进去。手机丢了 / 被 root 了，等于 Key 泄露；别把 `.env` 截图发出去。
- 进程技能可以在 Linux 环境里跑任意命令，**只导入你信得过的技能包**。
- 真实 QQ 接入要扫码登录你的账号，登录态缓存在 `files/linux/rootfs/root/.config/QQ`，同样等于账号凭证，注意保护。
- 协议端默认只监听本机 `127.0.0.1`，不要把 WebUI / OneBot 端口暴露到公网；真要外部访问，至少改成强 token。
- 语音复刻上传的录音会发到对应云服务，涉及你自己的声音权利，别上传别人的录音。

## 五、与平台的关系

- 本 App 不是腾讯、阿里或任何平台的官方产品，未获授权或背书。
- 真实 QQ 接入依赖第三方协议实现（NapCat / Lagrange 等），**可能违反腾讯用户协议**，账号有被限制或封禁风险。
- AI 生成内容、语音复刻、自动化发消息带来的后果由使用者承担。

## 六、数据删除

- **聊天记忆**：在聊天里给机器人发 `/reset`（清当前会话）或 `/resetall`（清全部），说明见 App 内「更多 → 命令说明」
- **日志**：更多 → 运行日志 → 清空
- **素材 / 技能**：在「更多 → 素材 / 高级」里逐项删除
- **手机内 Linux 环境**（Ubuntu + NapCat，约 1.5–1.8 GB）：接入 → 运行环境 → 清空环境
- **全部数据**：卸载 App，或系统里「清除数据」

## 七、相关文档

- 架构与数据流：[ARCHITECTURE.md](ARCHITECTURE.md)
- 第三方组件与许可：[THIRD_PARTY.md](THIRD_PARTY.md)
- 安全边界与问题报告：[../SECURITY.md](../SECURITY.md)
- 常见问题：[FAQ.md](FAQ.md)