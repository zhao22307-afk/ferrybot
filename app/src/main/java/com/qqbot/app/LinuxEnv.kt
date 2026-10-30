package com.qqbot.app

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream

/**
 * 手机本地 Linux 运行环境（proot + rootfs）。
 *
 * 目录结构：
 *   filesDir/linux/
 *     proot               aarch64 proot 可执行文件
 *     rootfs/             解压出来的 Linux 根文件系统
 *     downloads/          下载缓存（装完可删）
 *     env.log             安装/启动日志
 *     run/                协议端的工作目录、二维码等
 *
 * 为什么能跑：app 的 targetSdk 是 28，Android 10+ 对「应用私有目录里的可执行文件」的
 * W^X 限制不生效，所以解压出来的 proot 能直接 exec（Termux 就是靠这个）。
 */
class LinuxEnv(context: Context) {

    private val app = context.applicationContext
    private val base: File = File(app.filesDir, DIR_LINUX).apply { mkdirs() }

    val baseDir: File get() = base
    val rootfsDir: File get() = File(base, "rootfs")
    val downloadsDir: File get() = File(base, "downloads").apply { if (!exists()) mkdirs() }
    /** proot 及它依赖的 .so 放这儿（从 Termux 的 deb 里解出来） */
    val nativeDir: File get() = File(base, "native").apply { if (!exists()) mkdirs() }
    val prootBin: File get() = File(nativeDir, "proot")
    val libDir: File get() = File(nativeDir, "lib").apply { if (!exists()) mkdirs() }
    val logFile: File get() = File(base, "env.log")
    val runDir: File get() = File(base, "run").apply { if (!exists()) mkdirs() }

    /**
     * proot 需要的环境变量。
     * Termux 版 proot 把 RUNPATH / loader / 临时目录全写死成 /data/data/com.termux/…，
     * 所以必须用 LD_LIBRARY_PATH（RUNPATH 之后才搜）、PROOT_LOADER、PROOT_TMP_DIR 覆盖掉。
     */
    fun prootEnv(): Map<String, String> = mapOf(
        "PROOT_TMP_DIR" to File(rootfsDir, "tmp").apply { mkdirs() }.absolutePath,
        "PROOT_LOADER" to File(nativeDir, "libexec/proot/loader").absolutePath,
        "PROOT_L2S_DIR" to File(base, "l2s").apply { mkdirs() }.absolutePath,
        "LD_LIBRARY_PATH" to libDir.absolutePath,
    )

    /**
     * 把「rootfs 里要执行的命令」包成宿主机上的 proot 命令。
     * @param inner 例：["/bin/bash", "/host/install.sh"]
     * @param workdir rootfs 内的工作目录
     */
    fun prootWrap(inner: List<String>, workdir: String = "/root"): List<String> {
        val shm = File(rootfsDir, "tmp").apply { mkdirs() }.absolutePath
        val baseCmd = mutableListOf(
            prootBin.absolutePath,
            "--kill-on-exit",
            "--link2symlink",
            "-0",
            "-r", rootfsDir.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "$shm:/dev/shm",
            "-b", "${downloadsDir.absolutePath}:/dl",
            "-b", "${runDir.absolutePath}:/host",
            "-w", workdir,
            "/usr/bin/env", "-i",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            // 容器默认是 UTC，协议端日志会比北京时间慢 8 小时；手机基本都是 UTC+8，
            // 用系统时区 id 覆盖（读不到就退成 Asia/Shanghai）
            "TZ=${java.util.TimeZone.getDefault().id.takeIf { it.isNotBlank() } ?: "Asia/Shanghai"}",
        )
        baseCmd.addAll(inner)
        return baseCmd
    }

    /** proot 和 rootfs 都在 → 环境可用 */
    fun ready(): Boolean = prootBin.exists() && prootBin.canExecute() && rootfsUsable()

    private fun rootfsUsable(): Boolean {
        val bash = File(rootfsDir, "bin/bash")
        val sh = File(rootfsDir, "bin/sh")
        val dirs = File(rootfsDir, "etc").exists()
        return dirs && (bash.exists() || sh.exists())
    }

