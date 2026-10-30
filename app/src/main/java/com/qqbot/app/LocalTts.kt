package com.qqbot.app

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * 手机本地语音（Piper）：在 127.0.0.1:17891 起一个极小的 HTTP 服务，接收
 * `POST /tts {"text":"..."}`，转到 proot 里跑 piper 合成 wav 再回给 node。
 *
 * 这样 `llm.mjs` 的 local 分支（LOCAL_TTS_URL）不用改：它本来就是
 * 「POST JSON → 拿到 wav 字节」的约定。
 */
object LocalTts {

    const val PORT = 17891
    const val URL = "http://127.0.0.1:$PORT/tts"

    private const val MODEL_ONNX = "models/zh_CN-huayan-medium.onnx"
    private const val MODEL_JSON = "models/zh_CN-huayan-medium.onnx.json"
    private const val MAX_TEXT = 2000

    @Volatile
    private var serverThread: Thread? = null

    @Volatile
    private var running = false

    // piper 发行包里是顶级 piper/ 目录：/root/piper/{piper, espeak-ng-data, *.so}
    private fun ttsDir(ctx: Context): File = File(LinuxEnv(ctx).rootfsDir, "root/piper")

    /** piper 可执行 + 中文音色都在才算装好 */
    fun installed(ctx: Context): Boolean = runCatching {
        val dir = ttsDir(ctx)
        File(dir, "piper").exists() && File(dir, MODEL_ONNX).exists() && File(dir, MODEL_JSON).exists()
    }.getOrDefault(false)

    /**
     * voices/ 下已安装的音色包名字（相对 voices 的路径去掉 .onnx，按名字排序）。
     * 空列表 = 还没导入过音色包，只能先用内置的 huayan。
     */
    fun voices(context: Context): List<String> {
        return runCatching {
            val dir = File(ttsDir(context), "voices")
            if (!dir.isDirectory) {
                emptyList()
            } else {
                dir.walkTopDown()
                    .filter { it.isFile && it.name.lowercase().endsWith(".onnx") }
                    .map { it.relativeTo(dir).path.removeSuffix(".onnx") }
                    .sorted()
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    /** 请求里的 voice 名字 → piper 的 -m / -c 参数（相对 /root/piper）；找不到就用内置 huayan */
    private fun resolveVoice(ctx: Context, voice: String): Pair<String, String> {
        val name = voice.trim()
        if (name.isNotEmpty()) {
            resolveByName(ctx, name)?.let { return it }
        } else {
            voices(ctx).firstOrNull()?.let { first ->
                resolveByName(ctx, first)?.let { return it }
            }
        }
        return MODEL_ONNX to MODEL_JSON
    }

    /** 按音色名找 onnx（名字可以是 "某个音色" 也可以是 "文件夹/某个音色"），找不到返回 null */
    private fun resolveByName(ctx: Context, name: String): Pair<String, String>? {
        val dir = ttsDir(ctx)
        val voicesDir = File(dir, "voices")
        val want = name.removeSuffix(".onnx").trim()
        val onnx = File(voicesDir, "$want.onnx").takeIf { it.isFile }
            ?: voicesDir.walkTopDown().firstOrNull {
                it.isFile && it.relativeTo(voicesDir).path.removeSuffix(".onnx") == want
            }
            ?: return null
        val rel = onnx.relativeTo(dir).path
        if (!File(dir, "$rel.json").isFile) return null
        return rel to "$rel.json"
    }

    fun start(ctx: Context): Boolean {
        if (running) return true
        val app = ctx.applicationContext
        if (!installed(app)) return false
        running = true
        val t = Thread({
            try {
                val addr = InetAddress.getByName("127.0.0.1")
                ServerSocket(PORT, 8, addr).use { server ->
                    server.soTimeout = 0
                    while (running) {
                        val sock = runCatching { server.accept() }.getOrNull() ?: continue
                        Thread({ handleSafely(app, sock) }, "tts-conn").start()
                    }
                }
            } catch (e: Exception) {
                LinuxEnv(app).log("本地 TTS 服务退出：${e.message}")
            } finally {
                running = false
            }
        }, "local-tts")
        t.isDaemon = true
        t.start()
        serverThread = t
        return true
    }

    fun stop() {
        running = false
        runCatching { serverThread?.interrupt() }
        serverThread = null
    }

    fun isRunning(): Boolean = running

    // ---------------------------------------------------------------- HTTP

    private fun handleSafely(ctx: Context, sock: Socket) {
        runCatching { sock.use { handle(ctx, it) } }
    }

    private fun handle(ctx: Context, sock: Socket) {
        sock.soTimeout = 150_000
        val input = sock.getInputStream()
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0) ?: return
        val path = parts.getOrNull(1) ?: "/"

        var contentLength = 0
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0 && line.substring(0, idx).trim().equals("Content-Length", ignoreCase = true)) {
                contentLength = line.substring(idx + 1).trim().toIntOrNull() ?: 0
            }
        }

        if (method == "GET" || method == "HEAD") {
            respond(sock.getOutputStream(), 200, "text/plain; charset=utf-8", "piper local tts".toByteArray())
            return
        }
        if (method != "POST") {
            respond(sock.getOutputStream(), 405, "text/plain; charset=utf-8", "method not allowed".toByteArray())
            return
        }

        if (contentLength <= 0 || contentLength > 256 * 1024) {
            respond(sock.getOutputStream(), 400, "application/json", """{"error":"bad body"}""".toByteArray())
            return
        }
        // 注意：Content-Length 是「字节数」，必须按字节读。
        // 之前用 BufferedReader 按「字符」读，中文一个字 3 字节 → 字符数不够，一直等更多数据 → 客户端 120s 超时。
        val raw = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(raw, read, contentLength - read)
            if (n < 0) break
            read += n
        }
        val bodyText = String(raw, 0, read, Charsets.UTF_8)
        val text = extractText(bodyText)
        if (text.isBlank()) {
            respond(sock.getOutputStream(), 400, "application/json", """{"error":"no text"}""".toByteArray())
            return
        }
        // voice 可选：指定用哪个本地音色包；不填就用内置/第一个已装音色
        val voice = extractVoice(bodyText)

