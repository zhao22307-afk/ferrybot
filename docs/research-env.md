# 运行时 Linux 用户态环境 · 调研结论（arm64 / 无 root / targetSdk 28）

> 调研时间 2026-10-28。所有"已验证"= 本次实际用 HTTP 请求确认过状态码/字节数（部分文件已下载并解析 ELF）。
> 结论先行：**可行**。App 私有目录（`filesDir`）里 `exec` 在 targetSdk 28 下可用（本工程 node 已经这么跑），
> 无 root 跑 Linux 的标准组合 = **Termux 的 proot（bionic 动态链接）+ Ubuntu Base 的 tar.gz rootfs**。
> 总体积约 **30 MB 下载 / 350 MB 落盘**（Ubuntu Base 解压 ~90 MB，装完 Node/QQ 端后 300–600 MB）。

---

## 1. proot 二进制（已验证）

### 1.1 首选：Termux 的 proot 包（Android/bionic 构建，最稳）

| 文件 | 直链 | 体积 | SHA256（来自 Termux Packages 索引） |
|---|---|---|---|
| proot | `https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107.101_aarch64.deb` | 103,072 B | `a28355e9be257e1f87f4e9294b3118470d4fd7a5442fed8b278f4443c83407d7` |
| libtalloc | `https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/libtalloc_2.5.0_aarch64.deb` | 34,036 B | `556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7` |
| libandroid-shmem | `https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb` | 7,216 B | `0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6` |

- 三个 URL 均已实际下载成功；`proot_*.deb` 用 `tar -xf` 可直接解（内含 `data.tar.xz`）。
- deb 内布局（需要取出的就这 4 个文件）：
  - `data/data/com.termux/files/usr/bin/proot` → 259,784 B，aarch64 ELF
  - `data/data/com.termux/files/usr/libexec/proot/loader` → 18,136 B（aarch64，静态）
  - `data/data/com.termux/files/usr/libexec/proot/loader32` → 6,244 B（arm 32 位，仅 32 位 guest 需要，可不要）
  - libtalloc 包里取 `lib/libtalloc.so.2.5.0`（33,592 B，自己 `ln -s` 成 `libtalloc.so.2`）；
    libandroid-shmem 包里取 `lib/libandroid-shmem.so`（14,432 B）
- **依赖（ELF DT_NEEDED 实测）**：`libtalloc.so.2`、`libandroid-shmem.so`、`libc.so`（系统 bionic，不用带）。
- **坑（实测 ELF RUNPATH）**：`proot` 的 RUNPATH 被写死为 `/data/data/com.termux/files/usr/lib`，且默认 loader 路径写死为
  `/data/data/com.termux/files/usr/libexec/proot/loader`。所以：
  - 必须 `LD_LIBRARY_PATH=<放 libtalloc.so.2 + libandroid-shmem.so 的目录>`（RUNPATH 目录不存在时靠它兜底）；
  - 必须 `PROOT_LOADER=<loader 的绝对路径>`（同架构 aarch64 guest 一般用不到 loader，带上稳妥）。
- 合计约 **310 KB**，非常小。

### 1.2 备选：proot-me 官方 static 构建（aarch64）

- `https://github.com/proot-me/proot/releases/download/v5.3.0/proot-v5.3.0-aarch64-static`
- 已验证：HTTP 200，**1,479,432 B**，aarch64（e_machine=183），**完全静态**（无 PT_INTERP、无 DT_NEEDED）。
- 优点：单文件、零 .so。缺点：glibc 静态二进制在 Android 上不保证（可能被 kernel/seccomp 差异卡住），
  且没有配套 loader（跨架构 guest 不支持）。**建议只做 fallback，不主用**。
- 注意：proot-me 最新的 v5.5.0 / v5.4.1 资产 `proot` 是 **x86_64**（实测 e_machine=62），不要下错架构；
  aarch64 静态版只存在于 v5.2.0 / v5.3.0 的资产名里。

