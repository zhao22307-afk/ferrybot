# 架构与源码地图

面向想改代码的人：App 里有哪些层、消息怎么流、运行时资产怎么铺到磁盘上。
只想编译的话看 [构建说明.md](../构建说明.md)；只想用的话看 [README](../README.md)。

## 一、分层

```
┌───────────────────────────────────────────────────────────┐
│ Compose UI（ui/*.kt）                                      │
│   首页 / 对话 / 接入 / 更多 + 二级页；表单状态 FormState    │
└───────────────┬───────────────────────────────────────────┘
                │ AppViewModel（配置 / 人设 / 模型 / 自检 / 统计）
┌───────────────┴───────────────────────────────────────────┐
│ 服务层                                                     │
│   BotService   前台服务，spawn 内置 Node 跑 bot.mjs         │
│   ProtoManager 协议端进程单例（跨页面存活、服务重启恢复）    │
│   LocalTts     本地 Piper 的 HTTP 桥（127.0.0.1:17891）     │
└───────────────┬───────────────────────────────────────────┘
                │ 子进程 + .env + 日志
┌───────────────┴───────────────────────────────────────────┐
│ 运行时层                                                   │
│   Node 24（arm64，离线内置） → assets/bot/ 机器人 JS        │
│   proot + Ubuntu rootfs（可选）→ NapCat / LinuxQQ / Piper   │
└───────────────────────────────────────────────────────────┘
```

* App 进程里只有 Kotlin 代码；机器人逻辑全部在子进程 `node bot.mjs` 里，两者只通过
  **`.env`（配置）+ 标准输出（日志）+ 机器人自己写的 `data/` 文件**交互。
* UI 与 Node 之间没有本地 HTTP/API 端口；唯一的本机端口是 Kotlin 侧给 Node 用的
  Piper 桥（`127.0.0.1:17891`），以及（可选的）NapCat `3001` / WebUI `6099`。

## 二、Kotlin 源码地图

路径都在 `app/src/main/java/com/qqbot/app/`。

| 文件 | 职责 |
|---|---|
| `MainActivity.kt` | Compose 入口；4 个 Tab（首页 / 对话 / 接入 / 更多）+ 二级页导航骨架 |
| `BotService.kt` | 前台服务：铺运行时、spawn Node + `bot.mjs`、转发日志；正常启动返回 `START_STICKY`（异常路径返回 `START_NOT_STICKY`）、开机自启 |
| `BootReceiver.kt` | 开机广播 → 若开了自启则拉起 `BotService` |
| `ProtoManager.kt` | 协议端进程单例：跨 Activity 存活，记录「用户想要它运行」的标记，服务重启后自动恢复 |
| `ProtoConfig.kt` | 生成/写入 NapCat 的 `webui.json` 与 `onebot11.json`（端口、token） |
| `NapCatQr.kt` | 走 NapCat WebUI API 取登录二维码，用 ZXing 画成 PNG 给界面 |
| `EnvManager.kt` | 把 `assets/runtime/**` 铺到 `filesDir/runtime/`；负责 `BOT_CODE_VERSION` / `RUNTIME_VERSION` 版本戳 |
| `EnvInstaller.kt` | 通用安装器：按 `assets/installers/*.json` 描述下载 / 校验 / 解压 / 跑安装步骤 |
| `LinuxEnv.kt` | proot 环境：rootfs 解压、拼 proot 命令行、执行命令、环境变量 |
| `DebExtract.kt` | 从 `.deb` 里挑文件（ar + xz/gzip + tar） |
| `LocalTts.kt` | 本地 Piper 的 HTTP 桥，监听 `127.0.0.1:17891`，给 Node 侧调用 |
| `VoiceEnroll.kt` | 云端声音复刻：上传录音、创建音色 |
| `Skills.kt` | 技能目录：扫描 JS 技能与进程技能、启用/停用/删除 |
| `ConfigStore.kt` | 配置读写：`.env` 的解析与生成、人设预设、默认值 |
| `ModelCatalog.kt` | 拉取 OpenAI 兼容服务的模型列表；校验 QQ 官方机器人 AppID/AppSecret |
| `LogText.kt` | 日志清洗：去 ANSI、去噪声行 |
| `ui/AppViewModel.kt` | 界面状态总入口：配置 / 人设 / 模型 / 自检 / 素材 / 统计 |
| `ui/FormState.kt` | 表单数据类 + `toEnvMap()` / `from()`，是 UI 与 `.env` 的映射层 |
| `ui/InstallerEngine.kt` | 安装器状态机：下载、解压、日志节流、取消、安装前空间预检 |
| `ui/HomeScreen.kt` | 首页：状态卡、启停按钮、今日统计、快捷入口 |
| `ui/ChatTab.kt` | 对话设置：人设 / 主动消息 / 消息 / 语音 / 记忆 |
| `ui/AccessTab.kt` | 接入设置：接入方式、模型、运行环境（协议端安装 + 扫码） |
| `ui/MoreTab.kt` | 更多：素材、高级设置、命令说明、日志入口、关于 |
| `ui/PersonaScreen.kt` | 人设编辑页 |
| `ui/SetupScreen.kt` | 首次使用引导 |
| `ui/LogScreen.kt` | 运行日志查看 |
| `ui/CommandsScreen.kt` | 内置命令说明 |
| `ui/Diagnostics.kt` | 自检项与今日统计（纯逻辑，从 ViewModel 抽出便于测试） |
| `ui/Kit.kt` | 公共组件：`GroupCard`、`SegmentedChips`、`PasswordBox`、`InputBox`、`SaveBar` 等 |
| `ui/Theme.kt` | 颜色、排版、深色模式 |
| `ui/AboutScreen.kt` | 关于页（版本号从 `packageManager` 读） |

