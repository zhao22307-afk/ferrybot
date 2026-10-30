package com.qqbot.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qqbot.app.LocalTts
import java.io.File

// ===================== 基础组件 =====================

/* ============ 通用小部件 ============ */

/** 分段切换：一屏内分区，比一长条滚动页好用 */
@Composable
fun SegmentedChips(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (on) Brand else MaterialTheme.colorScheme.surface)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 15.dp, vertical = 8.dp)
            ) {
                Text(
                    label,
                    fontSize = 13.5.sp,
                    color = if (on) Color.White else appInk2(),
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/** 页面底部的保存条 */
@Composable
fun SaveBar(vm: AppViewModel) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline))
        Box(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            BigButton("保存设置", Modifier.fillMaxWidth()) { vm.save() }
        }
    }
}

/** 密码框：默认圆点遮挡，右侧眼睛可以临时看明文 */
@Composable
fun PasswordBox(value: String, placeholder: String, onChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(appField())
            .padding(start = 13.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                textStyle = TextStyle(
                    fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(Brand),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (value.isEmpty()) Text(placeholder, fontSize = 15.sp, color = Muted)
                    inner()
                },
            )
        }
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(9.dp))
                .clickable { visible = !visible },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (visible) "🙈" else "👁",
                fontSize = 15.sp,
                color = if (visible) Brand else Muted,
            )
        }
    }
}

@Composable
fun PictureThumb(file: File, label: String, onDelete: () -> Unit) {
    val bmp = remember(label, file.absolutePath, file.lastModified()) {
        runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }.getOrNull()
    }
    Box(Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(appField())) {
        if (bmp != null) {
            Image(bmp, label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(3.dp)
                .size(18.dp)
                .clip(CircleShape)
                .background(Color(0xB30F172A))
                .clickable(onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) { Text("×", color = Color.White, fontSize = 12.sp) }
    }
}

/** 把文件分享出去（FileProvider，能直接发到 QQ / 相册） */
fun shareFile(ctx: android.content.Context, file: File, mime: String, title: String) {
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            ctx, ctx.packageName + ".fileprovider", file
        )
        val i = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = mime
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(android.content.Intent.createChooser(i, title))
    }
}

/** 用系统浏览器打开一个链接 */
fun openUrl(ctx: android.content.Context, url: String) {
    runCatching {
        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    }
}

/* ============ 运行环境 ============ */

/**
 * 「运行环境」：手机本地下载 proot + rootfs、装协议端、启动、看登录二维码、看日志。
 * 选「真实QQ」的用户不需要电脑。
 */