### 1.3 proot 启动参数（Termux 官方脚本 `termux-chroot` 的写法，可直接照抄）

必须/强烈建议：

| 参数/变量 | 作用 |
|---|---|
| `--kill-on-exit` | proot 退出时杀掉 guest 子进程，避免残留 |
| `--link2symlink` | 用硬链接模拟 symlink，**很多 rootfs 的 apt/dpkg 需要**；需要 `PROOT_L2S_DIR` 可写 |
| `-0` | 伪装 uid/gid 0（rootfs 里 `whoami`=root，apt 才能装包） |
| `-r <rootfs>` | rootfs 根目录 |
| `-b /dev -b /proc -b /sys` | 让 guest 看到设备/进程/系统信息（**必须**，否则 ps/网络/随机数异常） |
| `-b /dev/urandom:/dev/random` | 可选，修 entitlement 类程序 |
| `--cwd=/` | 起始目录 |
| `PROOT_TMP_DIR` | **必须设**，默认写死 `/data/data/com.termux/files/usr/tmp/`（App 里不存在） |
| `PROOT_L2S_DIR` | link2symlink 的工作目录，**必须设**为 App 可写目录 |
| `PROOT_LOADER` / `PROOT_LOADER_32` | 指定 loader 全路径 |
| `PROOT_NO_SECCOMP=1` | 个别机型/kernel `PR_SET_SECCOMP` 失败时加这个兜底（Termux 也只在报错时加） |

另外 proot 二进制里还有 `PROOT_NO_MOUNTINFO`、`PROOT_DONT_POLLUTE_ROOTFS`、`PROOT_F2FS_WORKAROUND`、
`PROOT_IGNORE_MISSING_BINDINGS`、`PROOT_VERBOSE`（调试用），一般不用设。

---

## 2. rootfs（tar.gz，已验证）

### 2.1 首选：Ubuntu Base 22.04.5 arm64（纯 tar.gz，Java 侧 GZIP 直接能解）

| 源 | 直链 | 体积（Content-Length 实测） |
|---|---|---|
| Ubuntu 官方 | `https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.5-base-arm64.tar.gz` | **27,671,306 B（26.4 MiB）** |
| 清华镜像（国内快） | `https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.5-base-arm64.tar.gz` | 同上 27,671,306 B |

- 解压后约 90–100 MB，含 apt/dpkg/bash/coreutils；`usrmerge` 的 `/bin -> usr/bin`、`/lib -> usr/lib` 都是**相对软链**。
- 24.04 系列同样只有 tar.gz：`https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz`（约 29 MB，目录列表已验证，未逐字节 HEAD）。
- 建议锁 22.04.5：glibc 2.35 + 老 kernel 兼容性好，apt 源可按国内镜像改。

### 2.2 Debian：只有 tar.xz（未验证直链）

Debian 官方不发布 tar.gz rootfs。可用的是 `debuerreotype/docker-debian-artifacts` 的 `dist-arm64/…/rootfs.tar.xz`
（raw.githubusercontent 被墙，jsdelivr 对大文件也有限制；本次未实际下载验证，**标"未验证"**）。
如果一定要 Debian，二选一：
- **App 内解 xz（推荐）**：加 `org.tukaani:xz`（纯 Java，~120 KB）+ `org.apache.commons:commons-compress`（~1 MB，release 后几百 KB），
  `XZInputStream` 包一层即可复用现有 tar 解析，零 NDK。这是 Android 上最省事的 xz 方案。
- NDK 内置 xz-embedded/liblzma（~200 KB .so）：更快但增加 native 构建复杂度。
- toybox 的 `xzcat` **不能指望**：各厂商 toybox 命令集不齐，且 App 里 exec 系统 toybox 还受路径/权限影响。

> 结论：**别用 Debian**。Ubuntu Base tar.gz 已经满足"Java 只有 GZIP"的约束，省掉 xz 这条链路。

