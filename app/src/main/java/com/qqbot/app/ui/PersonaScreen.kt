package com.qqbot.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qqbot.app.ConfigStore

/** 人设编辑页 */
@Composable
fun PersonaScreen(vm: AppViewModel, onBack: () -> Unit) {
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
