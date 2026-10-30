package com.qqbot.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 「接入」页：机器人跑在哪个通道（官方 / 真实QQ）、用哪个模型、手机本地环境。
 */
@Composable
fun AccessTab(vm: AppViewModel) {
    var sec by remember { mutableStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        SegmentedChips(listOf("接入方式", "模型", "运行环境"), sec) { sec = it }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            when (sec) {
                0 -> ChannelSection(vm) { sec = 2 }
                1 -> ModelSection(vm)
                else -> EnvPage(vm)
            }
            Spacer(Modifier.height(16.dp))
        }

        if (sec != 2) SaveBar(vm)
    }
}

/* ---------------- 接入方式 ---------------- */

@Composable
private fun ChannelSection(vm: AppViewModel, onOpenEnv: () -> Unit) {
    val f = vm.form
    val ctx = LocalContext.current

    GroupLabel("接入方式", hint = if (f.channel == "onebot") "真实QQ" else "官方机器人")
    GroupCard {
        SelectBox(
            f.channel,
            listOf(
                "official" to "QQ 官方机器人（默认）",
                "onebot" to "真实QQ（自己搭协议端）",
            ),
        ) { v -> vm.updateForm { it.copy(channel = v) } }

        if (f.channel == "onebot") {
            Text(
                "手机只当客户端。先在电脑或服务器上跑 NapCat / Lagrange，把它的 WebSocket 地址填这里。\n" +
                    "用真实 QQ 号登录属于协议逆向，有封号风险，后果自负。",
                fontSize = 12.sp,
                color = Muted,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            FieldLabel("OneBot WebSocket 地址")
            InputBox(f.onebotUrl, "ws://192.168.1.10:3001") { v ->
                vm.updateForm { it.copy(onebotUrl = v) }
            }
            FieldLabel("Access Token（没设置就留空）")
            PasswordBox(f.onebotToken, "NapCat 里配的 token") { v ->
                vm.updateForm { it.copy(onebotToken = v) }
            }
            Spacer(Modifier.height(6.dp))
            SwitchLine("群里不 @ 也回", "关掉时只有 @ 机器人才回", f.onebotGroupAll) { v ->
                vm.updateForm { it.copy(onebotGroupAll = v) }
            }
            Spacer(Modifier.height(4.dp))
            RowDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "本机协议端：装机、启动、扫码",
                    fontSize = 13.5.sp,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                LinkText("去运行环境") { onOpenEnv() }
            }
            Spacer(Modifier.height(4.dp))
            RowDivider()
            Spacer(Modifier.height(10.dp))
            Text(
                "不想在手机上装环境：协议端放电脑/服务器上跑，这里填它的局域网地址，再点扫描自动填。",
                fontSize = 12.sp,
                color = Muted,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChipButton(if (vm.probing) "扫描中…" else "① 扫描本机") { vm.probeOneBot() }
                ChipButton("② 复制 Termux 命令") { vm.copyTermuxGuide() }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                ChipButton("Termux") { openUrl(ctx, "https://f-droid.org/packages/com.termux/") }
                ChipButton("Shamrock(root)") {
                    openUrl(ctx, "https://github.com/whitechi73/OpenShamrock/releases")
                }
                ChipButton("NapCat 教程") { openUrl(ctx, "https://napneko.github.io/guide/start") }
            }
            vm.probeResult?.let {
                Text(
                    it,
                    fontSize = 12.sp,
                    color = Muted,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            FieldLabel("QQ AppID")
            PasswordBox(f.appId, "开放平台里的 AppID") { v -> vm.updateForm { it.copy(appId = v) } }
            FieldLabel("QQ AppSecret")
            PasswordBox(f.appSecret, "开发设置里的密钥") { v -> vm.updateForm { it.copy(appSecret = v) } }
            Text(
                "在 q.qq.com 创建机器人后拿到。机器人是独立身份，不能用你自己的 QQ 号登录。",
                fontSize = 12.sp,
                color = Muted,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "想用自己的真实 QQ 号发消息？把上面「接入方式」切到「真实QQ」，" +
                    "这里会换成地址/Token/扫描本机，要去「运行环境」里装协议端。",
                fontSize = 12.sp,
                color = Muted,
                lineHeight = 18.sp,
            )
        }
    }
}

/* ---------------- 模型 ---------------- */

@Composable
private fun ModelSection(vm: AppViewModel) {
    val f = vm.form

    GroupLabel("模型", "对话用的模型服务，不限千问")
    GroupCard {
        FieldLabel("模型 API 地址")
        InputBox(f.baseUrl, "填自己的，如 https://api.deepseek.com/v1") { v ->
                vm.updateForm { it.copy(baseUrl = v) }
            }
        Text(
            "留空就用内置默认服务，不写进配置。想换别家就填它的兼容地址（到 /v1 为止）：OpenAI、DeepSeek、Kimi、智谱、硅基流动、本地 vLLM/Ollama 都行。",
            fontSize = 12.sp,
            color = Muted,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            "配置里现在是：" + vm.savedBaseUrl,
            fontSize = 12.sp,
            color = Brand,
            modifier = Modifier.padding(top = 4.dp),
        )

        FieldLabel("模型 API Key")
        PasswordBox(f.apiKey, "sk- 开头的密钥，留空则调不通，必须有") { v ->
                vm.updateForm { it.copy(apiKey = v) }
            }

        FieldLabel("用哪个模型")
        if (vm.models.isNotEmpty()) {
            SelectBox(
                f.model,
                listOf("" to "默认（不填就按服务端推荐的来）") + vm.models.map { it to it },
            ) { v ->
                vm.updateForm { it.copy(model = v) }
            }
        } else {
            InputBox(f.model, "留空用默认；也可填 deepseek-chat、qwen3-max、gpt-4o-mini 这类名字") { v ->
                vm.updateForm { it.copy(model = v) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChipButton(if (vm.fetchingModels) "拉取中…" else "⟳ 拉取模型列表") { vm.fetchModels() }
            Spacer(Modifier.width(10.dp))
            Text(
                when {
                    vm.fetchingModels -> "正在问服务端…"
                    vm.models.isNotEmpty() -> "已拿到 ${vm.models.size} 个"
                    else -> "按你填的 Key 拉可选模型"
                },
                fontSize = 12.sp,
                color = Muted,
            )
        }
        vm.modelError?.let { err ->
            Text(
                err,
                fontSize = 12.sp,
                color = Danger,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }

    GroupLabel("自检")
    GroupCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChipButton(if (vm.checking) "检测中…" else "运行自检") { vm.selfCheck() }
            Spacer(Modifier.width(10.dp))
            Text("测凭证和模型接口通不通", fontSize = 12.sp, color = Muted)
        }
        vm.checkLines.forEach { line ->
            Text(
                line,
                fontSize = 12.sp,
                color = if (line.contains("正常")) Ok else appInk2(),
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/* ---------------- 运行环境 ---------------- */

@Composable
private fun EnvPage(vm: AppViewModel) {
    GroupLabel("手机本地环境", hint = "proot + 协议端")
    GroupCard {
        EnvSection(vm)
    }
    HintText(
        "NapCat（跑真实QQ）：约下载 470MB、占地 1.5-1.8GB。\n" +
            "本地语音 Piper：约下载 120MB、占地 300MB，装完手机就能离线合成语音。\n" +
            "都建议在 WiFi 下装；国产系统可能在后台杀进程，装完在系统设置里给本应用开「允许后台活动」。"
    )

    GroupLabel("下载", hint = "网慢先调这里")
    GroupCard {
        FieldLabel("同时下载（并发数）")
        SelectBox(
            vm.downloadConns.toString(),
            listOf(
                "1" to "1 路 · 最稳但最慢",
                "2" to "2 路",
                "3" to "3 路",
                "4" to "4 路 · 推荐",
                "6" to "6 路 · 网好时最快",
            ),
            onChange = { vm.setConns(it.toIntOrNull() ?: 4) },
        )
        RowDivider()
        SwitchLine(
            "自动测速选源",
            "下载前先测各镜像/加速代理，用最快的那个；卡住 15 秒自动换下一个",
            vm.smartSource,
        ) { vm.setSmart(it) }
        RowDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChipButton("测速：看哪个源快") { vm.testSources() }
        }
    }
    HintText(
        "如果测速都不超过 100KB/s，多半是本机网络/运营商对这几个域名限速，" +
            "换个 WiFi、开关飞行模式或走流量再试。"
    )

    GroupLabel("下载源", hint = "可手动指定")
    GroupCard {
        val packId = vm.envPackId // 换协议端后这里要跟着换
        val tick = vm.sourceTick // 选完源要重组
        val speeds = vm.sourceSpeeds
        val files = if (packId.isBlank()) emptyList() else vm.packDownloads()
        if (files.isEmpty()) {
            Text("没有可选的下载源", fontSize = 12.sp, color = Muted)
        }
        files.forEachIndexed { fi, d ->
            if (fi > 0) RowDivider()
            FieldLabel(d.file)
            val opts = ArrayList<Pair<String, String>>(4)
            opts.add("-1" to "自动 · 测速选最快")
            vm.candidatesFor(d.file).forEachIndexed { k, u ->
                val s = speeds[u]
                val sp = when {
                    s == null -> ""
                    s <= 0L -> " · 连不上"
                    s >= 1024L * 1024L -> String.format(" · %.1f MB/s", s / 1048576.0)
                    else -> String.format(" · %d KB/s", s / 1024L)
                }
                opts.add(k.toString() to (vm.sourceName(u) + sp))
            }
            SelectBox(
                vm.sourceChoice(d.file).toString(),
                opts,
                onChange = { vm.setSourceChoice(d.file, it.toIntOrNull() ?: -1) },
            )
        }
        if (tick < 0) Unit
    }
    HintText("先点上面的「测速」，这里会标出每个源的速度；「连不上」的别选。选定的源会先试，失败才自动换下一个。")
    Box(Modifier.fillMaxWidth().height(4.dp))
}