---

## 3. 自写 tar 解压器必须处理的条目清单

rootfs tar（Ubuntu Base 实测）里会出现下列 `typeflag`，按此表处理：

| typeflag | 含义 | 必须做什么 |
|---|---|---|
| `'0'` / `'\0'` | 普通文件 | 写文件；**先建父目录**；文件可能先于目录出现 |
| `'5'` | 目录 | `mkdirs()`；权限位最后再补（否则先 chmod 555 的目录后面写不进去） |
| `'2'` | 符号链接 | `linkName` 可能是**相对**（`usr/bin`）也可能是**绝对**（`/bin`）：**原样**创建，不要解析成宿主机绝对路径；Java 用 `Files.createSymbolicLink`，rootfs 内路径直接拼 |
| `'1'` | 硬链接 | 目标可能还没解出来 → **延迟处理**：记录 (linkName → 当前路径)，全部解完再统一 `createLink`；同一 inode 多路径的 rootfs 里很常见 |
| `'3'/'4'` | 字符/块设备节点 | **跳过**（App 里 mknod 不允许，也无需） |
| `'6'` | FIFO | 跳过（或忽略） |
| `'x'/'g'` | pax 扩展头 | 读 `path`/`linkpath`/`size` 覆盖后续条目（长文件名必需，Ubuntu 里会出现） |
| `'L'/'K'` | GNU longname/longlink | 同 pax，读下一块的扩展名 |
| `'S'` | GNU sparse | 不处理（rootfs 里基本没有）；遇到直接报错更好 |
| 其它 | — | 跳过 + 记日志 |

其它细节：
- **权限位**：解压时用 `mode & 0777`；setuid/setgid 位（`04000/02000`）在 App 私有目录**无意义**（无 root），可以丢掉或保留都行。
  `chmod` 要在所有子项写完后自底向上补一遍，避免目录只读。
- **mtime**：`Files.setLastModifiedTime` 可选，apt 不依赖。
- **软链穿越**：`linkName` 是绝对路径时，**不能**用 `Path.resolve` 当成宿主机路径（会跳到宿主机 `/bin`），必须按"rootfs 内路径"字符串处理。
- **长度/大小**：Ubuntu Base 约 1.5–2 万个条目，GZIP 流式解 + 自写 tar 在手机上约 **5–20 s**（看 UFS 速度），
  建议在 `Dispatchers.IO` + 前台通知里做，并写 `解压中 x%`。
- **tar 的 `size` 字段是八进制**，且后面跟 512 对齐；pax 头 `size` 是十进制字符串——两种都要读。

---

## 4. Termux bootstrap 路线对照（已验证下载）

- 直链：`https://github.com/termux/termux-packages/releases/download/bootstrap-2026.10.25-r1%2Bapt.android-7/bootstrap-aarch64.zip`
  - 实测 HTTP 200，**33,081,376 B**；zip 内是 `$PREFIX` 的内容（根下直接 `bin/ etc/ lib/ usr/ …`，
    另有 `SYMLINKS.txt` 记录 zip 无法表达的软链，形如 `curses.h→/include/ncurses.h`）。
- **能否直接解压到 App 私有目录用？不能直接用。** 实测证据：
  - `bin/bash` 的 ELF `RUNPATH` = `/data/data/com.termux/files/usr/lib`（写死）；
  - `bin/bash` 的 DT_NEEDED = `libandroid-support.so`、`libreadline.so.8`、`libiconv.so`、`libdl.so`、`libc.so`；
  - 所有脚本 shebang 都是 `#!/data/data/com.termux/files/usr/bin/sh`（`termux-chroot` 实测如此），
    Termux 大量配置/脚本里还有 `$PREFIX` 绝对路径。
