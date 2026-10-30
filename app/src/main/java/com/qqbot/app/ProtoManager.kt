package com.qqbot.app

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * 协议端（NapCat / Lagrange）生命周期的唯一持有者。
 *
 * 为什么要单例：以前进程句柄放在 ViewModel 里，Activity 一销毁 / App 一被杀就跟着没了，
 * 前台服务重启后机器人连不上。现在句柄由这个进程级单例持有，状态落在 SharedPreferences，
 * [BotService] 重启时调 [resumeIfWanted] 就能把协议端拉回来再连。
 */
object ProtoManager {

    private var app: Application? = null
    private var linux: LinuxEnv? = null
    private var installer: EnvInstaller? = null
    private var process: Process? = null
    private var poller: Job? = null
    private var credential: String? = null
    private var credentialTs = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** UI 观察用 */
    var running by mutableStateOf(false); private set
    var port by mutableStateOf(3001); private set
    var webuiPort by mutableStateOf(0); private set
    var qrFile by mutableStateOf<File?>(null); private set
    var qrTick by mutableStateOf(0); private set
    var qrHint by mutableStateOf(""); private set

    /** 非空表示协议端被「平台侧」拦住了（最常见：QQ 风控拒绝登录），此时不再无意义重试 */
    var blockReason by mutableStateOf(""); private set
    var startedAt by mutableStateOf(0L); private set

    /** ViewModel 注册：协议端启动后把 ws 地址 / token 回填表单 */
    var onForm: ((url: String, token: String) -> Unit)? = null
    /** 运行日志往哪送（没有界面时直接写 LinuxEnv 日志文件） */
    var onLine: ((String) -> Unit)? = null

    private var packId = "napcat"

    fun init(context: Context) {
        if (app == null) {
            val a = context.applicationContext as Application
            app = a
            linux = LinuxEnv(a)
            installer = EnvInstaller(linux!!, a.assets)
            packId = prefs()?.getString("pack", "napcat") ?: "napcat"
        }
    }

    fun setPackId(id: String) {
        packId = id
        prefs()?.edit()?.putString("pack", id)?.apply()
    }

    fun currentPackId(): String = packId

    /** 读/写「用户希望协议端在跑」的意图，服务重启后照着恢复 */
    fun wantRunning(): Boolean = prefs()?.getBoolean("want", false) ?: false

    fun setWantRunning(v: Boolean) {
        prefs()?.edit()?.putBoolean("want", v)?.apply()
    }

    fun isAlive(): Boolean = process?.isAlive == true

    /**
     * 本机能不能把协议端拉起来：环境装好了 + 至少有一个带启动命令的安装包。
     * 用来在用户点了「启动机器人」但协议端没跑时自动补上，而不是干等着说“没在跑”。
     */
    fun canRun(): Boolean {
        val lin = linux ?: return false
        val inst = installer ?: return false
        if (!lin.ready()) return false
        return runCatching {
            inst.loadPack(packId)?.run != null ||
                inst.availablePacks().any { p -> inst.loadPack(p.first)?.run != null }
        }.getOrDefault(false)
    }

    fun wsToken(): String? = runCatching { linux?.let { ProtoConfig.tokens(it.runDir).ws } }.getOrNull()

    /** 自检用：协议端登录态一句话描述（不抛异常，只返回人话） */
    fun loginState(): String {
        val lin = linux ?: return "环境未就绪"
        if (!(running || isAlive())) return "协议端没在跑"
        if (webuiPort <= 0) return "非 NapCat（无 WebUI），查不到登录态"
        val token = runCatching { ProtoConfig.tokens(lin.runDir).webui }.getOrNull().orEmpty()
        val lg = NapCatQr.login(webuiPort, token) ?: return "WebUI 没响应（协议端可能还没起好）"
        return if (NapCatQr.loggedIn(webuiPort, lg.credential)) "QQ 已登录" else "QQ 还没登录（二维码还没扫）"
    }

    /** 自检用：OneBot WebSocket 端口是否可连（能连上说明 NapCat 的 OneBot 服务在监听） */
    fun portOpen(p: Int = port, timeoutMs: Int = 800): Boolean = runCatching {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", p), timeoutMs) }
    }.isSuccess

