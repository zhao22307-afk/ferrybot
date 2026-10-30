package com.qqbot.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 前台服务：跑 node bot.mjs，带常驻通知防止被系统清理。
 */
class BotService : Service() {

    private var process: Process? = null

    /** 本次退出是不是「我们自己让它停的」（停止按钮 / 保存配置后重启 / 服务被回收），要区别于崩溃 */
    @Volatile
    private var stopRequested: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_STOP) {
            // 停止机器人 ≠ 停止协议端：QQ 登录态缓存在协议端里，留着它下次启动能立刻连上，
            // 不用再等 30~90 秒重新拉起。想停协议端去「接入 → 手机本地环境」。
            stopRequested = intent?.getStringExtra(EXTRA_REASON) ?: "点了停止"
            pending = false
            stopBot()
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        startForeground(NOTI_ID, buildNotification("正在启动…"))

        // 协议端有独立生命周期：服务被系统重启后，先把它拉回来，再让 node 去连
        ProtoManager.init(this)
        if (ProtoManager.wantRunning() && !ProtoManager.running) {
            ProtoManager.resumeIfWanted()
        }

        val env = EnvManager(this)
        if (!env.runtimeReady() && !env.installRuntime()) {
            notify("启动失败：${env.lastError.ifBlank { "运行时缺失" }}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!env.installBot()) {
            notify("启动失败：代码缺失")
            stopSelf()
            return START_NOT_STICKY
        }
        startBotWhenReady(env)
        return START_STICKY
    }

    /**
     * 走本机协议端时，协议端启动要 30~90 秒（proot + LinuxQQ + 登录）。
     * 如果 node 立刻起来，只会不停地「连接错误」，看起来像坏了。
     * 所以先等 OneBot 端口就绪（最多 3 分钟）再启动机器人。
     */
    private fun startBotWhenReady(env: EnvManager) {
        val cfg = runCatching { ConfigStore(env).load() }.getOrDefault(emptyMap())
        val local = cfg["CHANNEL"] == "onebot" && (cfg["ONEBOT_WS_URL"] ?: "").contains("127.0.0.1")
        if (!local || ProtoManager.portOpen()) {
            pending = false
            startBot(env)
            return
        }
        // 本机协议端没跑：能自动拉起来就拉（用户点「启动」就是要整条链路起来），
        // 拉不动才告诉他人该去哪装/启动，避免点一下像没反应。
        // 注意：不能看 wantRunning —— 覆盖安装后进程被杀、但 SharedPreferences 里 want 还是 true，
        // 之前用 want 判断就会跳过去，然后空等 3 分钟。
        if (!ProtoManager.running && !ProtoManager.isAlive()) {
            if (!ProtoManager.canRun()) {
                pending = false
                appendLog(env.logFile, "协议端没在跑，也没有可启动的协议端（是不是「运行环境」里只装了 Piper / Python3？）")
                notify("协议端没在跑：去「接入 → 运行环境」装 NapCat 并启动")
                return
            }
            pending = true
            appendLog(env.logFile, "协议端没在跑，自动启动协议端")
            notify("正在启动协议端…")
            val ok = ProtoManager.start(silent = true)
            appendLog(
                env.logFile,
                if (ok) "协议端进程已拉起，等端口 ${ProtoManager.port} 就绪"
                else "协议端启动失败（环境或安装包有问题，去「运行环境」看日志）"
            )
            if (!ok) {
                pending = false
                lastExitReason = "协议端启动失败（去「运行环境」看日志）"
                notify("协议端启动失败，去「接入 → 运行环境 → 复制日志」看原因")
                return
            }
        }
        pending = true
        notify("等协议端就绪…")
        Thread({
            val deadline = System.currentTimeMillis() + 180_000L
            var sec = 0
            var lastHint = ""
            while (System.currentTimeMillis() < deadline) {
                if (ProtoManager.portOpen()) {
                    appendLog(env.logFile, "协议端就绪（${sec}s），启动机器人")
                    pending = false
                    startBot(env)
                    return@Thread
                }
                // 进程都挂了就别傻等三分钟，直接把原因摆出来
                if (sec >= 6 && !ProtoManager.running && !ProtoManager.isAlive()) {
                    val why = ProtoManager.blockReason.ifBlank { ProtoManager.qrHint }
                        .ifBlank { "协议端退出了，去「接入 → 运行环境 → 复制日志」看最后几行" }
                    appendLog(env.logFile, "协议端已退出，放弃启动机器人：$why")
                    pending = false
                    lastExitReason = "协议端退出了：${why.take(40)}"
                    notify("协议端退出了：${why.take(60)}")
                    return@Thread
                }
                val hint = ProtoManager.qrHint.ifBlank { ProtoManager.blockReason }
                if (hint.isNotBlank() && hint != lastHint) {
                    lastHint = hint
                    appendLog(env.logFile, "协议端提示：$hint")
                    notify(hint.take(60))
                }
                if (sec % 15 == 0) {
                    appendLog(env.logFile, "等待协议端就绪（${sec}s / 最多 180s）…")
                    notify("等协议端就绪… ${sec}s")
                }
                Thread.sleep(2000)
                sec += 2
            }
            pending = false
            lastExitReason = "协议端 180 秒没就绪"
            appendLog(env.logFile, "协议端 180s 没就绪，取消启动（去「运行环境」看协议端日志）")
            notify("协议端没就绪，去「运行环境」看日志")
        }, "bot-prep").start()
    }

    private fun startBot(env: EnvManager) {
        if (running) {
            pending = false
            notify("已在运行")
            return
        }
        try {
            val pb = ProcessBuilder(env.nodeBin.absolutePath, "bot.mjs")
            pb.directory(env.botDir)
            pb.redirectErrorStream(true)
            pb.environment().apply {
                // 凭证同时直接给到环境变量：node 侧 config.mjs 优先读 env，
                // 就算 .env 没写成功（或旧版本残留）也能拿到配置
                runCatching { ConfigStore(env).load() }.getOrDefault(emptyMap())
                    .forEach { (k, v) -> if (k.isNotBlank() && v.isNotBlank()) put(k, v) }
                // 协议端在跑时以它为准：.env 里可能是旧地址/旧 token
                if (ProtoManager.isAlive()) {
                    put("CHANNEL", "onebot")
                    put("ONEBOT_WS_URL", "ws://127.0.0.1:${ProtoManager.port}")
                    ProtoManager.wsToken()?.takeIf { it.isNotBlank() }?.let { put("ONEBOT_TOKEN", it) }
                }
                // 手机本地语音（Piper）：装好就起 127.0.0.1 的 TTS 服务。
                // 本地地址总是注入——这样用户把「语音 → 方案」切到「手机本地」时立刻可用，
                // 但不要擅自改用户选的方案（之前无条件覆盖 TTS_MODE=local，云端复刻被顶掉）。
                val piperUp = LocalTts.installed(this@BotService) && LocalTts.start(this@BotService)
                if (piperUp) {
                    put("LOCAL_TTS_URL", LocalTts.URL)
                    appendLog(env.logFile, "本地语音服务已就绪（${LocalTts.URL}），选「手机本地」即可直接用")
                } else if (get("TTS_MODE") == "local") {
                    // 选了本地语音，但本机没装 Piper：别让它一直报「语音合成失败」
                    put("TTS_MODE", "preset")
                    appendLog(env.logFile, "本机没装本地语音（Piper），已临时改用云端音色")
                }
                put("LD_LIBRARY_PATH", env.runtimeDir.absolutePath)
                put("HOME", env.botDir.absolutePath)
                put("TMPDIR", cacheDir.absolutePath)
                put("PATH", "${env.runtimeDir.absolutePath}:/system/bin:/system/xbin")
                put("NODE_OPTIONS", "--max-old-space-size=96")
            }

            lastExitCode = 0
            lastExitReason = null
            stopRequested = null
            // 上一条 node 可能在 App 被系统杀掉后又活了下来（子进程不会跟着 App 一起死），
            // 两个实例同时连同一个协议端 = 每条消息收两次、回两次。启动前先清掉它。
            killStaleBot(env)
            // 记下这次启动时日志写到哪：退出原因只在这之后的日志里找，
            // 否则会翻到上一次运行/几小时前的旧错误，把「保存配置后重启」误报成连接错误
            val startOffset = if (env.logFile.exists()) env.logFile.length() else 0L
            val p = pb.start()
            process = p
            running = true
            pending = false
            notify("运行中")

            Thread({ pumpOutput(p, env.logFile) }, "bot-stdout").start()
            Thread({
                val code = runCatching { p.waitFor() }.getOrDefault(-1)
                running = false
                pending = false
                appendLog(env.logFile, "===== 进程退出 code=$code =====")
                val stopped = stopRequested
                if (stopped != null) {
                    // 是我们让它停的（停止按钮 / 保存配置重启 / 服务回收），不算启动失败
                    appendLog(env.logFile, "退出原因：$stopped（主动停止）")
                    lastExitCode = 0
                    lastExitReason = null
                    notify("已停止")
                } else {
                    // 退出原因通常只在机器人自己的 qqbot.log 里，抄到界面能看的地方
                    val reason = if (code != 0) exitReason(env, startOffset) else null
                    if (reason != null) appendLog(env.logFile, "退出原因：$reason")
                    lastExitCode = code
                    lastExitReason = if (code != 0) reason ?: "没有输出原因，点「运行日志」看详情" else null
                    notify(
                        if (code == 0) "已停止"
                        else "启动失败（code=$code）" + (reason?.let { "：" + it.take(26) } ?: "")
                    )
                }
            }, "bot-wait").start()

        } catch (e: Exception) {
            running = false
            appendLog(env.logFile, "启动失败: ${e.message}")
            lastExitCode = -1
            lastExitReason = e.message
            notify("启动失败：${e.message}")
            Log.e(TAG, "start failed", e)
        }
    }

    /** 机器人自己会在 data/bot.pid 写 pid，用来保证单实例；这里把残留进程清掉再启动 */
    private fun killStaleBot(env: EnvManager) {
        val f = File(env.botDir, "data/bot.pid")
        val pid = runCatching { f.readText().trim().toInt() }.getOrNull() ?: return
        if (pid <= 0 || pid == android.os.Process.myPid()) return
        runCatching {
            android.os.Process.killProcess(pid)
            appendLog(env.logFile, "清理残留的机器人进程（pid $pid）")
        }
        Thread.sleep(400)
    }

    /** 只从「本次启动之后」写的日志里找最后一条像「原因」的行 */
    private fun exitReason(env: EnvManager, fromOffset: Long): String? {
        val f = env.botLogFile
        if (!f.exists()) return null
        val text = runCatching {
            java.io.RandomAccessFile(f, "r").use { raf ->
                val start = fromOffset.coerceAtMost(raf.length()).coerceAtLeast(0L)
                raf.seek(start)
                val buf = ByteArray((raf.length() - start).toInt().coerceAtMost(256 * 1024))
                raf.readFully(buf)
                String(buf, Charsets.UTF_8)
            }
        }.getOrNull().orEmpty()
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return null
        if (lines.any { it.contains("收到 SIGTERM") || it.contains("收到 SIGINT") }) {
            return "进程被停止（SIGTERM/SIGINT）"
        }
        val key = Regex("启动失败|Error|错误|失败|CANNOT LINK|Permission denied|ENOENT", RegexOption.IGNORE_CASE)
        return lines.asReversed().firstOrNull { key.containsMatchIn(it) }?.trim()?.take(300)
    }

    private fun pumpOutput(p: Process, logFile: File) {
        try {
            BufferedReader(p.inputStream.reader(Charsets.UTF_8)).use { reader ->
                logFile.appendText("")  // 确保文件存在
                while (true) {
                    val line = reader.readLine() ?: break
                    val clean = LogText.clean(line) ?: continue
                    logFile.appendText(clean + "\n")
                    if (logFile.length() > 2L * 1024 * 1024) {
                        val old = File(logFile.parentFile, "bot.log.1")
                        old.delete()
                        logFile.renameTo(old)
                        logFile.appendText("")
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun appendLog(f: File, msg: String) {
        runCatching {
            val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            f.appendText("$ts $msg\n")
        }
    }

    private fun stopBot() {
        process?.let { p ->
            runCatching { p.destroy() }
            Thread({
                Thread.sleep(1500)
                runCatching { if (p.isAlive) p.destroyForcibly() }
            }, "bot-kill").start()
        }
        process = null
        running = false
        pending = false
    }

    // ---------- 通知 ----------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "机器人运行状态", NotificationManager.IMPORTANCE_LOW)
                        .apply { setShowBadge(false) }
                )
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getActivity(this, 0, open, flags)
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, BotService::class.java).setAction(ACTION_STOP), flags
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else
            Notification.Builder(this).setPriority(Notification.PRIORITY_LOW)

        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentIntent(pi)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "停止", stopPi).build())
            .build()
    }

    private fun notify(text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val n = buildNotification(text)
        nm.notify(NOTI_ID, n)
        runCatching { startForeground(NOTI_ID, n) }
    }

    override fun onDestroy() {
        stopRequested = stopRequested ?: "服务被回收"
        stopBot()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.qqbot.app.START"
        const val ACTION_STOP = "com.qqbot.app.STOP"
        const val EXTRA_REASON = "stop_reason"
        private const val CHANNEL_ID = "bot_running"
        private const val NOTI_ID = 1001
        private const val TAG = "QqBot"

        @Volatile
        var running: Boolean = false
            private set

        /** 正在等协议端就绪 / 正在拉起，还没进入运行中：首页按钮显示「启动中…」 */
        @Volatile
        var pending: Boolean = false
            private set

        /** 最近一次退出的 code 与原因（首页/日志页要显示） */
        @Volatile
        var lastExitCode: Int = 0
            private set

        @Volatile
        var lastExitReason: String? = null
            private set

        fun start(ctx: Context) {
            val i = Intent(ctx, BotService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context, reason: String = "点了停止") {
            ctx.startService(
                Intent(ctx, BotService::class.java)
                    .setAction(ACTION_STOP)
                    .putExtra(EXTRA_REASON, reason)
            )
        }

        /** 读日志最后 n 行（App 的 stdout 日志 + 机器人自己写的 qqbot.log 合并） */
        fun tail(env: EnvManager, n: Int): String {
            val app = readTail(env.logFile, n)
            val own = readTail(env.botLogFile, n)
            if (app.isBlank() && own.isBlank()) return "（还没有日志）"
            return buildString {
                if (app.isNotBlank()) append(app)
                if (own.isNotBlank()) {
                    if (isNotEmpty()) append("\n\n")
                    append("—— 机器人内部日志 ——\n")
                    append(own)
                }
            }
        }

        private fun readTail(f: File, n: Int): String {
            if (!f.exists()) return ""
            val lines = runCatching { f.readLines(Charsets.UTF_8) }.getOrNull() ?: return ""
            if (lines.isEmpty()) return ""
            return lines.takeLast(n).joinToString("\n")
        }
    }
}