    /** 只铺了 proot、还没 rootfs */
    fun partial(): Boolean = prootBin.exists() && !rootfsUsable()

    fun log(line: String) {
        try {
            logFile.appendText("${nowStamp()}  $line\n")
        } catch (_: Exception) {
        }
    }

    /** 批量写日志（安装时一次几百行，省掉逐行开关文件的开销） */
    fun logLines(lines: List<String>) {
        if (lines.isEmpty()) return
        try {
            rotateIfBig()
            val ts = nowStamp()
            val sb = StringBuilder(lines.size * 40)
            lines.forEach { sb.append(ts).append("  ").append(it).append('\n') }
            logFile.appendText(sb.toString())
        } catch (_: Exception) {
        }
    }

    /** 日志超过 1.5MB 就只留最后 200KB，否则装几次环境 log 能涨到几十 MB */
    private fun rotateIfBig() {
        if (!logFile.exists() || logFile.length() < 1_500_000L) return
        val tail = readLog(200 * 1024)
        logFile.writeText("（日志过大已截断，只保留最近部分）\n$tail")
    }

    private fun nowStamp(): String =
        java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())

    fun readLog(maxBytes: Int = 64 * 1024): String = try {
        if (!logFile.exists()) ""
        else {
            val len = logFile.length()
            val from = if (len > maxBytes) len - maxBytes else 0L
            RandomAccessFile(logFile, "r").use { raf ->
                raf.seek(from)
                val buf = ByteArray((len - from).toInt())
                raf.readFully(buf)
                String(buf, Charsets.UTF_8)
            }
        }
    } catch (_: Exception) {
        ""
    }

    fun clearLog() = runCatching { logFile.delete() }

    // ---------------------------------------------------------------- 下载

    /**
     * 下载到 dest。支持断点续传：dest 已存在且服务器支持 Range 时接着下。
     * onProgress(已下字节, 总字节)，总字节未知时为 -1。
     *
     * @param attempts     最多尝试次数（网络差时自动退避重试）
     * @param expectedSize 期望大小（字节，0 = 不校验）；已下好且大小一致就直接跳过
     * @param shouldStop   返回 true 时中止（用于「取消安装」）
     */
    fun download(
        url: String,
        dest: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        attempts: Int = 3,
        expectedSize: Long = 0L,
        shouldStop: () -> Boolean = { false },
    ): Boolean {
        dest.parentFile?.let { if (!it.exists()) it.mkdirs() }
        if (expectedSize > 0 && dest.exists() && dest.length() >= expectedSize) {
            log("已下载过且大小一致，跳过：${dest.name}")
            return true
        }
        var attempt = 0
        while (attempt < attempts) {
            attempt++
            if (shouldStop()) {
                log("已取消下载：${dest.name}")
                return false
            }
            val existing = if (dest.exists()) dest.length() else 0L
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "ferry-bot-android/1.0")
                    if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    log("下载失败 HTTP $code：$url")
                    if (code in 400..499 && code != 408 && code != 429) return false // 4xx 重试没意义
                    continue
                }
                val total = if (code == 206) {
                    // Content-Range: bytes a-b/total
                    val cr = conn.getHeaderField("Content-Range") ?: ""
                    cr.substringAfterLast('/', "0").toLongOrNull() ?: -1L
                } else {
                    conn.contentLengthLong
                }
                val append = code == 206 && existing > 0
                val already = if (append) existing else 0L
                var got = already
                conn.inputStream.use { input ->
                    // append=false 时用 "rw" 会保留旧内容，这里明确用覆盖模式
                    dest.outputStream().use { out ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            if (shouldStop()) {
                                log("已取消下载：${dest.name}（已存 $got 字节，下次可续传）")
                                return false
                            }
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            got += n
                            onProgress(got, total)
                        }
                    }
                }
                if (total > 0 && got < total) {
                    log("下载不完整 ${got}/${total}，重试")
                    continue
                }
                if (expectedSize > 0 && dest.length() < expectedSize) {
                    // HTTP 明明传完了（got == total），只是和预设大小不同 —— 多半是「latest」地址换了版本，
                    // 不要死磕重下，交给上层做内容校验（zip/ar 魔数）。
                    if (total > 0 && got >= total) {
                        log("大小与预设不符（期望 $expectedSize，实际 ${dest.length()}），按实际内容继续")
                        return true
                    }
                    log("大小不符（期望 $expectedSize，实际 ${dest.length()}），重新下载")
                    dest.delete()
                    continue
                }
                return true
            } catch (e: Exception) {
                log("下载出错（第 $attempt/$attempts 次）：${e.message}")
                if (attempt >= attempts) return false
                runCatching { Thread.sleep(1200L * attempt) }
            } finally {
                runCatching { conn?.disconnect() }
            }
        }
        return false
    }


    /**
     * 探测服务器是否支持 Range，并拿到文件总大小。返回 null 表示不支持（调用方回退单连接）。
     */
    private fun probeRangeTotal(url: String): Long? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ferry-bot-android/1.0")
            setRequestProperty("Range", "bytes=0-0")
        }
        try {
            if (conn.responseCode != 206) {
                null
            } else {
                val cr = conn.getHeaderField("Content-Range") ?: ""
                cr.substringAfterLast('/', "0").toLongOrNull()?.takeIf { it > 0 }
            }
        } finally {
            runCatching { conn.inputStream.close() }
            runCatching { conn.disconnect() }
        }
    } catch (_: Exception) {
        null
    }

    /**
     * 分段并行下载：同一文件开 [segments] 个连接各下一个字节区间，写入同一文件的不同偏移。
     * 服务器不支持 Range 或拿不到大小时返回 null，调用方回退到单连接 [download]。
     *
     * 分段下载不做断点续传（中断后整文件删掉重下），用于「首次安装、网速慢」场景。
     * onProgress(已下字节, 总字节)；onSpeed 每秒回调一次当前速率（字节/秒）。
     */
    fun downloadSegmented(
        url: String,
        dest: File,
        segments: Int = 4,
        expectedSize: Long = 0L,
        shouldStop: () -> Boolean = { false },
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        onSpeed: (Double) -> Unit = {},
    ): Boolean? {
        if (segments <= 1) return null
        val probed = probeRangeTotal(url) ?: return null
        val total = if (expectedSize > 0) expectedSize else probed
        if (total <= 0) return null
        dest.parentFile?.let { if (!it.exists()) it.mkdirs() }
        if (dest.exists()) dest.delete()

        val n = minOf(segments, maxOf(1, (total / (2L * 1024 * 1024)).toInt()))
        val per = total / n
        val got = AtomicLong(0L)
        val failed = AtomicBoolean(false)
        val done = AtomicLong(0L)
        val threads = ArrayList<Thread>(n)
        for (i in 0 until n) {
            val start = i * per
            val end = if (i == n - 1) total - 1 else start + per - 1
            threads.add(Thread {
                var conn: HttpURLConnection? = null
                var raf: RandomAccessFile? = null
                try {
                    if (failed.get() || shouldStop()) return@Thread
                    conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 20_000
                        readTimeout = 40_000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "ferry-bot-android/1.0")
                        setRequestProperty("Range", "bytes=$start-$end")
                    }
                    if (conn.responseCode != 206) {
                        log("分段下载：服务器拒绝了 Range（段 ${i + 1}）")
                        failed.set(true)
                        return@Thread
                    }
                    raf = RandomAccessFile(dest, "rw")
                    raf.setLength(total)
                    raf.seek(start)
                    conn.inputStream.use { input ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            if (failed.get() || shouldStop()) return@Thread
                            val len = input.read(buf)
                            if (len <= 0) break
                            raf.write(buf, 0, len)
                            got.addAndGet(len.toLong())
                        }
                    }
                } catch (e: Exception) {
                    log("分段下载失败（段 ${i + 1}）：${e.message}")
                    failed.set(true)
                } finally {
                    runCatching { raf?.close() }
                    runCatching { conn?.disconnect() }
                    done.incrementAndGet()
                }
            })
        }
        threads.forEach { it.start() }

        var lastBytes = 0L
        var lastAt = System.currentTimeMillis()
        while (done.get() < n) {
            Thread.sleep(500)
            val now = System.currentTimeMillis()
            val cur = got.get()
            val dt = (now - lastAt) / 1000.0
            if (dt >= 0.4) {
                onSpeed((cur - lastBytes) / dt)
                lastAt = now
                lastBytes = cur
            }
            onProgress(cur, total)
        }
        threads.forEach { runCatching { it.join(1500) } }

        if (shouldStop()) {
            log("已取消分段下载：${dest.name}")
            dest.delete()
            return false
        }
        if (failed.get() || dest.length() != total) {
            log("分段下载失败：${dest.name}（${dest.length()}/$total）")
            dest.delete()
            return false
        }
        onSpeed(0.0)
        return true
    }

    /**
     * 测速：连上 url 后在 [timeoutMs] 内尽量多地读，返回字节/秒。
     *
     * 用于「多下载源选最快」——国内访问 GitHub / 腾讯 CDN / 清华镜像速度差好几个数量级，
     * 死磕主地址就会卡在几十 KB/s。
     *
     * @return 速度（字节/秒）；连不上或基本没数据返回 -1
     */
    fun probeSpeed(url: String, timeoutMs: Int = 6000): Long {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "ferry-bot-android/1.0")
                setRequestProperty("Range", "bytes=0-3145727")
            }
            val code = conn.responseCode
            if (code !in 200..299) return -1
            val t0 = System.currentTimeMillis()
            var got = 0L
            conn.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                while (System.currentTimeMillis() - t0 < timeoutMs) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    got += n
                }
            }
            val ms = (System.currentTimeMillis() - t0).coerceAtLeast(1)
            if (got < 16 * 1024) -1 else got * 1000 / ms
        } catch (_: Exception) {
            -1
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    // ---------------------------------------------------------------- 解压

    /**
     * 解压 .tar.gz 到 destDir（rootfs 用）。
     * onProgress(已处理字节)。
     */
    fun extractTarGz(src: File, destDir: File, onProgress: (Long) -> Unit = {}): Boolean {
        return try {
            if (!destDir.exists()) destDir.mkdirs()
            val total = src.length()
            FileInputStream(src).use { raw ->
                TarExtractor(destDir).use { ex ->
                    GZIPInputStream(BufferedInputStream(raw, 256 * 1024), 256 * 1024).use { gz ->
                        ex.extract(gz, total, onProgress)
                    }
                }
            }
            true
        } catch (e: Exception) {
            log("解压失败：${e.message}")
            false
        }
    }

    /**
     * 从 InputStream 解压 tar.gz（下载直解，省一份缓存时用）。
     */
    fun extractTarGz(input: InputStream, destDir: File, totalHint: Long = -1L, onProgress: (Long) -> Unit = {}): Boolean {
        return try {
            if (!destDir.exists()) destDir.mkdirs()
            TarExtractor(destDir).use { ex ->
                GZIPInputStream(BufferedInputStream(input, 256 * 1024), 256 * 1024).use { gz ->
                    ex.extract(gz, totalHint, onProgress)
                }
            }
            true
        } catch (e: Exception) {
            log("解压失败：${e.message}")
            false
        }
    }

    fun clearRootfs() = EnvManager.deleteRecursive(rootfsDir)

    fun clearAll() {
        EnvManager.deleteRecursive(base)
        if (!base.exists()) base.mkdirs()
    }

    /** 环境占用空间（字节） */
    fun diskUsage(): Long = walkSize(base)

    /** 磁盘剩余空间（字节，-1 表示取不到）。装环境前用它做预检，别装到一半没空间。 */
    fun freeSpaceBytes(): Long = try {
        android.os.StatFs(base.absolutePath).availableBytes
    } catch (_: Exception) {
        -1L
    }

    private fun walkSize(f: File): Long {
        if (f.isFile) return f.length()
        var sum = 0L
        f.listFiles()?.forEach { sum += walkSize(it) }
        return sum
    }

    // ---------------------------------------------------------------- 执行

    /**
     * 用系统 sh 跑一段脚本，逐行回调输出。返回退出码。
     * Android 自带 /system/bin/sh（mksh），POSIX 够用，不用额外带 bash。
     */
    fun runSh(
        script: String,
        envVars: Map<String, String> = emptyMap(),
        cwd: File = base,
        onLine: (String) -> Unit = {},
    ): Int {
        return try {
            val pb = ProcessBuilder("/system/bin/sh", "-c", script)
            pb.directory(cwd)
            pb.redirectErrorStream(true)
            val env = pb.environment()
            env["PATH"] = "/system/bin:/system/xbin:$base"
            env["HOME"] = base.absolutePath
            env["TMPDIR"] = app.cacheDir.absolutePath
            envVars.forEach { (k, v) -> env[k] = v }
            val p = pb.start()
            p.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    onLine(line)
                }
            }
            p.waitFor()
        } catch (e: Exception) {
            onLine("执行出错：${e.message}")
            -1
        }
    }

    /**
     * 阻塞执行一条命令（比如 proot），逐行回调输出。返回退出码。
     */
    fun runCmd(
        cmd: List<String>,
        envVars: Map<String, String> = emptyMap(),
        cwd: File = base,
        onLine: (String) -> Unit = {},
    ): Int {
        return try {
            val pb = ProcessBuilder(cmd)
            pb.directory(cwd)
            pb.redirectErrorStream(true)
            val env = pb.environment()
            env["PATH"] = "/system/bin:/system/xbin"
            env["HOME"] = base.absolutePath
            env["TMPDIR"] = app.cacheDir.absolutePath
            envVars.forEach { (k, v) -> env[k] = v }
            val p = pb.start()
            p.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    onLine(line)
                }
            }
            p.waitFor()
        } catch (e: Exception) {
            onLine("执行出错：${e.message}")
            -1
        }
    }

    /**
     * 启动常驻进程（协议端），把输出按行回调。调用方自己保存 Process 以便停止。
     */
    fun spawn(
        cmd: List<String>,
        cwd: File = runDir,
        envVars: Map<String, String> = emptyMap(),
        onLine: (String) -> Unit,
    ): Process? {
        return try {
            val pb = ProcessBuilder(cmd)
            pb.directory(cwd)
            pb.redirectErrorStream(true)
            val env = pb.environment()
            env["PATH"] = "/system/bin:/system/xbin"
            env["HOME"] = base.absolutePath
            env["TMPDIR"] = app.cacheDir.absolutePath
            envVars.forEach { (k, v) -> env[k] = v }
            val p = pb.start()
            Thread {
                try {
                    p.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                        while (true) {
                            val line = r.readLine() ?: break
                            onLine(line)
                        }
                    }
                } catch (_: Exception) {
                }
            }.apply { isDaemon = true }.start()
            p
        } catch (e: Exception) {
            onLine("启动失败：${e.message}")
            null
        }
    }

    /** 二维码：协议端把登录二维码写到工作目录里的图片，找最新的那张 */
    fun findQrImage(): File? {
        val dirs = listOf(runDir, rootfsDir, File(runDir, "napcat"), File(runDir, "lagrange"))
        var best: File? = null
        dirs.forEach { d ->
            d.listFiles()?.forEach { f ->
                val n = f.name.lowercase()
                if (!f.isFile) return@forEach
                if (!(n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".gif"))) return@forEach
                if (!n.contains("qr") && !n.contains("qrcode")) return@forEach
                if (best == null || f.lastModified() > best!!.lastModified()) best = f
            }
        }
        return best
    }

    companion object {
        const val DIR_LINUX = "linux"
    }
}

