package com.qqbot.app.ui


/** 今天回了多少条、调用了多少次、花了多少钱 */
data class TodayStats(val replies: Int, val calls: Int, val cost: Double)

/** 表单的可编辑值 */
data class FormState(
    /** 接入方式：official=QQ 官方机器人（默认） | onebot=真实QQ（连自建协议端） */
    val channel: String = "official",
    val onebotUrl: String = "",
    val onebotToken: String = "",
    val onebotGroupAll: Boolean = false,
    val appId: String = "",
    val appSecret: String = "",
    val apiKey: String = "",
    /** 模型接口地址，填到 /v1 为止；留空 = 用内置默认服务 */
    val baseUrl: String = "",
    /** 留空 = 用内置默认模型 */
    val model: String = "",
    val plan: String = "1:1:57,2:1:7,2:2:21,3:1:2,3:2:3,3:3:10",
    /** 机器人自称，留空 = 用人设里写的名字 */
    val botName: String = "",
    /** 一次回几条消息的权重，如 "60,30,10"（1/2/3 条） */
    val msgCountW: String = "",
    /** 每条消息写几句的权重，如 "60,30,10"（1/2/3 句） */
    val sentW: String = "",
    val voiceChance: String = "0.15",
    val proactive: Boolean = true,
    val maxDay: String = "6",
    val gapMin: String = "2",
    val gapMax: String = "5",
    val hourStart: String = "9",
    val hourEnd: String = "22",
    val pauseDays: String = "7",
    val vision: Boolean = true,
    val asr: Boolean = true,
    val tts: Boolean = true,
    val autoStart: Boolean = false,
    val ttsVoice: String = "",
    /** 音色方案：preset=现成音色 / clone=云端复刻 / local=本地小模型（自建 TTS 服务） */
    val ttsMode: String = "preset",
    val ttsModel: String = "",
    /** 语音服务商：dashscope（千问/CosyVoice）| openai（任何 OpenAI 兼容服务）| local（自建） */
    val ttsProvider: String = "dashscope",
    val ttsBase: String = "",
    val ttsKey: String = "",
    val localTtsUrl: String = "",
    val localTtsKey: String = "",
    val asrProvider: String = "dashscope",
    val asrBase: String = "",
    val asrKey: String = "",
    val asrModel: String = "",
    val memoryOn: Boolean = true,
    val factsOn: Boolean = true,
    val maxHistory: String = "80",
    val maxChars: String = "16000",
    val msgText: Boolean = true,
    val msgImage: Boolean = true,
    val msgVoice: Boolean = true,
    val msgEmoji: Boolean = true,
    val proactiveStyle: String = "mix",
    val proactiveTemplate: String = "",
    val proactiveTargets: String = "",
    val filterWords: String = "",
    val blacklistUsers: String = "",
    val blacklistGroups: String = "",
    val typingDelayMin: String = "400",
    val typingDelayMax: String = "2200",
    val recallSeconds: String = "0",
    val msgPrefix: String = "",
    // ---------- 主动消息 / 消息类型 / 素材 / 联网（env 名与 bot 侧约定死） ----------
    /** 每天最多几条主动消息（1~20） */
    val proactiveMaxDay: String = "3",
    /** 主动消息里带语音的概率（%，0~100）→ PROACTIVE_VOICE_CHANCE（/100） */
    val proactiveVoiceChance: String = "15",
    /** 主动消息里带链接的概率（%，0~100）→ LINK_CHANCE_PROACTIVE（/100） */
    val linkChanceProactive: String = "0",
    /** 回复里附带表情包的概率（%）→ EMOJI_CHANCE（/100） */
    val emojiChance: String = "8",
    /** 回复里附带图片的概率（%）→ IMAGE_CHANCE（/100） */
    val imageChance: String = "5",
    /** 纯语音回复的概率（%）→ VOICE_ONLY_CHANCE（/100） */
    val voiceOnlyChance: String = "15",
    /** 回复里带链接的概率（%）→ LINK_CHANCE（/100） */
    val linkChance: String = "0",
    /** #指令 / /usage / /reset 只给管理员用 → ADMIN_ONLY */
    val adminOnly: Boolean = true,
    /** 管理员 ID（真实 QQ 填 QQ 号，官方机器人填 openid，逗号分隔）→ ADMIN_IDS */
    val adminIds: String = "",
    /** 一次回复里各类消息的相对权重（整数，按比例） */
    val msgTextW: String = "70",
    val msgImageW: String = "1",
    val msgVoiceW: String = "8",
    val msgEmojiW: String = "8",
    /** 让 AI 按语境自己挑表情/图片（需要素材有标签） */
    val mediaSmart: Boolean = false,
    /** 能读取链接内容 */
    val linkOn: Boolean = true,
    /** 搜索服务：""=关 | tavily | serper | bing | bocha */
    val searchProvider: String = "",
    val searchBase: String = "",
    val searchKey: String = "",
    val searchMax: String = "5",
) {
    fun toEnvMap(old: Map<String, String>): LinkedHashMap<String, String> {
        val m = LinkedHashMap<String, String>()
        m.putAll(old)
        m["CHANNEL"] = channel
        m["ONEBOT_WS_URL"] = onebotUrl.trim()
        m["ONEBOT_TOKEN"] = onebotToken.trim()
        m["ONEBOT_GROUP_ALL"] = if (onebotGroupAll) "1" else "0"
        m["QQ_APPID"] = appId.trim()
        m["QQ_APP_SECRET"] = appSecret.trim()
        putIfNotBlank(m, "LLM_API_KEY", apiKey)
        putIfNotBlank(m, "LLM_MODEL", model)
        // 留空就别写进 .env，让 node 侧用它自己的默认值（这样界面是空的，行为仍然是默认服务）
        // 但「原来有值、这次表单是空」时保留旧值：避免某次空表单把配置静默删掉
        // 空 = 用内置默认：不写进 .env（写了空串反而会盖掉程序里的默认值）；填了就原样存、原样显示
        val base = baseUrl.trim().trimEnd('/')
        if (base.isNotEmpty()) m["LLM_BASE_URL"] = base else m.remove("LLM_BASE_URL")
        m["MSG_PLAN"] = plan
        m["BOT_NAME"] = botName.trim()
        // 概率配置：空就不写（留空走默认 MSG_PLAN），填了就存归一化后的权重串
        m["MSG_COUNT_W"] = normWeights(msgCountW)
        m["SENT_W"] = normWeights(sentW)
        m["VOICE_CHANCE"] = voiceChance
        m["PROACTIVE"] = if (proactive) "1" else "0"
        // 老的 maxDay 字段和新的 proactiveMaxDay 指向同一个 env（首页概况还在用 maxDay），
        // 这里统一取值，保证两边不打架；空串就当没填，落回默认 3
        m["PROACTIVE_MAX_DAY"] = maxDay.trim().ifBlank { "3" }
        m["PROACTIVE_GAP_MIN_H"] = gapMin
        m["PROACTIVE_GAP_MAX_H"] = gapMax
        m["PROACTIVE_HOUR_START"] = hourStart
        m["PROACTIVE_HOUR_END"] = hourEnd
        m["PROACTIVE_PAUSE_DAYS"] = pauseDays
        m["PROACTIVE_TO"] = "all"
        m["VISION"] = if (vision) "1" else "0"
        m["ASR"] = if (asr) "1" else "0"
        m["TTS"] = if (tts) "1" else "0"
        m["AUTO_START"] = if (autoStart) "1" else "0"
        m["TTS_MODE"] = ttsMode
        m["TTS_CLONE"] = if (ttsMode == "clone") "1" else "0"
        // 语音/识别这几项「空 = 用该服务商自己的默认」：必须从 .env 里删掉而不是跳过，
        // 否则换了服务商，旧地址/旧音色名还留在文件里，会把新服务商的地址覆盖成旧的
        if (ttsVoice.isBlank()) m.remove("TTS_VOICE") else m["TTS_VOICE"] = ttsVoice.trim()
        if (ttsModel.isBlank()) m.remove("TTS_MODEL") else m["TTS_MODEL"] = ttsModel.trim()
        m["TTS_PROVIDER"] = ttsProvider
        if (ttsBase.isBlank()) m.remove("TTS_BASE") else m["TTS_BASE"] = ttsBase.trimEnd('/')
        putIfNotBlank(m, "TTS_KEY", ttsKey)
        putIfNotBlank(m, "LOCAL_TTS_URL", localTtsUrl)
        putIfNotBlank(m, "LOCAL_TTS_KEY", localTtsKey)
        m["ASR_PROVIDER"] = asrProvider
        if (asrBase.isBlank()) m.remove("ASR_BASE") else m["ASR_BASE"] = asrBase.trimEnd('/')
        putIfNotBlank(m, "ASR_KEY", asrKey)
        if (asrModel.isBlank()) m.remove("ASR_MODEL") else m["ASR_MODEL"] = asrModel.trim()
        m["MEMORY"] = if (memoryOn) "1" else "0"
        m["FACTS"] = if (factsOn) "1" else "0"
        m["MAX_HISTORY"] = maxHistory.trim().ifBlank { "80" }
        m["RECENT_KEEP"] = old["RECENT_KEEP"] ?: "16"
        m["MAX_HISTORY_CHARS"] = maxChars.trim().ifBlank { "16000" }
        m["SYSTEM_PROMPT_FILE"] = "prompt.txt"
        m["ENABLE_THINKING"] = "0"
        m["LLM_TEMPERATURE"] = old["LLM_TEMPERATURE"] ?: "1.05"
        m["EMOJI_CHANCE"] = old["EMOJI_CHANCE"] ?: "0.08"
        m["VOICE_ONLY_CHANCE"] = old["VOICE_ONLY_CHANCE"] ?: "0.15"
        m["HUMANIZE"] = "1"
        m["MSG_TEXT"] = if (msgText) "1" else "0"
        m["MSG_IMAGE"] = if (msgImage) "1" else "0"
        m["MSG_VOICE"] = if (msgVoice) "1" else "0"
        m["MSG_EMOJI"] = if (msgEmoji) "1" else "0"
        m["PROACTIVE_STYLE"] = proactiveStyle
        m["PROACTIVE_TEMPLATE"] = proactiveTemplate
        m["PROACTIVE_TARGETS"] = proactiveTargets
        m["FILTER_WORDS"] = filterWords
        m["BLACKLIST_USERS"] = blacklistUsers
        m["BLACKLIST_GROUPS"] = blacklistGroups
        m["TYPING_DELAY_MIN_MS"] = typingDelayMin
        m["TYPING_DELAY_MAX_MS"] = typingDelayMax
        m["RECALL_SECONDS"] = recallSeconds
        m["MSG_PREFIX"] = msgPrefix
        // ---------- 主动消息 / 消息类型 / 素材 / 联网 ----------
        // 空串就跳过（不覆盖 old 里的旧值），避免用户清空输入框把配置写坏
        if (proactiveMaxDay.trim().isNotEmpty()) m["PROACTIVE_MAX_DAY"] = proactiveMaxDay.trim()
        putPct(m, "PROACTIVE_VOICE_CHANCE", proactiveVoiceChance)
        putPct(m, "LINK_CHANCE_PROACTIVE", linkChanceProactive)
        putPct(m, "EMOJI_CHANCE", emojiChance)
        putPct(m, "IMAGE_CHANCE", imageChance)
        putPct(m, "VOICE_ONLY_CHANCE", voiceOnlyChance)
        putPct(m, "LINK_CHANCE", linkChance)
        putInt(m, "MSG_TEXT_W", msgTextW)
        putInt(m, "MSG_IMAGE_W", msgImageW)
        putInt(m, "MSG_VOICE_W", msgVoiceW)
        putInt(m, "MSG_EMOJI_W", msgEmojiW)
        m["MEDIA_SMART"] = if (mediaSmart) "1" else "0"
        m["LINK_ON"] = if (linkOn) "1" else "0"
        m["ADMIN_ONLY"] = if (adminOnly) "1" else "0"
        if (adminIds.trim().isNotEmpty()) m["ADMIN_IDS"] = adminIds.trim().replace(Regex("[\\s,，、]+"), ",")
        m["SEARCH_PROVIDER"] = searchProvider.trim().lowercase()
        m["SEARCH_BASE"] = searchBase.trim().trimEnd('/')
        m["SEARCH_KEY"] = searchKey.trim()
        putInt(m, "SEARCH_MAX", searchMax)
        return m
    }

    /** 空值不写：既不给用户凭空填值，也不把 .env 里已有的值抹成空串 */
    private fun putIfNotBlank(m: LinkedHashMap<String, String>, key: String, v: String) {
        val t = v.trim()
        if (t.isNotEmpty()) m[key] = t
    }

    companion object {
        fun from(c: Map<String, String>): FormState {
            // 「每天最多几条」有两个字段指向同一个 env（老的 maxDay 给首页概况显示用），
            // 这里统一取值，保证两边不打架
            val proMaxDay = (c["PROACTIVE_MAX_DAY"] ?: "").trim().ifBlank { "3" }
            return FormState(
            channel = c["CHANNEL"] ?: "official",
            onebotUrl = c["ONEBOT_WS_URL"] ?: "",
            onebotToken = c["ONEBOT_TOKEN"] ?: "",
            onebotGroupAll = c["ONEBOT_GROUP_ALL"] == "1",
            appId = c["QQ_APPID"] ?: "",
            appSecret = c["QQ_APP_SECRET"] ?: "",
            apiKey = c["LLM_API_KEY"] ?: "",
            // 存了什么就显示什么：不预填、也不隐藏（旧版可能把默认地址写进过 .env，那是文件里的实际值）
            baseUrl = (c["LLM_BASE_URL"] ?: "").trim(),
            model = c["LLM_MODEL"] ?: "",
            plan = c["MSG_PLAN"] ?: "1:1:57,2:1:7,2:2:21,3:1:2,3:2:3,3:3:10",
            botName = c["BOT_NAME"] ?: "",
            msgCountW = c["MSG_COUNT_W"] ?: "",
            sentW = c["SENT_W"] ?: "",
            voiceChance = c["VOICE_CHANCE"] ?: "0.15",
            proactive = (c["PROACTIVE"] ?: "1") != "0",
            maxDay = proMaxDay,
            gapMin = c["PROACTIVE_GAP_MIN_H"] ?: "2",
            gapMax = c["PROACTIVE_GAP_MAX_H"] ?: "5",
            hourStart = c["PROACTIVE_HOUR_START"] ?: "9",
            hourEnd = c["PROACTIVE_HOUR_END"] ?: "22",
            pauseDays = c["PROACTIVE_PAUSE_DAYS"] ?: "7",
            vision = (c["VISION"] ?: "1") != "0",
            asr = (c["ASR"] ?: "1") != "0",
            tts = (c["TTS"] ?: "1") != "0",
            autoStart = c["AUTO_START"] == "1",
            ttsVoice = c["TTS_VOICE"] ?: "",
            ttsMode = c["TTS_MODE"] ?: if (c["TTS_CLONE"] == "1") "clone" else "preset",
            ttsModel = c["TTS_MODEL"] ?: "",
            ttsProvider = c["TTS_PROVIDER"] ?: "dashscope",
            ttsBase = (c["TTS_BASE"] ?: "").trim(),
            ttsKey = c["TTS_KEY"] ?: "",
            localTtsUrl = c["LOCAL_TTS_URL"] ?: "",
            localTtsKey = c["LOCAL_TTS_KEY"] ?: "",
            asrProvider = c["ASR_PROVIDER"] ?: "dashscope",
            asrBase = (c["ASR_BASE"] ?: "").trim(),
            asrKey = c["ASR_KEY"] ?: "",
            asrModel = c["ASR_MODEL"] ?: "",
            memoryOn = (c["MEMORY"] ?: "1") != "0",
            factsOn = (c["FACTS"] ?: "1") != "0",
            maxHistory = c["MAX_HISTORY"] ?: "80",
            maxChars = c["MAX_HISTORY_CHARS"] ?: "16000",
            msgText = (c["MSG_TEXT"] ?: "1") != "0",
            msgImage = (c["MSG_IMAGE"] ?: "1") != "0",
            msgVoice = (c["MSG_VOICE"] ?: "1") != "0",
            msgEmoji = (c["MSG_EMOJI"] ?: "1") != "0",
            proactiveStyle = c["PROACTIVE_STYLE"] ?: "mix",
            proactiveTemplate = c["PROACTIVE_TEMPLATE"] ?: "",
            proactiveTargets = c["PROACTIVE_TARGETS"] ?: "",
            filterWords = c["FILTER_WORDS"] ?: "",
            blacklistUsers = c["BLACKLIST_USERS"] ?: "",
            blacklistGroups = c["BLACKLIST_GROUPS"] ?: "",
            typingDelayMin = c["TYPING_DELAY_MIN_MS"] ?: "400",
            typingDelayMax = c["TYPING_DELAY_MAX_MS"] ?: "2200",
            recallSeconds = c["RECALL_SECONDS"] ?: "0",
            msgPrefix = c["MSG_PREFIX"] ?: "",
            proactiveMaxDay = proMaxDay,
            proactiveVoiceChance = pctIn(c["PROACTIVE_VOICE_CHANCE"]) ?: "15",
            linkChanceProactive = pctIn(c["LINK_CHANCE_PROACTIVE"]) ?: "0",
            emojiChance = pctIn(c["EMOJI_CHANCE"]) ?: "8",
            imageChance = pctIn(c["IMAGE_CHANCE"]) ?: "5",
            voiceOnlyChance = pctIn(c["VOICE_ONLY_CHANCE"]) ?: "15",
            linkChance = pctIn(c["LINK_CHANCE"]) ?: "0",
            adminOnly = (c["ADMIN_ONLY"] ?: "1") != "0",
            adminIds = c["ADMIN_IDS"] ?: "",
            msgTextW = c["MSG_TEXT_W"] ?: "70",
            msgImageW = c["MSG_IMAGE_W"] ?: "1",
            msgVoiceW = c["MSG_VOICE_W"] ?: "8",
            msgEmojiW = c["MSG_EMOJI_W"] ?: "8",
            mediaSmart = c["MEDIA_SMART"] == "1",
            linkOn = (c["LINK_ON"] ?: "1") != "0",
            searchProvider = (c["SEARCH_PROVIDER"] ?: "").trim().lowercase(),
            searchBase = c["SEARCH_BASE"] ?: "",
            searchKey = c["SEARCH_KEY"] ?: "",
            searchMax = c["SEARCH_MAX"] ?: "5",
            )
        }
    }
}

