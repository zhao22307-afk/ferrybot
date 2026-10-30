#!/bin/bash
# 在 proot 的 Ubuntu 里装 NapCat（由 App 调用：/bin/bash /host/install-napcat.sh）
# 约定：/dl 是 App 的下载目录（rootfs 外），/root 是 rootfs 内的 home
set -e
export DEBIAN_FRONTEND=noninteractive

echo "== 0. DNS（rootfs 里的 resolv.conf 可能是空的）"
if [ ! -s /etc/resolv.conf ]; then
  echo "nameserver 8.8.8.8" > /etc/resolv.conf
  echo "nameserver 223.5.5.5" >> /etc/resolv.conf
fi

echo "== 1. 换 apt 源（清华 ubuntu-ports；首次没有 CA 证书，只能 http）"
mkdir -p /etc/apt/sources.list.d
cat > /etc/apt/sources.list.d/ubuntu.sources <<'EOF'
Types: deb
URIs: http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/
Suites: noble noble-updates noble-backports noble-security
Components: main universe restricted multiverse
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
EOF

echo "== 2. apt update"
apt-get -o APT::Sandbox::User=root update

echo "== 2.5 修复上次中断的 dpkg"
# proot 里 groupadd 写 /etc/group.lock 会失败，dbus 的 postinst 就卡在这。
# 先把 messagebus 组/用户造好，postinst 里的 addgroup 会直接跳过。
if ! grep -q '^messagebus:' /etc/group 2>/dev/null; then
  echo 'messagebus:x:101:' >> /etc/group
fi
if ! grep -q '^messagebus:' /etc/passwd 2>/dev/null; then
  echo 'messagebus:x:101:101:message bus:/nonexistent:/usr/sbin/nologin' >> /etc/passwd
fi
dpkg --configure -a || true
# 上次卡在 unzip（dpkg 里半装状态）时把它清掉，不然 apt 会一直被它卡住
dpkg --remove --force-remove-reinstreq unzip || true

echo "== 3. 装依赖（24.04 包名带 t64）"
apt-get install -y --no-install-recommends \
  xz-utils ca-certificates curl jq xvfb xauth procps \
  libgtk-3-0t64 libnotify4 libnss3 libxss1 libxtst6 xdg-utils \
  libatspi2.0-0t64 libuuid1 libsecret-1-0 libglib2.0-0t64 \
  libatk1.0-0t64 libasound2t64 libgbm1 libgssapi-krb5-2 || true

echo "== 4. 解 LinuxQQ（arm64，官方 deb）"
rm -rf /tmp/qqx
mkdir -p /tmp/qqx /root/Napcat/opt
if [ ! -f /dl/linuxqq.deb ]; then
  echo "缺少 /dl/linuxqq.deb"; exit 2
fi
dpkg-deb -x /dl/linuxqq.deb /tmp/qqx
rm -rf /root/Napcat/opt/QQ
mv /tmp/qqx/opt/QQ /root/Napcat/opt/QQ
test -x /root/Napcat/opt/QQ/qq || { echo "QQ 主程序不可执行"; exit 3; }
echo "QQ 版本: $(jq -r .version /root/Napcat/opt/QQ/resources/app/package.json 2>/dev/null || echo 未知)"

echo "== 5. 装 NapCat 本体（zip 已由 App 解到 /host/qqshell）"
SRC=/host/qqshell
if [ ! -d "$SRC" ] || [ -z "$(ls -A "$SRC" 2>/dev/null)" ]; then
  echo "缺少 /host/qqshell（App 没解开 NapCat.Shell.zip）"; exit 4
fi
# 发行包可能是「文件在根目录」或「外面套一层 NapCat.Shell/」，两种都认
if [ ! -f "$SRC/napcat.mjs" ]; then
  for d in "$SRC"/*/; do
    if [ -f "${d}napcat.mjs" ]; then SRC="${d%/}"; break; fi
  done
fi
[ -f "$SRC/napcat.mjs" ] || { echo "NapCat 目录里找不到 napcat.mjs"; exit 4; }

APP=/root/Napcat/opt/QQ/resources/app
mkdir -p "$APP/app_launcher/napcat"
cp -a "$SRC"/. "$APP/app_launcher/napcat/"

echo "== 6. 写 loader + 改入口"
cat > "$APP/loadNapCat.js" <<'EOFJS'
const path = require('node:path');
const { pathToFileURL } = require('node:url');
import(pathToFileURL(path.join(__dirname, 'app_launcher/napcat/napcat.mjs')).href);
EOFJS

jq '.main = "./loadNapCat.js"' "$APP/package.json" > /tmp/pkg.json
chmod --reference="$APP/package.json" /tmp/pkg.json || true
mv /tmp/pkg.json "$APP/package.json"

echo "== 7. 钉住 QQ 版本（防强制更新）"
mkdir -p /root/.config/QQ/versions
cat > /root/.config/QQ/versions/config.json <<'EOFJSON'
{"baseVersion":"3.2.34-53644","curVersion":"3.2.34-53644","buildId":"53644"}
EOFJSON

echo "== 8. 清理下载残留，安装完成"
rm -rf /tmp/qqx
du -sh /root/Napcat/opt/QQ 2>/dev/null || true
echo "ALL DONE"