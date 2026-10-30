package com.qqbot.app

import android.content.res.AssetManager
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile

/**
 * 协议端「一键安装包」：把安装步骤写成 assets/installers/<id>.json，
 * 这样 NapCat / Lagrange / 以后别的协议端加进来都不用改 Kotlin 代码。
 *
 * JSON 结构（示例）：
 * {
 *   "id": "napcat",
 *   "name": "NapCat",
 *   "sizeHint": 780,                     // 预计下载 MB，仅用于 UI 提示
 *   "downloads": [
 *     {"url": "https://…rootfs.tar.gz", "file": "rootfs.tar.gz"},
 *     {"url": "https://…proot",         "file": "proot"}
 *   ],
 *   "steps": [
 *     {"type": "extract", "file": "rootfs.tar.gz", "dest": "rootfs"},
 *     {"type": "chmod",   "path": "proot", "mode": "755"},
 *     {"type": "sh",      "cmd": "…"},                      // 占位符：{{BASE}} {{ROOTFS}} {{PROOT}} {{LIB}} {{RUN}}
 *     {"type": "sh",      "cmd": "…", "timeoutMs": 600000}
 *   ],
 *   "run": {
 *     "cmd": ["{{PROOT}}", "-r", "{{ROOTFS}}", …],
 *     "workdir": "run",
 *     "port": 3001,
 *     "qrGlob": "*.png"
 *   }
 * }
 */
