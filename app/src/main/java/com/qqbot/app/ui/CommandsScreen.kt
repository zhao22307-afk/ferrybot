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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 命令说明：在 QQ 里发什么，机器人会做什么 */
@Composable
fun CommandsScreen() {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
    ) {
        GroupLabel("怎么用")
        GroupCard {
            Text(
                "不用打开这个 App，直接在 QQ 里给机器人发消息就行。\n" +
                    "下面这些是特殊指令，其余的话都当正常聊天处理。",
                fontSize = 12.sp, color = appInk2(), lineHeight = 20.sp,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }

        GroupLabel("查状态")
        GroupCard {
            CmdRow("/usage", "看花了多少钱", "调用次数、输入输出 tokens、累计费用")
            RowDivider()
            CmdRow("#状态", "看它现在怎么样", "在线多久、今天调用了多少次")
            RowDivider()
            CmdRow("#磁盘", "看手机剩多少空间", "总量、已用、剩余")
            RowDivider()
            CmdRow("#日志", "看最近发生了什么", "最近几行运行日志")
        }

        GroupLabel("清记忆")
        GroupCard {
            CmdRow("/reset", "清空你们的聊天记录", "它忘掉聊过什么，但还记得你是谁")
            RowDivider()
            CmdRow("/resetall", "全部忘掉", "连「你是谁」的画像一起删除，不可恢复")
            Spacer(Modifier.height(8.dp))
            Text(
                "清完之后它会像第一次见你一样",
                fontSize = 12.sp, color = Muted,
            )
        }

        GroupLabel("不用命令也能触发")
        GroupCard {
            CmdRow("发条语音", "它用语音回你", "「说句话」「想听你声音」「讲两句」都行")
            RowDivider()
            CmdRow("发一张图", "它会看图", "能理解图片内容再回话")
            RowDivider()
            CmdRow("发一条语音", "它会听", "先把语音转成文字再回复")
            RowDivider()
            CmdRow("别主动找我", "暂停主动消息", "默认静音 7 天，在「对话 → 主动消息」里改")
        }

        GroupLabel("补充")
        GroupCard {
            Bullet("不知道的事它不会瞎编，只会问一句「怎么了」")
            Bullet("回复太啰嗦？在「对话 → 消息」里调一条消息说几句")
            Bullet("嫌它话多？在「对话 → 主动消息」里关掉就行")
            Bullet("记忆存在本机，卸载 App 就没了；记多记少在「对话 → 记忆」里调")
        }

        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun CmdRow(cmd: String, title: String, desc: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .width(96.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(appBrandSoft())
                .padding(horizontal = 7.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                cmd,
                color = Brand,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = appInk(), fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(desc, fontSize = 12.sp, color = Muted, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.padding(vertical = 5.dp)) {
        Text("·", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(end = 8.dp))
        Text(text, fontSize = 12.sp, color = appInk2(), lineHeight = 19.sp)
    }
}
