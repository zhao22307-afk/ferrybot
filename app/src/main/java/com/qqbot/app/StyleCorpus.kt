package com.qqbot.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 说话方式语料（bot/data/style.json）。
 *
 * 用户在「对话 → 人设 → 说话方式（语料）」里选一个文件，这里把它解析成
 * 两类样例，交给机器人侧的 lib/style.mjs 拼进 system prompt：
 *  - mode = "dialog"：一问一答的片段 `[{u, a}]`，最贴近真实聊天语气
 *  - mode = "lines"：只有句子 `["..."]`，当作语气的参考
 *
 * 支持：纯文本（「我：… / 对方：…」对话行，或一行一句）、
 * JSON 数组（role/content、{user, assistant}、{isSend, content}）、JSONL。
 * 认不出发言人的聊天导出（只有「昵称: 内容」）按句子学习。
 *
 * 这里只做文本解析和落盘，不发请求、不做副作用；解析失败抛异常给界面提示。
 */
object StyleCorpus {

    const val FILE_NAME = "style.json"

    /** 最多保留多少条样例（再多也没意义，读的时候还会再截） */
    private const val MAX_SAMPLES = 400
    /** 单条样例最长多少字 */
    private const val MAX_SAMPLE_CHARS = 120
    /** 整份语料最长多少字（防止误选了几十 MB 的日志文件） */
    private const val MAX_TOTAL_CHARS = 40_000

    private val BOT_WORDS = setOf("我", "自己", "本人", "me", "Me")
    private val SPEAKER =
        Regex("""^(我|自己|本人|me|Me|对方|对方说|TA|Ta|ta|他|她|用户|朋友)\s*[:：]\s*(.+)""")
    /** 「2024-01-01 12:00:00 」「[2024/1/1]」这类时间戳前缀 */
    private val TIMESTAMP =
        Regex("""^\[?\d{1,4}[-/.年]\d{1,2}[-/.月]\d{1,2}日?\]?\s*(\d{1,2}:\d{2}(:\d{2})?)?\s*""")
    /** 「[12:00] 」这类纯时间前缀 */
    private val CLOCK = Regex("""^\[\d{1,2}:\d{2}(:\d{2})?\]\s*""")
    /** 「昵称: 内容」：认不出哪边是它，只把内容当语气句子 */
    private val NICK = Regex("""^\S{1,16}\s*[:：]\s*(\S.*)""")

    data class Info(
        val mode: String,
        val count: Int,
        val chars: Int,
        val source: String,
        val updatedAt: Long,
    ) {
        val isDialog: Boolean get() = mode == "dialog"
    }

    private class Parsed(
        val mode: String,
        val pairs: List<Pair<String, String>>,
        val lines: List<String>,
    )

    fun file(botDir: File): File = File(File(botDir, "data"), FILE_NAME)

    /** 已导入语料的摘要；null = 没导入 / 读不出来 */
    fun read(botDir: File): Info? {
        val f = file(botDir)
        if (!f.isFile || f.length() == 0L) return null
        return runCatching {
            val o = JSONObject(f.readText(Charsets.UTF_8))
            Info(
                mode = o.optString("mode", "lines").ifBlank { "lines" },
                count = o.optInt("count", 0),
                chars = o.optInt("chars", 0),
                source = o.optString("source", ""),
                updatedAt = o.optLong("updatedAt", 0L),
            )
        }.getOrNull()
    }

    /** 解析文本并写入 data/style.json；内容为空时抛 IllegalArgumentException */
    fun parseAndSave(botDir: File, sourceName: String, raw: String): Info {
        val text = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').trim()
        if (text.isEmpty()) throw IllegalArgumentException("文件是空的")

        val parsed = parseJson(text) ?: parseText(text)
        if (parsed.mode == "dialog" && parsed.pairs.isEmpty()) throw IllegalArgumentException("没找到可用的对话")
        if (parsed.mode == "lines" && parsed.lines.isEmpty()) throw IllegalArgumentException("没找到可用的句子")

        val pairs = ArrayList<Pair<String, String>>()
        val lines = ArrayList<String>()
        var chars = 0
        if (parsed.mode == "dialog") {
            for (p in downsample(parsed.pairs, MAX_SAMPLES)) {
                val cost = p.first.length + p.second.length
                if (chars + cost > MAX_TOTAL_CHARS) break
                pairs.add(p)
                chars += cost
            }
        } else {
            for (s in downsample(parsed.lines, MAX_SAMPLES)) {
                if (chars + s.length > MAX_TOTAL_CHARS) break
                lines.add(s)
                chars += s.length
            }
        }
        if (pairs.isEmpty() && lines.isEmpty()) throw IllegalArgumentException("内容太多且都很短，解析失败")

        val root = JSONObject()
        root.put("version", 1)
        root.put("mode", parsed.mode)
        root.put("source", sourceName.take(80))
        root.put("updatedAt", System.currentTimeMillis())
        root.put("count", if (parsed.mode == "dialog") pairs.size else lines.size)
        root.put("chars", chars)
        val arr = JSONArray()
        if (parsed.mode == "dialog") {
            pairs.forEach { (u, a) ->
                arr.put(JSONObject().put("u", u).put("a", a))
            }
        } else {
            lines.forEach { arr.put(it) }
        }
        root.put("samples", arr)

        val f = file(botDir)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(root.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(f)) {
            f.writeText(root.toString(), Charsets.UTF_8)
            tmp.delete()
        }
        return read(botDir) ?: throw IllegalStateException("写入后读不回来")
    }

