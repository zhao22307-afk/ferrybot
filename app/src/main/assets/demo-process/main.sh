#!/bin/sh
# 进程技能示例（跑在 App 装的 Linux 环境里）：
#   stdin  一行 JSON：{"text":"对方说的","convKey":"c2c:123","args":{}}
#   stdout 输出什么，机器人就回什么（不想要回复就什么都不输出）

read -r input || true
now=$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo '未知')

echo "进程技能跑通了：这段 shell 是在 App 的 Linux 环境里执行的"
echo "现在时间：$now"