- 规避办法（按推荐度）：
  1. **proot 假 prefix 绑定（推荐）**：用 1.1 的 proot，`-b <App>/termux/usr:/data/data/com.termux/files/usr`，
     再把 `/tmp`、`/dev`、`/proc`、`/sys` 绑好，直接在 guest 里 exec `/data/data/com.termux/files/usr/bin/bash`。
     因为 proot 会把 tracee 的**所有**绝对路径翻译到 bind 上，写死的 RUNPATH/shebang 全部自然命中。**不用装 Termux**，也不用 patchelf。
  2. `patchelf --set-rpath` 改 RUNPATH + `LD_LIBRARY_PATH`：能救 ELF，救不了 shebang/配置里的绝对路径，工作量更大，不推荐。
  3. 干脆不用 bootstrap：Ubuntu rootfs 里 apt 装 Node，跑 glibc 版 QQ 协议端（见 §6）。
- 提醒：Termux 包体系是 bionic 的，`pkg install` 出来的东西只能在 bionic 环境跑；在 Ubuntu rootfs 里它们不通用。
  两条路线别混。

---

## 5. 最小可用命令（可直接落地成 ProcessBuilder）

### 方案 A（推荐）：proot + Ubuntu Base rootfs

目录约定（都在 `filesDir` 下，GZIP 解压得到）：
```
filesDir/env/proot/{proot,loader,libtalloc.so.2,libandroid-shmem.so,tmp/,l2s/}
filesDir/env/rootfs/          # ubuntu-base-22.04.5 tar.gz 解开
filesDir/env/proot.log
```

进程环境变量：
```
LD_LIBRARY_PATH = <filesDir>/env/proot
PROOT_TMP_DIR   = <filesDir>/env/proot/tmp
PROOT_L2S_DIR   = <filesDir>/env/proot/l2s
PROOT_LOADER    = <filesDir>/env/proot/loader
PATH            = /usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
HOME            = /root
TERM            = xterm-256color
LANG            = C.UTF-8
```
给 Android/bionic 留后路（termux-chroot 的做法，targetSdk 28 下很有用）：
```
-b /system:/system -b /vendor:/vendor -b /apex:/apex -b /linkerconfig/ld.config.txt:/linkerconfig/ld.config.txt
```
命令行（A 方案里 guest 是 Ubuntu，用 rootfs 自己的 bash）：
```
<filesDir>/env/proot/proot \
  --kill-on-exit --link2symlink -0 \
  -r <filesDir>/env/rootfs \
  -b /dev -b /proc -b /sys -b /dev/urandom:/dev/random \
  -b /system:/system -b /vendor:/vendor -b /apex:/apex \
  --cwd=/root \
  /bin/bash -lc 'exec /usr/bin/env bash -l'
```
> 同架构 aarch64 guest 一般不需要 `PROOT_LOADER` 真正生效（loader 是给 x86/arm32 guest 用的），
> 但设上不亏；如果启动报 `the loader was not found or doesn't work`，先查 `PROOT_LOADER` 路径。

装 Node / QQ 协议端（在 proot 里，先改国内 apt 源）：
```
/bin/bash -lc 'apt-get update && apt-get install -y nodejs npm ca-certificates curl'
```

### 方案 B：proot + Termux bootstrap（假 prefix）

```
<filesDir>/env/proot/proot \
  --kill-on-exit --link2symlink -0 \
  -r <filesDir>/env/termux \
  -b /dev -b /proc -b /sys \
  -b <filesDir>/env/termux/usr:/data/data/com.termux/files/usr \
  -b <filesDir>/env/termux/usr/tmp:/tmp \
  --cwd=/data/data/com.termux/files/usr \
  /data/data/com.termux/files/usr/bin/bash -l
```
（`-r` 指向 `termux/` 这一层，里面放 `usr/` 等；bootstrap zip 解到 `termux/usr/`，`SYMLINKS.txt` 按行重建软链。）

---

## 6. 协议端（QQ 协议实现）：未验证，只给方向

