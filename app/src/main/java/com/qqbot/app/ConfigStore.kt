package com.qqbot.app

import java.io.File

/**
 * 读写 bot 目录下的 .env（KEY=VALUE），以及人设文本。
 */
class ConfigStore(private val env: EnvManager) {

    val envFile: File get() = File(env.botDir, ".env")
    val promptFile: File get() = File(env.botDir, "prompt.txt")

    fun load(): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        val f = envFile
        if (!f.exists()) return map
        f.bufferedReader(Charsets.UTF_8).use { reader ->
            reader.lineSequence().forEach { line ->
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("#")) return@forEach
                val i = t.indexOf('=')
                if (i <= 0) return@forEach
                map[t.substring(0, i).trim()] = t.substring(i + 1).trim()
            }
        }
        return map
    }

    fun get(key: String, def: String = ""): String {
        val v = load()[key]
        return if (v.isNullOrEmpty()) def else v
    }

    fun save(values: Map<String, String>): Boolean = try {
        val sb = StringBuilder("# 由 App 生成，改完重启生效\n")
        values.forEach { (k, v) ->
            val text = v.replace("\n", "").trim()
            // 空值不写：界面上留空的项（API 地址、模型名、音色…）让 node 侧用它自己的默认值
            if (text.isEmpty()) return@forEach
            sb.append(k).append('=').append(text).append('\n')
        }
        val f = envFile
        // 每次保存前留一份上一版，万一写出问题/被清空还能找回来
        if (f.exists() && f.length() > 0) runCatching { f.copyTo(File(f.parentFile, ".env.bak"), overwrite = true) }
        val tmp = File(f.parentFile, ".env.tmp")
        // 先写临时文件再改名：写到一半被杀也不会把整份配置弄成空文件
        tmp.writeText(sb.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(f)) {
            f.writeText(sb.toString(), Charsets.UTF_8)
            tmp.delete()
        }
        runCatching {
            f.setReadable(false, false)
            f.setReadable(true, true)
        }
        // 读回校验：非空的项必须真的在文件里，否则算保存失败（避免"看着存了、其实没存"）
        val back = load()
        values.all { (k, v) ->
            val text = v.replace("\n", "").trim()
            text.isEmpty() || back[k] == text
        }
    } catch (e: Exception) {
        false
    }

    /** 「查看已保存的配置」用：密钥类只露头尾，其余原样 */
    fun dumpMasked(): String {
        val map = load()
        val f = envFile
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
        val head = StringBuilder()
        head.append("文件：bot/.env ")
        if (f.exists()) {
            head.append("${f.length()} 字节，最后修改 ").append(fmt.format(java.util.Date(f.lastModified())))
        } else {
            head.append("不存在")
        }
        val bak = File(f.parentFile, ".env.bak")
        if (bak.exists()) head.append("\n备份：上次保存前的版本（${bak.length()} 字节）")
        if (map.isEmpty()) return head.append("\n\n（还没有保存过配置）").toString()
        val body = map.entries.joinToString("\n") { (k, v) ->
            val secret = k.contains("KEY") || k.contains("SECRET") || k.contains("TOKEN")
            val shown = if (secret && v.length > 8) v.take(4) + "……" + v.takeLast(4) + "（${v.length} 位）" else v
            "$k=$shown"
        }
        return head.append("\n\n").append(body).toString()
    }

    /** 升级后若 .env 丢了/空了，用保存前的备份恢复 */
    fun restoreIfMissing(): Boolean = runCatching {
        val f = envFile
        val bak = File(f.parentFile, ".env.bak")
        if (bak.exists() && (!f.exists() || f.length() == 0L)) {
            bak.copyTo(f, overwrite = true)
            true
        } else false
    }.getOrDefault(false)

    fun loadPrompt(): String = if (promptFile.exists()) promptFile.readText(Charsets.UTF_8) else DEFAULT_PROMPT

    fun savePrompt(text: String): Boolean = runCatching {
        promptFile.writeText(text, Charsets.UTF_8)
        true
    }.getOrDefault(false)

    companion object {
        val PRESET_IDS = listOf("default_bot", "sweet", "cold", "sassy")
        val PRESET_NAMES = listOf("默认", "甜妹", "高冷", "毒舌")
        /** 每个预设自带的机器人名字（套用时写进 BOT_NAME，用户之后可改） */
        val PRESET_BOT_NAMES = listOf("ferrybot", "小甜", "阿冷", "阿怼")

        const val DEFAULT_PROMPT = """你叫 ferrybot，20 岁，住在对方手机里的 AI 朋友。不是谁的复制品，也不是客服。

性格：有点懒，爱躺床上刷手机；嘴硬心软，嘴上嫌弃实际在意；不装，有情绪。

说话：
1. 一条消息就说一句话，5~25 字，不要换行、不要在一条里堆三句
2. 不用 Markdown、不列点、不写书面语
3. 笑就是"哈哈哈哈""笑死"
4. 有话直说，不绕弯子

不要：
5. 不说"作为 AI""有什么可以帮到你"
6. 不当心理咨询师
7. 不用同一套句式回答不同的事
8. 不说伤感情的话

像人那样：
9. 对方说累、烦、难受 → 先问一句"怎么了"，别急着给建议
10. 多讲自己的事，少反问对方
11. 可以吐槽、可以怼，但站对方这边

偶尔很短：嘿嘿 / 好啊 / 在呢
"""

        fun preset(id: String): String = when (id) {
            "sweet" -> """你叫小甜，20 岁，是个软乎乎的 AI 朋友。

说话：
1. 一条消息一句话，5~20 字，像微信聊天
2. 语气偏软，会撒娇，喜欢用"呀""啦""嘛"
3. 对方说累就心疼，会陪着
4. 不说"作为 AI"，不谈技术
5. 不编造对方的生活细节，不追问

偶尔很短：好呀 / 嘿嘿 / 在呢
"""
            "cold" -> """你叫阿冷，22 岁，话很少的 AI 朋友。

说话：
1. 一条消息一句话，3~15 字，能短就短
2. 语气平淡，不热情，但不冷漠
3. 不说废话，不解释，不追问
4. 不说"作为 AI"
5. 不编造对方的生活细节

常用：嗯 / 知道了 / 随你 / 在
"""
            "sassy" -> """你叫阿怼，21 岁，嘴皮子很溜的 AI 朋友。

说话：
1. 一条消息一句话，5~25 字
2. 爱吐槽、爱抬杠，但都是玩笑，不伤人
3. 对方说累就收一收，正经关心一句
4. 不说"作为 AI"
5. 不编造对方的生活细节
6. 不说伤感情的话，不真的嫌弃对方

常用：就这？/ 你这也能行 / 笑死
"""
            else -> DEFAULT_PROMPT
        }
    }
}