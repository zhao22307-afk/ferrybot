# 第三方组件与许可

本仓库自己写的代码以 [MIT](../LICENSE) 发布。仓库里还直接分发了少量第三方二进制/运行时代码，
它们**不适用 MIT**，各自遵循上游许可。下表分成「随仓库分发」和「运行时下载」两类。

## 一、随仓库分发的组件

| 组件 | 版本 | 许可 | 在仓库中的位置 | 上游 |
|---|---|---|---|---|
| Node.js（arm64 二进制） | 24.18.0 | MIT | `app/src/main/assets/runtime/node.xz` | <https://nodejs.org> |
| ICU（数据与库） | 78.3 | Unicode License v3 | `assets/runtime/libicudata.so.78.xz`、`libicui18n.so.78`、`libicuuc.so.78` | <https://icu.unicode.org> |
| OpenSSL（libcrypto / libssl） | 3.6.5 | Apache-2.0 | `assets/runtime/libcrypto.so.3`、`libssl.so.3` | <https://www.openssl.org> |
| zlib | 1.x | zlib License | `assets/runtime/libz.so.1` | <https://zlib.net> |
| c-ares | 1.34.x | MIT | `assets/runtime/libcares.so.zz` | <https://c-ares.org> |
| libc++（LLVM） | LLVM 运行时 | Apache-2.0 WITH LLVM-exception | `assets/runtime/libc++_shared.so.zz`、`libsqlite3.so.zz` | <https://libcxx.llvm.org> |
| silk-wasm | 3.7.1 | MIT（Copyright © 2024 idranme） | `assets/bot/node_modules/silk-wasm/`（含 LICENSE） | <https://github.com/idranme/silk-wasm> |
| Gradle Wrapper | 与 Gradle 9.7.1 配套 | Apache-2.0 | `gradle/wrapper/gradle-wrapper.jar` | <https://gradle.org> |

> 运行时资产是上游官方 aarch64 构建的**原样副本**（仅做了 xz 压缩或改名打包），未修改二进制内容。
> 如果你要再分发本仓库或基于此构建的 APK，请保留上述许可声明；`node.xz` 里包含 Node.js 的 LICENSE 文件。

## 二、运行时时按需下载的组件（不随仓库分发）

这些组件由 App 在用户设备上从公开镜像下载，仓库里只有下载地址与校验信息
（`app/src/main/assets/installers/*.json`），**不包含它们的二进制**。使用时请遵守各自许可与条款：

| 组件 | 用途 | 许可 / 条款 | 来源 |
|---|---|---|---|
| proot（Termux 构建，5.1.107.101） | 在手机上跑 Linux 用户态 | GPL-2.0（以上游为准） | <https://packages.termux.dev> |
| libtalloc / libandroid-shmem | proot 运行依赖 | LGPL-3.0 / 见上游 | <https://packages.termux.dev> |
| Ubuntu Base 24.04.5 (arm64) | proot 的 rootfs | 由 Ubuntu 各软件包许可组成 | <https://cdimage.ubuntu.com/ubuntu-base/> |
| LinuxQQ 3.2.34-53644 (arm64) | 真实 QQ 登录 | 腾讯专有许可（仅限官方客户端使用） | `qqdl.gtimg.cn`（腾讯官方 CDN） |
| NapCat.Shell 4.18.41 | QQ 协议端（OneBot v11） | 以 [NapNeko/NapCatQQ](https://github.com/NapNeko/NapCatQQ) 仓库 LICENSE 为准 | GitHub Releases |
| Piper | 手机本地离线 TTS | MIT | <https://github.com/rhasspy/piper> |
| 中文语音模型 `zh_CN-huayan-medium` | Piper 音色 | 以模型卡标注为准 | <https://huggingface.co/rhasspy/piper-voices> |
| Python 3（Termux 包） | 进程技能运行环境 | PSF License 等（各包不同） | <https://packages.termux.dev> |

## 三、合规提醒

- 本项目与腾讯、阿里、Ubuntu、Node.js 基金会等均**无关联**，未获授权或背书。
- 真实 QQ 接入依赖第三方协议实现，可能违反腾讯用户协议，账号存在被限制或封禁的风险。
- LinuxQQ 为腾讯专有软件，请仅在遵守其许可的前提下、用于你自己合法持有的账号。
- 若你将本仓库/构建产物再分发，需自行确认上述每项许可的再分发要求（尤其是 GPL/LGPL 组件与腾讯专有组件）。
- 本文件仅为整理，不构成法律意见；有疑问请以各上游 LICENSE 原文为准。