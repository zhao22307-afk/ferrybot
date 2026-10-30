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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 「更多」页：素材、高级设置、帮助、危险操作。
 */
@Composable
fun MoreTab(
    vm: AppViewModel,
    onOpenMaterial: () -> Unit,
    onOpenAdvanced: () -> Unit,
    onOpenCommands: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val f = vm.form

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        GroupLabel("内容与扩展")
        GroupCard {
            ItemRow(
                "素材库",
                desc = "表情包 ${vm.emojis.size} · 图片 ${vm.images.size} · 插件 ${vm.skills.size}",
                showArrow = true,
                onClick = onOpenMaterial,
            )
            RowDivider()
            ItemRow(
                "高级设置",
                desc = "内容过滤、打字延迟、自动撤回、消息前缀",
                showArrow = true,
                onClick = onOpenAdvanced,
            )
        }

        GroupLabel("通用")
        GroupCard {
            ItemRow("命令说明", desc = "看看在 QQ 里能发什么", showArrow = true, onClick = onOpenCommands)
            RowDivider()
            ItemRow("运行日志", desc = "它最近在干什么", showArrow = true, onClick = onOpenLogs)
            RowDivider()
            ItemRow("关于", desc = "版本、实现说明、免责声明", showArrow = true, onClick = onOpenAbout)
        }

        GroupLabel("危险操作")
        GroupCard {
            ItemRow(
                "重置机器人代码",
                desc = "改了 JS 或升级后行为没变时点一下，人设和记忆保留",
                showArrow = true,
                onClick = { vm.reinstallBot() },
            )
        }

        Spacer(Modifier.height(18.dp))
        Text(
            "当前接入：" + if (f.channel == "onebot") "真实QQ" else "QQ 官方机器人" +
                " · 模型：${f.model.ifBlank { "未设置" }}",
            fontSize = 12.sp,
            color = Muted,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(24.dp))
    }
}

/* ---------------- 素材库 ---------------- */