class EnvInstaller(
    private val linux: LinuxEnv,
    private val assets: AssetManager,
    /** 同时下载几个文件（1-6 路）。手机网络差时调小更稳，网好时调大更快 */
    private val connsProvider: () -> Int = { 4 },
    /** 下载前先测各镜像速度、优先用最快的；关掉就按配置顺序死磕 */
    private val smartProvider: () -> Boolean = { true },
    /** 用户在「下载源」里手动指定的下标（-1 = 自动）；参数是文件名 */
    private val sourceChooser: (String) -> Int = { -1 },
    /** piper 等本地语音包装好后回调，上层好把 TTS 地址自动填进表单 */
    private val onTtsReady: (String) -> Unit = {},
) {

    data class Pack(
        val id: String,
        val name: String,
        val sizeHint: Int,
        val kind: String,
        val downloads: List<Download>,
        val steps: List<Step>,
        val run: RunSpec?,
        val needDiskMb: Int = 0,
    ) {
        /** 需要 proot 才能在 rootfs 里跑的安装包（NapCat / Lagrange 都是） */
        val isProot: Boolean get() = kind == "proot"
    }

    /** mirrors：主地址失败/太慢时依次再试的备份地址（GitHub 走加速代理用） */
    data class Download(
        val url: String,
        val file: String,
        val size: Long = 0L,
        val mirrors: List<String> = emptyList(),
    )

    /** 单个安装步骤执行过一次就落一个戳，重试/续装时不再从头跑（下载那个 470MB 的包不会再重复解压） */
    private fun stampFile(pack: Pack, s: Step): File {
        val key = listOf(s.type, s.file ?: "", s.dest ?: "", s.path ?: "", s.cmd ?: "").joinToString("|")
        val hash = Integer.toHexString(key.hashCode())
        return File(File(linux.baseDir, ".steps"), "${pack.id}-${s.type}-$hash.done")
    }

    /**
     * 内容校验：光看大小不够 —— chunked 传输被掐断、镜像返回报错页、GitHub 加速代理返回半截文件，
     * 都会得到「大小看着差不多但不是有效文件」的结果。这里按后缀验魔数/能否打开。
     */
    private fun looksValid(dest: File, file: String): Boolean {
        if (!dest.exists() || dest.length() == 0L) return false
        return try {
            when {
                file.endsWith(".zip") -> ZipFile(dest).use { it.size() > 0 }
                file.endsWith(".deb") -> {
                    val h = ByteArray(8)
                    dest.inputStream().use { it.read(h) }
                    String(h, Charsets.US_ASCII) == "!<arch>\n"
                }
                file.endsWith(".tar.gz") || file.endsWith(".tgz") -> {
                    dest.inputStream().use {
                        val a = it.read()
                        val b = it.read()
                        a == 0x1f && b == 0x8b
                    }
                }
                else -> true
            }
        } catch (_: Exception) {
            false
        }
    }

    /** 主地址 + 镜像，去重后依次尝试 */
    fun candidates(d: Download): List<String> = (listOf(d.url) + d.mirrors).distinct()

    /** 给界面看的短名字：域名 → 「清华/中科大/腾讯/ghfast…」 */
    fun sourceName(url: String): String = when {
        "mirrors.tuna.tsinghua.edu.cn" in url -> "清华镜像"
        "mirrors.ustc.edu.cn" in url -> "中科大镜像"
        "mirrors.aliyun.com" in url -> "阿里云镜像"
        "mirror.nju.edu.cn" in url -> "南大镜像"
        "packages.termux.dev" in url -> "Termux 官方"
        "qqdl.gtimg.cn" in url -> "腾讯 CDN"
        "dldir1v6.qq.com" in url -> "腾讯 dldir1v6"
        "dldir1.qq.com" in url -> "腾讯 dldir1"
        "ghfast.top" in url -> "ghfast 加速"
        "gh-proxy.com" in url -> "gh-proxy 加速"
        "ghproxy.net" in url -> "ghproxy 加速"
        "moeyy" in url -> "moeyy 加速"
        "github.com" in url -> "GitHub 原站"
        else -> url.substringAfter("://").substringBefore("/").take(28)
    }

    fun sourceShort(url: String): String = sourceName(url) + " · " + url.substringAfter("://").take(48)

    /**
     * 多源测速，按速度从快到慢排序（都测不到就保持原顺序）。
     *
     * 国内访问 GitHub / 腾讯 CDN / 清华镜像速度能差几十倍，死磕主地址会卡在几十 KB/s；
     * 每个源只测 6 秒，比用慢源硬下 200MB 便宜得多。
     */
    private fun orderBySpeed(urls: List<String>, onLine: (String) -> Unit): List<String> {
        if (urls.size <= 1) return urls
        val speeds = HashMap<String, Long>()
        val threads = urls.map { u ->
            Thread {
                val s = linux.probeSpeed(u)
                synchronized(speeds) { speeds[u] = s }
            }.also {
                it.isDaemon = true
                it.start()
            }
        }
        threads.forEach { runCatching { it.join(8000) } }
        val ordered = urls.sortedByDescending { speeds[it] ?: -1L }
        for (u in ordered) {
            val s = speeds[u] ?: -1L
            val txt = if (s > 0) String.format("%.1f MB/s", s / 1024.0 / 1024.0) else "连不上/太慢"
            onLine("测速 $txt  ${sourceName(u)}  ${u.take(48)}")
        }
        return ordered
    }

    /** 下载+解压大概要这么多空间（MB）；配置里可用 needDiskMb 覆盖 */
    private fun requiredDiskMb(pack: Pack): Int {
        val approx = (pack.downloads.sumOf { it.size } / 1024 / 1024).toInt() + pack.sizeHint
        // 环境已装好时（例如只补装 Python 之类的小包），别再按 2GB 门槛拦人
        if (linux.ready()) return maxOf(pack.needDiskMb, approx, 200)
        return maxOf(pack.needDiskMb, approx, 2000)
    }

    data class Step(
        val type: String,
        val file: String?,
        val dest: String?,
        val path: String?,
        val mode: String?,
        val cmd: String?,
        val timeoutMs: Long,
        val pick: Map<String, String>,
        /** 这一步依赖的 rootfs 内文件已存在就跳过（避免已有环境重复下 rootfs/proot） */
        val ifMissing: String = "",
    )

    data class RunSpec(val cmd: List<String>, val workdir: String, val port: Int, val webui: Int)

    fun loadPack(id: String): Pack? = try {
        val text = assets.open("installers/$id.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        parse(text)
    } catch (e: Exception) {
        linux.log("读取安装包配置失败 $id：${e.message}")
        null
    }

    fun availablePacks(): List<Pair<String, String>> = try {
        (assets.list("installers") ?: emptyArray())
            .filter { it.endsWith(".json") }
            .map { f ->
                val id = f.removeSuffix(".json")
                val name = runCatching {
                    val t = assets.open("installers/$f").bufferedReader().use { it.readText() }
                    JSONObject(t).optString("name", id)
                }.getOrDefault(id)
                id to name
            }
    } catch (_: Exception) {
        emptyList()
    }

    private fun parse(text: String): Pack {
        val o = JSONObject(text)
        val downloads = ArrayList<Download>()
        o.optJSONArray("downloads")?.let { arr ->
            for (i in 0 until arr.length()) {
                val d = arr.getJSONObject(i)
                val mirrors = ArrayList<String>()
                d.optJSONArray("mirrors")?.let { m ->
                    for (k in 0 until m.length()) mirrors.add(m.getString(k))
                }
                downloads.add(
                    Download(d.getString("url"), d.getString("file"), d.optLong("size", 0L), mirrors)
                )
            }
        }
        val steps = ArrayList<Step>()
        o.optJSONArray("steps")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val pick = LinkedHashMap<String, String>()
                s.optJSONObject("pick")?.let { p ->
                    for (k in p.keys()) pick[k] = p.getString(k)
                }
                steps.add(
                    Step(
                        type = s.getString("type"),
                        file = s.optString("file").ifBlank { null },
                        dest = s.optString("dest").ifBlank { null },
                        path = s.optString("path").ifBlank { null },
                        mode = s.optString("mode").ifBlank { null },
                        cmd = s.optString("cmd").ifBlank { null },
                        timeoutMs = s.optLong("timeoutMs", 0L),
                        pick = pick,
                        ifMissing = s.optString("ifMissing"),
                    )
                )
            }
        }
        var run: RunSpec? = null
        o.optJSONObject("run")?.let { r ->
            val cmdList = ArrayList<String>()
            r.optJSONArray("cmd")?.let { arr ->
                for (i in 0 until arr.length()) cmdList.add(arr.getString(i))
            }
            if (cmdList.isNotEmpty()) {
                run = RunSpec(
                    cmd = cmdList,
                    workdir = r.optString("workdir", "run"),
                    port = r.optInt("port", 3001),
                    webui = r.optInt("webui", 0),
                )
            }
        }
        return Pack(
            id = o.optString("id", "unknown"),
            name = o.optString("name", o.optString("id", "unknown")),
            sizeHint = o.optInt("sizeHint", 0),
            kind = o.optString("kind", "rootfs"),
            downloads = downloads,
            steps = steps,
            run = run,
            needDiskMb = o.optInt("needDiskMb", 0),
        )
    }

    /**
     * 依次执行安装步骤。onStep 给 UI 显示当前步骤，onProgress 0..1（-1 表示不确定）。
     *
     * 可重入：下载会断点续传、解压步骤有完成标记（[stampFile]），
     * 中途失败后重新点「安装」不会把 470MB 再下一遍。
     */
    @Volatile
    var cancelRequested = false
        private set

    fun cancel() {
        cancelRequested = true
        linux.log("用户请求取消安装")
    }

    fun install(
        pack: Pack,
        onStep: (String) -> Unit,
        onProgress: (Float) -> Unit,
        onLine: (String) -> Unit,
    ): Boolean {
        cancelRequested = false
        val base = linux.baseDir
        onStep("准备目录")
        linux.downloadsDir.mkdirs()
        linux.runDir.mkdirs()
        File(base, ".steps").mkdirs()

        // 0. 空间预检：装完要 1.5-2GB，别装到一半报 ENOSPC
        val needMb = requiredDiskMb(pack)
        val freeMb = linux.freeSpaceBytes().let { if (it < 0) -1 else it / 1024 / 1024 }
        if (freeMb in 0..(needMb.toLong())) {
            onLine("❌ 存储空间不足：需要约 $needMb MB，当前只剩 $freeMb MB")
            return false
        }

        // 1. 下载：4 路并发 + 大文件分段 + 镜像回退（原来串行下载，用户反馈太慢）
        val downloads = pack.downloads
        if (downloads.isNotEmpty()) {
            val skipDone = BooleanArray(downloads.size)
            val got = Array(downloads.size) { AtomicLong(0L) }
            val pending = AtomicInteger(0)
            val failed = AtomicBoolean(false)
            val cancelledFlag = AtomicBoolean(false)
            val conns = connsProvider().coerceIn(1, 6)
            val smart = smartProvider()
            val gate = java.util.concurrent.Semaphore(conns)
            onLine("开始下载 ${downloads.size} 个文件（$conns 路并发${if (smart) " · 自动测速选源" else ""}）")

            val workers = ArrayList<Thread>()
            for (i in downloads.indices) {
                val d = downloads[i]
                val dest = File(linux.downloadsDir, d.file)
                if (d.size > 0 && dest.exists() && dest.length() >= d.size && looksValid(dest, d.file)) {
                    onLine("已下载：${d.file}（跳过）")
                    got[i].set(d.size)
                    skipDone[i] = true
                    continue
                }
                pending.incrementAndGet()
                workers.add(Thread {
                    try {
                        if (cancelRequested) {
                            cancelledFlag.set(true)
                            return@Thread
                        }
                        linux.log("下载 ${d.url} → ${dest.absolutePath}")
                        val cands = candidates(d)
                        val manual = sourceChooser(d.file)
                        val urls: List<String> = when {
                            manual in cands.indices -> {
                                onLine("使用指定下载源：${sourceShort(cands[manual])}")
                                listOf(cands[manual]) + cands.filterIndexed { k, _ -> k != manual }
                            }
                            smart && cands.size > 1 -> orderBySpeed(cands, onLine)
                            else -> cands
                        }
                        var ok = false
                        var attempt = 0
                        gate.acquire() // 限制同时下载的文件数：慢网开太多连接反而互相拖
                        try {
                            while (!ok && attempt < 2 && !cancelRequested && !failed.get()) {
                                attempt++
                                for (u in urls) {
                                    if (failed.get() || cancelRequested) break
                                    // 看门狗：15 秒没有任何新字节进来 = 这个地址被限速/掐了，换下一个
                                    val stall = AtomicBoolean(false)
                                    val mon = Thread {
                                        var seen = got[i].get()
                                        var at = System.currentTimeMillis()
                                        while (!stall.get() && !cancelRequested) {
                                            Thread.sleep(1000)
                                            val g = got[i].get()
                                            if (g > seen) {
                                                seen = g
                                                at = System.currentTimeMillis()
                                            } else if (System.currentTimeMillis() - at > 15_000) {
                                                stall.set(true)
                                            }
                                        }
                                    }.also {
                                        it.isDaemon = true
                                        it.start()
                                    }
                                    val stop = { cancelRequested || stall.get() }
                                    // 60MB 以上先试 3 段并发，绕过单连接限速
                                    val big = d.size >= 60L * 1024 * 1024
                                    var done = false
                                    if (big) {
                                        done = linux.downloadSegmented(
                                            u, dest,
                                            segments = 3,
                                            expectedSize = if (d.size > 0) d.size else 0L,
                                            shouldStop = stop,
                                            onProgress = { g, _ -> got[i].set(g) },
                                        ) == true
                                    }
                                    if (!done) {
                                        done = linux.download(
                                            u, dest,
                                            onProgress = { g, _ -> got[i].set(g) },
                                            expectedSize = d.size,
                                            shouldStop = stop,
                                        )
                                    }
                                    stall.set(true)
                                    runCatching { mon.join(400) }
                                    if (done) {
                                        ok = true
                                        break
                                    }
                                    if (cancelRequested) break
                                    onLine("这个地址太慢，换下一个：${u.take(56)}…")
                                }
                                if (ok && !looksValid(dest, d.file)) {
                                    onLine("文件不完整，重新下载：${d.file}（已下 ${dest.length() / 1024 / 1024} MB）")
                                    linux.log("校验失败，删除重下：${dest.absolutePath}")
                                    dest.delete()
                                    got[i].set(0L)
                                    ok = false
                                }
                            }
                        } finally {
                            gate.release()
                        }
                        if (cancelRequested) cancelledFlag.set(true)
                        else if (!ok) failed.set(true)
                        else onLine("下载完成：${d.file}（${dest.length() / 1024 / 1024} MB）")
                    } finally {
                        pending.decrementAndGet()
                    }
                })
            }
            workers.forEach { it.start() }

            // 汇总进度 + 速度 + 剩余时间（0.6s 刷新一次）
            var lastBytes = 0L
            var lastAt = System.currentTimeMillis()
            var speed = 0.0
            var unknownExists = downloads.indices.any { downloads[it].size <= 0L && !skipDone[it] }
            val knownTotal = downloads.indices.sumOf { if (skipDone[it] || downloads[it].size > 0) maxOf(0L, downloads[it].size) else 0L }
            while (pending.get() > 0 && !cancelRequested) {
                Thread.sleep(600)
                val now = System.currentTimeMillis()
                val totalGot = got.sumOf { it.get() }
                val dt = (now - lastAt) / 1000.0
                if (dt >= 0.4) {
                    speed = (totalGot - lastBytes) / dt
                    lastAt = now
                    lastBytes = totalGot
                }
                val doneCount = downloads.indices.count {
                    val sz = downloads[it].size
                    sz > 0 && got[it].get() >= sz
                }
                val speedText = if (speed >= 1024 * 1024) String.format("%.1f MB/s", speed / 1024 / 1024)
                else if (speed > 1024) String.format("%.0f KB/s", speed / 1024)
                else "…"
                val etaText = if (!unknownExists && knownTotal > 0 && speed > 65536) {
                    val left = (knownTotal - totalGot).coerceAtLeast(0L)
                    val sec = (left / speed).toInt()
                    " · 还剩约 ${sec / 60} 分 ${sec % 60} 秒"
                } else ""
                onStep("下载 $doneCount/${downloads.size} · ${"%.1f".format(totalGot / 1024.0 / 1024.0)} MB · $speedText$etaText")
                if (knownTotal > 0) onProgress((totalGot.toDouble() / knownTotal).toFloat() * 0.8f)
            }
            // 取消时也要等线程退出
            workers.forEach { runCatching { it.join(2000) } }
            if (cancelRequested || cancelledFlag.get()) {
                onLine("已取消下载（下好的文件保留，下次续传）")
                return false
            }
            if (failed.get()) {
                val bad = downloads.indices.filter { i ->
                    val sz = downloads[i].size
                    !(sz > 0 && got[i].get() >= sz) && !skipDone[i]
                }.map { downloads[it].file }
                onLine("下载失败：${bad.joinToString("、")}")
                return false
            }
        }

        // 2. 步骤
        for ((i, s) in pack.steps.withIndex()) {
            if (cancelRequested) {
                onLine("已取消（已完成的部分下次会跳过）")
                return false
            }
            val label = when (s.type) {
                "extract", "tar" -> "解压 ${s.file ?: ""}"
                "deb" -> "解包 ${s.file ?: ""}"
                "chmod" -> "设置可执行 ${s.path ?: ""}"
                "mkdir" -> "建目录 ${s.path ?: ""}"
                "script" -> "运行安装脚本 ${s.file ?: ""}"
                "sh" -> "执行：${(s.cmd ?: "").take(60)}"
                "move" -> "搬文件 ${s.file ?: ""}"
                "unzip" -> "解压 zip ${s.file ?: ""}"
                else -> s.type
            }
            onStep("${i + 1}/${pack.steps.size} $label")
            if (s.ifMissing.isNotBlank() && File(linux.rootfsDir, s.ifMissing).exists()) {
                onLine("已存在的环境，跳过这一步：$label")
                continue
            }
            val stamp = stampFile(pack, s)
            if (stamp.exists() && (s.type == "extract" || s.type == "tar" || s.type == "deb" || s.type == "unzip")) {
                // 戳在、目录却没了（被清空/换版本/上次异常）：不能盲跳，否则脚本会因为缺文件失败
                val dest = resolveBase(s.dest ?: "")
                val dirOk = when (s.type) {
                    "unzip", "tar", "extract" -> dest.exists() && !dest.listFiles().isNullOrEmpty()
                    else -> true
                }
                if (dirOk) {
                    onLine("已完成的步骤，跳过：$label")
                    continue
                }
                onLine("完成标记和目录对不上，重做这一步：$label")
                runCatching { stamp.delete() }
            }
            val ok = when (s.type) {
                "extract", "tar" -> {
                    val src = File(linux.downloadsDir, s.file ?: return false)
                    val dest = resolveBase(s.dest ?: "rootfs")
                    if (dest.exists()) EnvManager.deleteRecursive(dest)
                    dest.mkdirs()
                    if (!src.exists()) {
                        onLine("缺少文件：${src.name}")
                        false
                    } else {
                        linux.extractTarGz(src, dest) { done ->
                            val total = src.length()
                            if (total > 0) onProgress(0.8f + (done.toDouble() / total).toFloat() * 0.15f)
                        }
                    }
                }
                "move" -> {
                    val src = File(linux.downloadsDir, s.file ?: return false)
                    val dest = resolveBase(s.dest ?: return false)
                    dest.parentFile?.mkdirs()
                    if (!src.exists()) {
                        onLine("缺少文件：${src.name}")
                        false
                    } else if (!src.renameTo(dest)) {
                        src.copyTo(dest, overwrite = true)
                        src.delete()
                        dest.exists()
                    } else true
                }
                "unzip" -> {
                    // App 侧解 zip：proot 里的 dpkg 解 unzip 包会因硬链接报错，干脆不让它装 unzip
                    val src = File(linux.downloadsDir, s.file ?: return false)
                    val dest = resolveBase(s.dest ?: "rootfs")
                    if (dest.exists()) EnvManager.deleteRecursive(dest)
                    dest.mkdirs()
                    if (!src.exists()) {
                        onLine("缺少文件：${src.name}")
                        false
                    } else {
                        val basePath = dest.canonicalPath
                        var count = 0
                        runCatching {
                            java.util.zip.ZipInputStream(src.inputStream().buffered()).use { zin ->
                                while (true) {
                                    val e = zin.nextEntry ?: break
                                    val out = File(dest, e.name)
                                    val canonical = out.canonicalPath
                                    if (canonical != basePath && !canonical.startsWith(basePath + File.separator)) {
                                        onLine("跳过可疑路径：${e.name}")
                                        continue
                                    }
                                    if (e.isDirectory) {
                                        out.mkdirs()
                                        continue
                                    }
                                    out.parentFile?.mkdirs()
                                    out.outputStream().use { o -> zin.copyTo(o) }
                                    count++
                                }
                            }
                        }.onFailure { onLine("解压失败：${it.message}") }
                        onLine("解压完成：$count 个文件")
                        count > 0
                    }
                }
                "deb" -> {
                    val src = File(linux.downloadsDir, s.file ?: return false)
                    val dest = resolveBase(s.dest ?: "native")
                    dest.mkdirs()
                    val extracted = DebExtract.extractPick(src, dest, s.pick) { line ->
                        linux.log(line)
                        onLine(line)
                    }
                    // loader 只认 SONAME，把 libtalloc.so.2.5.0 这类真实文件补成 libtalloc.so.2
                    DebExtract.ensureSoname(File(dest, "lib"), listOf("libtalloc.so.2", "libandroid-shmem.so"))
                    extracted
                }
                "script" -> {
                    val name = s.file ?: return false
                    val target = File(linux.runDir, name)
                    try {
                        assets.open("installers/$name").use { input ->
                            target.outputStream().use { o -> input.copyTo(o) }
                        }
                    } catch (e: Exception) {
                        onLine("缺少脚本 $name：${e.message}")
                        return false
                    }
                    val cmd = if (pack.isProot) {
                        linux.prootWrap(listOf("/bin/bash", "/host/$name"), "/root")
                    } else {
                        listOf("/system/bin/sh", target.absolutePath)
                    }
                    linux.log("RUN> ${cmd.joinToString(" ").take(600)}")
                    val code = linux.runCmd(cmd, if (pack.isProot) linux.prootEnv() else emptyMap(), linux.baseDir) { line ->
                        linux.log("  $line")
                        onLine(line)
                    }
                    onLine("[脚本退出码 $code]")
                    code == 0
                }
                "chmod" -> {
                    val f = resolveBase(s.path ?: return false)
                    val mode = (s.mode ?: "755").toIntOrNull() ?: 755
                    val okExec = f.setExecutable(true, false)
                    if (!okExec) onLine("chmod 失败：${f.absolutePath}")
                    f.setReadable(true, false)
                    onLine("已设置 ${f.name} 可执行（mode $mode）")
                    f.exists()
                }
                "mkdir" -> resolveBase(s.path ?: return false).let { it.mkdirs() || it.exists() }
                "sh" -> {
                    val script = fillPlaceholders(s.cmd ?: "", pack)
                    linux.log("SH> ${script.take(500)}")
                    val code = linux.runSh(script, cwd = base) { line ->
                        linux.log("  $line")
                        onLine(line)
                    }
                    onLine("[退出码 $code]")
                    code == 0
                }
                else -> true
            }
            if (!ok) {
                onStep("失败：$label")
                return false
            }
            // 解压类步骤成功后落戳：重试时直接跳过，不再花 10 分钟重复解压
            if (s.type == "extract" || s.type == "tar" || s.type == "deb" || s.type == "unzip") {
                runCatching { stamp.parentFile?.mkdirs(); stamp.writeText("ok") }
            }
        }

        onProgress(1f)
        onStep("安装完成")
        val okAll = linux.ready()
        // 本地语音包：装完直接把地址交给上层填表
        if (okAll && pack.id == "piper") onTtsReady(LocalTts.URL)
        return okAll
    }

    /** 启动命令的占位符替换，App 和 JSON 各自独立；proot 包自动套上 proot 前缀 */
    fun buildRunCmd(pack: Pack): List<String>? {
        val run = pack.run ?: return null
        val inner = run.cmd.map { fillPlaceholders(it, pack) }
        return if (pack.isProot) linux.prootWrap(inner, run.workdir) else inner
    }

    fun runWorkdir(pack: Pack): File {
        val wd = resolveBase(pack.run?.workdir ?: "run")
        if (!wd.exists()) wd.mkdirs()
        return wd
    }

    private fun fillPlaceholders(s: String, pack: Pack): String {
        val watched = linux.baseDir.absolutePath
        return s
            .replace("{{BASE}}", watched)
            .replace("{{ROOTFS}}", linux.rootfsDir.absolutePath)
            .replace("{{PROOT}}", linux.prootBin.absolutePath)
            .replace("{{LIB}}", linux.libDir.absolutePath)
            .replace("{{RUN}}", linux.runDir.absolutePath)
            .replace("{{DL}}", linux.downloadsDir.absolutePath)
            .replace("{{HOME}}", linux.baseDir.absolutePath)
            .replace("{{PKG}}", pack.id)
    }

    private fun resolveBase(rel: String): File {
        val f = File(linux.baseDir, rel)
        return f
    }
}