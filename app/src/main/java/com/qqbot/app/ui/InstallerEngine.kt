package com.qqbot.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qqbot.app.EnvInstaller
import com.qqbot.app.LinuxEnv
import com.qqbot.app.LocalTts
import com.qqbot.app.LogText
import com.qqbot.app.ProtoConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 「安装器」块：本地 Linux 环境（proot + rootfs）的下载 / 解压 / 安装 / 日志 / 清空。
 *
 * 从 AppViewModel 拆出来，状态直接用 Compose state 暴露，ViewModel 只做转发。
 * 这样这块的下载/解压/磁盘操作和「配置」「协议端」两块互不干扰。
 */
class InstallerEngine(
    private val app: Application,
    private val scope: CoroutineScope,
    private val toast: (String) -> Unit,
    /** 装完 NapCat 后拿到 OneBot ws token，交给 ViewModel 填进表单 */
    private val onWsToken: (String) -> Unit,
    /** 清空环境前先停协议端 */
    private val stopProto: () -> Unit,
    /** 本地语音包装好后回调（自动填 TTS 地址） */
    private val onTtsReady: (String) -> Unit = {},
) {

    val linux = LinuxEnv(app)

    private val prefs = app.getSharedPreferences("env", android.content.Context.MODE_PRIVATE)

    /** 同时下载几个文件（1-6）；存 SharedPreferences，装完/重启都记得 */
    var downloadConns by mutableStateOf(prefs.getInt("conns", 4)); private set

    /** 下载前自动测速选最快源 */
    var smartSource by mutableStateOf(prefs.getBoolean("smart", true)); private set

    private val installer = EnvInstaller(
        linux,
        app.assets,
        connsProvider = { downloadConns },
        smartProvider = { smartSource },
        sourceChooser = { file -> prefs.getInt("src_$file", -1) },
        onTtsReady = onTtsReady,
    )

    /** 最近一次测速结果：url → 字节/秒（界面上显示在源名字后面） */
    var sourceSpeeds by mutableStateOf<Map<String, Long>>(emptyMap()); private set

    /** 手动选源后 bump 一下，让界面重组 */
    var sourceTick by mutableStateOf(0); private set

    private var cachedPack: EnvInstaller.Pack? = null
    private var cachedPackId: String? = null

    var envReady by mutableStateOf(linux.ready()); private set
    var envBusy by mutableStateOf(false); private set
    var envStep by mutableStateOf(""); private set
    var envProgress by mutableStateOf(-1f); private set
    var envLog by mutableStateOf(""); private set
    var envPackId by mutableStateOf("napcat"); private set
    var envPacks by mutableStateOf(listOf<Pair<String, String>>()); private set
    var envSizeHint by mutableStateOf(0); private set
    var envDiskMb by mutableStateOf(0L); private set
    var qrFile by mutableStateOf<File?>(null); private set

    /** 本地语音自检结果（多行文本，直接显示给用户） */
    var ttsTest by mutableStateOf<String?>(null); private set

    /** 一键试听：结果一句话 + 生成好的 wav（可播放） */
    var ttsQuickResult by mutableStateOf<String?>(null); private set
    var ttsQuickWav by mutableStateOf<File?>(null); private set
    var ttsQuickBusy by mutableStateOf(false); private set

    private var envLines = ArrayDeque<String>()

    /** 日志节流：安装时命令行输出很密，逐行刷 UI 会把主线程压满 */
    private var logDirty = false
    private val pendingLog = ArrayList<String>(64)
    private var logFlusher: Job? = null

    fun packId(): String = envPackId

    fun loadPack() = runCatching { installer.loadPack(envPackId) }.getOrNull()

    fun refreshEnvState() {
        envReady = linux.ready()
        envPacks = installer.availablePacks()
        if (envPacks.none { it.first == envPackId } && envPacks.isNotEmpty()) {
            envPackId = envPacks.first().first
        }
        envSizeHint = installPackHint(envPackId)
        if (envLog.isBlank()) envLog = linux.readLog(24 * 1024)
        // 目录遍历 + 扫二维码放到 IO 线程：装完环境有上万个小文件，在主线程算会明显卡顿
        scope.launch(Dispatchers.IO) {
            val disk = linux.diskUsage() / (1024 * 1024)
            val qr = linux.findQrImage()
            withContext(Dispatchers.Main) {
                envDiskMb = disk
                if (qrFile == null) qrFile = qr
            }
        }
    }

    fun choosePack(id: String) {
        envPackId = id
        envSizeHint = installPackHint(id)
        qrFile = linux.findQrImage()
    }

    private fun installPackHint(id: String): Int =
        runCatching { installer.loadPack(id)?.sizeHint ?: 0 }.getOrDefault(0)

    fun appendEnvLine(line: String) {
        val clean = LogText.clean(line) ?: return
        synchronized(pendingLog) {
            envLines.addLast(clean)
            while (envLines.size > 200) envLines.removeFirst()
            pendingLog.add(clean)
            logDirty = true
        }
        startLogFlusher()
    }

    private fun startLogFlusher() {
        if (logFlusher?.isActive == true) return
        logFlusher = scope.launch {
            while (isActive) {
                delay(250)
                flushEnvLog()
            }
        }
    }

    fun flushEnvLog() {
        var newText: String? = null
        val batch: ArrayList<String>
        synchronized(pendingLog) {
            if (logDirty) {
                newText = envLines.joinToString("\n")
                logDirty = false
            }
            batch = ArrayList(pendingLog)
            pendingLog.clear()
        }
        if (batch.isNotEmpty()) linux.logLines(batch)
        newText?.let { envLog = it }
    }

    /** 一键安装：下载环境包 → 解压 → 装协议端 */
    fun installEnv() {
        if (envBusy) return
        val pack = installer.loadPack(envPackId)
        if (pack == null) {
            toast("找不到安装配置：$envPackId")
            return
        }
        envBusy = true
        envProgress = -1f
        envStep = "准备中…"
        synchronized(pendingLog) {
            envLines = ArrayDeque()
            pendingLog.clear()
            logDirty = false
        }
        envLog = ""
        appendEnvLine("开始安装：${pack.name}（预计 ${pack.sizeHint} MB，需剩 ${pack.needDiskMb.coerceAtLeast(2400)} MB 空间）")
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                installer.install(
                    pack,
                    onStep = { step -> envStep = step },
                    onProgress = { p -> envProgress = p },
                    onLine = { line -> appendEnvLine(line) },
                )
            }
            envReady = linux.ready()
            envBusy = false
            envProgress = -1f
            if (ok && pack.isProot) {
                appendEnvLine("写入协议端配置（WebUI / OneBot WebSocket）…")
                val tokens = ProtoConfig.writeNapCat(
                    linux,
                    wsPort = pack.run?.port ?: 3001,
                    webuiPort = pack.run?.webui ?: 6099,
                )
                if (tokens == null) {
                    appendEnvLine("⚠️ 没找到 NapCat 配置目录，可能安装不完整")
                } else {
                    onWsToken(tokens.ws)
                    appendEnvLine("配置就绪：OneBot ws 端口 ${pack.run?.port ?: 3001}")
                }
            }
            appendEnvLine(if (ok) "✅ 环境就绪" else "❌ 安装失败，看上面的日志")
            toast(if (ok) "${pack.name} 环境装好了" else "安装失败，详情看运行环境日志")
        }
    }

    /** 取消安装：下一个步骤/下载块边界生效，已下好的文件保留，重新点安装会续传 */
    fun cancelInstall() {
        if (!envBusy) return
        envStep = "正在取消…"
        installer.cancel()
        toast("已请求取消，等当前步骤结束")
    }

    // ---------------------------------------------------------------- 下载设置

    fun setConns(n: Int) {
        downloadConns = n.coerceIn(1, 6)
        prefs.edit().putInt("conns", downloadConns).apply()
    }

    fun setSmart(on: Boolean) {
        smartSource = on
        prefs.edit().putBoolean("smart", on).apply()
    }

    // ---- 手动选源 ----

    fun packDownloads(): List<EnvInstaller.Download> {
        if (cachedPackId != envPackId) {
            cachedPack = runCatching { installer.loadPack(envPackId) }.getOrNull()
            cachedPackId = envPackId
        }
        return cachedPack?.downloads ?: emptyList()
    }

    fun candidatesFor(file: String): List<String> {
        val d = packDownloads().firstOrNull { it.file == file } ?: return emptyList()
        return installer.candidates(d)
    }

    fun sourceName(url: String): String = installer.sourceName(url)

    /** -1 = 自动（测速选最快） */
    fun sourceChoice(file: String): Int = prefs.getInt("src_$file", -1)

    fun setSourceChoice(file: String, idx: Int) {
        prefs.edit().putInt("src_$file", idx).apply()
        sourceTick++
    }

    /**
     * 测一遍所有下载源的速度，结果写进日志。
     *
     * 用户反馈「只有 28KB/s」时先点这个：能看出是某个源被限速（换源能好），
     * 还是整体网络就这样（换 IP/WiFi/流量）。
     */
    fun testSources() {
        if (envBusy) {
            toast("安装/测速进行中，先取消再测")
            return
        }
        val pack = installer.loadPack(envPackId)
        if (pack == null) {
            toast("找不到安装配置：$envPackId")
            return
        }
        envBusy = true
        envStep = "测速中…"
        envProgress = -1f
        appendEnvLine("开始测速（每个源最多 6 秒，会消耗一点点流量）…")
        scope.launch {
            val speeds = HashMap<String, Long>()
            withContext(Dispatchers.IO) {
                for (d in pack.downloads) {
                    appendEnvLine("· ${d.file}")
                    for (u in installer.candidates(d)) {
                        val s = linux.probeSpeed(u)
                        speeds[u] = s
                        val txt = if (s > 0) String.format("%.1f MB/s", s / 1024.0 / 1024.0) else "连不上"
                        appendEnvLine("   $txt  ${installer.sourceName(u)}")
                    }
                }
            }
            sourceSpeeds = speeds
            envBusy = false
            envStep = ""
            appendEnvLine("测速结束：可在上面「下载源」里手动指定；不指定就自动用最快的源")
        }
    }

    fun copyEnvLog(clipboard: (String, String) -> Unit) {
        flushEnvLog()
        clipboard("运行环境日志", envLog.ifBlank { linux.readLog(24 * 1024) })
        toast("日志已复制")
    }

    /** 本地语音自检：装好了却发不出声时点这个，把每一步的结果摆出来 */
    fun testLocalTts() {
        if (ttsTest == "正在自检…") return
        ttsTest = "正在自检…"
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { LocalTts.selftest(app) }.getOrElse { "自检崩溃：${it.message}" }
            }
            ttsTest = r
        }
    }

    /**
     * 一键试听：「Piper 到底能不能用」的最终答案。
     * 走 App 自己的本地 TTS 服务完整合成一句，能拿到 wav 就是能用。
     */
    fun quickTestTts() {
        if (ttsQuickBusy) return
        ttsQuickBusy = true
        ttsQuickResult = "正在合成…（第一次要 2~10 秒）"
        scope.launch {
            val (msg, wav) = withContext(Dispatchers.IO) {
                runCatching { LocalTts.quickTest(app) }
                    .getOrElse { ("合成崩溃：${it.message}" as String) to null }
            }
            ttsQuickResult = msg
            ttsQuickWav = wav
            ttsQuickBusy = false
            if (wav != null) toast("试听音频已生成，点「播放」听听")
        }
    }

    /** 播放刚合成的试听音频（MediaPlayer 必须留引用，否则会被回收） */
    fun playTts() {
        val f = ttsQuickWav ?: return
        runCatching {
            player?.release()
            val p = android.media.MediaPlayer()
            p.setDataSource(f.absolutePath)
            p.setOnCompletionListener { runCatching { it.release() } }
            p.prepare()
            p.start()
            player = p
        }.onFailure { toast("播放失败：${it.message}") }
    }

    private var player: android.media.MediaPlayer? = null

    /** 清掉整个 Linux 环境（省空间 / 重装） */
    fun wipeEnv() {
        stopProto()
        scope.launch {
            withContext(Dispatchers.IO) { linux.clearAll() }
            envReady = false
            envLog = ""
            envLines = ArrayDeque()
            synchronized(pendingLog) {
                pendingLog.clear()
                logDirty = false
            }
            qrFile = null
            toast("环境已清空")
        }
    }
}