## 三、机器人 JS 地图

路径在 `app/src/main/assets/bot/`，纯 ESM，无构建步骤。

| 文件 | 职责 |
|---|---|
| `bot.mjs` | 入口；`main()` 里按 `CHANNEL` 选官方 QQ 或 OneBot，注册消息处理 |
| `lib/config.mjs` | 所有配置从环境变量读（`.env` 由 Kotlin 写好后注入），含默认值与派生路径 |
| `lib/transport.mjs` | 传输层统一出口；**其它模块只 import 它**，不直接碰 `qq.mjs` / `onebot.mjs` |
| `lib/qq.mjs` | QQ 官方机器人：AccessToken、WebSocket 网关、发消息、富媒体上传 |
| `lib/onebot.mjs` | OneBot v11 客户端：连 `ws://…`、收发事件、调 API |
| `lib/llm.mjs` | 模型调用（OpenAI 兼容）、提示词组装、工具/搜索触发 |
| `lib/memory.mjs` | 短记忆（最近 N 条）+ 长期画像（facts） |
| `lib/contacts.mjs` | 最近聊过的人（主动消息目标） |
| `lib/emoji.mjs` | 表情包挑选与概率附带 |
| `lib/link.mjs` | 提取链接、抓正文、收藏链接库 |
| `lib/media.mjs` | 图片/语音/文件的下载、SILK 编解码、语音条上传 |
| `lib/search.mjs` | 联网搜索（Tavily / Serper / Bing / Bocha） |
| `lib/skill.mjs` | JS 技能加载与调用 |
| `lib/procskill.mjs` | 进程技能：拼命令、在 Linux 环境执行、收集输出 |
| `lib/store.mjs` | `data/` 下的持久化（记忆、画像、联系人、统计、素材标签） |
| `lib/log.mjs` | 日志文件写入与行数控制 |
| `skills/*.mjs` | 内置示例技能（coin / dice / joke / ping） |

约定：

- 只有 `lib/transport.mjs` 知道当前是「官方」还是「OneBot」；写业务逻辑时别判断 `CHANNEL`。
- 所有配置都从 `lib/config.mjs` 读，不要硬编码地址、Key、端口。
- 日志用 `lib/log.mjs` 写文件（App 的日志页会读它），别直接 `console.log` 到爆炸。

## 四、配置与数据流

```
UI 表单 FormState
   │ toEnvMap()
   ▼
ConfigStore ──写──▶ files/bot/.env ──读取──▶ Node 进程环境变量 ──▶ lib/config.mjs
   ▲                                                  │
   └────────── from(读当前值回填界面) ◀───────────────┘
```

- `.env` 是唯一事实来源；界面只是它的编辑器，所以你手改 `.env` 再重启机器人也会生效。
- 记忆、画像、联系人、素材标签、统计由 JS 侧写到 `files/bot/data/`；表情包/图片在
  `files/bot/emoji/`、`files/bot/image/`；技能在 `files/bot/skills/`。
- 人设提示词落在 `files/bot/prompt.txt`（首次由 `ConfigStore.DEFAULT_PROMPT` 生成）。

