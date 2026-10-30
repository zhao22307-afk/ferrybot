package com.qqbot.app

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * 解 .deb（本质是 ar 归档，里面装 data.tar.xz）。
 *
 * 用来从 Termux 仓库的 deb 里抠出 proot 三件套（proot / loader / libtalloc.so.2 / libandroid-shmem.so），
 * 这些文件必须和 App 打包在一起或首启下载，因为 Termux 版 proot 写死了 Termux 的路径。
 */
object DebExtract {

    /**
     * 解 deb，把 pick 点名的文件拷到 destBase 下。
     * @param pick 键 = deb 内路径，支持星号通配（例：usr/libexec/proot 下的所有文件）；
     *             值 = 目标相对路径；以 "/" 结尾表示「保留原文件名放进这个目录」。
     *
     * 符号链接会被跳过（我们只要真实文件，避免拷出一个 0 字节的空壳）。
     */
    fun extractPick(deb: File, destBase: File, pick: Map<String, String>, onLine: (String) -> Unit = {}): Boolean {
        if (!deb.exists()) {
            onLine("缺少文件：${deb.name}")
            return false
        }
        val found = HashSet<String>()
        val samples = ArrayList<String>(16)
        FileInputStream(deb).buffered(256 * 1024).use { fis ->
            val magic = ByteArray(8)
            if (fis.read(magic) != 8 || String(magic, Charsets.US_ASCII) != "!<arch>\n") {
                onLine("不是 deb/ar 文件：${deb.name}")
                return false
            }
            val header = ByteArray(60)
            var stream: InputStream? = null
            while (true) {
                var n = 0
                while (n < 60) {
                    val r = fis.read(header, n, 60 - n)
                    if (r < 0) break
                    n += r
                }
                if (n < 60) break
                val name = String(header, 0, 16, Charsets.US_ASCII).trim().trimEnd('/')
                val size = String(header, 48, 10, Charsets.US_ASCII).trim().toLongOrNull() ?: 0L
                if (name.startsWith("data.tar")) {
                    stream = when {
                        name.endsWith(".xz") -> XZCompressorInputStream(fis, true)
                        name.endsWith(".gz") -> GzipCompressorInputStream(fis, true)
                        else -> fis
                    }
                    break
                }
                discard(fis, size)
                if (size % 2L == 1L) fis.read()
            }
            val dataStream = stream ?: run {
                onLine("deb 里没有 data.tar：${deb.name}")
                return false
            }
            TarArchiveInputStream(dataStream).use { tar ->
                while (true) {
                    val e = tar.nextEntry ?: break
                    val raw = e.name.trimStart('.', '/').removeSuffix("/")
                    if (e.isSymbolicLink || e.isLink) continue
                    if (!e.isDirectory && raw.isNotEmpty() && samples.size < 12 && !samples.contains(raw)) {
                        samples.add(raw)
                    }
                    var destRel: String? = null
                    for ((pattern, target) in pick) {
                        if (matchesAny(pattern, raw)) {
                            destRel = if (target.endsWith("/")) target + raw.substringAfterLast('/') else target
                            found.add(pattern)
                            break
                        }
                    }
                    val rel = destRel ?: continue
                    val out = File(destBase, rel)
                    out.parentFile?.mkdirs()
                    if (e.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.outputStream().use { o -> tar.copyTo(o) }
                        if (e.mode and 0b001_000_000 != 0) out.setExecutable(true, false)
                    }
                }
            }
        }
        val missing = pick.keys - found
        if (missing.isNotEmpty()) {
            val hint = if (samples.isEmpty()) "" else "；包内路径示例：${samples.joinToString("、")}"
            onLine("deb 里找不到：${missing.joinToString()}$hint")
            return false
        }
        onLine("已从 ${deb.name} 取出 ${found.size} 个文件")
        return true
    }

    /**
     * 补齐 SONAME：loader 只认 "libtalloc.so.2" 这种名字，
     * 但 deb 里的真实文件可能叫 libtalloc.so.2.5.0，所以按前缀找一份拷过去。
     */
    fun ensureSoname(dir: File, sonames: List<String>) {
        for (soname in sonames) {
            val exact = File(dir, soname)
            if (exact.exists() && exact.length() > 0) continue
            val candidate = dir.listFiles()?.firstOrNull {
                it.isFile && it.length() > 0 && it.name.startsWith(soname) && it.name != soname
            } ?: continue
            candidate.copyTo(exact, overwrite = true)
            exact.setReadable(true, false)
        }
    }

    /** Termux 的 deb 里路径带 data/data/com.termux/files/ 前缀，配置里通常写 usr/...；两种都认 */
    private const val TERMUX_PREFIX = "data/data/com.termux/files/"
    private const val TERMUX_USR_PREFIX = "data/data/com.termux/files/usr/"

    private fun candidates(raw0: String): List<String> {
        val raw = raw0.trimStart('.', '/').removeSuffix("/")
        val out = ArrayList<String>(3)
        out.add(raw)
        if (raw.startsWith(TERMUX_PREFIX)) out.add(raw.removePrefix(TERMUX_PREFIX))
        if (raw.startsWith(TERMUX_USR_PREFIX)) out.add("usr/" + raw.removePrefix(TERMUX_USR_PREFIX))
        return out
    }

    private fun matchesAny(pattern: String, raw: String): Boolean =
        candidates(raw).any { matches(pattern, it) }

    private fun matches(pattern: String, path: String): Boolean {
        if (!pattern.contains('*')) return pattern == path
        val regex = Regex(
            "^" + pattern.split('*').joinToString(".*") { Regex.escape(it) } + "$"
        )
        return regex.matches(path)
    }

    private fun discard(input: InputStream, size: Long) {
        var left = size
        val buf = ByteArray(8192)
        while (left > 0) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) break
            left -= r
        }
    }
}