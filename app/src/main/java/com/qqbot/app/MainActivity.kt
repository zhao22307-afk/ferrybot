package com.qqbot.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qqbot.app.ui.AboutScreen
import com.qqbot.app.ui.AccessTab
import com.qqbot.app.ui.AdvancedPage
import com.qqbot.app.ui.AppViewModel
import com.qqbot.app.ui.Brand
import com.qqbot.app.ui.ChatTab
import com.qqbot.app.ui.CommandsScreen
import com.qqbot.app.ui.HomeScreen
import com.qqbot.app.ui.LogScreen
import com.qqbot.app.ui.MaterialPage
import com.qqbot.app.ui.MoreTab
import com.qqbot.app.ui.Muted
import com.qqbot.app.ui.PersonaScreen
import com.qqbot.app.ui.QqBotTheme
import com.qqbot.app.ui.SetupScreen
import com.qqbot.app.ui.StatusPill
import com.qqbot.app.ui.TopBar

/** 底部四个主 Tab */
private enum class Tab { Home, Chat, Access, More }

/** 二级页面（从 Tab 里点进去） */
private enum class Page { Commands, Logs, Persona, About, Material, Advanced }

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }

        setContent {
            QqBotTheme {
                val vm: AppViewModel = viewModel()
                // 选图/选文件：必须用 Compose 版 launcher。
                // 旧写法把返回值存进普通字段（不是 Compose state），选完不会触发处理 → 表现为「添加完没反应」
                val pickEmojiLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) vm.addEmoji(uri)
                }
                val pickImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) vm.addImage(uri)
                }
                val pickSkillLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) vm.importSkill(uri)
                }
                val pickVoicePackLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) vm.importVoicePack(uri)
                }
                // 系统字体放大到 1.3+ 时整页会挤坏；这里跟随系统但夹在 0.85~1.1
                val baseDensity = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(
                        density = baseDensity.density,
                        fontScale = baseDensity.fontScale.coerceIn(0.85f, 1.1f),
                    )
                ) {

                    AppRoot(
                        vm,
                        onPickEmoji = { pickEmojiLauncher.launch(arrayOf("image/*")) },
                        onPickImage = { pickImageLauncher.launch(arrayOf("image/*")) },
                        onPickSkill = {
                            pickSkillLauncher.launch(
                                arrayOf(
                                    "application/zip", "application/x-zip-compressed",
                                    "application/javascript", "text/javascript",
                                    "application/octet-stream", "*/*"
                                )
                            )
                        },
                        onPickVoicePack = {
                            pickVoicePackLauncher.launch(
                                arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AppRoot(
    vm: AppViewModel,
    onPickEmoji: () -> Unit,
    onPickImage: () -> Unit,
    onPickSkill: () -> Unit,
    onPickVoicePack: () -> Unit,
) {
    var tab by remember { mutableStateOf(Tab.Home) }
    var page by remember { mutableStateOf<Page?>(null) }

    // 返回键：先退二级页，再回首页
    BackHandler(enabled = page != null || tab != Tab.Home) {
        if (page != null) page = null else tab = Tab.Home
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {

                if (!vm.runtimeReady) {
                    SetupScreen(vm) { tab = Tab.Access }
                } else {
                    val p = page
                    TopBarFor(vm, tab, p) { page = null }

                    Box(Modifier.weight(1f)) {
                        if (p != null) {
                            when (p) {
                                Page.Commands -> CommandsScreen()
                                Page.Logs -> LogScreen(vm) { page = null }
                                Page.Persona -> PersonaScreen(vm) { page = null }
                                Page.About -> AboutScreen()
                                Page.Material -> MaterialPage(vm, onPickEmoji, onPickImage, onPickSkill)
                                Page.Advanced -> AdvancedPage(vm)
                            }
                        } else {
                            when (tab) {
                                Tab.Home -> HomeScreen(
                                    vm,
                                    onOpenCommands = { page = Page.Commands },
                                    onOpenLogs = { page = Page.Logs },
                                    onOpenChat = { tab = Tab.Chat },
                                    onOpenAccess = { tab = Tab.Access },
                                )
                                Tab.Chat -> ChatTab(vm, onEditPersona = { page = Page.Persona }, onPickVoicePack = onPickVoicePack)
                                Tab.Access -> AccessTab(vm)
                                Tab.More -> MoreTab(
                                    vm,
                                    onOpenMaterial = { page = Page.Material },
                                    onOpenAdvanced = { page = Page.Advanced },
                                    onOpenCommands = { page = Page.Commands },
                                    onOpenLogs = { page = Page.Logs },
                                    onOpenAbout = { page = Page.About },
                                )
                            }
                        }
                    }

                    if (p == null) {
                        BottomNav(tab) { tab = it }
                    }
                }
            }

            // ---- Toast ----
            vm.toast?.let { msg ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier
                            .padding(bottom = 92.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(Color(0xEB0F172A))
                            .padding(horizontal = 20.dp, vertical = 11.dp)
                    ) {
                        Text(msg, color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

/* ================= 顶栏 ================= */

@Composable
private fun TopBarFor(vm: AppViewModel, tab: Tab, page: Page?, onBack: () -> Unit) {
    if (page != null) {
        val title = when (page) {
            Page.Commands -> "命令说明"
            Page.Logs -> "运行日志"
            Page.Persona -> "编辑人设"
            Page.About -> "关于"
            Page.Material -> "素材库"
            Page.Advanced -> "高级设置"
        }
        TopBar(title, onBack = onBack)
        return
    }

    when (tab) {
        Tab.Home -> TopBar(
            title = "ferrybot",
            subtitle = if (vm.running) "正在替你聊天" else "未启动",
            trailing = { StatusPill(vm.running) },
        )
        Tab.Chat -> TopBar("对话", subtitle = "它怎么说话、什么时候主动说话")
        Tab.Access -> TopBar("接入", subtitle = "跑在哪个通道、用哪个模型")
        Tab.More -> TopBar("更多", subtitle = "素材、高级设置、帮助")
    }
}

/* ================= 底部导航 ================= */

@Composable
private fun BottomNav(current: Tab, onSelect: (Tab) -> Unit) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline))
        Row(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            NavTab("首页", Tab.Home, current, Modifier.weight(1f), onSelect)
            NavTab("对话", Tab.Chat, current, Modifier.weight(1f), onSelect)
            NavTab("接入", Tab.Access, current, Modifier.weight(1f), onSelect)
            NavTab("更多", Tab.More, current, Modifier.weight(1f), onSelect)
        }
    }
}

@Composable
private fun RowScope.NavTab(
    label: String,
    tab: Tab,
    current: Tab,
    modifier: Modifier,
    onSelect: (Tab) -> Unit,
) {
    val active = tab == current
    Box(
        modifier.fillMaxSize().clickable { onSelect(tab) },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            NavGlyph(tab, active)
            Spacer(Modifier.height(4.dp))
            Text(
                label,
                fontSize = 10.5.sp,
                color = if (active) Brand else Muted,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

/** 四个图标用 Canvas 画，省掉图标依赖 */
@Composable
private fun NavGlyph(tab: Tab, active: Boolean) {
    val color = if (active) Brand else Muted
    Canvas(Modifier.size(23.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(
            width = w * 0.085f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        when (tab) {
            Tab.Home -> {
                val p = Path().apply {
                    moveTo(w * 0.14f, h * 0.47f)
                    lineTo(w * 0.5f, h * 0.17f)
                    lineTo(w * 0.86f, h * 0.47f)
                    moveTo(w * 0.25f, h * 0.43f)
                    lineTo(w * 0.25f, h * 0.83f)
                    lineTo(w * 0.75f, h * 0.83f)
                    lineTo(w * 0.75f, h * 0.43f)
                }
                drawPath(p, color, style = stroke)
            }
            Tab.Chat -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * 0.12f, h * 0.17f),
                    size = Size(w * 0.76f, h * 0.5f),
                    cornerRadius = CornerRadius(w * 0.17f),
                    style = stroke,
                )
                val p = Path().apply {
                    moveTo(w * 0.35f, h * 0.67f)
                    lineTo(w * 0.33f, h * 0.87f)
                    lineTo(w * 0.55f, h * 0.67f)
                }
                drawPath(p, color, style = stroke)
            }
            Tab.Access -> {
                drawCircle(color, radius = w * 0.2f, center = Offset(w * 0.35f, h * 0.5f), style = stroke)
                drawCircle(color, radius = w * 0.2f, center = Offset(w * 0.65f, h * 0.5f), style = stroke)
            }
            Tab.More -> {
                drawCircle(color, radius = w * 0.095f, center = Offset(w * 0.22f, h * 0.5f))
                drawCircle(color, radius = w * 0.095f, center = Offset(w * 0.5f, h * 0.5f))
                drawCircle(color, radius = w * 0.095f, center = Offset(w * 0.78f, h * 0.5f))
            }
        }
    }
}