        val detail = ArrayList<String>()
        val wav = synthRun(ctx, text, voice, detail)
        if (wav == null) {
            // 把 piper 的真实报错带回去，UI 才能告诉用户到底缺什么
            val det = jsonEsc(detail.takeLast(20).joinToString("\n")).take(1200)
            respond(sock.getOutputStream(), 500, "application/json", """{"error":"tts failed","log":"$det"}""".toByteArray())
        } else {
            val bytes = runCatching { wav.readBytes() }.getOrNull()
            if (bytes == null || bytes.size < 512) {
                respond(sock.getOutputStream(), 500, "application/json", """{"error":"empty audio"}""".toByteArray())
            } else {
                respond(sock.getOutputStream(), 200, "audio/wav", bytes)
            }
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var b = input.read()
        if (b < 0) return null
        while (b >= 0) {
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            b = input.read()
        }
        return sb.toString()
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val reason = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            405 -> "Method Not Allowed"
            else -> "Internal Server Error"
        }
        val head = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(body)
        out.flush()
    }

    /** 只认一个 "text" 字段；不拼 shell，文字通过文件喂给 piper，天然免疫引号/分号注入 */
    private fun extractText(json: String): String {
        val m = Regex("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json) ?: return ""
        val raw = m.groupValues[1]
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (val n = raw[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append(' ')
                    'r' -> sb.append(' ')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    'u' -> {
                        val hex = raw.substring(i + 2, minOf(i + 6, raw.length))
                        val code = hex.toIntOrNull(16)
                        if (code != null) sb.append(code.toChar())
                        i += 4
                    }
                    else -> sb.append(n)
                }
                i += 2
            } else {
                if (c == '\n') sb.append(' ') else sb.append(c)
                i++
            }
        }
        return sb.toString().trim().take(MAX_TEXT)
    }

    /** 只认一个 "voice" 字段（可选），指定本地音色包名字 */
    private fun extractVoice(json: String): String {
        val m = Regex("\"voice\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json) ?: return ""
        return m.groupValues[1].trim()
    }

    // ---------------------------------------------------------------- piper

    /** 一步接一步跑，把每一步的结果都记下来，方便用户一眼看出卡在哪 */
    fun selftest(ctx: Context): String {
        val linux = LinuxEnv(ctx)
        val sb = StringBuilder()
        fun line(s: String) {
            sb.append(s).append('\n')
            linux.log("语音自检> $s")
        }

        val dir = ttsDir(ctx)
        line("piper 目录：${dir.absolutePath}")
        listOf("piper", MODEL_ONNX, MODEL_JSON).forEach { f ->
            val file = File(dir, f)
            line("· $f：" + if (file.isFile) "${file.length() / 1024} KB" else "缺失")
        }
        val espeak = File(dir, "espeak-ng-data")
        line("· espeak-ng-data：" + if (espeak.isDirectory) "在" else "缺失")
        val proot = linux.prootBin
        line("proot：" + if (proot.isFile) "${proot.length() / 1024} KB" else "缺失（${proot.absolutePath}）")
        line("rootfs /bin/bash：" + if (File(linux.rootfsDir, "bin/bash").isFile) "在" else "缺失")

        if (!installed(ctx)) {
            line("结论：语音文件不全 → 需要先「下载并安装」本地语音")
            return sb.toString()
        }

        // 1) proot 能不能起来
        val t0 = System.currentTimeMillis()
        val echo = ArrayList<String>()
        val code1 = runCatching {
            linux.runCmd(
                linux.prootWrap(listOf("/bin/sh", "-c", "echo ok"), "/"),
                linux.prootEnv(),
                linux.baseDir,
            ) { echo.add(it) }
        }.getOrDefault(-1)
        line("1) proot 冒烟：exit=$code1（${System.currentTimeMillis() - t0}ms）" +
            if (echo.isEmpty()) "" else " → ${echo.takeLast(3).joinToString(" | ")}")

        // 2) piper 能不能跑
        val t1 = System.currentTimeMillis()
        val helpLines = ArrayList<String>()
        val code2 = runCatching {
            linux.runCmd(
                linux.prootWrap(piperCmd(listOf("--help")), "/root/piper"),
                linux.prootEnv(),
                linux.baseDir,
            ) { helpLines.add(it) }
        }.getOrDefault(-1)
        line("2) piper --help：exit=$code2（${System.currentTimeMillis() - t1}ms）")
        helpLines.takeLast(6).forEach { line("   $it") }

        // 3) 真合一句
        val t2 = System.currentTimeMillis()
        val synthLines = ArrayList<String>()
        val wav = synthRun(ctx, "你好，我是本地语音测试。", "", synthLines)
        line("3) 合成：exit=${if (wav == null) "失败" else "成功"}（${System.currentTimeMillis() - t2}ms）" +
            if (wav == null) "" else " · ${wav.length() / 1024} KB")
        synthLines.takeLast(10).forEach { line("   $it") }

        line(
            if (wav != null) "结论：本地语音可用（把「语音 → 方案」选「手机本地」就行）"
            else "结论：piper 跑不起来，上面的输出就是原因（常见：模型路径不对 / 缺少 .so / espeak 数据不全）"
        )
        return sb.toString()
    }

    /** proot 里跑 piper 的命令行：带上 espeak 数据和 piper 目录里的 .so */
    private fun piperCmd(args: List<String>): List<String> = listOf(
        "/usr/bin/env",
        "ESPEAK_DATA_PATH=/root/piper/espeak-ng-data",
        "LD_LIBRARY_PATH=/root/piper:/root/piper/lib",
        "/root/piper/piper",
    ) + args

    private fun synth(ctx: Context, text: String, voice: String = ""): File? =
        synthRun(ctx, text, voice, null)

    private fun synthRun(
        ctx: Context,
        text: String,
        voice: String,
        sink: MutableList<String>?,
    ): File? {
        val linux = LinuxEnv(ctx)
        val dir = ttsDir(ctx)
        if (!dir.exists()) return null
        val inFile = File(dir, "in.txt")
        val outFile = File(dir, "out.wav")
        runCatching { inFile.writeText(text, Charsets.UTF_8) }
        runCatching { outFile.delete() }

        val (model, config) = resolveVoice(ctx, voice)
        val inner = piperCmd(
            listOf(
                "-m", "/root/piper/$model",
                "-c", "/root/piper/$config",
                "-f", "/root/piper/out.wav",
            )
        )
        val cmd = linux.prootWrap(listOf("/bin/sh", "-c", shellJoin(inner)), "/root/piper")
        val code = runCatching {
            linux.runCmd(cmd, linux.prootEnv(), linux.baseDir) { line ->
                sink?.add(line)
                linux.log("tts> $line")
            }
        }.getOrDefault(-1)
        if (code != 0) {
            sink?.add("piper 退出码 $code")
            linux.log("本地 TTS 合成失败（code=$code）")
            return null
        }
        return outFile.takeIf { it.exists() && it.length() > 512 }
    }

    /** 参数里没有空白/引号/分号之类，拼成一行给 /bin/sh -c 是安全的 */
    private fun shellJoin(args: List<String>): String {
        fun q(s: String) = if (s.any { it.isWhitespace() || it in "'\"$\\;|&<>()`" }) "'" + s.replace("'", "'\\''") + "'" else s
        // 重定向用文件喂进去，避免文字里出现特殊字符
        val decl = args.joinToString(" ") { q(it) }
        return "$decl < /root/piper/in.txt"
    }

    // ---------------------------------------------------------------- 一键试听

    /** 从 HTTP 错误体里挖出 piper 的真实输出，翻成人话 */
    private fun explainError(code: Int, body: String): String {
        val m = Regex("\"log\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)
        val raw = m?.groupValues?.get(1)?.let { unescapeJson(it) }?.trim().orEmpty()
        val head = when {
            body.contains("empty audio") -> "piper 跑完了但没产出音频（模型文件可能是坏的/只下了一半）"
            body.contains("tts failed") -> "piper 合成失败"
            else -> "本地 TTS 服务返回 HTTP $code"
        }
        if (raw.isBlank()) return "合成失败：$head。去「接入 → 运行环境 → 复制日志」看 tts> 开头的几行。"
        return "合成失败：$head\npiper 的输出：\n${raw.take(600)}"
    }

    private fun unescapeJson(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    'u' -> {
                        val hex = s.substring(i + 2, minOf(i + 6, s.length))
                        hex.toIntOrNull(16)?.let { sb.append(it.toChar()) }
                        i += 4
                    }
                    else -> sb.append(n)
                }
                i += 2
            } else {
                sb.append(c)
                i += 1
            }
        }
        return sb.toString()
    }

    /** 最近一次试听生成的 wav（UI 用来播放） */
    @Volatile
    var lastWav: File? = null
        private set

    private fun jsonEsc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(' ') else sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * 端到端试一句：确保服务起着 → POST /tts → 存成 cache 里的 wav。
     * 这是「Piper 到底能不能用」最直接的答案：能生成非空 wav 就是能用。
     * @return 给人看的结果 + 生成的 wav（失败时 wav 为 null）
     */
    fun quickTest(ctx: Context, text: String = "你好，我是你的机器人。这句语音是手机自己合成的，没有用云服务。"): Pair<String, File?> {
        val app = ctx.applicationContext
        if (!installed(app)) {
            return "本地语音还没装好（缺 piper 或中文模型）。去「接入 → 运行环境」选「Piper 本地语音」→ 下载并安装。" to null
        }
        if (!isRunning() && !start(app)) {
            return "本地 TTS 服务没起来（端口 $PORT 被占用？）。先点「停止协议端」再试，或重启 App。" to null
        }
        val t0 = System.currentTimeMillis()
        LinuxEnv(app).log("语音试听> 开始（服务${if (isRunning()) "在跑" else "刚启动"}，端口 $PORT）")
        return try {
            val conn = java.net.URL(URL).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val body = """{"text":"${jsonEsc(text)}"}""".toByteArray(Charsets.UTF_8)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = runCatching { conn.errorStream?.readBytes()?.toString(Charsets.UTF_8) }.getOrNull()
                val msg = explainError(code, err.orEmpty())
                LinuxEnv(app).log("语音试听> 失败：$msg")
                return msg to null
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.size < 512) {
                LinuxEnv(app).log("语音试听> 失败：只拿到 ${bytes.size} 字节")
                return "合成失败：只拿到 ${bytes.size} 字节（piper 没吐出有效音频）" to null
            }
            val wav = File(app.cacheDir, "tts-test.wav")
            runCatching { wav.writeBytes(bytes) }
            lastWav = wav
            val ms = System.currentTimeMillis() - t0
            LinuxEnv(app).log("语音试听> 成功 ${bytes.size / 1024}KB / ${ms}ms")
            "✅ 合成成功：${bytes.size / 1024} KB，用时 $ms ms，${text.length} 个字\n下面点「播放」能听到，就说明手机本地语音真的能用。" to wav
        } catch (e: Exception) {
            val msg = "合成失败：${e.javaClass.simpleName} ${e.message ?: ""}（${System.currentTimeMillis() - t0}ms）"
            LinuxEnv(app).log("语音试听> 异常：$msg")
            msg.take(300) to null
        }
    }
}