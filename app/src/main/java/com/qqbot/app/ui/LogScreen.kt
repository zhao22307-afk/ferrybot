package com.qqbot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 完整日志页 */
@Composable
fun LogScreen(vm: AppViewModel, onBack: () -> Unit) {
    val clipboard = LocalClipboardManager.current

    Column(Modifier.fillMaxSize()) {
        // 上次启动失败的原因直接怼在最上面，不用翻日志找
        vm.lastExitMessage?.let { reason ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(appBrandSoft())
                    .padding(12.dp)
            ) {
                Text(
                    "上次没起来：$reason",
                    color = Danger,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }
        }

        Box(
            Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(LogBg)
                .padding(14.dp)
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    vm.logText.ifBlank { "（还没有日志，启动后这里会实时更新）" },
                    color = LogText,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            BigButton("返回", Modifier.weight(1f), filled = false) { onBack() }
            Spacer(Modifier.width(8.dp))
            BigButton("复制", Modifier.weight(1f), filled = false) {
                clipboard.setText(AnnotatedString(vm.logText))
                vm.showToast("已复制")
            }
            Spacer(Modifier.width(8.dp))
            BigButton("清空", Modifier.weight(1f)) { vm.clearLog() }
        }
    }
}