@Composable
fun MaterialPage(vm: AppViewModel, onPickEmoji: () -> Unit, onPickImage: () -> Unit, onPickSkill: () -> Unit) {
    val ctx = LocalContext.current
    // 链接库、素材标签都是文件里读的，进页面刷一次
    LaunchedEffect(Unit) {
        vm.refreshLinks()
        vm.loadMediaTags()
    }
    var linkTitle by remember { mutableStateOf("") }
    var linkUrl by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        GroupLabel("表情包", hint = if (vm.emojis.isEmpty()) "空" else "${vm.emojis.size} 张")
        GroupCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChipButton("＋ 添加图片", onPickEmoji)
                Spacer(Modifier.size(10.dp))
                Text("聊天时随机配一张", fontSize = 12.sp, color = Muted)
            }
            if (vm.emojis.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    vm.emojis.take(4).forEach { name ->
                        PictureThumb(vm.emojiFile(name), name) { vm.deleteEmoji(name) }
                    }
                    if (vm.emojis.size > 4) {
                        Box(
                            Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(appField()),
                            contentAlignment = Alignment.Center,
                        ) { Text("+${vm.emojis.size - 4}", fontSize = 13.sp, color = Muted) }
                    }
                }
            }
        }

        GroupLabel("图片", hint = if (vm.images.isEmpty()) "空" else "${vm.images.size} 张")
        GroupCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChipButton("＋ 添加图片", onPickImage)
                Spacer(Modifier.size(10.dp))
                Text("聊天时偶尔发一张", fontSize = 12.sp, color = Muted)
            }
            if (vm.images.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    vm.images.take(4).forEach { name ->
                        PictureThumb(vm.imageFile(name), name) { vm.deleteImage(name) }
                    }
                    if (vm.images.size > 4) {
                        Box(
                            Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(appField()),
                            contentAlignment = Alignment.Center,
                        ) { Text("+${vm.images.size - 4}", fontSize = 13.sp, color = Muted) }
                    }
                }
            }
        }

        if (vm.mediaDiag.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(vm.mediaDiag, fontSize = 12.sp, color = Muted, lineHeight = 18.sp)
        }

        GroupLabel("链接库", hint = if (vm.links.isEmpty()) "空" else "${vm.links.size} 条")
        GroupCard {
            Text(
                "机器人按「带链接概率」从这里随机挑一条发。点链接可以打开看看。",
                fontSize = 12.sp,
                color = Muted,
                lineHeight = 18.sp,
            )
            vm.links.forEach { (title, url) ->
                RowDivider()
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier
                            .weight(1f)
                            .clickable { openUrl(ctx, url) }
                    ) {
                        Text(
                            title,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                        Text(url, fontSize = 11.sp, color = Muted, maxLines = 1)
                    }
                    Box(
                        Modifier.clip(RoundedCornerShape(8.dp))
                            .clickable { vm.deleteLink(url) }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text("删除", fontSize = 12.sp, color = Color(0xFFE5484D))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            FieldLabel("链接标题")
            InputBox(linkTitle, "如 我的博客") { linkTitle = it }
            FieldLabel("链接地址")
            InputBox(linkUrl, "https://...") { linkUrl = it }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                ChipButton("添加") {
                    vm.addLink(linkTitle, linkUrl)
                    linkTitle = ""
                    linkUrl = ""
                }
                Spacer(Modifier.size(10.dp))
                Text("存在 bot/data/links.json", fontSize = 12.sp, color = Muted)
            }
        }

        GroupLabel("素材标签", hint = "AI 按语境挑素材用")
        GroupCard {
            Text(
                "给表情包/图片打标签，AI 才能按语境挑；文件名本身也算标签。\n" +
                    "每行一条，格式：文件名=标签，例如 a01.png=开心,b01.gif=无语",
                fontSize = 12.sp,
                color = Muted,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(10.dp))
            InputArea(vm.mediaTagsText, lines = 6) { vm.mediaTagsText = it }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                ChipButton("保存标签") { vm.saveMediaTags(vm.mediaTagsText) }
                Spacer(Modifier.size(10.dp))
                Text("存在 bot/data/media-tags.json", fontSize = 12.sp, color = Muted)
            }
        }

        GroupLabel("技能（插件）", hint = if (vm.skills.isEmpty()) "无" else "${vm.skills.size} 个")
        GroupCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChipButton("＋ 导入技能(.zip)", onPickSkill)
                Spacer(Modifier.size(10.dp))
                Text("导入后自动生效（最多 1 分钟）", fontSize = 12.sp, color = Muted)
            }
            Text(
                "技能分两种：\n" +
                    "· JS 技能：单个 .mjs / .js，或 .zip（里面放 index.mjs / main.mjs 都行）\n" +
                    "  纯 JS 跑在内置 Node 里，能用 Node 内置模块和 fetch；不自动装 npm 包（依赖自己打进 zip）\n" +
                    "· 进程技能：目录里放 plugin.json，命令在 Linux 环境里跑（Python / shell 都行）\n" +
                    "  Python 要先在「接入 → 运行环境」把「Python 3 支持」装上\n" +
                    "· JS 写法：export default { name, description, priority, match, handle }",
                fontSize = 12.sp,
                color = Muted,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                "// plugin.json（进程技能）\n" +
                    "{\"name\":\"查天气\",\"match\":[\"天气\"],\n" +
                    " \"cmd\":[\"python3\",\"/skills/天气/main.py\"]}",
                fontSize = 11.sp,
                color = Muted,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                ChipButton("导入示例技能") { vm.importDemoSkill() }
                Spacer(Modifier.size(10.dp))
                Text("装好发「ping」试一下", fontSize = 12.sp, color = Muted)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                ChipButton("导入示例进程技能") { vm.importDemoProcessSkill() }
                Spacer(Modifier.size(10.dp))
                Text("装好发「进程测试」", fontSize = 12.sp, color = Muted)
            }
            vm.skills.forEach { s ->
                RowDivider()
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            s.name + (if (s.kind == "process") "  · 进程" else "") + if (s.enabled) "" else "（已停用）",
                            fontSize = 14.sp,
                            color = if (s.enabled) MaterialTheme.colorScheme.onSurface else Muted,
                            fontWeight = FontWeight.Medium,
                        )
                        if (s.desc.isNotBlank()) {
                            Text(s.desc, fontSize = 12.sp, color = Muted)
                        }
                    }
                    Box(
                        Modifier.clip(RoundedCornerShape(8.dp))
                            .clickable { vm.setSkillEnabled(s.key, !s.enabled) }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text(if (s.enabled) "停用" else "启用", fontSize = 12.sp, color = Brand)
                    }
                    Box(
                        Modifier.clip(RoundedCornerShape(8.dp))
                            .clickable { vm.deleteSkill(s.key) }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text("删除", fontSize = 12.sp, color = Color(0xFFE5484D))
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}


