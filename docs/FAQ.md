# 常见问题

## 安装与运行

**Q：装完打开，首页提示「环境未就绪」/ 点启动没反应？**
先点首页「获取环境」等它铺完内置运行时（解压约 90 MB，几十秒）；再看「更多 → 运行日志」有没有报错。
首次装完还没配模型 Key 时，机器人会启动但不会回复，这是正常的。

**Q：能装到模拟器吗？/ 我的旧手机能装吗？**
不行。内置 Node、proot、LinuxQQ 都只有 **arm64** 版本，模拟器多是 x86_64；
Android 版本要求 **8.0（API 26）及以上**。32 位老机器、鸿蒙 NEXT 不适用。

**Q：为什么 targetSdk 才 28，会不会不安全？**
这是刻意的：Android 10 起禁止从 App 私有目录执行二进制，而本项目就是要在手机里跑
`node` 和 `proot`，targetSdk 一升就废（Termux 同样停在 28）。代价是不能上架 Play 商店。
偶发兼容性提示可忽略；仍建议只从自己构建或可信来源安装。

**Q：用一会儿就被系统杀了 / 协议端掉线？**
Android 12+ 的 phantom process killer 和各厂商省电策略会杀子进程。能做的：
电池设置里允许 App 后台活动、手机管家允许自启动、最近任务里锁定卡片、关掉「电池优化」。
前台服务只能尽量保活，**不保证 24 小时在线**。

**Q：能开机自启吗？**
可以，「更多」里有开关（默认开）。部分系统需要在系统设置里额外允许自启动。

## 配置与费用

**Q：必须用哪家的模型？**
不需要。任何 **OpenAI 兼容**接口都行（DashScope、DeepSeek、OpenAI、自建/中转），
在「接入 → 模型」里填 Base URL、模型名、Key 即可。地址留空会用默认值。

**Q：要花多少钱？**
App 本身免费。费用只来自你自己配的模型/语音/搜索服务：轻度自用通常每月几元到几十元，
取决于聊天量和模型单价。**开启长期记忆、主动消息、Vision 都会增加 token 消耗**，界面里有提示。

**Q：语音一定要花钱吗？**
不一定。云端合成/识别/声音复刻要钱；也可以只装「Piper 本地语音」（免费离线，一个女声），
在「对话 → 语音」里选「手机本地」。

**Q：联网搜索需要配吗？**
默认关闭。想让它查实时信息再在「高级」里填 Tavily / Serper / Bing / Bocha 的 Key。

## 真实 QQ 相关

**Q：会被封号吗？**
真实 QQ 走的是第三方协议端（NapCat / Lagrange），**属于非官方途径，可能违反腾讯用户协议**，
有被限制或封禁的风险。建议只用**小号**、别做批量/营销/骚扰行为。风险自担。

**Q：必须装 NapCat 吗？**
不是。协议端跑在电脑/服务器上也行（NapCat、Lagrange 等），App 里填 `ws://IP:3001` + token 即可；
只有「不带电脑」才需要在手机里装 Ubuntu + NapCat（约 470 MB 下载、1.5–1.8 GB 占用）。

**Q：装到一半失败了 / 断网了怎么办？**
安装器支持断点续传与失败续装，回「运行环境」再点一次即可从上次步骤继续；
空间不足会提前预检（建议留 2.4 GB 以上）。镜像下载慢可以在界面上切换下载源。

**Q：扫码登录后协议端要一直开着吗？**
是。NapCat 进程在，才能收发消息；它掉线机器人就没有消息来源。App 会尝试保活。

## 数据与隐私

**Q：聊天记录/Key 存在哪？会上传吗？**
全部在 App 私有目录（`files/bot/.env`、`data/` 等），卸载即删。App 没有作者服务器、不加遥测；
但聊天内容会发送给你**自己配置的**模型/语音/搜索服务。详见 [PRIVACY.md](PRIVACY.md)。

**Q：`.env` 是明文吗？**
是。Key 直接以明文写在私有目录里（普通应用读不到，root/ADB 可读）。别截图外发，
手机丢了请尽快去各平台重置 Key。

**Q：系统备份会带上我的 Key 吗？**
`allowBackup="true"`，系统可能把私有数据备份到你的云备份。介意可关系统自动备份，
或把 manifest 改成 `allowBackup="false"` 后自行重编。

**Q：怎么彻底清干净？**
「清除记忆 / 清空素材 / 清空环境 / 清空日志」分别清对应数据；最彻底是卸载 App 或系统里「清除数据」。
注意「清空环境」会删掉手机里的 Ubuntu/NapCat（1.5–1.8 GB），重装要重新下载。

## 构建相关

**Q：构建报 `Unsupported class file major version 65`？**
有环节把编译目标设成了 21。确认 `app/build.gradle` 里 `sourceCompatibility` / `targetCompatibility`
与 `jvmTarget` 都是 17（仓库现状如此，别改）。

**Q：构建卡在下载 JDK / `Provisioning toolchain`？**
`gradle/gradle-daemon-jvm.properties` 把 Gradle daemon 固定到 JDK 21，本机没有就会去 foojay 下载。
离线环境自己装 JDK 21，或把该文件里的 `toolchainVersion=21` 改成 `17`。

**Q：报 `InvalidPluginException ... kotlin.android`？**
AGP 9 已内置 Kotlin 编译，别再声明 `org.jetbrains.kotlin.android` 插件。

**Q：release 包 R8 报 `Missing class org.tukaani.xz.*`？**
`app/build.gradle` 里必须有 `implementation 'org.tukaani:xz:1.10'`（运行时要用，别删）。

**Q：改完 JS 为什么手机上没变化？**
内置代码有版本戳：把 `EnvManager.kt` 里的 `BOT_CODE_VERSION` 加 1 再重装；
改的是 `assets/runtime/**` 则要递增 `RUNTIME_VERSION` 并清一次环境。

**Q：能自己签名发布吗？**
可以。仓库默认用 debug 签名只为方便覆盖安装；正式分发请自建 keystore 并配 `signingConfig`，
**不要把 keystore 提交进仓库**。详见 [构建说明.md](../构建说明.md)。

## 其它

**Q：支持个人微信 / 插件 exe / 实时语音通话吗？**
不支持。项目只做 QQ（官方或 OneBot），不支持 `.node` 原生插件，也没有实时语音通话能力。

**Q：文档里的版本号和我的对不上？**
以仓库 `CHANGELOG.md` 和 `app/build.gradle` 的 `versionName` 为准；README 里的数字会随版本更新。