- **未完成验证**：本次网络下 GitHub API 被限流，未取到 release 资产直链。已知 NapCat 最新 release tag 为 `v4.18.41`
  （`https://github.com/NapNeko/NapCatQQ/releases/tag/v4.18.41`，HEAD 200），但**具体资产 URL/体积未验证**；
  Lagrange.Core 的 linux-arm64 构建同理未验证。请勿把未验证 URL 写进代码。
- 落地建议（与上面的 rootfs 匹配）：
  - Ubuntu rootfs 里 `apt install nodejs`（22.04 自带 Node 12 偏旧，建议 NodeSource 或直接下 nodejs.org 的 linux-arm64 tar.gz 解到 `/opt/node`），
    再跑 NapCat（纯 JS，无 native 依赖时最省事）。
  - 若协议端是 .NET（Lagrange.OneBot），Ubuntu rootfs 里装 `dotnet-runtime-8.0`，arm64 版可跑。
  - 在 proot 里跑 glibc 程序**没有问题**，权限/网络（connect、listen 本地端口）都正常；长连接走宿主网络栈，无端口映射问题。

---

## 7. 运行期坑清单（重要）

| 坑 | 说明 / 对策 |
|---|---|
| **私有目录 exec** | Android 10（API 29）起禁止 targetSdk≥29 的 App 执行 home 目录内文件；**targetSdk 28 可 exec**。本工程 node 已实证。别升 targetSdk。 |
| **SELinux W^X** | 只要走 `filesDir`（`app_data_file`，targetSdk 28 域）就没问题；`getExternalFilesDir` 是 FUSE noexec，**不能**放可执行文件。 |
| **Android 12+ phantom process killer** | 系统会杀 App 的子进程（Termux 社区常见，需 `adb shell settings put global settings_enable_monitor_phantom_procs false` 或 `device_config`）。无 adb 时对策：**控制进程数 < 32**、常驻前台服务、引导用户给「自启动/省电白名单」。此项需真机实测。 |
| **后台被杀** | 前台服务（`startForeground` + 常驻通知，已有）+ `START_STICKY` + 请求 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + 引导 OEM 白名单/锁任务。proot 派生的 guest 进程也算 App 子进程，一并被杀。 |
| **存储位置** | rootfs 必须放**内部** `getFilesDir()`（可 exec、无 noexec 挂载）；内部空间紧张时 `getFilesDir` 依旧首选，不要放 external。清理缓存时注意别把 rootfs 当 cache 清掉（用 `filesDir/env`，"清除缓存"不会删）。 |
| **首次解压耗时** | 27.7 MB gzip → 约 90 MB / 1.5–2 万条目，手机约 5–20 s；要前台通知 + 进度，避免 ANR；解压完再 chmod。 |
| **空间估算** | proot ~0.3 MB；rootfs 解压 ~90–100 MB；apt 装 node+依赖 ~+80 MB；NapCat ~+30 MB；QQ 端数据/日志另计。**建议预留 1 GB**。 |
| **termux-exec 干扰** | proot 会检测 `libtermux-exec.so`；我们没装 Termux，`LD_PRELOAD` 里也不要有它，忽略。 |
| **32 位 guest** | 纯 aarch64 rootfs 不需要 loader32；若要跑 arm32 程序，需 `loader32` 且设备支持 32 位（Android 14+ 部分设备已砍）。 |

---

## 8. 一句话组合（推荐落地）

**Termux proot（310 KB，三个 .deb 取 4 个文件）+ Ubuntu Base 22.04.5 arm64 tar.gz（27.7 MB）+ apt 装 Node + QQ 协议端**；
启动 `proot --kill-on-exit --link2symlink -0 -r rootfs -b /dev -b /proc -b /sys ...`，环境变量 `LD_LIBRARY_PATH/PROOT_TMP_DIR/PROOT_L2S_DIR/PROOT_LOADER`。
备选：单文件 proot-me static aarch64（1.48 MB，零依赖，兜底）。