/** 界面上填的百分数（0~100）→ env 里的 0~1 小数串；空/非法返回 null（调用方跳过不写） */
private fun pctOf(v: String): String? {
    val d = v.trim().toDoubleOrNull() ?: return null
    val n = d / 100.0
    return if (n == n.toLong().toDouble()) n.toLong().toString() else n.toString()
}

private fun putPct(m: LinkedHashMap<String, String>, key: String, v: String) {
    pctOf(v)?.let { m[key] = it }
}

/** 整数字段：空串跳过，非整数也不写 */
private fun putInt(m: LinkedHashMap<String, String>, key: String, v: String) {
    val t = v.trim()
    if (t.isEmpty()) return
    t.toIntOrNull()?.let { m[key] = it.toString() }
}

/** env 里的 0~1 小数 → 界面上 0~100 的整数串；兼容有人直接把百分数写进 env 的情况 */
private fun pctIn(v: String?): String? {
    val d = v?.trim()?.toDoubleOrNull() ?: return null
    val n = if (d <= 1.0) d * 100.0 else d
    val r = kotlin.math.round(n * 100.0) / 100.0
    return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
}

/** 概率输入（"60,30,10" 或 "60 30 10" 或全角逗号）→ 规整成 "60,30,10"；空/全非法返回空串 */
private fun normWeights(v: String): String {
    var parts = v.replace('，', ',').split(',', ' ', '/', '|')
        .map { it.trim().toDoubleOrNull() ?: 0.0 }
        .map { if (it < 0) 0.0 else it }
    while (parts.isNotEmpty() && parts.last() == 0.0) parts = parts.dropLast(1)
    if (parts.isEmpty() || parts.all { it == 0.0 }) return ""
    return parts.joinToString(",") { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() }
}