    private fun prefs() = app?.getSharedPreferences("proto", Context.MODE_PRIVATE)

    /** 协议端日志既写进日志区，也顺手识别「平台侧拒绝」并中止无意义重试 */
    private fun ingest(msg: String) {
        line(msg)
        if (blockReason.isNotBlank()) return
        val why = platformBlock(msg) ?: return
        blockReason = why
        qrHint = why
        poller?.cancel()
        poller = null
        qrFile = null
        // 继续跑只会每隔几十秒重试一次扫码，平台侧风控会越记越重，直接收掉
        scope.launch { runCatching { process?.destroy() } }
    }

    private val codeRegex = Regex("\"serverErrorCode\"\\s*:\\s*(\\d+)")
    private val msgRegex = Regex("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.){2,200})\"")

    private fun platformBlock(l: String): String? {
        if (l.contains("存在安全风险")) {
            return "QQ 拒绝了这次登录：这个账号被判定「存在安全风险」。" +
                "第三方协议登录属于平台风控重点，继续重试只会加重风险。" +
                "请用手机 QQ 登录该账号并按提示完成验证（改密/验证手机/常用设备登录），或换一个账号再试。"
        }
        // NapCat 把服务端的拒绝原样打出来：Login Error , ErrInfo: [1,{"account":"...","serverErrorCode":168,"message":"..."}]
        if (!l.contains("Login Error") && !l.contains("ErrInfo") && !l.contains("serverErrorCode")) return null
        val code = codeRegex.find(l)?.groupValues?.get(1)
        if (code == null || code == "0") return null
        val raw = msgRegex.find(l)?.groupValues?.get(1) ?: ""
        val msg = raw.replace("\\n", " ").replace("\\\"", "\"").trim()
        val detail = if (msg.isNotBlank()) "：$msg" else ""
        val tail = "（serverErrorCode $code）。扫码本身成功了，是 QQ 服务端拒绝了这个登录会话，" +
            "所以又回到二维码。先按提示解掉限制，或换一个账号。"
        return when (code) {
            "168" -> "QQ 拒绝了这次登录$detail" + tail
            "237" -> "QQ 拒绝了这次登录$detail" + "（237：账号状态异常或客户端版本过低，先升级手机 QQ 并确认账号状态）"
            else -> "QQ 拒绝了这次登录$detail" + tail
        }
    }

    private fun line(msg: String) {
        onLine?.invoke(msg) ?: runCatching { linux?.log(msg) }
    }

    /**
     * 启动协议端。[silent] 为 true 时（服务恢复路径）不抛 toast，只写日志。
     */
    fun start(silent: Boolean = false): Boolean {
        val lin = linux ?: return false
        val inst = installer ?: return false
        if (running || isAlive()) return true
        if (!lin.ready()) {
            if (!silent) line("协议端没启动：环境还没装好")
            return false
        }
        // 当前选中的「安装内容」不一定是协议端（可能是 Python3 / Piper 这类环境包），
        // 这时自动挑一个能启动的协议端包，别直接报启动失败
        var pack = inst.loadPack(packId)
        if (pack?.run == null) {
            val alt = inst.availablePacks().firstOrNull { p ->
                runCatching { inst.loadPack(p.first)?.run != null }.getOrDefault(false)
            }
            if (alt != null) {
                pack = inst.loadPack(alt.first)
                line("当前安装内容不是协议端，改用「${alt.second}」启动")
            }
        }
        if (pack == null) {
            if (!silent) line("找不到安装配置：$packId")
            return false
        }
        val cmd = inst.buildRunCmd(pack)
        if (cmd == null) {
            if (!silent) {
                line("「${pack.name}」不是协议端（它只是给技能/语音用的环境）；启动协议端请先在「接入 → 手机本地环境」装 NapCat")
            }
            return false
        }

        port = pack.run?.port ?: 3001
        webuiPort = pack.run?.webui ?: 0
        credential = null
        credentialTs = 0L
        qrFile = null
        qrHint = ""
        blockReason = ""
        line("启动 ${pack.name}：${cmd.joinToString(" ").take(400)}")

        // proot 包：命令已经是「宿主机的 proot + rootfs 内的参数」，cwd 必须是 App 私有目录，
        // 环境变量要带上 PROOT_LOADER / LD_LIBRARY_PATH 等（Termux 版 proot 写死了自己的路径）
        val spawnEnv = if (pack.isProot) lin.prootEnv() else emptyMap()
        val spawnCwd = if (pack.isProot) lin.baseDir else inst.runWorkdir(pack)
        val p = lin.spawn(cmd, spawnCwd, spawnEnv) { l -> ingest(l) } ?: return false

        process = p
        running = true
        startedAt = System.currentTimeMillis()
        setWantRunning(true)
        onForm?.invoke("ws://127.0.0.1:$port", wsToken() ?: "")
        startQrPolling()
        scope.launch {
            p.waitFor()
            if (process === p) {
                running = false
                process = null
                line("协议端已退出")
                poller?.cancel()
                poller = null
                qrFile = null
                qrHint = blockReason.ifBlank { "协议端已退出" }
            }
        }
        return true
    }