    /** 清空语料（文件删掉 = 机器人那边自然不带了） */
    fun clear(botDir: File): Boolean {
        val f = file(botDir)
        runCatching { if (f.exists()) f.delete() }
        return !f.exists()
    }

    // ---------------- 解析 ----------------

    private fun parseJson(text: String): Parsed? {
        val t = text.trim()
        if (!t.startsWith("[") && !t.startsWith("{")) return null
        val objs = ArrayList<JSONObject>()
        if (t.startsWith("[")) {
            val arr = runCatching { JSONArray(t) }.getOrNull() ?: return parseJsonl(t)
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { objs.add(it) }
        } else {
            val one = runCatching { JSONObject(t) }.getOrNull()
            if (one == null) return parseJsonl(t)
            val nested = sequenceOf("messages", "data", "conversations", "list", "records")
                .mapNotNull { k -> one.optJSONArray(k) }
                .firstOrNull()
            if (nested != null) {
                for (i in 0 until nested.length()) nested.optJSONObject(i)?.let { objs.add(it) }
            } else if (one.has("role") || one.has("content") || one.has("user")) {
                objs.add(one)
            } else {
                return null
            }
        }
        if (objs.isEmpty()) return null
        return objectsToParsed(objs)
    }

    private fun parseJsonl(text: String): Parsed? {
        val objs = text.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("{") }
            .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .toList()
        if (objs.isEmpty()) return null
        return objectsToParsed(objs)
    }

    private fun objectsToParsed(objs: List<JSONObject>): Parsed? {
        val pairs = ArrayList<Pair<String, String>>()
        val botLines = ArrayList<String>()
        val leftovers = ArrayList<String>()
        var pending: String? = null

        fun pushBot(body: String) {
            val u = pending
            if (!u.isNullOrEmpty()) {
                pairs.add(u to body)
                pending = null
            } else {
                botLines.add(body)
            }
        }

        for (o in objs) {
            // {user, assistant}：直接就是一对
            if (o.has("user") && o.has("assistant")) {
                val u = clip(textOf(o.opt("user")))
                val a = clip(textOf(o.opt("assistant")))
                if (u.isNotEmpty() && a.isNotEmpty()) pairs.add(u to a)
                continue
            }
            val content = clip(textOf(o.opt("content")).ifBlank { textOf(o.opt("text")) })
            if (content.isEmpty()) continue
            val role = o.optString("role").lowercase()
            val isSend = o.optBoolean("isSend", false)
            when {
                role.contains("assistant") || role.contains("bot") -> pushBot(content)
                role.contains("user") || role.contains("human") -> pending = content
                role.contains("system") -> Unit
                o.has("isSend") && isSend -> pushBot(content)
                o.has("isSend") -> pending = content
                else -> leftovers.add(content)
            }
        }

        return when {
            pairs.isNotEmpty() -> Parsed("dialog", pairs, emptyList())
            botLines.isNotEmpty() -> Parsed("lines", emptyList(), botLines)
            leftovers.isNotEmpty() -> Parsed("lines", emptyList(), leftovers)
            else -> null
        }
    }

    private fun parseText(text: String): Parsed {
        val pairs = ArrayList<Pair<String, String>>()
        val botLines = ArrayList<String>()
        val unknown = ArrayList<String>()
        var pending: String? = null

        for (raw in text.lineSequence()) {
            val line = stripLead(raw)
            if (line.isEmpty() || line == "-") continue
            val m = SPEAKER.matchEntire(line)
            if (m != null) {
                val body = clip(m.groupValues[2])
                if (body.isEmpty()) continue
                if (m.groupValues[1] in BOT_WORDS) {
                    val u = pending
                    if (!u.isNullOrEmpty()) {
                        pairs.add(u to body)
                        pending = null
                    } else {
                        botLines.add(body)
                    }
                } else {
                    pending = body
                }
                continue
            }
            val nick = NICK.matchEntire(line)
            unknown.add(clip(if (nick != null) nick.groupValues[1] else line))
        }

        return when {
            pairs.isNotEmpty() -> Parsed("dialog", pairs, emptyList())
            botLines.isNotEmpty() -> Parsed("lines", emptyList(), botLines)
            else -> Parsed("lines", emptyList(), unknown)
        }
    }

    /** 去掉时间戳/钟点/项目符号前缀 */
    private fun stripLead(raw: String): String {
        var s = raw.trim()
        s = s.removePrefix("-").removePrefix("*").trim()
        s = s.replaceFirst(TIMESTAMP, "").trim()
        s = s.replaceFirst(CLOCK, "").trim()
        return s
    }

    private fun textOf(v: Any?): String = when (v) {
        null -> ""
        is String -> v
        is JSONObject -> v.optString("content").ifBlank { v.optString("text").ifBlank { v.optString("message") } }
        is JSONArray -> (0 until v.length()).joinToString("\n") { textOf(v.opt(it)) }
        else -> v.toString()
    }

    private fun clip(s: String): String = s.replace(Regex("\\s+"), " ").trim().take(MAX_SAMPLE_CHARS)

    /** 太多条就等距抽，保留开头结尾、避免只学到开场白 */
    private fun <T> downsample(list: List<T>, max: Int): List<T> {
        if (list.size <= max) return list
        val step = list.size.toDouble() / max
        return (0 until max).map { list[(it * step).toInt().coerceAtMost(list.size - 1)] }
    }
}