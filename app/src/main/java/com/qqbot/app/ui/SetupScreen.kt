package com.qqbot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 首次启动：装环境 → 填凭证 */
@Composable
fun SetupScreen(vm: AppViewModel, onGoSettings: () -> Unit) {
    val ctx = LocalContext.current

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
    ) {
        // 头部
        Column(
            Modifier.fillMaxWidth().padding(top = 56.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier.size(80.dp).clip(RoundedCornerShape(24.dp)).background(appField()),
                contentAlignment = Alignment.Center
            ) { Text("💬", fontSize = 36.sp) }
            Spacer(Modifier.height(16.dp))
            Text("ferrybot", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(5.dp))
            Text("把 QQ 机器人装进手机", fontSize = 13.sp, color = Muted)
        }

        Column(Modifier.padding(horizontal = 16.dp)) {
            // 步骤 1
            GroupCard {
                StepHead(1, "获取运行环境", if (vm.installing) "正在解压…" else "只需一次，约 90MB")
                Spacer(Modifier.height(14.dp))
                BigButton(
                    text = when {
                        vm.installing -> "正在解压…"
                        vm.runtimeReady -> "环境已就绪 ✓"
                        else -> "获取环境"
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !vm.installing && !vm.runtimeReady,
                ) { vm.install() }
                vm.installError?.let { err ->
                    Spacer(Modifier.height(12.dp))
                    Text(err, fontSize = 12.sp, color = Danger, lineHeight = 18.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "点下面复制诊断信息发我，能直接看出卡在哪一步（版本、空间、文件大小都在里面）",
                        fontSize = 12.sp,
                        color = Muted,
                        lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    ChipButton("复制诊断信息") { vm.copyEnvDiagnostics() }
                }
                if (vm.installing || vm.installError != null || vm.freeSpaceMb >= 0L) {
                    Spacer(Modifier.height(8.dp))
                    val free = if (vm.freeSpaceMb >= 0L) "当前可用 ${vm.freeSpaceMb}MB" else "读不到剩余空间"
                    Text(
                        "解压需要约 110MB 可用空间（node 45 + ICU 数据 33 + 依赖库 15）。$free",
                        fontSize = 12.sp,
                        color = Muted,
                        lineHeight = 18.sp,
                    )
                }
            }

            // 步骤 2
            GroupCard(Modifier.padding(top = 12.dp)) {
                StepHead(2, "申请 QQ 机器人（可选）", "只有走 QQ 官方机器人时需要")
                Spacer(Modifier.height(10.dp))
                Text(
                    "打开 q.qq.com 创建机器人，拿到 AppID 和 AppSecret。\n" +
                        "在「开发设置」里把事件订阅方式改成 WebSocket，并勾选单聊 / 群 @ 事件。\n\n" +
                        "想用真实 QQ 号：这步可以跳过，去「接入」页选「真实 QQ」，再决定协议端跑在电脑还是手机上。",
                    fontSize = 12.sp,
                    color = appInk2(),
                    lineHeight = 19.sp,
                )
                Spacer(Modifier.height(14.dp))
                Row {
                    BigButton("去开放平台", Modifier.weight(1f), filled = false) {
                        runCatching {
                            ctx.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://q.qq.com/#/apps")
                                )
                            )
                        }
                    }
                    Spacer(Modifier.size(10.dp))
                    BigButton("我已申请好", Modifier.weight(1f)) { onGoSettings() }
                }
            }

            // 步骤 3（说明性质）
            GroupCard(Modifier.padding(top = 12.dp)) {
                StepHead(3, "填凭证", "官方机器人填 AppID/Secret；模型填 Key")
                Spacer(Modifier.height(10.dp))
                Text(
                    "模型不限千问：任何 OpenAI 兼容接口都行（OpenAI、DeepSeek、Kimi、硅基流动、自建 vLLM…），" +
                        "填地址 + Key + 模型名即可，语音合成/识别也能各自换服务商。\n\n" +
                        "填完保存，回首页点「启动」就跑起来了。以后所有配置在底部「对话」「接入」「更多」三页里改。",
                    fontSize = 12.sp,
                    color = appInk2(),
                    lineHeight = 19.sp,
                )
                Spacer(Modifier.height(14.dp))
                BigButton("去填写", Modifier.fillMaxWidth(), filled = false) { onGoSettings() }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "内置运行时要铺开约 90MB；若之后在手机上跑真实 QQ 的协议端，另需 1.5GB 以上空间（第一次下载约 470MB）",
                fontSize = 12.sp,
                color = Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun StepHead(n: Int, title: String, sub: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(26.dp).clip(RoundedCornerShape(9.dp)).background(appBrandSoft()),
            contentAlignment = Alignment.Center
        ) { Text("$n", color = Brand, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.size(11.dp))
        Column {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, fontSize = 12.sp, color = Muted)
        }
    }
}