## 五、内置 Node 运行时怎么铺到磁盘

`assets/runtime/` 里为减小安装包做了两层压缩/改名：

| 资产 | 磁盘还原为 | 说明 |
|---|---|---|
| `node.xz` | `files/runtime/node` | 45,318,136 字节的 Node 24.18.0（arm64），`EnvManager.NODE_EXPECT` 校验大小 |
| `libicudata.so.78.xz` | `files/runtime/libicudata.so.78` | ICU 数据，约 33 MB |
| `libcrypto.so.3`、`libssl.so.3`、`libicui18n.so.78` 等 | 原名 | 直接拷贝 |
| `*.so.zz` | 去掉 `.zz` 后缀 | 强制高压缩的 arm64 库（libc++、libsqlite3、libcares） |

- 版本戳：`EnvManager.BOT_CODE_VERSION` 管 `assets/bot/**`，`RUNTIME_VERSION` 管 `assets/runtime/**`；
  改完必须递增，否则已装设备不会重铺。
- 为什么不用 Android 的 `extractNativeLibs`：这些不是 APK 的 JNI 库，是给子进程 `node` 用的
  普通 ELF，必须按原样落到可执行目录。
- 为什么 `androidResources { noCompress += "xz" }`：xz 已经压过，再让 AGP 压一遍没意义且解压更慢。

## 六、两条接入链路

**A. QQ 官方机器人（`CHANNEL=official`，默认）**

```
bot.mjs → transport → qq.mjs
   ├─ POST https://api.bot.qq.com/app/getAppAccessToken（AppID/AppSecret 换 token）
   ├─ WebSocket 网关连官方，订阅单聊消息 + 群 @ 消息（intents 1<<25）
   └─ 收到事件 → llm.mjs 生成回复 → 发消息 / 上传富媒体
```

**B. 真实 QQ（`CHANNEL=onebot`）**

```
bot.mjs → transport → onebot.mjs ──ws──▶ 协议端（NapCat / Lagrange）
                                          └─ 它再和 QQ 服务器通信
```

协议端有两种放法：

1. 跑在电脑 / 服务器上：App 里填 `ws://IP:3001` + token；
2. 跑在手机里：`EnvInstaller` 装 Ubuntu + NapCat，`ProtoManager` 用 proot 起进程，
   `ProtoConfig` 写配置，`NapCatQr` 取二维码。

App 对真实 QQ **只做 OneBot v11 客户端**，不内置任何 QQ 协议实现——这也是「合规边界」所在。

## 七、手机内 Linux 环境

- 安装内容由 `assets/installers/*.json` 描述（步骤 + 下载 URL + 校验 + 解压命令），
  `EnvInstaller` 负责断点续传、分片并发、镜像回退、失败续装、空间预检。
- 安装位置：`filesDir/linux/`（rootfs、proot、协议端、Piper、Python），约 1.5–1.8 GB。
- 关键文件：
  - `files/linux/rootfs/` Ubuntu Base 24.04.5
  - `files/linux/proot`、`libtalloc.so.*`、`libandroid-shmem.so`
  - `files/linux/napcat/`、`files/linux/piper/`、`files/linux/python/`
- 端口约定：NapCat OneBot `3001`、NapCat WebUI `6099`、本地 Piper 桥 `17891`；都只监听本机。

## 八、前台服务与保活

- `BotService` 是 `dataSync` 类型前台服务，常驻通知显示状态；正常启动返回 `START_STICKY`，
  被杀后系统会尝试重拉（异常路径返回 `START_NOT_STICKY`，避免错误状态下反复重启）。
- `BootReceiver` 处理开机自启（用户可关）；`WAKE_LOCK` 只在下载/安装时短暂持有。
- Android 12+ 的 phantom process killer 和厂商省电策略仍可能杀掉 proot 子进程，这是已知限制，
  只能靠「允许后台活动 + 自启动 + 锁定最近任务」缓解。

## 九、为什么 targetSdk 是 28

Android 10（API 29）起禁止「从 App 私有目录执行二进制文件」。而本项目的核心就是执行
`filesDir/runtime/node` 与解压出来的 `proot`，targetSdk ≥ 29 会直接被系统拒绝（Termux 同理）。
代价是过不了 Play 商店的时效检查，所以 lint 里关掉了 `ExpiredTargetSdkVersion`。
**不要为了「更现代」去改 targetSdk，改了机器人就跑不起来。**