# NapCat 在「无 root / arm64 / Android 8+」上长期运行：可行性调研

> 调研时间：2026-10-28 ｜ 调研范围：NapCat 4.18.41 / LinuxQQ 3.2.34-53644 / Ubuntu base 24.04.5 arm64 / Termux proot 5.1.107.101
> 本文只记录**已实际验证**的内容；无法验证的一律标注 `【未验证】`，请勿当成事实使用。
> 文中命令均以「App 私有目录 + 自带 proot」为背景，不使用 Termux App。

---

## 0. 结论（先看这段）

| 问题 | 结论 |
|---|---|
| arm64 无 root 安卓能跑 NapCat 吗？ | **能**。Tencent 官方发布 **aarch64 LinuxQQ deb**，NapCat 官方文档有 **Termux/proot 专章**，**不需要 box64、不需要 qemu** |
| 架构 | proot（Termux 5.1.107.101, aarch64）+ Ubuntu base 24.04.5 arm64 rootfs + LinuxQQ 3.2.34-53644 arm64 + NapCat.Shell 4.18.41 |
| 首次总下载量 | **≈ 470 MiB**（详见 §4） |
| 装完占盘 | **≈ 1.5 ~ 1.8 GiB**【部分未实测，见 §4 备注】 |
| 二维码怎么拿 | **NapCat WebUI HTTP API**：`POST /api/QQLogin/GetQQLoginQrcode` 返回二维码**内容字符串**（不是图片），App 端用 ZXing 本地生成位图。token 我们自己在 `config/webui.json` 里写死，不用猜 |
| 最大的坑 | **不是"能不能跑"，而是"能不能长期活着"**：Android 12+ phantom process killer + Doze 会杀掉 Electron 的进程树；NapCat 自身还有"跑约 1 小时后收不到新消息"的已知 issue(#1201)。第二大的坑是 **Termux 版 proot 二进制写死了 Termux 路径**（RUNPATH `/data/data/com.termux/files/usr/lib`、`PROOT_TMP_DIR` 默认 `/data/data/com.termux/files/usr/tmp/`），必须整包搬运并覆盖环境变量 |
| 建议 | 先用 **Termux App + 官方 install.termux.sh** 做 1 天 POC 验证"能登录、能收发、能活多久"，**再**做 App 内置 proot（`docs` 里 §5 的命令可直接抄，POC 时不用改） |

---

## 1. NapCat 是什么结构（关键：它不是纯 Linux 程序）

- NapCat 是 **QQ NT（Electron 桌面客户端）的内存注入 / 加载器**。它没有独立协议实现，**必须**跑在 LinuxQQ NT 本体里：
  `qq`（Electron 二进制，arm64）→ 读 `resources/app/package.json` 的 `main` 字段 → 我们把它改成 `./loadNapCat.js` → 该 loader `import()` 了 `app_launcher/napcat/napcat.mjs` → NapCat 在 QQ 的 Node 环境里启动，对外开 HTTP/WS（OneBot v11）。
- 所以整条链路是：**App → proot → rootfs → Xvfb（QQ 是 GUI 程序，必须有 X）→ qq --no-sandbox → NapCat → WebUI 6099 / OneBot 3001**。
- **必须 Xvfb**：无桌面环境下 `xvfb-run -a ./qq --no-sandbox` 是官方写法（可执行文件 `/usr/bin/xvfb-run`，来自 `xvfb` + `xauth` 包）。
- **arm64 原生**：`linuxqq_3.2.34-53644_arm64.deb` 是 Tencent 官方产物（`qqdl.gtimg.cn`），deb 控制文件 `Architecture: arm64`，NapCat 的 PacketBackend 文档也明确列了 **Linux Arm64** 支持。
- 官方文档甚至直接给了 Termux 章节（`https://napneko.github.io/guide/boot/Shell`）：
  ```bash
  curl -fL -o napcat.termux.sh https://raw.githubusercontent.com/NapNeko/NapCat-Installer/main/script/install.termux.sh
  bash napcat.termux.sh
  # 启动：
  proot-distro login napcat -- bash -c 'xvfb-run -a /root/Napcat/opt/QQ/qq --no-sandbox'
  ```
  > `raw.githubusercontent.com` 在当前网络不可达，可用 jsdelivr 镜像：`https://cdn.jsdelivr.net/gh/NapNeko/NapCat-Installer@main/script/install.termux.sh`（镜像规则已验证，此文件**未逐一验证**）。

**arm64 真实表现（启动耗时/内存/稳定性/是否必须前台）**：`【未验证】`——社区没有可引用的公开实测数据，公开渠道暂未检索到可引用的数据。**必须自己测**，测法见 §8。以下为工程估算，**不可当结论用**：Xvfb+Electron 冷启动约 20~60 s（低端机更久）；常驻内存约 400~800 MB（Electron 主进程 + 渲染进程 + NapCat）；**不需要前台**（无 Activity 也能跑，前提是有前台 Service 撑进程，见 §7）。

---

## 2. 三个必须先说清楚的硬约束

1. **proot 必须是 Termux 编译的那只**（bionic 链接），不能拿 proot-me 的 glibc 版。已实测解析 `proot_5.1.107.101_aarch64.deb` 里的二进制：
   - `NEEDED: libc.so (bionic) / libtalloc.so.2 / libandroid-shmem.so`
   - **`DT_RUNPATH = /data/data/com.termux/files/usr/lib`**（写死 Termux 私有路径）
   - 内含默认 loader 路径 `/data/data/com.termux/files/usr/libexec/proot/loader`、`loader32`
   - 内含默认临时目录 `/data/data/com.termux/files/usr/tmp/`，并支持 `PROOT_TMP_DIR` 覆盖
   - 支持的开关（strings 实测）：`PROOT_LOADER`、`PROOT_LOADER_32`、`PROOT_TMP_DIR`、`PROOT_NO_SECCOMP`、`PROOT_L2S_DIR`、`PROOT_F2FS_WORKAROUND`、`PROOT_ASSUME_MEMFD_UNSUPPORTED`、`PROOT_IGNORE_MISSING_BINDINGS`、`PROOT_DONT_SHARE_LIBANDROID_SHMEM`、`PROOT_VERBOSE`
   → **落地做法**：把 `proot` + `loader` + `libtalloc.so.2` + `libandroid-shmem.so` 一起放进 App 私有目录，启动时用 `LD_LIBRARY_PATH`（RUNPATH 在 LD_LIBRARY_PATH 之后搜索，可覆盖）+ `PROOT_TMP_DIR` + `PROOT_LOADER` 指到我们自己的路径。
2. **能 exec 私有目录里的二进制**，靠的是 `targetSdk 28`（Android 10+ 对 targetSdk≥29 禁 W^X）。保持 targetSdk 28 不变，这个功能才成立。
3. **rootfs 很小但要联网装依赖**：`ubuntu-base-24.04.5-base-arm64.tar.gz` 里 apt/dpkg/tar/gzip/bash 都有，但**没有** `xz`、`unzip`、`curl`、`wget`、`ca-certificates`（已实测列目录）。apt 源的默认 URI 是 `http://ports.ubuntu.com/ubuntu-ports/`（deb822 格式 `etc/apt/sources.list.d/ubuntu.sources`），因此**首次只能走 http**（没有 CA 证书）。

---

## 3. 一趟完整的安装流程（可直接抄）

### 3.1 目录布局（App 私有目录）

```
/data/data/<pkg>/files/napcat/
├── native/            # proot 三件套（约占 350 KB）
│   ├── proot                    # 259784 B
│   ├── libexec/proot/loader     # 18136 B
│   ├── libexec/proot/loader32   # 6244 B（arm64 用不到，可删）
│   ├── lib/libtalloc.so.2
│   └── lib/libandroid-shmem.so
├── rootfs/            # Ubuntu base 解压结果（约 102 MB）
├── dl/                # 下载缓存（qq.deb / NapCat.Shell.zip / rootfs.tar.gz）
└── logs/
```

### 3.2 宿主侧：拿到 proot（一次，打进 APK assets 或首启下载）

| 文件 | URL | 体积 |
|---|---|---|
| proot aarch64 | `https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107.101_aarch64.deb` | 103072 B |
| libtalloc | `https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/libtalloc_2.5.0_aarch64.deb` | 34036 B |
| libandroid-shmem | `https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb` | 7216 B |

deb 是 `ar` 归档，App 端可以：
- 用 Java/Commons-Compress 解 `ar` + `data.tar.xz`（需要 xz 解压库），或
- **在构建期**把这些文件解出来直接塞进 `assets/`（推荐：只 3 个文件 + 2 个 .so，约 350 KB，省掉 App 里的 ar/xz 解码）。

### 3.3 rootfs

| 文件 | URL | 体积 |
|---|---|---|
| Ubuntu base 24.04.5 arm64 | `https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz` | **29,936,675 B**（28.5 MiB，解压后 101.8 MiB，6826 个条目） |
| 国内镜像 | `https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz` | 同上（实测 3.9 s 下完） |

**决策点**：Android 没有 `tar` 保证（toybox tar 的 gzip 支持不确定），建议 **App 端自己解 tar.gz**（Java `GZIPInputStream` + 手写 tar 解析，约 100 行；或引入 Apache Commons Compress ≈ 700 KB）。解压要点：只处理 `typeflag 0/5`（文件/目录），`1`（硬链接）和 `2`（符号链接）必须支持——rootfs 里有符号链接（如 `usr/bin/sh → dash`）。

### 3.4 启动 proot（这一段是本功能的地基）

```bash
PKG=/data/data/<pkg>/files/napcat
NATIVE=$PKG/native
ROOTFS=$PKG/rootfs

export PROOT_TMP_DIR=$ROOTFS/tmp          # 关键：默认值指向 Termux 目录
export PROOT_LOADER=$NATIVE/libexec/proot/loader
export LD_LIBRARY_PATH=$NATIVE/lib
export PROOT_NO_SECCOMP=0                  # 若遇奇怪崩溃改 1（有 f2fs/内核相关 workaround 分支）

exec $NATIVE/proot \
  --link2symlink -0 \
  -r $ROOTFS \
  -b /dev -b /proc -b /sys \
  -b $ROOTFS/tmp:/dev/shm \
  -w /root \
  /usr/bin/env -i \
    HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    TERM=xterm-256color LANG=C.UTF-8 \
    /bin/bash -l
```

说明：
- `-0` 伪造 root（uid 0），apt/dpkg 需要。
- `-b /dev /proc /sys`：不绑 `/dev` 会缺 `/dev/null`、`/dev/urandom`，apt 和 Electron 都会挂；不绑 `/proc` 则 `ps`/线程探测失败。
- `-b $ROOTFS/tmp:/dev/shm`：Android 没有可用的 `/dev/shm`，Xvfb/Electron 的共享内存要落到磁盘。不加这行常见报错：`Xvfb failed to start` / Chromium `Failed to create shared memory`。
- `--link2symlink`：Android 的 `/data` 分区（f2fs/ext4）对硬链接有限制，rootfs 安装组件会遇到；配套可设 `PROOT_L2S_DIR`。
- `--kill-on-exit`：如果 proot 被 App 直接 kill，建议加上（proot 文档选项），避免残留 tracee。

### 3.5 容器内：换源 + 装依赖（一条命令跑完）

```bash
# 1) 换源（tuna，注意是 ubuntu-ports，arm64 的仓库名；首次没有 CA 证书，必须 http）
cat > /etc/apt/sources.list.d/ubuntu.sources <<'EOF'
Types: deb
URIs: http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/
Suites: noble noble-updates noble-backports noble-security
Components: main universe restricted multiverse
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
EOF

# 2) 更新（proot 下 _apt 沙箱用户常报权限错，显式关掉沙箱用户）
apt-get -o APT::Sandbox::User=root update

# 3) 装依赖（Ubuntu 24.04 = noble，注意 t64 改名）
apt-get install -y --no-install-recommends \
  xz-utils unzip ca-certificates curl jq \
  xvfb xauth \
  libgtk-3-0t64 libnotify4 libnss3 libxss1 libxtst6 xdg-utils \
  libatspi2.0-0t64 libuuid1 libsecret-1-0 \
  libglib2.0-0t64 libatk1.0-0t64 libasound2t64 \
  libgbm1 libgssapi-krb5-2 procps
```

依据：NapCat `install.sh` 的依赖清单是 `zip unzip jq curl xvfb screen xauth procps rpm2cpio cpio libnss3 libgbm1 libgssapi-krb5-2` + 对 `libglib2.0-0/libatk1.0-0/libatspi2.0-0/libgtk-3-0/libasound2` **动态检测 t64 变体**；LinuxQQ arm64 deb 自己的 `Depends` 是 `libgtk-3-0, libnotify4, libnss3, libxss1, libxtst6, xdg-utils, libatspi2.0-0, libuuid1, libsecret-1-0`（已从 deb 的 control 实测读出）。
> 24.04 上是 `*t64` 后缀；如果你换 Debian bookworm（官方 Termux 脚本用的就是 `debian:bookworm-slim`），包名是**不带 t64**的 `libgtk-3-0` 等。两条路线都行，Ubuntu 24.04 的 t64 是个容易踩的坑。

### 3.6 装 LinuxQQ

| 文件 | URL | 体积 |
|---|---|---|
| LinuxQQ 3.2.34-53644 **arm64** | `https://qqdl.gtimg.cn/qqfile/QQNT/9.9.36/beta/9ee04bef/linuxqq_3.2.34-53644_arm64.deb` | **207,538,924 B（197.9 MiB）**，`Installed-Size: 655698 KB ≈ 640 MiB` |
| （参考）amd64 版 | 同目录 `linuxqq_3.2.34-53644_amd64.deb` | 178.5 MB |

```bash
mkdir -p /root/Napcat/opt /tmp/qqx
dpkg-deb -x /root/linuxqq.deb /tmp/qqx          # 只解包，不装依赖
mv /tmp/qqx/opt/QQ /root/Napcat/opt/QQ
# 校验（必须完全等于 3.2.34-53644，否则 NapCat 可能不兼容）
jq -r .version /root/Napcat/opt/QQ/resources/app/package.json
test -x /root/Napcat/opt/QQ/qq && echo OK
```
> `qqdl.gtimg.cn` 这条 URL 是 NapCat `install.sh` 里按架构硬编码的，实测 **HEAD 200**、支持 Range。QQ 侧会更新，脚本里还有一个 `linuxqq_target_version="3.2.34-53644"`；**该 URL 有失效风险，建议 App 端做 302/大小校验 + 失败回退到"用户自选本地 deb"**。

### 3.7 装 NapCat 本体

| 文件 | URL | 体积 |
|---|---|---|
| NapCat.Shell.zip v4.18.41 | `https://github.com/NapNeko/NapCatQQ/releases/latest/download/NapCat.Shell.zip` | 34.23 MB（`content-length` 实测） |
| 国内镜像 | `https://ghproxy.net/https://github.com/NapNeko/NapCatQQ/releases/latest/download/NapCat.Shell.zip` | 同，HEAD 200 |
| release 页 | `https://github.com/NapNeko/NapCatQQ/releases/tag/v4.18.41`（2026-10-21） | — |

> **实测风险**：`ghproxy.net` 对 34 MB 资产的**流式下载会中断**（本机重试两次，一次 10 分钟无进展、一次 `terminated`）。所以 App 端必须做 **断点续传 + 多镜像回退 + hash 校验**，或者由发布方自建对象存储镜像。这是整条链路里**最脆的一环**。

```bash
unzip -o /root/NapCat.Shell.zip -d /root/qqshell
APP=/root/Napcat/opt/QQ/resources/app
mkdir -p $APP/app_launcher/napcat
# 首次安装：整体拷入；升级时 config/ plugins/ 必须保留（官方 install.sh 用 cp -rn 合并）
cp -rf /root/qqshell/* $APP/app_launcher/napcat/

# 写 loader（逐字来自官方 install.sh）
cat > $APP/loadNapCat.js <<'EOF'
const path = require('node:path');
const { pathToFileURL } = require('node:url');
import(pathToFileURL(path.join(__dirname, 'app_launcher/napcat/napcat.mjs')).href);
EOF

# 改入口
jq '.main = "./loadNapCat.js"' $APP/package.json > /tmp/pkg.json \
  && chmod --reference=$APP/package.json /tmp/pkg.json \
  && mv /tmp/pkg.json $APP/package.json
```

> 另有官方"手动安装"版 loader（`loadNapCat.cjs`，仅当 argv 含 `--no-sandbox` 才注入，否则回落原入口）。**proot 场景建议用上面的 `.js` 版**（无条件注入，最省事）。

### 3.8 钉死 QQ 版本（防强制更新，必做）

```bash
mkdir -p /root/.config/QQ/versions
cat > /root/.config/QQ/versions/config.json <<'EOF'
{"baseVersion":"3.2.34-53644","curVersion":"3.2.34-53644","buildId":"53644"}
EOF
```
这就是官方 `install.sh` 里 `update_linuxqq_config` 做的事，目的是让 QQ 认为自己是最新版、不弹更新/不罢工。

### 3.9 配置 OneBot v11 WebSocket + WebUI token

配置目录：`/root/Napcat/opt/QQ/resources/app/app_launcher/napcat/config/`（Termux 文档里给的就是这个路径）。

**`config/webui.json`**（我们自己写死 token，App 就不必去日志里 grep 随机 token）：
```json
{"host":"127.0.0.1","port":6099,"token":"<32位随机串，建议含字母+数字>","loginRate":3}
```

**`config/onebot11.json`**（v4.5.3+ 支持它作为新账号的默认模板；登录后实际生效文件是 `onebot11_<QQ号>.json`）：
```json
{
  "network": {
    "httpServers": [], "httpClients": [], "websocketClients": [],
    "websocketServers": [
      {
        "name": "ferry",
        "enable": true,
        "host": "127.0.0.1",
        "port": 3001,
        "messagePostFormat": "array",
        "reportSelfMessage": false,
        "token": "<ws-token>",
        "enableForcePushEvent": true,
        "debug": false,
        "heartInterval": 30000
      }
    ]
  },
  "musicSignUrl": "",
  "enableLocalFile2Url": false,
  "parseMultMsg": false
}
```
- 字段名与 NapCat 文档示例一致（`name/enable/host/port/messagePostFormat/reportSelfMessage/token/enableForcePushEvent/debug/heartInterval`）。
- **host 用 `127.0.0.1` 而不是 `0.0.0.0`**：Node 运行时和协议端在**同一台手机**上，`127.0.0.1` 就够，且不会把有 token 的 WS 暴露到局域网（用户连公共 WiFi 时有实际风险）。真要局域网访问再开 `0.0.0.0`。
- token 必填；NapCat 的 WS 服务端支持 `token` 字段做鉴权。

### 3.10 启动

```bash
cd /root/Napcat/opt/QQ
NAPCAT_FORCE_NODE_PROCESS=1 xvfb-run -a ./qq --no-sandbox
# 想免扫码：追加 -q <QQ号>（首次仍需扫码，之后可能可快速登录）
```
**`NAPCAT_FORCE_NODE_PROCESS=1` 是 arm64/proot 场景的关键变量**：NapCat v4.18.x 在 arm64/proot 下 Electron `UtilityProcess` 会 **SIGSEGV（退出码 11）**，导致主进程连 WebUI 一起退出；该变量强制改用 `child_process.fork` 绕过（来源：NapCat PR #1984 "修复 WebUI 白屏与 arm64/proot 下 Worker SIGSEGV"）。

---

## 4. 下载物清单与体积

| 项 | URL | 下载体积 | 装完占用 |
|---|---|---|---|
| proot + loader + 2 个 .so | `packages.termux.dev` 三个 deb（见 §3.2） | 144 KB | 350 KB |
| Ubuntu rootfs | `cdimage.ubuntu.com` / 清华镜像（见 §3.3） | **28.5 MiB** | **101.8 MiB**（实测解压后） |
| rootfs 内 apt 依赖（xvfb/gtk3/nss…） | `mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/` | ~60–90 MiB | **~350–450 MiB**【未实测，量级估算】 |
| LinuxQQ arm64 | `qqdl.gtimg.cn`（见 §3.6） | **197.9 MiB** | **640 MiB**（deb `Installed-Size` 实测） |
| NapCat.Shell 4.18.41 | GitHub release（见 §3.7） | **34.23 MiB** | **~90–150 MiB**【zip 内部结构未验证】 |
| **合计** | | **≈ 470 MiB**（首次全量下载） | **≈ 1.5–1.8 GiB** |

**实用建议**：APK 只内置 proot 三件套（350 KB），其余全部首启分步下载 + 断点续传 + 进度条；每步可独立重试（rootfs / apt / QQ / NapCat 四段），别做成一个 200MB 的大包，否则弱网必炸。

---

## 5. 登录二维码怎么拿到（App 侧最稳路径）

**结论：走 NapCat WebUI 的 HTTP API，拿二维码"内容字符串"，App 端用 ZXing 渲染成图片。不要解析终端 ASCII 二维码（除非做兜底）。**

**依据（源码级，已核对）**：
- `packages/napcat-webui-backend/src/router/QQLogin.ts`：`POST /api/QQLogin/GetQQLoginQrcode` → 返回 `{code:0, data:{qrcode:"<字符串>"}}`。
- 前端 `packages/napcat-webui-frontend/src/components/qr_code_login.tsx` 用 `<QRCodeSVG value={qrcode} size={180} />` —— 说明 **`qrcode` 是二维码的内容（登录 URL），不是图片**。
- QQ 底层其实有 PNG：事件 `onQRCodeGetPicture({ pngBase64QrcodeData, qrcodeUrl })`（`packages/napcat-core/listeners/NodeIKernelLoginListener.ts:11`），但 **WebUI 没有把 base64 暴露出来**，只给了字符串。

### 5.1 完整调用链（可直接实现）

WebUI 鉴权（`packages/napcat-webui-backend/src/helper/SignToken.ts`）：
- `hash = sha256(token + ".napcat")` 的十六进制小写。
- `POST /api/auth/login`，body `{"hash":"<hash>"}` → `{code:0, data:{Credential:"<base64>"}}`
- 之后所有请求加头：`Authorization: Bearer <Credential>`
- **Credential 有效期 3600 秒**，App 侧要缓存并在 401 时重新登录。

```bash
TOKEN='<webui.json 里的 token>'
HASH=$(printf '%s' "$TOKEN.napcat" | sha256sum | cut -d' ' -f1)
CRED=$(curl -s -X POST http://127.0.0.1:6099/api/auth/login \
  -H 'Content-Type: application/json' -d "{\"hash\":\"$HASH\"}" | jq -r .data.Credential)

# 取二维码
curl -s -X POST http://127.0.0.1:6099/api/QQLogin/GetQQLoginQrcode \
  -H "Authorization: Bearer $CRED"
# → {"code":0,"data":{"qrcode":"https://...tx.../..."}}

# 轮询登录状态
curl -s -X POST http://127.0.0.1:6099/api/QQLogin/CheckLoginStatus \
  -H "Authorization: Bearer $CRED"

# 二维码过期刷新
curl -s -X POST http://127.0.0.1:6099/api/QQLogin/RefreshQRcode \
  -H "Authorization: Bearer $CRED"
```
Android 侧：`hash` 用 `MessageDigest("SHA-256")` 算 token+".napcat" 的字节；`qrcode` 字符串丢给 ZXing（`QRCodeWriter`）生成 Bitmap 显示；登录成功后 `CheckLoginStatus` 返回已登录 → 切到"运行中"界面；二维码通常 2 分钟左右过期，UI 上给个"刷新"按钮调 `RefreshQRcode`。

### 5.2 兜底方案（WebUI 起不来时）

- **终端 ANSI 二维码解析**：`packages/napcat-qrcode/lib/main.ts` 用 ANSI 背景色块渲染，`black = "\x1b[40m  \x1b[0m"`、`white = "\x1b[47m  \x1b[0m"`，每模块 2 字符；small 模式用 `▀▄█`。把 pty 日志按行过滤即可重建点阵，再按 module 尺寸画图。
- 注意：**OneBot v11 API 里没有 get_qrcode**（已核 /api/、/onebot/api 全量接口清单，无 qrcode 字样），别浪费时间找。
- WebUI 若端口被占会自动 +1 重试（最多 100 次），所以**不要假定 6099**：要么先探测，要么从日志里读 `[WebUi] ... Url: http://127.0.0.1:6099/webui?token=...` 那行确认端口。

---

## 6. 启动顺序 / App 状态机建议

```
检查 native+rootfs 完好
 → (缺) 下载并解压 rootfs         [28.5 MiB]
 → (缺) 进入 proot 执行 apt 换源+装依赖   [~60–90 MiB]
 → (缺) 下载 linuxqq deb → dpkg-deb -x   [197.9 MiB]
 → (缺) 下载 NapCat.Shell.zip → unzip + loader + 钉版本 + 写配置  [34.2 MiB]
 → 启动 qq（NAPCAT_FORCE_NODE_PROCESS=1, xvfb-run）
 → 轮询 6099 WebUI 就绪（登录拿 Credential）
 → 定期调 GetQQLoginQrcode 显示二维码 / CheckLoginStatus
 → 登录成功 → 探测 3001 WS → 交给 Node 运行时（OneBot 客户端）
```
每一步都要**可单独重试**，并把日志落到 `files/napcat/logs/{install,qq}.log`。

---

## 7. Android 侧必须处理的系统限制

1. **目标 SDK 不能升**：`targetSdk 28` 是 exec 私有目录二进制的前提（Android 10+ targetSdk≥29 禁 execute）。这条是本功能的地基，勿动。
2. **前台服务 + WakeLock**：`Service` 用 `startForeground()`（API 28 上类型不是必需，做兼容）、`PARTIAL_WAKE_LOCK`，否则息屏后 CPU 停 → QQ 掉线。
3. **Android 12+ phantom process killer**：系统会杀掉"应用的一堆子进程"（阈值 ~32 个 phantom process），Electron+proot 很容易超。用户可 `adb shell settings put global settings_enable_monitor_phantom_procs false` 关掉（**需 adb，非 root**），App 内只能引导，改不了。这是长期后台运行的头号系统级敌人。
4. **电池优化白名单**：引导用户把 App 加入"不受限制"（Doze / 厂商省电策略，尤其国产 ROM 的"自启动/关联启动/后台弹窗"三件套）。真实手机上**不引导必掉**。
5. **SELinux**：App 私有目录 exec 在 targetSdk 28 下是允许的；不要尝试写 `/data/local/tmp` 之类外部路径。
6. **ptrace**：proot 依赖 `ptrace`，同一 App 内对子进程 trace 是允许的（Termux 同理），不需要 root；但个别 ROM（加固/安全内核）可能限制——加个"启动自检"（跑 `proot --version`）报错更好排查。
7. **存储**：装完约 1.5–1.8 GiB，首启前检查 `StatFs` 可用空间。
8. **进程被杀后的恢复**：`WorkManager` 定时自检 3001 端口，不在则重启（注意 Android 8+ 后台启动 Service 限制 → 用前台服务 + 通知，或 `BOOT_COMPLETED` + 用户手动点）。

---

## 8. 已知坑清单

| # | 现象 | 根因 | 对策 |
|---|---|---|---|
| 1 | 装了新 QQ 后 NapCat 不启动 | QQ 更新后 `package.json` 被覆盖，`main` 不再是 `loadNapCat.js` | 钉死 `~/.config/QQ/versions/config.json` 版本；升级 QQ 后重跑 §3.7 的 loader 注入 |
| 2 | 启动即崩 / Worker 退出码 11，WebUI 一起消失 | **arm64/proot 下 Electron `UtilityProcess` SIGSEGV** | `NAPCAT_FORCE_NODE_PROCESS=1`（PR #1984） |
| 3 | 跑 ~1 小时后收不到新消息，但还能发 | NapCat 已知 bug（issue #1201，环境正是 `linux arm64 6.2.1-PRoot-Distro` + QQNT 3.2.18 + NapCat 4.8.95），WS/HTTP 都不上报事件 | 定时重启进程 / 升级 NapCat / 关注 issue #1197。**长期稳定性必须实测** |
| 4 | `apt-get update` 报 `Permission denied ... /var/lib/apt/lists/lock` | proot 下 `_apt` 沙箱用户 | `apt-get -o APT::Sandbox::User=root ...` |
| 5 | apt 报 `Could not resolve` / 证书错误 | rootfs 里没 CA 证书；arm64 仓库是 `ubuntu-ports` 不是 `archive.ubuntu.com` | 用 `http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/`；先装 `ca-certificates` 再上 https |
| 6 | `Xvfb failed to start` / Electron 共享内存报错 | proot 里没有 `/dev/shm` | `-b $ROOTFS/tmp:/dev/shm`；必要时 `--disable-dev-shm-usage` |
| 7 | 缺 `/dev/null`、`/dev/urandom` 类错误 | 没绑 `/dev` | `-b /dev -b /proc -b /sys` |
| 8 | proot 找不到 `libtalloc.so.2` / `libandroid-shmem.so` | Termux 版 proot 的 RUNPATH 写死 Termux 路径 | 带这俩 .so，`LD_LIBRARY_PATH` 指向自己目录 |
| 9 | proot 报临时目录不可写 | `PROOT_TMP_DIR` 默认 `/data/data/com.termux/files/usr/tmp/` | 显式 `export PROOT_TMP_DIR=$ROOTFS/tmp` |
| 10 | 某些机型 proot 随机崩 / f2fs 相关 | f2fs + seccomp | 试 `PROOT_F2FS_WORKAROUND=1`、`PROOT_NO_SECCOMP=1`、`PROOT_ASSUME_MEMFD_UNSUPPORTED=1` |
| 11 | 装完缺库（`libgtk-3-0` 等找不到） | 24.04 的 t64 改名 | 用 `libgtk-3-0t64` 等；或改 Debian bookworm |
| 12 | WebUI token 对不上 | v4.8.106+ token 随机生成 | 我们**自己预写** `config/webui.json`，不从日志猜 |
| 13 | WebUI 白屏 | React chunk 拆分 bug（已在 #1984 修复） | 用 ≥ 该修复版本（4.18.x 之后） |
| 14 | 登录掉线频繁 / 风控 | 同一 IP/设备同时登 Bot 和常用号；官方安全文档明确提醒 | 用独立号 + 独立网络；`NAPCAT_PROXY_ADDRESS/NAPCAT_PROXY_PORT` 走 socks5；`o3Hook=0` 关包拦截 |
| 15 | 内存 OOM 被杀 | Electron + Xvfb 常驻，1 GB 以下机器吃紧 | 建议 ≥ 3 GB RAM；`largeHeap` 无帮助（是另一个进程） |
| 16 | 首启下载 34MB NapCat 一直中断 | `ghproxy.net` 大文件流不稳定（实测） | 断点续传 + 多镜像 + 本地缓存；或自建镜像 |

---

## 9. 必须自己实测的清单（本调研无法覆盖）

1. **启动耗时**：从 `qq` 进程 spawn 到 6099 端口可用（建议埋点，分机型记录）。
2. **常驻内存**：`/proc/<pid>/status` 的 VmRSS，Session 峰值；低端机（4 GB）是否会 LMK。
3. **长稳**：连续跑 24 h，`CheckLoginStatus` + 3001 WS 心跳，验证是否复现 issue #1201 的"1 小时掉事件"。
4. **后台存活**：息屏 8 h、切后台、内存压力下的存活率；phantom process killer 是否触发（`adb shell dumpsys activity processes | grep -i phantom`）。
5. **apt 依赖实际安装体积**（`du -sh /root/Napcat /var/cache/apt`）。
6. **NapCat.Shell.zip 解压后体积与内部结构**（本机下载失败，`【未验证】`）。
7. **首次登录后 OneBot WS 是否真的按 `config/onebot11.json` 模板生效**（还是只建了 `onebot11_<QQ>.json` 默认值 → 需要用 WebUI API 补建）。
8. **扫码二维码有效期**与本机时间偏差容忍度。

---

## 10. 替代方案与取舍（如果 NapCat 路线成本过高）

| 方案 | 优点 | 缺点 |
|---|---|---|
| **Termux App + 官方 install.termux.sh**（POC） | 官方维护、一条命令、已在大量用户跑 | 用户要装第二个 App、要手动点打开；不算"App 内一键" |
| **App 内置 proot（本文）** | 真正一键、无广告、可打包资源镜像 | 工程量大：自研 tar/ar 解包、下载器、FGS 守护、1.5 GB 磁盘、长稳风险 |
| 直装 Termux 原生 NapCat（不用 proot） | 更轻 | **已确认不可行**：issue #1261 明确 `npm install` 阶段 `node-pty` 编译失败（`undefined variable android_ndk_path`），且需要 Termux 里自己编 node |
| Docker/udocker | 无 | Android 上跑 Docker 需要 root；udocker 仍要 proot 底座，收益为 0 |
| 其他协议端（Lagrange.Core / LiteLoaderQQNT 等） | 可能省资源 | 与本项目"跑 QQ 官方协议端"的等价性、风控表现都需另做调研，不在本文范围 |

---

## 11. 参考链接（均已实际访问验证，除标注）

- NapCat 文档 · Shell 安装（含 Termux 章节）：`https://napneko.github.io/guide/boot/Shell`
- NapCat 文档 · Linux 手动安装（Xvfb/xauth、loader、启动命令）：`https://napneko.github.io/guide/boot/Shell-Linux-SemiAuto`
- NapCat 文档 · 高级配置（PacketBackend 支持 Linux Arm64）：`https://napneko.github.io/config/advanced`
- NapCat 文档 · 基础配置（WebUI/OneBot/鉴权）：`https://napneko.github.io/config/basic`
- NapCat 文档 · 安全（风控/掉线）：`https://napneko.github.io/other/security`
- NapCat 仓库：`https://github.com/NapNeko/NapCatQQ` ｜ release 资产：`https://github.com/NapNeko/NapCatQQ/releases/latest/download/NapCat.Shell.zip`
- PR #1984（arm64/proot Worker SIGSEGV 修复）：`https://github.com/NapNeko/NapCatQQ/pull/1984`
- issue #1201（arm64 proot 一小时后不收消息）：`https://github.com/NapNeko/NapCatQQ/issues/1201`
- issue #1261（Termux 原生安装 node-pty 编译失败）：`https://github.com/NapNeko/NapCatQQ/issues/1261`
- Termux 包源：`https://packages.termux.dev/apt/termux-main/`
- Ubuntu base 镜像：`https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/`
- 清华 Ubuntu ports 镜像：`https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/`