package com.qqbot.app

import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 插件（技能）目录：filesDir/bot/skills/
 *
 * 三种形态：
 *  1) 单个 .mjs / .js 文件
 *  2) 目录 + index.mjs / main.mjs（纯 JS，跑在 App 内置 Node 里）
 *  3) 目录 + plugin.json（进程技能：Python / shell 等，跑在 App 的 Linux 环境里）
 * 文件名/目录名前加 "_" 表示停用（机器人会跳过）。
 */
object Skills {

    data class Skill(
        val key: String,
        val name: String,
        val desc: String,
        val enabled: Boolean,
        /** "js" 纯 JS 技能；"process" 要跑外部程序（Python/shell），需要 Linux 环境 */
        val kind: String = "js",
    )

    fun dir(botDir: File): File = File(botDir, "skills").apply { if (!exists()) mkdirs() }

    private val ENTRY_NAMES = listOf("index.mjs", "index.js", "main.mjs", "main.js")
    private const val MANIFEST = "plugin.json"

    private fun entryOf(f: File): File? = when {
        f.isDirectory -> ENTRY_NAMES.map { File(f, it) }.firstOrNull { it.isFile }
        f.name.endsWith(".mjs") || f.name.endsWith(".js") -> f
        else -> null
    }

    private fun manifestOf(f: File): org.json.JSONObject? {
        if (!f.isDirectory) return null
        val mf = File(f, MANIFEST)
        if (!mf.isFile) return null
        return runCatching { org.json.JSONObject(mf.readText(Charsets.UTF_8)) }.getOrNull()
    }

    fun list(botDir: File): List<Skill> {
        val entries = dir(botDir).listFiles()?.filter { !it.name.startsWith(".") } ?: return emptyList()
        return entries.mapNotNull { f ->
            val enabled = !f.name.startsWith("_")
            val bare = f.name.removePrefix("_")

            // 进程技能：有 plugin.json
            val manifest = manifestOf(f)
            if (manifest != null) {
                val cmd = manifest.optJSONArray("cmd")
                if (cmd != null && cmd.length() > 0) {
                    return@mapNotNull Skill(
                        key = f.name,
                        name = manifest.optString("name", bare),
                        desc = manifest.optString("description", manifest.optString("desc", "")),
                        enabled = enabled,
                        kind = "process",
                    )
                }
            }

            val entry = entryOf(f) ?: return@mapNotNull null
            val text = runCatching { entry.readText(Charsets.UTF_8).take(4000) }.getOrDefault("")
            val name = Regex("""name\s*:\s*['"]([^'"]+)['"]""").find(text)?.groupValues?.get(1)
                ?: bare.removeSuffix(".mjs").removeSuffix(".js")
            val desc = Regex("""desc(?:ription)?\s*:\s*['"]([^'"]+)['"]""").find(text)?.groupValues?.get(1) ?: ""
            Skill(key = f.name, name = name, desc = desc, enabled = enabled, kind = "js")
        }.sortedBy { it.name }
    }

    /** 导入 zip：解压到 skills/<文件名>/, 返回技能 key，失败返回 null */
    fun importZip(botDir: File, zipName: String, input: InputStream): String? {
        val base = zipName.substringBeforeLast('.').ifBlank { "skill_${System.currentTimeMillis()}" }
        val safe = Regex("[^\\p{L}\\p{N}_-]").replace(base, "_").take(40).ifBlank { "skill" }
        val target = File(dir(botDir), safe)
        deleteRecursive(target)
        if (!target.mkdirs()) return null

        val rootPath = target.canonicalPath + File.separator
        try {
            ZipInputStream(input.buffered()).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    val raw = e.name.replace('\\', '/').trim()
                    if (raw.isNotEmpty() && !raw.contains("..")) {
                        val out = File(target, raw)
                        if (out.canonicalPath.startsWith(rootPath)) {
                            if (e.isDirectory) {
                                out.mkdirs()
                            } else {
                                out.parentFile?.mkdirs()
                                out.outputStream().use { os -> zis.copyTo(os) }
                            }
                        }
                    }
                    zis.closeEntry()
                    e = zis.nextEntry
                }
            }
        } catch (ex: Exception) {
            deleteRecursive(target)
            return null
        }

        // zip 里多套了一层目录（如 my-skill/index.mjs、my-skill/plugin.json）时，把内容提上来
        val kids = target.listFiles()?.filter { !it.name.startsWith(".") } ?: emptyList()
        if (kids.size == 1 && kids[0].isDirectory) {
            val inner = kids[0]
            if (ENTRY_NAMES.any { File(inner, it).isFile } || File(inner, MANIFEST).isFile) {
                inner.listFiles()?.forEach { f -> f.renameTo(File(target, f.name)) }
                inner.delete()
            }
        }

        return if (list(botDir).any { it.key == safe }) safe else null
    }

    fun delete(botDir: File, key: String): Boolean {
        val f = File(dir(botDir), key)
        if (!f.exists()) return false
        deleteRecursive(f)
        return true
    }

    /** 通过加/去 "_" 前缀来启停 */
    fun setEnabled(botDir: File, key: String, enabled: Boolean): Boolean {
        val f = File(dir(botDir), key)
        if (!f.exists()) return false
        val bare = f.name.removePrefix("_")
        val to = File(f.parentFile, if (enabled) bare else "_$bare")
        return f.renameTo(to)
    }

    private fun deleteRecursive(f: File?) {
        if (f == null || !f.exists()) return
        if (f.isDirectory) f.listFiles()?.forEach { deleteRecursive(it) }
        f.delete()
    }
}