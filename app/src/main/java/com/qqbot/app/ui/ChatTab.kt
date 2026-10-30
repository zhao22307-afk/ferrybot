package com.qqbot.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qqbot.app.ConfigStore

/**
 * 「对话」页：机器人怎么说话、什么时候主动说话、发什么。
 * 分成四个分区，用顶部胶囊切换，避免一长条滚动找不到东西。
 */
@Composable
fun ChatTab(vm: AppViewModel, onEditPersona: () -> Unit, onPickVoicePack: () -> Unit = {}) {
    var sec by remember { mutableStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        SegmentedChips(listOf("人设", "主动消息", "消息", "语音", "记忆", "联网"), sec) { sec = it }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            when (sec) {
                0 -> PersonaSection(vm, onEditPersona)
                1 -> ProactiveSection(vm)
                2 -> MessageSection(vm)
                3 -> VoiceSection(vm, onPickVoicePack)
                4 -> MemorySection(vm)
                else -> NetworkSection(vm)
            }
            Spacer(Modifier.height(16.dp))
        }

        SaveBar(vm)
    }
}

/* ---------------- 人设 ---------------- */

@Composable
private fun PersonaSection(vm: AppViewModel, onEditPersona: () -> Unit) {
    val f = vm.form

    GroupLabel("人格")
    GroupCard {
        ItemRow(
            "当前人设",
            desc = vm.prompt.lineSequence().firstOrNull { it.isNotBlank() }?.take(28) ?: "未设置",
            showArrow = true,
            onClick = onEditPersona,
        )
        RowDivider()
        FieldLabel("它的名字")
        InputBox(f.botName, "不填就用人设里写的名字（默认 ferrybot）") { v ->
            vm.updateForm { it.copy(botName = v) }
        }
        Text(
            "改了名字，人设里「你叫…」会自动跟着改；预设也会带自己的名字。改完点右上角「保存」。",
            fontSize = 12.sp,
            color = Muted,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        RowDivider()
        Text(
            "快速套用",
            fontSize = 12.sp,
            color = Muted,
            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ConfigStore.PRESET_IDS.forEachIndexed { i, id ->
                ChipButton(ConfigStore.PRESET_NAMES[i]) { vm.applyPreset(id) }
            }
        }
        Spacer(Modifier.height(4.dp))
    }

    GroupLabel("回复风格")
    GroupCard {
        FieldLabel("一次回几条消息的概率")
        InputBox(f.msgCountW, "60,30,10") { v -> vm.updateForm { it.copy(msgCountW = v) } }
        Text(
            "按「回 1 条 / 2 条 / 3 条」的顺序填权重，程序自动按比例随机。留空 = 用内置默认。",
            fontSize = 12.sp,
            color = Muted,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        FieldLabel("每条消息写几句的概率")
        InputBox(f.sentW, "65,25,10") { v -> vm.updateForm { it.copy(sentW = v) } }
        Text(
            "按「1 句 / 2 句 / 3 句」的顺序填权重。全填 0 或不填，就走下面的整体风格。",
            fontSize = 12.sp,
            color = Muted,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        FieldLabel("快捷风格（会覆盖上面两个概率）")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChipButton("话少") {
                vm.updateForm { it.copy(msgCountW = "85,12,3", sentW = "75,20,5") }
            }
            ChipButton("正常") {
                vm.updateForm { it.copy(msgCountW = "60,30,10", sentW = "65,25,10") }
            }
            ChipButton("话多") {
                vm.updateForm { it.copy(msgCountW = "25,40,35", sentW = "35,40,25") }
            }
        }
        FieldLabel("偶尔主动发语音")
        SelectBox(
            f.voiceChance,
            listOf("0" to "从不", "0.08" to "偶尔（8%）", "0.15" to "有时（15%）", "0.3" to "经常（30%）"),
        ) { v -> vm.updateForm { it.copy(voiceChance = v) } }
    }
}

/* ---------------- 主动消息 ---------------- */

@Composable
private fun ProactiveSection(vm: AppViewModel) {
    val f = vm.form

    GroupLabel("主动消息", hint = if (f.proactive) "已开启" else "已关闭")
    GroupCard {
        SwitchLine("让它主动找你", "关掉就永远不主动发消息", f.proactive) { v ->
            vm.updateForm { it.copy(proactive = v) }
        }
        if (f.proactive) {
            RowDivider()
            FieldLabel("每天最多几条")
            NumberBox(f.proactiveMaxDay, "3", Modifier.fillMaxWidth()) { v ->
                // 老字段 maxDay 给首页概况显示用，这里同步更新，别让两处数字不一样
                vm.updateForm { it.copy(proactiveMaxDay = v, maxDay = v) }
            }
            Text(
                "填 1~20 之间的整数。条数越多越容易被嫌烦，建议 2~5 条。",
                fontSize = 12.sp,
                color = Muted,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
            FieldLabel("间隔（小时）")
            RangeRow(
                "最短", "最长", f.gapMin, f.gapMax,
                { v -> vm.updateForm { it.copy(gapMin = v) } },
                { v -> vm.updateForm { it.copy(gapMax = v) } },
            )
            FieldLabel("时段（24 小时制）")
            RangeRow(
                "开始", "结束", f.hourStart, f.hourEnd,
                { v -> vm.updateForm { it.copy(hourStart = v) } },
                { v -> vm.updateForm { it.copy(hourEnd = v) } },
            )
            FieldLabel("对方嫌烦时静音（天）")
            NumberBox(f.pauseDays, "7", Modifier.fillMaxWidth()) { v ->
                vm.updateForm { it.copy(pauseDays = v) }
            }
            RowDivider()
            FieldLabel("主动消息里带语音的概率（%）")
            NumberBox(f.proactiveVoiceChance, "15", Modifier.fillMaxWidth()) { v ->
                vm.updateForm { it.copy(proactiveVoiceChance = v) }
            }
            Text(
                "填 0~100。比如 15 就是大约每 7 条主动消息里有一条是语音。",
                fontSize = 12.sp,
                color = Muted,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
            FieldLabel("主动消息里带链接的概率（%）")
            NumberBox(f.linkChanceProactive, "0", Modifier.fillMaxWidth()) { v ->
                vm.updateForm { it.copy(linkChanceProactive = v) }
            }
            Text(
                "链接从「素材 → 链接库」里随机挑。填 0 = 主动消息不带链接。",
                fontSize = 12.sp,
                color = Muted,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
            RowDivider()
            FieldLabel("主动消息风格")
            SelectBox(
                f.proactiveStyle,
                listOf(
                    "mix" to "混合（随机切换）",
                    "caring" to "关心型（多穿衣服之类）",
                    "flirty" to "暧昧型（想你之类）",
                    "roast" to "吐槽型（今天又咋了）",
                    "custom" to "自定义模板",
                ),
            ) { v -> vm.updateForm { it.copy(proactiveStyle = v) } }
            if (f.proactiveStyle == "custom") {
                FieldLabel("自定义模板")
                InputBox(f.proactiveTemplate, "用 {name} 占位对方名，如「{name} 在干嘛呢」") { v ->
                    vm.updateForm { it.copy(proactiveTemplate = v) }
                }
            }
            RowDivider()
            FieldLabel("只对谁发")
            val targets = f.proactiveTargets.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            Text(
                if (targets.isEmpty()) "当前：所有聊过的人" else "当前：已选 ${targets.size} 个目标",
                fontSize = 12.sp,
                color = Muted,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
            var pickOpen by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                ChipButton(if (pickOpen) "收起最近联系人" else "从最近聊过的人里选") {
                    pickOpen = !pickOpen
                    if (pickOpen) vm.refreshContacts()
                }
            }
            if (pickOpen) {
                // 直接在本页展开，不走弹窗：联系人列表本来就是选着看的
                Spacer(Modifier.height(4.dp))
                if (vm.contacts.isEmpty()) {
                    Text(
                        "还没有联系人记录：机器人跑起来、有人聊过之后这里才会出现。",
                        fontSize = 12.sp,
                        color = Muted,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                } else {
                    vm.contacts.forEach { (id, name) ->
                        val on = id in targets
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val next = if (on) targets - id else targets + id
                                    vm.updateForm { it.copy(proactiveTargets = next.joinToString(",")) }
                                }
                                .padding(vertical = 6.dp),
                        ) {
                            Text(if (on) "☑" else "☐", fontSize = 16.sp, color = if (on) Brand else Muted)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    name.ifBlank { id },
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    id + (vm.contactLast[id]?.let { t -> " · $t" } ?: ""),
                                    fontSize = 11.sp,
                                    color = Muted,
                                    lineHeight = 16.sp,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
            FieldLabel("手动输入（openid 逗号分隔，空=全部）")
            InputBox(f.proactiveTargets, "不填就是对所有聊过的人") { v ->
                vm.updateForm { it.copy(proactiveTargets = v) }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

/* ---------------- 消息 ---------------- */

@Composable
private fun MessageSection(vm: AppViewModel) {
    val f = vm.form

    GroupLabel("消息类型", hint = "控制回复时发什么")
    GroupCard {
        SwitchLine("文本", "发文字消息", f.msgText) { v -> vm.updateForm { it.copy(msgText = v) } }
        WeightRow("文本权重", f.msgTextW) { v -> vm.updateForm { it.copy(msgTextW = v) } }
        RowDivider()
        SwitchLine("图片", "随机发 image/ 里的图片", f.msgImage) { v ->
            vm.updateForm { it.copy(msgImage = v) }
        }
        WeightRow("图片权重", f.msgImageW) { v -> vm.updateForm { it.copy(msgImageW = v) } }
        RowDivider()
        SwitchLine("语音", "发语音回复", f.msgVoice) { v -> vm.updateForm { it.copy(msgVoice = v) } }
        WeightRow("语音权重", f.msgVoiceW) { v -> vm.updateForm { it.copy(msgVoiceW = v) } }
        RowDivider()
        SwitchLine("表情包", "随机配一张表情包", f.msgEmoji) { v ->
            vm.updateForm { it.copy(msgEmoji = v) }
        }
        WeightRow("表情包权重", f.msgEmojiW) { v -> vm.updateForm { it.copy(msgEmojiW = v) } }
        Text(
            "权重 = 同一次回复里各类的相对比例。比如 70 / 1 / 8 / 8，大约就是 7 条文字配 1 张图、1 条语音、1 张表情包。",
            fontSize = 12.sp,
            color = Muted,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 8.dp),
        )

        RowDivider()
        FieldLabel("附带表情包概率（%）")
        NumberBox(f.emojiChance, "8", Modifier.fillMaxWidth()) { v ->
            vm.updateForm { it.copy(emojiChance = v) }
        }
        FieldLabel("附带图片概率（%）")
        NumberBox(f.imageChance, "5", Modifier.fillMaxWidth()) { v ->
            vm.updateForm { it.copy(imageChance = v) }
        }
        FieldLabel("纯语音概率（%）")
        NumberBox(f.voiceOnlyChance, "15", Modifier.fillMaxWidth()) { v ->
            vm.updateForm { it.copy(voiceOnlyChance = v) }
        }
        FieldLabel("带链接概率（%）")
        NumberBox(f.linkChance, "0", Modifier.fillMaxWidth()) { v ->
            vm.updateForm { it.copy(linkChance = v) }
        }
        Text(
            "填 0~100，每次回复按这个概率触发；填 0 = 从不。",
            fontSize = 12.sp,
            color = Muted,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 6.dp),
        )

        RowDivider()
        SwitchLine(
            "让 AI 按语境挑表情/图片",
            "开启后模型自己决定发哪个素材，需要素材有标签（标签文件 media-tags.json，或文件名本身）。",
            f.mediaSmart,
        ) { v -> vm.updateForm { it.copy(mediaSmart = v) } }
    }

    GroupLabel("能力")
    GroupCard {
        SwitchLine("看图", "对方发图片时它能看懂", f.vision) { v -> vm.updateForm { it.copy(vision = v) } }
        RowDivider()
        SwitchLine("语音识别", "对方发语音时它能听懂", f.asr) { v -> vm.updateForm { it.copy(asr = v) } }
        RowDivider()
        SwitchLine("开机自启", "手机重启后自动运行", f.autoStart) { v -> vm.updateForm { it.copy(autoStart = v) } }
    }

    GroupLabel("指令", "#状态 / #磁盘 / #日志 / /usage / /reset 这类是给号主用的")
    GroupCard {
        SwitchLine(
            "指令只给管理员用",
            "开着：只有下面的 ID 发指令才有反应，别人发会被无视（日志里记一行）。",
            f.adminOnly,
        ) { v -> vm.updateForm { it.copy(adminOnly = v) } }
        if (f.adminOnly) {
            RowDivider()
            FieldLabel("管理员 ID（真实 QQ 填 QQ 号，官方机器人填 openid，多个用逗号隔开）")
            InputBox(f.adminIds, "如 123456789", Modifier.fillMaxWidth()) { v ->
                vm.updateForm { it.copy(adminIds = v) }
            }
            Text(
                "不知道自己的 ID：让你自己/别人先发一次 #状态，机器人日志里会写「忽略非管理员指令（那个ID）」，把那个 ID 填进来。",
                fontSize = 12.sp,
                color = Muted,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/* ---------------- 语音 ---------------- */

@Composable
private fun VoiceSection(vm: AppViewModel, onPickVoicePack: () -> Unit) {
    val f = vm.form
    val ctx = LocalContext.current

    GroupLabel(
        "语音与音色",
        hint = when (f.ttsProvider) {
            "openai" -> "OpenAI 兼容"
            "local" -> "本地模型"
            else -> "千问 / DashScope"
        },
    )
    GroupCard {
        SwitchLine("语音回复", "它能用语音回话", f.tts) { v -> vm.updateForm { it.copy(tts = v) } }
        RowDivider()

        FieldLabel("语音服务商")
        SelectBox(
            f.ttsProvider,
            listOf(
                "dashscope" to "千问 / DashScope（阿里）",
                "openai" to "OpenAI 兼容（OpenAI、硅基流动、自建…）",
                "local" to "本地 TTS 服务（自己电脑跑）",
            ),
        ) { v ->
            // 换服务商时把「这一家专用」的三项清空，自动落回这家自己的默认值，
            // 免得把百炼的地址/音色名带到 OpenAI 那边去
            if (v != f.ttsProvider) {
                vm.updateForm { it.copy(ttsProvider = v, ttsBase = "", ttsModel = "", ttsVoice = "") }
            }
        }
        val pName = when (f.ttsProvider) {
            "openai" -> "OpenAI 兼容"
            "local" -> "本地 TTS"
            else -> "千问 / 百炼"
        }
        val baseNow = f.ttsBase.ifBlank {
            when (f.ttsProvider) {
                "openai" -> "api.openai.com（默认）"
                "local" -> "本地地址（见下面）"
                else -> "百炼官方（默认）"
            }
        }
        val keyNow = if (f.ttsKey.isNotBlank()) "单独填的语音 Key" else "跟聊天共用的 Key"
        Text(
            "现在生效：$pName · 地址 $baseNow · $keyNow。\n" +
                "语音和聊天是分开的：聊天用「接入 → 模型」里的 Key/地址；语音可以用别的服务商、\n" +
                "另一个 Key（比如聊天走中转网关，语音单独用百炼/OpenAI 官方 Key）。",
            fontSize = 12.sp,
            color = Muted,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
        )

        if (f.ttsProvider == "openai") {
            FieldLabel("TTS 接口地址")
            InputBox(f.ttsBase, "留空用默认；如 https://api.openai.com/v1（到 /v1 为止）") { v ->
                vm.updateForm { it.copy(ttsBase = v) }
            }
            FieldLabel("TTS Key（留空 = 用「模型」里的 Key）")
            PasswordBox(f.ttsKey, "留空 = 用「模型」里的 Key") { v ->
                vm.updateForm { it.copy(ttsKey = v) }
            }
            FieldLabel("TTS 模型")
            InputBox(f.ttsModel, "留空用默认；如 tts-1、gpt-4o-mini-tts、FunAudioLLM/CosyVoice2-0.5B") { v ->
                vm.updateForm { it.copy(ttsModel = v) }
            }
            FieldLabel("音色（voice）")
            InputBox(f.ttsVoice, "留空用默认；如 alloy、nova 或服务商给的音色名") { v ->
                vm.updateForm { it.copy(ttsVoice = v) }
            }
            Text(
                "不同服务商音色名不一样，看它的文档；不支持时接口会返回 400，日志里能看到。\n" +
                    "费用按服务商实际计费（一般按字符或按秒）。",
                fontSize = 12.sp,
                color = Muted,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        if (f.ttsProvider == "dashscope") {
        Text(
            "用千问语音：地址、Key 都可以留空。\n" +
                "· 地址留空 = 阿里云百炼 https://dashscope.aliyuncs.com/api/v1\n" +
                "· Key 留空 = 用「模型」里的 Key\n" +
                "注意：语音走的是百炼。如果「模型」里填的是别的中转网关地址，那个 Key 在百炼上不一定有效，" +
                "那就单独把百炼 Key 填在下面。",
            fontSize = 12.sp,
            color = Muted,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )
        FieldLabel("语音地址（高级，留空 = 百炼官方）")
        InputBox(f.ttsBase, "留空 = https://dashscope.aliyuncs.com/api/v1") { v ->
            vm.updateForm { it.copy(ttsBase = v) }
        }
        FieldLabel("语音 Key（留空 = 用「模型」里的 Key）")
        PasswordBox(f.ttsKey, "阿里云百炼的 Key（sk- 开头）") { v ->
            vm.updateForm { it.copy(ttsKey = v) }
        }
        SelectBox(
            f.ttsMode,
            listOf(
                "preset" to "现成音色（最简单）",
                "clone" to "云端复刻（用你的声音 · 要付费）",
                "local" to "本地小模型（自己电脑跑）",
            ),
        ) { v -> vm.updateForm { it.copy(ttsMode = v) } }

        if (f.ttsMode == "preset") {
            FieldLabel("发语音时用哪个音色")
            SelectBox(
                f.ttsVoice,
                listOf(
                    "" to "默认（Cherry·女声·甜）",
                    "Cherry" to "Cherry（女声·甜）",
                    "Serena" to "Serena（女声·温柔）",
                    "Chelsie" to "Chelsie（女声·活泼）",
                    "Ethan" to "Ethan（男声）",
                    "Dylan" to "Dylan（男声·低沉）",
                ),
            ) { v -> vm.updateForm { it.copy(ttsVoice = v) } }
            FieldLabel("TTS 模型")
            InputBox(f.ttsModel, "留空用默认（qwen3-tts-flash）") { v ->
                vm.updateForm { it.copy(ttsModel = v) }
            }
        }

        if (f.ttsMode == "clone") {
            Text(
                "复刻你自己的声音，这里可以直接做完：\n" +
                    "1. 录 10~30 秒干净人声（安静环境、别加背景音乐），传到任意能公网访问的地方（网盘直链 / 图床 / 对象存储）；\n" +
                    "2. 把那个链接填到下面，点「创建音色」，服务商下载并分析后返回 voice_id；\n" +
                    "3. 创建成功后会自动填好「音色」和「TTS 模型」，保存设置即可。\n\n" +
                    "不需要公网链接的话，也可以去服务商控制台复刻，再把 voice_id 手填到下面。\n" +
                    "费用按对应平台实际计费为准（一般有音色开通费 + 按合成量收费）。声音必须是你本人的，或已取得明确授权。",
                fontSize = 12.sp,
                color = Muted,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            FieldLabel("音色名 / voice id")
            InputBox(f.ttsVoice, "复刻出来的 voice id，如 qwen-audio-3.1-tts-flash-myvoice-xxxx") { v ->
                vm.updateForm { it.copy(ttsVoice = v) }
            }
            FieldLabel("TTS 模型")
            InputBox(f.ttsModel, "留空用默认；复刻一般填 qwen-audio-3.1-tts-flash") { v ->
                vm.updateForm { it.copy(ttsModel = v) }
            }
            RowDivider()
            FieldLabel("录音的公网链接（用来创建音色）")
            InputBox(vm.voiceAudioUrl, "https://.../我的声音.wav 或 .mp3（10~30 秒干净人声）") { v ->
                vm.updateVoiceAudio(v)
            }
            FieldLabel("音色名前缀（英文/数字，方便你自己认出来）")
            InputBox(vm.voicePrefix, "myvoice") { v -> vm.updateVoicePrefix(v) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChipButton(if (vm.voiceEnrolling) "创建中…" else "创建音色") { vm.createVoice() }
                Spacer(Modifier.width(10.dp))
                Text("用上面那段录音生成自定义音色", fontSize = 12.sp, color = Muted)
            }
            vm.voiceEnrollMsg?.let { m ->
                Text(
                    m,
                    fontSize = 12.sp,
                    color = if (m.startsWith("✅")) Ok else Danger,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChipButton("打开百炼控制台") { openUrl(ctx, "https://bailian.console.aliyun.com/") }
                Spacer(Modifier.width(10.dp))
                Text("复刻服务的 Key 留空时，用的是「模型」里的 Key", fontSize = 12.sp, color = Muted)
            }
        }
        } // dashscope 分支结束

        if (f.ttsProvider == "local" || f.ttsMode == "local") {
            Text(
                "想手机自己出声（不用电脑）：去「接入 → 运行环境 → 手机本地环境」，把「安装内容」选成「本地语音 Piper」，点「下载并安装」，装好这里会自动填地址。\n" +
                "想用自己电脑/服务器上的 TTS：手机只把文字发过去取回音频。\n" +
                    "手机端跑不动克隆模型（几个 GB、纯 CPU 推理慢到没法聊），所以要在电脑上起一个 TTS 服务：\n" +
                    "· 想真克隆你的音色：GPT-SoVITS（小模型约 100MB，最好有 6G 以上显存）\n" +
                    "· 想省事：CosyVoice 本地版 / XTTS-v2\n" +
                    "服务跑起来后把 HTTP 地址填到下面，App 会 POST {text, voice, format:\"wav\"} 取 wav 音频。" +
                    "电脑要和你手机在同一个局域网，或者映射到公网。",
                fontSize = 12.sp,
                color = Muted,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            // 手机本地跑 Piper 才能选音色；音色包是用户自己导入的 .onnx/.onnx.json
            // 装了本地语音后（地址自动填成 127.0.0.1）或已经导入过音色包时也显示，否则默认音色藏得太深
            if (f.ttsProvider == "local" || f.localTtsUrl.contains("127.0.0.1") || vm.localVoices.isNotEmpty()) {
                LaunchedEffect(Unit) { vm.refreshVoices() }
                FieldLabel("音色")
                SelectBox(
                    f.ttsVoice,
                    if (vm.localVoices.isEmpty())
                        listOf("" to "未安装音色包（先在上面装本地语音，或点下面导入）")
                    else
                        listOf("" to "默认音色（第一个）") + vm.localVoices.map { it to it },
                ) { v -> vm.updateForm { it.copy(ttsVoice = v) } }
                FieldLabel("导入音色包")
                ChipButton("导入音色包（.zip）") { onPickVoicePack() }
                Text(
                    "音色包 = Piper 的 .onnx 模型 + 同名 .onnx.json，打成一个 zip 导入；" +
                        "导入后放进 rootfs 的 /root/piper/voices/<名字>/，上面就能选到。",
                    fontSize = 12.sp,
                    color = Muted,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 6.dp, bottom = 6.dp),
                )
            }
            FieldLabel("本地 TTS 地址（装 Piper 后自动填）")
            InputBox(f.localTtsUrl, "如 http://192.168.1.10:9880/tts（电脑和服务要在同一网络）") { v ->
                vm.updateForm { it.copy(localTtsUrl = v) }
            }
            FieldLabel("访问口令（服务没开鉴权就留空）")
            PasswordBox(f.localTtsKey, "服务没开鉴权就留空") { v ->
                vm.updateForm { it.copy(localTtsKey = v) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChipButton(if (vm.ttsQuickBusy) "合成中…" else "试听一句（测能不能用）") { vm.quickTestTts() }
                if (vm.ttsQuickWav != null) {
                    Spacer(Modifier.width(10.dp))
                    ChipButton("播放") { vm.playTts() }
                }
            }
            vm.ttsQuickResult?.let { t ->
                Text(
                    t,
                    fontSize = 12.sp,
                    color = if (t.startsWith("✅")) Ok else Danger,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChipButton("自检本地语音") { vm.testLocalTts() }
                Spacer(Modifier.width(10.dp))
                Text("装好却发不出声时点它", fontSize = 12.sp, color = Muted)
            }
            vm.ttsTest?.let { t ->
                Text(
                    t,
                    fontSize = 11.sp,
                    color = Muted,
                    lineHeight = 16.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                )
                ChipButton("复制自检结果") { vm.copyTtsTest() }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChipButton("下载 GPT-SoVITS") {
                    openUrl(ctx, "https://github.com/RVC-Boss/GPT-SoVITS/releases")
                }
                Spacer(Modifier.width(10.dp))
                Text("模型在电脑上下载", fontSize = 12.sp, color = Muted)
            }
        }
    }

    /* ---------- 语音识别 ---------- */

    GroupLabel("听懂语音", "它收到语音消息时，先转成文字再回")
    GroupCard {
        SwitchLine("语音转文字", "关掉就只能听声音、不懂内容", f.asr) { v ->
            vm.updateForm { it.copy(asr = v) }
        }
        RowDivider()

        // 服务商/地址/Key/模型这些平时不用改，收起来，别把页面撑长
        var asrOpen by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChipButton(if (asrOpen) "收起识别参数" else "识别参数（可选）") { asrOpen = !asrOpen }
            Spacer(Modifier.width(10.dp))
            Text(
                if (f.asr) "当前：" + if (f.asrProvider == "openai") "OpenAI 兼容" else "千问 / DashScope"
                else "未开启",
                fontSize = 12.sp,
                color = Muted,
            )
        }
        if (!asrOpen) return@GroupCard

        FieldLabel("识别服务商")
        SelectBox(
            f.asrProvider,
            listOf(
                "dashscope" to "千问 / DashScope（阿里）",
                "openai" to "OpenAI 兼容（whisper 等）",
            ),
        ) { v ->
            if (v != f.asrProvider) {
                vm.updateForm { it.copy(asrProvider = v, asrBase = "", asrModel = "") }
            }
        }

        FieldLabel("识别接口地址")
        InputBox(f.asrBase, "留空用默认；OpenAI 兼容地址，到 /v1 为止") { v -> vm.updateForm { it.copy(asrBase = v) } }
        FieldLabel("识别 Key（留空 = 用「模型」里的 Key）")
        PasswordBox(f.asrKey, "留空 = 用「模型」里的 Key") { v -> vm.updateForm { it.copy(asrKey = v) } }
        FieldLabel("识别模型")
        InputBox(
            f.asrModel,
            if (f.asrProvider == "openai") "whisper-1" else "qwen-audio-3.1-asr-flash",
        ) { v -> vm.updateForm { it.copy(asrModel = v) } }

        Text(
            (if (f.asrProvider == "openai")
                "走 OpenAI 的 /audio/transcriptions 接口，multipart 上传音频。OpenAI、Groq、本地 whisper.cpp 之类都兼容。"
            else
                "走 DashScope 的多模态接口（qwen-audio 系列）。换服务就把地址、Key、模型名一起换掉。") +
                "\n识别也可以和聊天分开：这里的 Key/地址只用于听懂语音，不影响聊天用的「模型」配置。",
            fontSize = 12.sp,
            color = Muted,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/* ---------------- 记忆 ---------------- */

@Composable
private fun MemorySection(vm: AppViewModel) {
    val f = vm.form

    GroupLabel("记忆", "记多少、记多久，直接决定花费")
    GroupCard {
        SwitchLine("记住这段聊天", "关掉后每次对话都像第一次认识", f.memoryOn) { v ->
            vm.updateForm { it.copy(memoryOn = v) }
        }
        RowDivider()
        SwitchLine("长期记忆", "把关于你的偏好、近期状况提炼成事实存下来", f.factsOn) { v ->
            vm.updateForm { it.copy(factsOn = v) }
        }

        Text(
            "长期记忆不是白记的：聊天攒到一定条数后，会额外发一次模型请求去提炼要点（每次约 300~1500 token）。\n" +
                "按 ¥1/百万 token 算，一次约 ¥0.0003~0.0015；聊得勤一天几次到十几次，基本可以忽略。\n" +
                "但如果用单价高的模型（十几到几十元/百万 token），这笔钱要乘十几倍。\n" +
                "关掉：不再产生新记忆，已经记下的内容保留；也更省钱、回复更快。",
            fontSize = 12.sp,
            color = Muted,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 8.dp),
        )

        RowDivider()
        FieldLabel("短期记忆：保留最近多少条消息")
        InputBox(f.maxHistory, "80") { v -> vm.updateForm { it.copy(maxHistory = v) } }
        Text(
            "每条几十个字，80 条约 2~3 千 token。条数越多，每次请求带的历史越长 —— 越贵、越慢，但更不容易忘事；" +
                "调小最省钱，适合只看单句的玩法。",
            fontSize = 12.sp,
            color = Muted,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 6.dp),
        )

        FieldLabel("上限：历史最多占多少字符")
        InputBox(f.maxChars, "16000") { v -> vm.updateForm { it.copy(maxChars = v) } }
        Text(
            "超过就丢掉最早的部分。和上一条共同决定「它还记得多久」。\n" +
                "费用 = 输入 token 数 × 模型单价，所以这两项本质是在给「记性」定价。",
            fontSize = 12.sp,
            color = Muted,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/* ---------------- 联网 ---------------- */

@Composable
private fun NetworkSection(vm: AppViewModel) {
    val f = vm.form

    GroupLabel("联网", "读链接 / 自己搜")
    GroupCard {
        SwitchLine("能读取链接内容", "对方发链接、或让它看某个网页时，它会抓正文再回", f.linkOn) { v ->
            vm.updateForm { it.copy(linkOn = v) }
        }
        RowDivider()
        FieldLabel("搜索服务")
        SelectBox(
            f.searchProvider,
            listOf(
                "" to "关闭（不搜索）",
                "tavily" to "Tavily",
                "serper" to "Serper（Google 结果）",
                "bing" to "Bing",
                "bocha" to "博查 Bocha（国内）",
            ),
        ) { v -> vm.updateForm { it.copy(searchProvider = v) } }
        FieldLabel("搜索 API 地址（可留空）")
        InputBox(f.searchBase, "留空用默认：${defaultSearchUrl(f.searchProvider)}") { v ->
            vm.updateForm { it.copy(searchBase = v) }
        }
        FieldLabel("搜索 API Key")
        PasswordBox(f.searchKey, "填了 Key 机器人才能自己搜") { v ->
            vm.updateForm { it.copy(searchKey = v) }
        }
        FieldLabel("每次取几条结果")
        NumberBox(f.searchMax, "5", Modifier.fillMaxWidth()) { v ->
            vm.updateForm { it.copy(searchMax = v) }
        }
        Text(
            "填了 Key 之后，机器人回复时可以作为工具自己触发搜索（模型输出 [SEARCH:…] 时自动去搜，再把结果说给你）。" +
                "关掉就不参与任何联网请求，省流量也更安全。",
            fontSize = 12.sp,
            color = Muted,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** 各搜索服务的默认接口地址，只用来做输入框里的占位提示 */
private fun defaultSearchUrl(provider: String): String = when (provider) {
    "tavily" -> "https://api.tavily.com"
    "serper" -> "https://google.serper.dev"
    "bing" -> "https://api.bing.microsoft.com/v7.0"
    "bocha" -> "https://api.bochaai.com/v1"
    else -> ""
}

/* ---------------- 小控件 ---------------- */

/** 权重输入：左边一行标签，右边整数框（和开关并排太挤，单独占一行） */
@Composable
private fun WeightRow(label: String, value: String, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, fontSize = 12.sp, color = Muted, modifier = Modifier.width(80.dp))
        NumberBox(value, "1~100", Modifier.weight(1f), onChange)
    }
}