@Composable
fun EnvSection(vm: AppViewModel) {
    LaunchedEffect(Unit) { vm.refreshEnvState() }
    val ctx = LocalContext.current

    val isTtsPack = vm.envPackId == "piper"
    Text(
        when {
            isTtsPack && LocalTts.installed(ctx) -> "本地语音已装好" + if (vm.envDiskMb > 0) " · 占用 ${vm.envDiskMb} MB" else ""
            isTtsPack -> "未安装。装好后手机自己就能合成语音，不用电脑。"
            vm.protoRunning -> "协议端运行中（端口 ${vm.protoPort}）"
            vm.envReady -> "环境已就绪" + if (vm.envDiskMb > 0) " · 占用 ${vm.envDiskMb} MB" else ""
            else -> "未安装。装好后手机自己就能跑协议端，不用电脑。"
        },
        fontSize = 12.sp,
        color = if (vm.protoRunning) Ok else Muted,
        lineHeight = 18.sp,
    )
    if (vm.envPacks.size > 1) {
        Spacer(Modifier.height(10.dp))
        FieldLabel(if (isTtsPack) "安装内容" else "协议端")
        SelectBox(vm.envPackId, vm.envPacks, onChange = { vm.choosePack(it) })
    }
    Spacer(Modifier.height(14.dp))
    // 小屏上一排放三四个会被挤出去，统一每行最多两个
    val actions = ArrayList<Pair<String, () -> Unit>>()
    if (vm.envBusy) {
        actions.add("取消安装" to { vm.cancelInstall() })
    } else {
        actions.add((if (vm.envReady) "重装 / 修复" else "下载并安装") to { vm.installEnv() })
    }
    if (vm.envReady && !vm.protoRunning && !isTtsPack) actions.add("启动协议端" to { vm.startProto() })
    if (vm.protoRunning) actions.add("停止" to { vm.stopProto() })
    actions.chunked(2).forEach { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            pair.forEach { (label, action) -> ChipButton(label) { action() } }
        }
    }
    // 小屏上一排放四个会被挤成竖排，改成每行最多两个
    val ops = ArrayList<Pair<String, () -> Unit>>()
    if (vm.envLog.isNotBlank()) ops.add("复制日志" to { vm.copyEnvLog() })
    if (vm.envReady) ops.add("清空环境" to { vm.wipeEnv() })
    ops.add("重新检测" to { vm.refreshEnvState() })
    ops.add("连接自检" to { vm.selfCheckBot() })
    ops.chunked(2).forEach { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            pair.forEach { (label, action) -> ChipButton(label) { action() } }
        }
    }
    if (isTtsPack) {
        HintText("装好后「对话 → 语音 → 本地模型」的地址会自动填成 http://127.0.0.1:17891/tts，不用手填别的。")
    }
    if (vm.envBusy) {
        Spacer(Modifier.height(12.dp))
        Text(vm.envStep, fontSize = 12.sp, color = Muted, lineHeight = 16.sp)
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(appField())) {
            val p = vm.envProgress
            if (p > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(p.coerceIn(0f, 1f))
                        .height(6.dp)
                        .background(Brand)
                )
            }
        }
    }

    val qr = vm.qrFile
    if (qr != null) {
        val bmp = remember(qr.absolutePath, vm.qrTick) {
            runCatching { BitmapFactory.decodeFile(qr.absolutePath) }.getOrNull()
        }
        if (bmp != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                "用另一台手机的 QQ 扫这个码登录；只有一台手机就先点下面的「分享 / 保存二维码」，存成图片后用 QQ「相册选图」扫。",
                fontSize = 12.sp,
                color = Muted,
                lineHeight = 18.sp,
            )
            // 按钮放二维码上面：小屏上二维码会把按钮顶到屏幕外，看不见也点不到
            Spacer(Modifier.height(10.dp))
            ChipButton("分享 / 保存二维码") { shareFile(ctx, qr, "image/png", "登录二维码") }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Image(
                    bmp.asImageBitmap(),
                    "登录二维码",
                    modifier = Modifier.size(200.dp).clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }

    if (vm.qrHint.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        Text(
            vm.qrHint,
            fontSize = 12.sp,
            color = if (vm.protoBlockReason.isNotBlank()) Danger else Brand,
            lineHeight = 18.sp,
        )
    }

    if (vm.envLog.isNotBlank()) {
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(LogBg)
                .padding(10.dp)
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    vm.envLog.takeLast(4000),
                    fontSize = 11.sp,
                    color = LogText,
                    lineHeight = 15.sp,
                )
            }
        }
    }
    // 底部留白：最后一行内容别贴着底部导航栏
    Spacer(Modifier.height(28.dp))
}

// ===================== 表单 / 设置用组件 =====================

/* ============ 布局骨架 ============ */

/** 白色分组卡片 */
@Composable
fun GroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        content = content,
    )
}

/** 分组标题（在卡片外，小写字母间距大） */
@Composable
fun GroupLabel(text: String, hint: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = Muted,
            modifier = Modifier.weight(1f),
        )
        if (hint != null) Text(
            hint,
            fontSize = 12.sp,
            color = Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** 卡片内的一行（左标题右内容/箭头） */
@Composable
fun ItemRow(
    title: String,
    value: String? = null,
    desc: String? = null,
    showArrow: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val rowModifier = if (onClick != null)
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 12.dp)
    else
        Modifier.fillMaxWidth().padding(vertical = 12.dp)

    Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
            if (desc != null) {
                Spacer(Modifier.height(3.dp))
                Text(desc, fontSize = 12.sp, color = Muted, lineHeight = 18.sp)
            }
        }
        if (value != null) {
            Text(
                value,
                fontSize = 14.sp,
                color = appInk2(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        if (showArrow) {
            Text("›", fontSize = 18.sp, color = Muted, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

/** 卡片之间的分隔线 */
@Composable
fun RowDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(appLine()))
}

/* ============ 按钮 ============ */

@Composable
fun BigButton(
    text: String,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bg = when {
        !filled -> appField()
        danger -> Danger
        else -> Brand
    }
    val fg = when {
        !filled -> appInk2()
        else -> Color.White
    }
    Box(
        modifier
            .height(if (filled) 50.dp else 44.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(if (enabled) bg else appField())
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = if (filled) 15.sp else 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) fg else Muted,
        )
    }
}

@Composable
fun ChipButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(11.dp))
            .background(appField())
            .clickable { onClick() }
            .padding(horizontal = 15.dp, vertical = 9.dp)
    ) {
        Text(text, fontSize = 13.5.sp, color = appInk2(), fontWeight = FontWeight.Medium)
    }
}