    /** 服务重启时调用：用户上次让它跑，就把它拉起来 */
    fun resumeIfWanted(): Boolean {
        if (running || isAlive()) return true
        if (!wantRunning()) return false
        return start(silent = true)
    }

    fun stop() {
        poller?.cancel()
        poller = null
        runCatching { process?.destroy() }
        process = null
        running = false
        qrFile = null
        qrHint = ""
        blockReason = ""
        credential = null
        credentialTs = 0L
        setWantRunning(false)
        line("已停止协议端")
    }

    private fun startQrPolling() {
        poller?.cancel()
        val lin = linux ?: return
        val port = webuiPort

        if (port <= 0) {
            // 非 NapCat（比如自己起的 Lagrange）：退化成扫工作目录里的图片文件
            poller = scope.launch {
                while (isActive && running) {
                    val f = lin.findQrImage()
                    if (f?.absolutePath != qrFile?.absolutePath) {
                        qrFile = f
                        qrTick++
                        if (f != null) line("找到登录二维码：${f.name}")
                    }
                    delay(2000)
                }
            }
            return
        }

        // NapCat：走 WebUI API 拿二维码「内容」，再用 ZXing 画成 PNG 显示
        poller = scope.launch(Dispatchers.IO) {
            val token = ProtoConfig.tokens(lin.runDir).webui
            var lastContent = ""
            var lastRender = 0L
            while (isActive && running) {
                try {
                    var cred = credential
                    if (cred.isNullOrBlank() || System.currentTimeMillis() - credentialTs > 45 * 60 * 1000L) {
                        cred = NapCatQr.login(port, token)?.credential
                        if (cred != null) {
                            credential = cred
                            credentialTs = System.currentTimeMillis()
                        }
                    }
                    if (cred.isNullOrBlank()) {
                        qrHint = "等协议端起来…（WebUI $port 还没响应）"
                        delay(3000)
                        continue
                    }
                    if (NapCatQr.loggedIn(port, cred)) {
                        qrHint = "已登录"
                        line("✅ QQ 已登录成功")
                        break
                    }
                    val content = NapCatQr.qrContent(port, cred)
                    if (content.isNullOrBlank()) {
                        qrHint = "协议端还在启动（约 20-60 秒）"
                        delay(3000)
                        continue
                    }
                    if (content != lastContent || System.currentTimeMillis() - lastRender > 60_000L) {
                        val out = File(lin.runDir, "napcat-qr.png")
                        val f = NapCatQr.render(content, out)
                        if (f != null) {
                            qrFile = f
                            qrTick++
                            qrHint = "用手机 QQ 扫这个码登录"
                            lastContent = content
                            lastRender = System.currentTimeMillis()
                            line("已生成登录二维码（${content.length} 字符）")
                        }
                    }
                } catch (e: Exception) {
                    qrHint = "取二维码失败：${e.message}"
                }
                delay(4000)
            }
        }
    }
}