/**
 * 最小 tar 解压器（够解 Debian/Ubuntu/Alpine rootfs）：
 * - 普通文件 / 目录 / 软链 / 硬链
 * - GNU 长文件名（typeflag 'L' / 'K'）
 * - PAX 扩展头（'x' / 'g'，取 path=/linkpath=）
 * - 设备节点、FIFO 直接跳过（手机上建不了也没必要）
 * - 路径穿越（..、绝对路径）会被拦截
 */
class TarExtractor(private val dest: File) : AutoCloseable {

    private val hardlinks = ArrayList<Pair<File, String>>()
    private var processed = 0L

    fun extract(input: InputStream, totalHint: Long, onProgress: (Long) -> Unit) {
        val header = ByteArray(512)
        var longName: String? = null
        var longLink: String? = null
        var paxPath: String? = null
        var paxLink: String? = null

        while (true) {
            if (!readFully(input, header)) break
            if (header.all { it == 0.toByte() }) break

            val type = header[156].toInt().toChar()
            val rawName = cstr(header, 0, 100)
            var name = rawName
            var size = octal(header, 124, 12)

            when (type) {
                'L' -> { // GNU long name：下一块的 name
                    val data = ByteArray(size.toInt())
                    readFully(input, data)
                    skipPadding(input, size)
                    longName = cstr(data, 0, data.size)
                    continue
                }
                'K' -> {
                    val data = ByteArray(size.toInt())
                    readFully(input, data)
                    skipPadding(input, size)
                    longLink = cstr(data, 0, data.size)
                    continue
                }
                'x', 'g' -> { // PAX
                    val data = ByteArray(size.toInt())
                    readFully(input, data)
                    skipPadding(input, size)
                    val text = String(data, Charsets.UTF_8)
                    parsePax(text).forEach { (k, v) ->
                        when (k) {
                            "path" -> paxPath = v
                            "linkpath" -> paxLink = v
                            "size" -> v.toLongOrNull()?.let { size = it }
                        }
                    }
                    continue
                }
            }

            val finalName = paxPath ?: longName ?: rawName
            val linkName = paxLink ?: longLink ?: cstr(header, 157, 100)
            longName = null
            longLink = null
            paxPath = null
            paxLink = null

            val safe = safePath(finalName) ?: run {
                skipBytes(input, size)
                skipPadding(input, size)
                continue
            }

            when (type) {
                '5' -> {
                    safe.mkdirs()
                    applyMode(safe, octal(header, 100, 8))
                    skipPadding(input, size)
                }
                '2' -> { // symlink
                    safe.parentFile?.mkdirs()
                    runCatching { safe.delete() }
                    try {
                        Files.createSymbolicLink(Paths.get(safe.absolutePath), Paths.get(linkName))
                    } catch (_: Exception) {
                        // 建不了软链（极少见）：留空文件占位，后面 report
                    }
                    skipPadding(input, size)
                }
                '1' -> { // hardlink：等全部解完再补
                    hardlinks.add(safe to linkName)
                    skipPadding(input, size)
                }
                '0', '\u0000', '7' -> {
                    safe.parentFile?.mkdirs()
                    safe.outputStream().use { out -> copyN(input, out, size) }
                    applyMode(safe, octal(header, 100, 8))
                    skipPadding(input, size)
                }
                else -> { // 3,4 设备 / 6 FIFO / 其它：跳过数据
                    skipBytes(input, size)
                    skipPadding(input, size)
                }
            }

            processed += 512 + size
            onProgress(processed)
        }

        // 硬链补完（指向的文件必须已存在）
        hardlinks.forEach { (target, linkName) ->
            val src = safePath(linkName)
            if (src == null || !src.exists()) return@forEach
            target.parentFile?.mkdirs()
            runCatching {
                if (target.exists()) target.delete()
                try {
                    Files.createLink(Paths.get(target.absolutePath), Paths.get(src.absolutePath))
                } catch (_: Exception) {
                    Files.copy(Paths.get(src.absolutePath), Paths.get(target.absolutePath), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    /** 只允许落在 dest 之内；去掉开头的 ./ 和 / */
    private fun safePath(raw: String): File? {
        var p = raw.trim()
        if (p.isEmpty()) return null
        p = p.removePrefix("./")
        p = p.trimStart('/')
        if (p.isEmpty()) return null
        val f = File(dest, p)
        val canonDest = dest.canonicalPath
        val canon = f.canonicalPath
        if (canon != canonDest && !canon.startsWith(canonDest + File.separator)) return null
        return f
    }

    private fun applyMode(f: File, mode: Long) {
        val perms = HashSet<PosixFilePermission>()
        if (mode and 0b100_000_000L != 0L) perms.add(PosixFilePermission.OWNER_READ)
        if (mode and 0b010_000_000L != 0L) perms.add(PosixFilePermission.OWNER_WRITE)
        if (mode and 0b001_000_000L != 0L) perms.add(PosixFilePermission.OWNER_EXECUTE)
        if (mode and 0b000_100_000L != 0L) perms.add(PosixFilePermission.GROUP_READ)
        if (mode and 0b000_010_000L != 0L) perms.add(PosixFilePermission.GROUP_WRITE)
        if (mode and 0b000_001_000L != 0L) perms.add(PosixFilePermission.GROUP_EXECUTE)
        if (mode and 0b000_000_100L != 0L) perms.add(PosixFilePermission.OTHERS_READ)
        if (mode and 0b000_000_010L != 0L) perms.add(PosixFilePermission.OTHERS_WRITE)
        if (mode and 0b000_000_001L != 0L) perms.add(PosixFilePermission.OTHERS_EXECUTE)
        runCatching { Files.setPosixFilePermissions(Paths.get(f.absolutePath), perms) }
    }

    private fun parsePax(text: String): Map<String, String> {
        val out = HashMap<String, String>()
        var i = 0
        while (i < text.length) {
            val sp = text.indexOf(' ', i)
            if (sp <= i) break
            val len = text.substring(i, sp).toIntOrNull() ?: break
            if (len <= 0) break
            val rec = text.substring(i, minOf(i + len, text.length))
            val kv = rec.substringAfter(' ', "")
            val eq = kv.indexOf('=')
            if (eq > 0) {
                val k = kv.substring(0, eq)
                val v = kv.substring(eq + 1).trimEnd('\n', '\u0000')
                out[k] = v
            }
            i += len
        }
        return out
    }

    private fun cstr(buf: ByteArray, off: Int, len: Int): String {
        var end = off
        val max = minOf(off + len, buf.size)
        while (end < max && buf[end] != 0.toByte()) end++
        return String(buf, off, end - off, Charsets.UTF_8)
    }

    private fun octal(buf: ByteArray, off: Int, len: Int): Long {
        var i = off
        val max = minOf(off + len, buf.size)
        var value = 0L
        while (i < max) {
            val c = buf[i].toInt() and 0xff
            if (c == 0 || c == ' '.code) { i++; continue }
            if (c < '0'.code || c > '7'.code) break
            value = value * 8 + (c - '0'.code)
            i++
        }
        return value
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n <= 0) return false
            read += n
        }
        return true
    }

    private fun copyN(input: InputStream, out: java.io.OutputStream, n: Long) {
        var left = n
        val buf = ByteArray(128 * 1024)
        while (left > 0) {
            val want = minOf(buf.size.toLong(), left).toInt()
            val r = input.read(buf, 0, want)
            if (r <= 0) break
            out.write(buf, 0, r)
            left -= r
        }
        // 若文件比 header 里写的短，补零以免后面错位
        while (left > 0) { out.write(0); left-- }
    }

    private fun skipBytes(input: InputStream, n: Long) {
        var left = n
        val buf = ByteArray(64 * 1024)
        while (left > 0) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r <= 0) break
            left -= r
        }
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val pad = (512 - (size % 512)) % 512
        skipBytes(input, pad)
    }

    override fun close() {}
}