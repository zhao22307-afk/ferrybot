package com.qqbot.app.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qqbot.app.ConfigStore

/** 人设编辑页（人设文本 + 说话方式语料） */
@Composable
fun PersonaScreen(vm: AppViewModel, onPickStyle: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
        ) {
            GroupLabel("预设")
            GroupCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ConfigStore.PRESET_IDS.forEachIndexed { i, id ->
                        ChipButton(ConfigStore.PRESET_NAMES[i]) { vm.applyPreset(id) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "点一下就会替换下面的内容，记得保存",
                    fontSize = 12.sp, color = Muted,
                )
            }

            GroupLabel("人设内容")
            GroupCard {
                InputArea(vm.prompt, lines = 16, modifier = Modifier.fillMaxWidth()) { vm.updatePrompt(it) }
            }

            GroupLabel("说话方式（语料）", hint = "可选")
            GroupCard {
                val info = vm.styleInfo
                if (info == null) {
                    Text(
                        "没导入。可以选一段聊天记录、或几句你喜欢的话，让它照着那个语气说。",
                        fontSize = 13.sp, color = appInk2(), lineHeight = 20.sp,
                    )
                } else {
                    Text(
                        "已导入 ${info.count} 条" + (if (info.isDialog) "对话" else "句子"),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "${info.chars} 字 · 来源 ${info.source.ifBlank { "文件" }}",
                        fontSize = 12.sp, color = Muted,
                    )
                }
                Spacer(Modifier.height(10.dp))
                if (info == null) {
                    BigButton("导入语料", Modifier.fillMaxWidth()) { onPickStyle() }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BigButton("重新导入", Modifier.weight(1f)) { onPickStyle() }
                        BigButton("清空", Modifier.weight(1f), filled = false) { vm.clearStyle() }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "支持 .txt / .json / .jsonl。写成「我：… / 对方：…」这样的对话行效果最好，一行一句话也行。",
                    fontSize = 12.sp, color = Muted, lineHeight = 18.sp,
                )
                Text(
                    "只学语气、用词和标点，不会照抄内容；每次请求最多带约 1200 字，太长的会自动截取。",
                    fontSize = 12.sp, color = Muted, lineHeight = 18.sp,
                )
            }

            GroupLabel("写人设的小建议")
            GroupCard {
                Tip("说清「它叫什么、多大、什么性格」")
                Tip("用「不要」列出你不想要的说话方式")
                Tip("给两三句你满意的话当例子")
                Tip("别写太长，600 字以内效果最好")
                Spacer(Modifier.height(4.dp))
            }

            Spacer(Modifier.height(20.dp))
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            BigButton("返回", Modifier.weight(1f), filled = false) { onBack() }
            Spacer(Modifier.height(0.dp))
            Spacer(Modifier.width(10.dp))
            BigButton("保存人设", Modifier.weight(1f)) { vm.savePromptOnly() }
        }
    }
}

@Composable
private fun Tip(text: String) {
    Text(
        "· $text",
        fontSize = 12.sp,
        color = appInk2(),
        lineHeight = 20.sp,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}
