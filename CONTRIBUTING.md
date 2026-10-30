# 参与贡献

感谢你愿意改进 ferrybot。这个项目是自用向工具，改动前请先读下面的约定，能让 PR 更容易被合并。

## 环境

- JDK **17**、Android SDK **Platform 35**（Build-Tools 35）
- Android Studio 任意较新版本（需支持 AGP 9），或直接用命令行
- 想改机器人 JS 的话，任意 **Node 22+**（内置运行时是 Node 24）
- 测试机：**arm64**、Android 8.0+（模拟器不行：内置运行时只有 arm64 版本）

详细步骤见 [构建说明.md](构建说明.md)。

## 代码约定

**Kotlin / Compose**

- 不要重新应用 `org.jetbrains.kotlin.android` 插件（AGP 9 已内置），否则构建直接失败
- Kotlin 2.x 用顶层 `kotlin { compilerOptions { jvmTarget = ... } }`，不要用 `kotlinOptions`
- 新 UI 尽量复用 `ui/Kit.kt` 里的组件（`GroupCard`、`InputBox`、`ChipButton` 等），保持风格一致
- 改 `AndroidManifest.xml` 的权限时，同步更新 [docs/PRIVACY.md](docs/PRIVACY.md) 的权限表

**机器人 JS（`app/src/main/assets/bot/`）**

- 纯 ESM，没有构建步骤；入口 `bot.mjs`
- 其它模块只 import `lib/transport.mjs`，不要直接 import `qq.mjs` / `onebot.mjs`
- 所有配置从环境变量读（`lib/config.mjs`），不要硬编码 Key / 地址
- 改完至少跑一遍语法检查：

```bash
node --check app/src/main/assets/bot/bot.mjs
node --check app/src/main/assets/bot/lib/*.mjs   # 或逐个文件检查
```

**改了 assets 之后**

- 改 `assets/bot/**`：把 `EnvManager.BOT_CODE_VERSION` +1，否则已装的手机会继续用旧代码
- 改 `assets/runtime/**`：把 `EnvManager.RUNTIME_VERSION` +1，并在真机上清一次「环境」
- 改 `assets/installers/**`：跟着 `BOT_CODE_VERSION` 走

## 提交前检查

- [ ] 没有提交 `.env`、`local.properties`、`.gradle-home/`、`.tmp/`、keystore、`dist/`
- [ ] 没有把私人域名、中转地址、真实 QQ 号、AppSecret、API Key 写进代码、注释或文档
- [ ] `./gradlew :app:assembleRelease` 能构建通过
- [ ] 改过 JS 的：所有 `.mjs` 过 `node --check`
- [ ] 改过 UI 的：浅色/深色、360dp 窄屏都看一眼
- [ ] 改过第三方依赖/资产的：更新 [docs/THIRD_PARTY.md](docs/THIRD_PARTY.md)

## PR 建议

- 一个 PR 只做一件事；顺手重构和功能改动请分开
- 说明**为什么**改，而不只是改了什么；涉及行为变化请写清影响
- 不要在这次 PR 里带入个人配置、调试日志或与项目无关的文件

## 不能接受的内容

- 用于骚扰、诈骗、冒充他人、批量营销、传播违法信息的功能
- 绕过平台风控、批量养号、买卖账号相关的实现
- 任何把用户凭证上传到作者或第三方服务器的代码