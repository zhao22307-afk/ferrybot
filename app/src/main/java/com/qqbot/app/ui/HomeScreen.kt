package com.qqbot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 首页：一眼看到状态，一键启动，其余都是我的事。
 */
@Composable
fun HomeScreen(
    vm: AppViewModel,
    onOpenCommands: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenAccess: () -> Unit,
) {
    val stats = remember(vm.running, vm.logText) { vm.todayStats() }
    val f = vm.form

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        HeroCard(vm)

        // QQ 登录和「机器人」是两层：协议端在线但机器人没启动时，消息没人回，这里点明
        if (!vm.running && vm.protoRunning) {
            GroupCard(Modifier.padding(top = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "QQ 协议端已在线，但机器人没启动",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = appInk(),
                        modifier = Modifier.weight(1f),
                    )
                    LinkText("去启动") { vm.toggle() }
                }
                Text(
                    "登录二维码只让 QQ 上线，回消息的是下面那层「机器人」。点首页的启动按钮才会开始回。",
                    fontSize = 12.sp,
                    color = appInk2(),
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        if (!vm.running && vm.lastExitMessage != null) {
            GroupCard(Modifier.padding(top = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "上次没启动成功",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Danger,
                        modifier = Modifier.weight(1f),
                    )
                    LinkText("看日志") { onOpenLogs() }
                }
                Text(
                    vm.lastExitMessage!!,
                    fontSize = 12.sp,
                    color = appInk2(),
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        // 配置速览：点一下直达对应页
        GroupLabel("当前配置")
        GroupCard {
            InfoRow("接入", if (f.channel == "onebot") "真实QQ" else "QQ 官方机器人") { onOpenAccess() }
            RowDivider()
            InfoRow("模型", f.model.ifBlank { "未设置" }) { onOpenAccess() }
            RowDivider()
            InfoRow(
                "主动消息",
                if (f.proactive) "开着 · 每天最多 ${f.maxDay} 条" else "关着",
            ) { onOpenChat() }
        }

        GroupLabel("今天", hint = "截至现在")
        GroupCard {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatTile("回复", "${stats.replies}", "条消息", modifier = Modifier.weight(1f))
                Box(Modifier.size(1.dp, 34.dp).background(appLine()).align(Alignment.CenterVertically))
                StatTile("调用", "${stats.calls}", "次模型", modifier = Modifier.weight(1f))
                Box(Modifier.size(1.dp, 34.dp).background(appLine()).align(Alignment.CenterVertically))
                StatTile("花费", formatCost(stats.cost), "元", modifier = Modifier.weight(1f))
            }
        }

        GroupLabel("快捷")
        GroupCard {
            ItemRow("对话设置", desc = "人设、主动消息、发什么", showArrow = true, onClick = onOpenChat)
            RowDivider()
            ItemRow("接入设置", desc = "官方 / 真实QQ、模型、运行环境", showArrow = true, onClick = onOpenAccess)
            RowDivider()
            ItemRow("命令说明", desc = "看看在 QQ 里能发什么", showArrow = true, onClick = onOpenCommands)
            RowDivider()
            ItemRow("运行日志", desc = "它最近在干什么", showArrow = true, onClick = onOpenLogs)
        }

        GroupLabel("保活")
        GroupCard {
            Text(
                "国产系统会清理后台。建议在系统设置里给本应用开「允许后台活动」和「自启动」，" +
                    "并在最近任务里锁定卡片。",
                fontSize = 12.sp,
                color = appInk2(),
                lineHeight = 19.sp,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** 顶部主卡：蓝紫渐变，状态 + 一键开关 */
@Composable
private fun HeroCard(vm: AppViewModel) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF2F6BED), Color(0xFF5B8DFF))
                )
            )
            .padding(20.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0x33FFFFFF)),
                    contentAlignment = Alignment.Center,
                ) { Text(if (vm.running) "🤖" else "💤", fontSize = 22.sp) }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        when {
                            vm.running -> "正在替你聊天"
                            vm.starting -> "正在启动…"
                            else -> "还没启动"
                        },
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        when {
                            vm.running -> "关掉这个 App 也在跑"
                            vm.starting -> "等协议端上线，最多 3 分钟"
                            else -> "点下面按钮开始"
                        },
                        fontSize = 12.sp,
                        color = Color(0xCCFFFFFF),
                    )
                }
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(Color(0x33FFFFFF))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        vm.running -> Color(0xFF7CF0C4)
                                        vm.starting -> Color(0xFFFFD86B)
                                        else -> Color(0xCCFFFFFF)
                                    }
                                )
                        )
                        Spacer(Modifier.size(5.dp))
                        Text(
                            when {
                                vm.running -> "运行中"
                                vm.starting -> "启动中"
                                else -> "未运行"
                            },
                            fontSize = 11.sp,
                            color = Color.White,
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (vm.running) Color(0x26FFFFFF) else Color.White)
                    .clickable { vm.toggle() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when {
                        vm.running -> "停止"
                        vm.starting -> "启动中…（等协议端上线）"
                        else -> "启动"
                    },
                    fontSize = if (vm.starting) 13.sp else 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (vm.running) Color.White else Brand,
                )
            }
        }
    }
}

/** 可点击的一行摘要 */
@Composable
private fun InfoRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.5.sp, color = Muted, modifier = Modifier.widthIn(min = 68.dp))
        Text(
            value,
            fontSize = 14.sp,
            color = appInk2(),
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        Text("›", fontSize = 18.sp, color = Muted)
    }
}

/** 花费显示：小于 0.01 元给 4 位小数 */
private fun formatCost(v: Double): String = when {
    v <= 0.0 -> "0"
    v < 0.01 -> String.format(java.util.Locale.US, "%.4f", v)
    else -> String.format(java.util.Locale.US, "%.3f", v)
}