@Composable
fun LinkText(text: String, color: Color = Brand, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 13.sp,
        color = color,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.clickable { onClick() }.padding(vertical = 4.dp),
    )
}

/* ============ 表单 ============ */

@Composable
fun FieldLabel(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        color = Muted,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
    )
}

/** 单行输入（外观由调用方给背景） */
@Composable
fun InputBox(
    value: String,
    placeholder: String = "",
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(appField())
            .padding(horizontal = 13.dp, vertical = 13.dp)
    ) {
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Brand),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(placeholder, fontSize = 15.sp, color = Muted)
                }
                inner()
            },
        )
    }
}

/** 多行输入 */
@Composable
fun InputArea(
    value: String,
    lines: Int = 8,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(appField())
            .padding(13.dp)
    ) {
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 13.5.sp,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 22.sp,
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Brand),
            modifier = Modifier.fillMaxWidth().height((lines * 22).dp),
        )
    }
}

/** 下拉选择 */
@Composable
fun SelectBox(
    value: String,
    options: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.first == value }?.second
        ?: value.ifBlank { "默认（不填）" }
    Box(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(11.dp))
                .background(appField())
                .clickable { open = true }
                .padding(horizontal = 13.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("⌄", fontSize = 14.sp, color = Muted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (v, l) ->
                DropdownMenuItem(
                    text = { Text(l, fontSize = 14.sp) },
                    onClick = { onChange(v); open = false },
                )
            }
        }
    }
}

/** 开关行 */
@Composable
fun SwitchLine(
    title: String,
    desc: String? = null,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
            if (desc != null) {
                Spacer(Modifier.height(3.dp))
                Text(desc, fontSize = 12.sp, color = Muted, lineHeight = 18.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Brand,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFFE2E5EA),
            ),
        )
    }
}

/** 两个数字并排（如 2 ~ 5） */
@Composable
fun RangeRow(
    minLabel: String,
    maxLabel: String,
    minValue: String,
    maxValue: String,
    onMin: (String) -> Unit,
    onMax: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        NumberBox(minValue, minLabel, Modifier.weight(1f), onMin)
        Text("  ~  ", color = Muted, fontSize = 13.sp)
        NumberBox(maxValue, maxLabel, Modifier.weight(1f), onMax)
    }
}

@Composable
fun NumberBox(
    value: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(11.dp))
            .background(appField())
            .padding(horizontal = 13.dp, vertical = 12.dp)
    ) {
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = { t -> if (t.length <= 4 && t.all { it.isDigit() }) onChange(t) },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Brand),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(placeholder, fontSize = 14.sp, color = Muted)
                inner()
            },
        )
    }
}

/* ============ 展示 ============ */

/** 数据块（首页统计用） */
@Composable
fun StatTile(
    label: String,
    value: String,
    sub: String? = null,
    accent: Color = appInk(),
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = accent)
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 12.sp, color = Muted)
        if (sub != null) Text(sub, fontSize = 11.sp, color = Muted)
    }
}

/** 状态徽标 */
@Composable
fun StatusPill(running: Boolean) {
    val bg = if (running) OkSoft else appField()
    val fg = if (running) Ok else Muted
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(bg).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(fg))
        Spacer(Modifier.width(6.dp))
        Text(if (running) "运行中" else "未运行", fontSize = 12.sp, color = fg, fontWeight = FontWeight.Medium)
    }
}

/** 提示条（可用在页面底部） */
@Composable
fun HintText(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        color = Muted,
        lineHeight = 18.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp),
    )
}

/* ============ 顶部栏 ============ */

@Composable
fun TopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                Text("‹", fontSize = 26.sp, color = appInk(), modifier = Modifier.padding(bottom = 3.dp))
            }
            Spacer(Modifier.width(6.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, fontSize = 12.sp, color = Muted)
            }
        }
        trailing?.invoke(this)
    }
}
