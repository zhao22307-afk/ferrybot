#!/bin/bash
# 在 proot 的 Ubuntu 里装 python3（给「进程技能」用）
# 由 App 调用：/bin/bash /host/install-python3.sh
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

echo "== 3. 装 python3"
apt-get install -y --no-install-recommends -o APT::Sandbox::User=root \
  python3 python3-pip ca-certificates

echo "== 4. 检查"
python3 -V
pip3 --version 2>/dev/null || true

echo "OK python3 装好了"