/* ---------------- 高级设置 ---------------- */

@Composable
fun AdvancedPage(vm: AppViewModel) {
    val f = vm.form

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        GroupLabel("内容过滤", hint = "含这些词的消息不入历史、不回复")
        GroupCard {
            FieldLabel("敏感词（逗号分隔）")
            InputBox(f.filterWords, "如 广告,红包,加群") { v ->
                vm.updateForm { it.copy(filterWords = v) }
            }
            RowDivider()
            FieldLabel("黑名单用户（openid 逗号分隔）")
            InputBox(f.blacklistUsers, "不回复这些人的消息") { v ->
                vm.updateForm { it.copy(blacklistUsers = v) }
            }
            RowDivider()
            FieldLabel("黑名单群（群号逗号分隔）")
            InputBox(f.blacklistGroups, "不在这些群里回复") { v ->
                vm.updateForm { it.copy(blacklistGroups = v) }
            }
        }

        GroupLabel("细节", hint = "高级调参")
        GroupCard {
            FieldLabel("模拟打字延迟（毫秒）")
            RangeRow(
                "最小", "最大", f.typingDelayMin, f.typingDelayMax,
                { v -> vm.updateForm { it.copy(typingDelayMin = v) } },
                { v -> vm.updateForm { it.copy(typingDelayMax = v) } },
            )
            RowDivider()
            FieldLabel("自动撤回（秒，0=不撤回）")
            SelectBox(
                f.recallSeconds,
                listOf("0" to "不撤回", "10" to "10 秒后撤回", "30" to "30 秒后撤回", "60" to "60 秒后撤回"),
            ) { v -> vm.updateForm { it.copy(recallSeconds = v) } }
            RowDivider()
            FieldLabel("消息前缀（如 [bot]）")
            InputBox(f.msgPrefix, "每条回复前面加的前缀，留空不加") { v ->
                vm.updateForm { it.copy(msgPrefix = v) }
            }
        }

        GroupLabel("排查", hint = "看不清存没存时用它")
        GroupCard {
            var dump by remember { mutableStateOf<String?>(null) }
            ItemRow(
                "已保存的配置",
                desc = "看 .env 里实际存了什么（密钥打码）",
                showArrow = true,
            ) { dump = vm.envDump() }
            if (dump != null) {
                AlertDialog(
                    onDismissRequest = { dump = null },
                    title = { Text("已保存的配置", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
                    text = {
                        Text(
                            dump ?: "",
                            fontSize = 11.5.sp,
                            lineHeight = 17.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { dump = null }) { Text("关闭") }
                    },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth()) {
            BigButton("保存设置", Modifier.fillMaxWidth()) { vm.save() }
        }
        Spacer(Modifier.height(24.dp))
    }
}