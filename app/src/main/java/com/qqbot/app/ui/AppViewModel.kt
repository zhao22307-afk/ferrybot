package com.qqbot.app.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qqbot.app.BotService
import com.qqbot.app.ConfigStore
import com.qqbot.app.EnvInstaller
import com.qqbot.app.EnvManager
import com.qqbot.app.LocalTts
import com.qqbot.app.ModelCatalog
import com.qqbot.app.ProtoManager
import com.qqbot.app.Skills
import com.qqbot.app.StyleCorpus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 界面状态与操作入口。
 *
 * 原来是 970 行的「上帝类」，现在按职责拆成三块，这里只留配置/人设/素材，其余转发：
 *  - 配置：本类（form / save / prompt / 模型 / 自检）
 *  - 安装器：[InstallerEngine]（下载 / 解压 / 日志 / 清空）
 *  - 协议端：[ProtoManager]（进程生命周期，跨 Activity、可由前台服务恢复）
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    val env = EnvManager(app)
    val store = ConfigStore(env)

    /** 模型地址的本机副本：.env 万一被写丢，用它兜底 */
    private val prefs by lazy {
        getApplication<Application>().getSharedPreferences("ferry_config", android.content.Context.MODE_PRIVATE)
    }

    /** 上一次保存时表单/文件里地址各是什么，排查用 */
    var lastSaveInfo by mutableStateOf(""); private set

    var runtimeReady by mutableStateOf(env.runtimeReady()); private set
    var running by mutableStateOf(BotService.running); private set
    /** 已点启动、协议端还在上线（30~90 秒）：首页按钮显示「启动中…」 */
    var starting by mutableStateOf(false); private set
    var installing by mutableStateOf(false); private set
    var logText by mutableStateOf(""); private set
    var toast by mutableStateOf<String?>(null); private set

    var config by mutableStateOf(store.load()); private set
    var prompt by mutableStateOf(store.loadPrompt()); private set
    var emojis by mutableStateOf(listOf<String>()); private set
    var images by mutableStateOf(listOf<String>()); private set
    var skills by mutableStateOf<List<Skills.Skill>>(emptyList()); private set
    /** 说话方式语料（bot/data/style.json）的摘要；null = 没导入 */
    var styleInfo by mutableStateOf(StyleCorpus.read(env.botDir)); private set
    /** 素材添加/导入的最近一次结果（显示在素材页底部，方便排查「添加了没反应」） */
    var mediaDiag by mutableStateOf(""); private set

    /** 已安装的 piper 本地音色包（voices/ 下的 .onnx 名） */
    var localVoices by mutableStateOf<List<String>>(emptyList()); private set

    /** 最近聊过的人：id → 显示名（按最后活跃倒序，最多 50 个，「主动消息」目标选择用） */
    var contacts by mutableStateOf<List<Pair<String, String>>>(emptyList()); private set

    /** 联系人最后活跃时间（id → 格式化后的时间），列表里显示用 */
    var contactLast by mutableStateOf<Map<String, String>>(emptyMap()); private set

    /** 链接库：标题 → 地址（bot/data/links.json） */
    var links by mutableStateOf<List<Pair<String, String>>>(emptyList()); private set

    /** 素材标签编辑框的文本（文件名=标签，每行一条） */
    var mediaTagsText by mutableStateOf("")

    /** 表单的临时值（未保存） */
    var form by mutableStateOf(FormState.from(config)); private set

    /** 从服务端拉到的可选模型（空 = 还没拉过，用输入框手填） */
    var models by mutableStateOf(listOf<String>()); private set
    var fetchingModels by mutableStateOf(false); private set
    var modelError by mutableStateOf<String?>(null); private set

    /** 「运行自检」的结果，一行一个结论 */
    var checkLines by mutableStateOf(listOf<String>()); private set
    var checking by mutableStateOf(false); private set

    /** 上次启动失败的原因，首页要提示 */
    var lastExitMessage by mutableStateOf<String?>(null); private set

    /** 扫描本机 OneBot 的结果 */
    var probeResult by mutableStateOf<String?>(null); private set
    var probing by mutableStateOf(false); private set

    /** 铺运行时的失败原因（首页/准备页显示，不用只靠 toast 一闪而过） */
    var installError by mutableStateOf<String?>(null); private set

    /** filesDir 剩余空间（MB），装了这么多东西后值得让人看见 */
    var freeSpaceMb by mutableStateOf(-1L); private set

    // ---------- 安装器块（转发） ----------

    val installer = InstallerEngine(
        app = app,
        scope = viewModelScope,
        toast = { showToast(it) },
        onWsToken = { tok -> updateForm { it.copy(onebotToken = tok) } },
        stopProto = { ProtoManager.stop() },
        onTtsReady = { url ->
            updateForm { it.copy(ttsMode = "local", localTtsUrl = url) }
            runCatching { store.save(form.toEnvMap(config)) }
            showToast("本地语音装好了，语音地址已自动填上")
        },
    )

    val envReady: Boolean get() = installer.envReady
    val envBusy: Boolean get() = installer.envBusy
    val envStep: String get() = installer.envStep
    val envProgress: Float get() = installer.envProgress
    val envLog: String get() = installer.envLog
    val envPackId: String get() = installer.envPackId
    val envPacks: List<Pair<String, String>> get() = installer.envPacks
    val envSizeHint: Int get() = installer.envSizeHint
    val envDiskMb: Long get() = installer.envDiskMb

    // ---------- 协议端块（转发 ProtoManager） ----------

    val protoRunning: Boolean get() = ProtoManager.running
    val protoPort: Int get() = ProtoManager.port
    val protoWebuiPort: Int get() = ProtoManager.webuiPort
    val qrTick: Int get() = ProtoManager.qrTick
    val qrHint: String get() = ProtoManager.qrHint
    val protoBlockReason: String get() = ProtoManager.blockReason
    val qrFile: File? get() = ProtoManager.qrFile ?: installer.qrFile

    init {
        ProtoManager.init(app)
        // 万一上次升级/异常把 .env 弄空了，先拿备份恢复，再读配置
        store.restoreIfMissing()
        // .env 里缺模型地址时用本机副本兜底（防某次空表单把地址删掉）
        val loaded = withPrefsFallback(store.load())
        if (loaded != config) {
            config = loaded
            form = FormState.from(config)
        }
        ProtoManager.onForm = { url, token ->
            updateForm {
                it.copy(
                    channel = "onebot",
                    onebotUrl = if (it.onebotUrl.contains("127.0.0.1") || it.onebotUrl.isBlank()) url else it.onebotUrl,
                    onebotToken = token.ifBlank { it.onebotToken },
                )
            }
        }
        ProtoManager.onLine = { installer.appendEnvLine(it) }
        refreshEnvState()
        refreshEmojis()
        refreshImages()
        refreshSkills()
        refreshStyle()
        refreshContacts()
        refreshVoices()
        refreshLinks()
        viewModelScope.launch {
            freeSpaceMb = withContext(Dispatchers.IO) { env.freeSpaceMb() }
        }
        viewModelScope.launch {
            while (true) {
                running = BotService.running
                starting = !running && BotService.pending
                lastExitMessage = if (running) null else BotService.lastExitReason
                // 不跑的时候也刷新：崩了的现场日志得留着给人看
                logText = withContext(Dispatchers.IO) { BotService.tail(env, 60) }
                delay(2000)
            }
        }
    }

    // ---------- 环境 ----------

    /** 复制一份环境诊断（版本、空间、assets 与 runtime 目录、xz 魔数、错误原因），让用户直接发过来 */
    fun copyEnvDiagnostics() {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { env.diagnostics() }
            copyToClipboard("环境诊断", text)
            showToast("诊断信息已复制，直接粘贴发我就能定位")
        }
    }

    /**
     * 一键自检：机器人是否在跑、协议端是否在线、QQ 是否已登录、OneBot 端口是否可连、模型凭证是否齐全。
     * 结果写进日志区（用户能直接复制发过来），并弹一句 toast。
     */
    fun selfCheckBot() {
        viewModelScope.launch {
            val lines = withContext(Dispatchers.IO) {
                val out = ArrayList<String>(8)
                out.add("机器人：" + if (running) "运行中" else "没启动（首页点「启动」）")
                out.add("协议端：" + if (protoRunning) "运行中（端口 $protoPort）" else "没在跑（接入→手机本地环境里启动）")
                out.add("登录：" + ProtoManager.loginState())
                out.add(
                    "OneBot 端口 $protoPort：" +
                        if (protoRunning && ProtoManager.portOpen()) "可连" else "连不上（NapCat 的 OneBot 服务没起来？）"
                )
                val modelMissing = buildList {
                    if (form.apiKey.isBlank()) add("API Key")
                    if (form.model.isBlank()) add("模型名")
                }
                out.add("模型：" + if (modelMissing.isEmpty()) "凭证已填（${form.model}）" else "缺 " + modelMissing.joinToString("、"))
                out.add("接入方式：" + if (form.channel == "onebot") "真实QQ（${form.onebotUrl.ifBlank { "地址空" }}）" else "QQ 官方机器人")
                out.add(
                    if (!running && protoRunning)
                        "结论：QQ 已经在线，但机器人没启动——去首页点「启动」"
                    else if (running && !ProtoManager.portOpen())
                        "结论：机器人连不上协议端，检查协议端是否真起来（看上面端口）"
                    else if (running && modelMissing.isNotEmpty())
                        "结论：机器人缺模型凭证，回不上话"
                    else "结论：链路看起来是通的；还不回就看日志区有没有「收到[...]」"
                )
                out
            }
            installer.appendEnvLine("【自检】" + lines.joinToString(" ｜ "))
            showToast("自检完成，看下面的日志")
        }
    }

    fun install() {
        if (installing) return
        installing = true
        installError = null
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                var r = env.installRuntime()
                if (!r) {
                    // 第一次失败可能是残留半截文件，清掉再试一次
                    env.wipeRuntime()
                    r = env.installRuntime()
                }
                env.installBot()
                r
            }
            installing = false
            runtimeReady = ok
            freeSpaceMb = withContext(Dispatchers.IO) { env.freeSpaceMb() }
            // 装完环境后这些人/音色/链接文件可能才出现，刷一遍
            refreshContacts()
            refreshVoices()
            refreshLinks()
            if (ok) {
                showToast("环境就绪，可以去填凭证了")
            } else {
                installError = env.lastError.ifBlank { "解压失败，检查存储空间" }
                showToast(installError!!)
            }
        }
    }

    // ---------- 启停 ----------

    fun toggle() {
        val ctx = getApplication<Application>()
        if (running) {
            BotService.stop(ctx, "点了「停止」")
            showToast("正在停止…")
            return
        }
        if (starting) {
            showToast("正在启动，别重复点；协议端上线要 30~90 秒")
            return
        }
        run {
            val needQq = form.channel != "onebot"
            if ((needQq && (form.appId.isBlank() || form.appSecret.isBlank())) || form.apiKey.isBlank()) {
                showToast(if (needQq) "先把三个凭证填上" else "先填模型 API Key")
                return
            }
            if (!needQq && form.onebotUrl.isBlank()) {
                showToast("先填 OneBot WebSocket 地址")
                return
            }
            if (form.model.isBlank()) {
                showToast("先选一个模型")
                return
            }
            lastExitMessage = null
            // 走本机协议端时，如果还没启动，顺手把它拉起来：否则机器人一连就失败，用户以为坏了
            if (!needQq && form.onebotUrl.contains("127.0.0.1") && !protoRunning) {
                if (installer.linux.ready() && installer.loadPack() != null) {
                    startProto()
                    showToast("协议端在启动，机器人会等它就绪后自动连（约 1 分钟）")
                }
            }
            env.installBot()
            BotService.start(ctx)
            showToast("正在启动…")
        }
    }

    fun clearLog() {
        // 界面上显示的是「App 抓的 stdout」+「机器人自己写的 qqbot.log」两份合并，
        // 只删前者看起来就像没清掉，所以两份（含轮转出来的旧文件）一起删
        val files = listOf(
            env.logFile,
            env.botLogFile,
            File(env.botLogFile.parentFile, "qqbot.log.1"),
            File(env.logFile.parentFile, "bot.log.1"),
        )
        val deleted = files.count { runCatching { it.exists() && it.delete() }.getOrDefault(false) }
        logText = "（已清空）"
        showToast(if (deleted > 0) "日志已清空" else "没有可清的日志")
    }

    // ---------- 配置 ----------

    fun updateForm(block: (FormState) -> FormState) {
        form = block(form)
    }

    fun save() {
        val missing = ArrayList<String>()
        if (form.channel == "onebot") {
            if (form.onebotUrl.isBlank()) missing.add("OneBot 地址")
        } else {
            if (form.appId.isBlank()) missing.add("QQ AppID")
            if (form.appSecret.isBlank()) missing.add("QQ AppSecret")
        }
        if (form.apiKey.isBlank()) missing.add("模型 API Key")
        if (form.model.isBlank()) missing.add("模型")
        if (missing.isNotEmpty()) {
            showToast("还差：" + missing.joinToString("、"))
            return
        }
        val map = form.toEnvMap(config)
        val ok = store.save(map)
        store.savePrompt(prompt)
        if (ok) {
            val typedUrl = form.baseUrl.trim().trimEnd('/')
            if (typedUrl.isNotBlank()) {
                prefs.edit().putString("llm_base_url", typedUrl).apply()
            } else {
                prefs.edit().remove("llm_base_url").apply()
            }
            config = withPrefsFallback(store.load())
            // 不再用磁盘回刷表单：以用户刚填的为准，避免"保存后输入框被重置"的观感
            form = form.copy(
                baseUrl = typedUrl,
                ttsBase = form.ttsBase.trim().trimEnd('/'),
                asrBase = form.asrBase.trim().trimEnd('/'),
            )
            lastSaveInfo = "上次保存：" + java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date()) +
                "\n保存时表单里的地址：" + typedUrl.ifBlank { "（空）" } +
                "\n写完文件里读到的地址：" + (config["LLM_BASE_URL"] ?: "（没有这一项）")
            if (running) {
                showToast("已保存，正在重启")
                restart()
            } else {
                showToast("已保存")
            }
        } else {
            showToast("保存失败")
        }
    }

    /** 「查看已保存的配置」：.env 里实际存了什么（密钥打码） */
    fun envDump(): String = if (lastSaveInfo.isBlank()) store.dumpMasked() else lastSaveInfo + "\n\n" + store.dumpMasked()

    /** 配置里实际存着的模型地址（给界面显示「到底存进去了什么」） */
    val savedBaseUrl: String
        get() = (config["LLM_BASE_URL"] ?: "").trim().takeIf { it.isNotEmpty() } ?: "未设置（用程序内置默认）"

    /** .env 里没有模型地址时，用本机另存的副本兜底（防某次空表单把地址删掉） */
    private fun withPrefsFallback(src: Map<String, String>): LinkedHashMap<String, String> {
        val out = LinkedHashMap(src)
        val url = prefs.getString("llm_base_url", "") ?: ""
        if (out["LLM_BASE_URL"].isNullOrBlank() && url.isNotBlank()) {
            out["LLM_BASE_URL"] = url
        }
        return out
    }

    /** 让 node 重新读一遍配置 */
    private fun restart() {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            BotService.stop(ctx, "保存配置后自动重启")
            delay(1500)
            env.installBot()
            BotService.start(ctx)
        }
    }

    fun savePromptOnly() {
        val ok = store.savePrompt(prompt)
        showToast(if (ok) "人设已保存，重启生效" else "保存失败")
    }

    // 注意：不能叫 setPrompt —— prompt 属性的 setter 已占用 setPrompt(String) 的 JVM 签名
    fun updatePrompt(text: String) {
        prompt = text
    }

    fun applyPreset(id: String) {
        prompt = ConfigStore.preset(id)
        // 预设自带名字，套用时一起把名字改掉（之后还能手动改）
        val idx = ConfigStore.PRESET_IDS.indexOf(id)
        val nm = ConfigStore.PRESET_BOT_NAMES.getOrNull(idx) ?: ""
        if (nm.isNotBlank()) updateForm { it.copy(botName = nm) }
        showToast("已套用「${ConfigStore.PRESET_NAMES[idx]}」，记得保存")
    }

    // ---------- 模型 / 自检 ----------

    /** 按当前填的 API 地址 + Key 去服务端拉模型列表 */
    fun fetchModels(silent: Boolean = false) {
        if (fetchingModels) return
        val base = form.baseUrl.trim()
        val key = form.apiKey.trim()
        if (base.isBlank() || key.isBlank()) {
            if (!silent) showToast("先填 API 地址和 API Key")
            return
        }
        fetchingModels = true
        modelError = null
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching { ModelCatalog.fetchModels(base, key) }
            }
            fetchingModels = false
            res.onSuccess { list ->
                models = list
                if (form.model !in list) updateForm { it.copy(model = list.first()) }
                if (!silent) showToast("拿到 ${list.size} 个模型")
            }.onFailure { e ->
                models = emptyList()
                modelError = e.message ?: "获取失败"
                if (!silent) showToast("获取失败，看下面的提示")
            }
        }
    }

    /** 用填好的凭证跑一遍连通性 / 凭证自检 */
    fun selfCheck() {
        if (checking) return
        checking = true
        checkLines = emptyList()
        viewModelScope.launch {
            val lines = Diagnostics.selfCheck(form)
            checking = false
            checkLines = lines
        }
    }

    /** 扫本机有没有已在跑的 OneBot（Termux / Shamrock / 本机转发） */
    fun probeOneBot() {
        if (probing) return
        probing = true
        probeResult = null
        viewModelScope.launch {
            val ports = listOf(3001, 5700, 5701, 6700, 6701, 8080, 5800)
            val found = withContext(Dispatchers.IO) {
                ports.filter { p ->
                    runCatching {
                        java.net.Socket().use { s ->
                            s.connect(java.net.InetSocketAddress("127.0.0.1", p), 400)
                        }
                        true
                    }.getOrDefault(false)
                }
            }
            probing = false
            if (found.isEmpty()) {
                probeResult =
                    "本机没发现 OneBot（查过 3001/5700/5701/5800/6700/6701/8080）。\n" +
                        "协议端在电脑上跑的话，这里要填电脑的局域网 IP，比如 ws://192.168.1.10:3001。"
            } else {
                val p = found.first()
                probeResult = "发现本机端口 $p，已帮你填上 ws://127.0.0.1:$p。如果连不上，按协议端配置改成对应端口。"
                updateForm { it.copy(onebotUrl = "ws://127.0.0.1:$p") }
            }
        }
    }

    /** 复制在 Termux 里装环境的命令 */
    fun copyTermuxGuide() {
        val text = buildString {
            appendLine("# 1) 装 proot-distro，再装一个 Debian")
            appendLine("pkg update -y && pkg install -y proot-distro")
            appendLine("proot-distro install debian")
            appendLine("proot-distro login debian")
            appendLine("# 2) 在 Debian 里按 NapCat 教程装 box64 + NapCat.Shell")
            appendLine("#    https://napneko.github.io/guide/start")
            appendLine("# 3) 让 NapCat 的 WebSocket 监听 0.0.0.0:3001，回到 App 点「扫描本机」")
        }
        copyToClipboard("Termux 环境命令", text)
        showToast("命令已复制，去 Termux 里粘贴执行")
    }

    private fun copyToClipboard(label: String, text: String) {
        runCatching {
            val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
        }
    }

    // ---------- 安装器（转发） ----------

    fun refreshEnvState() = installer.refreshEnvState()

    fun choosePack(id: String) {
        installer.choosePack(id)
        ProtoManager.setPackId(id)
    }

    fun installEnv() = installer.installEnv()

    fun cancelInstall() = installer.cancelInstall()

    val downloadConns: Int get() = installer.downloadConns
    val smartSource: Boolean get() = installer.smartSource

    fun setConns(n: Int) = installer.setConns(n)
    fun setSmart(on: Boolean) = installer.setSmart(on)
    fun testSources() = installer.testSources()

    val sourceTick: Int get() = installer.sourceTick
    val sourceSpeeds: Map<String, Long> get() = installer.sourceSpeeds

    fun packDownloads(): List<EnvInstaller.Download> = installer.packDownloads()
    fun candidatesFor(file: String): List<String> = installer.candidatesFor(file)
    fun sourceName(url: String): String = installer.sourceName(url)
    fun sourceChoice(file: String): Int = installer.sourceChoice(file)
    fun setSourceChoice(file: String, idx: Int) = installer.setSourceChoice(file, idx)

    fun copyEnvLog() = installer.copyEnvLog(::copyToClipboard)

    /** 本地语音自检结果（null=还没测） */
    val ttsTest: String? get() = installer.ttsTest

    fun testLocalTts() = installer.testLocalTts()

    fun copyTtsTest() {
        val t = installer.ttsTest ?: return
        copyToClipboard("本地语音自检", t)
        showToast("自检结果已复制")
    }

    // ---------- 本地语音一键试听 ----------
    val ttsQuickResult: String? get() = installer.ttsQuickResult
    val ttsQuickWav: java.io.File? get() = installer.ttsQuickWav
    val ttsQuickBusy: Boolean get() = installer.ttsQuickBusy
    fun quickTestTts() = installer.quickTestTts()
    fun playTts() = installer.playTts()

    // ---------- 声音复刻（App 内创建 voice_id） ----------
    var voiceAudioUrl by mutableStateOf(""); private set
    var voicePrefix by mutableStateOf(""); private set
    var voiceEnrollMsg by mutableStateOf<String?>(null); private set
    var voiceEnrolling by mutableStateOf(false); private set

    fun updateVoiceAudio(v: String) { voiceAudioUrl = v }
    fun updateVoicePrefix(v: String) { voicePrefix = v }

    /** 用一段公网录音在服务商那边创建音色，成功后自动填 voice_id / 模型 / 切到复刻模式 */
    fun createVoice() {
        if (voiceEnrolling) return
        val f = form
        val key = f.ttsKey.trim().ifBlank { f.apiKey.trim() }
        voiceEnrolling = true
        voiceEnrollMsg = "正在创建音色…（服务商要下载并分析你的录音，通常 10~60 秒）"
        viewModelScope.launch {
            val (ok, msg) = withContext(Dispatchers.IO) {
                runCatching {
                    com.qqbot.app.VoiceEnroll.create(
                        base = f.ttsBase,
                        key = key,
                        audioUrl = voiceAudioUrl,
                        prefix = voicePrefix,
                        targetModel = com.qqbot.app.VoiceEnroll.DEFAULT_TARGET_MODEL,
                    )
                }.getOrElse { false to "创建音色失败：${it.message}" }
            }
            voiceEnrolling = false
            if (ok) {
                updateForm {
                    it.copy(
                        ttsMode = "clone",
                        ttsVoice = msg,
                        ttsModel = com.qqbot.app.VoiceEnroll.DEFAULT_TARGET_MODEL,
                    )
                }
                voiceEnrollMsg = "✅ 创建成功，音色已填好：$msg\n保存设置后发「发个语音」试试"
                showToast("音色创建成功")
            } else {
                voiceEnrollMsg = msg
            }
        }
    }

    fun wipeEnv() {
        installer.wipeEnv()
        showToast("环境已清空")
    }

    // ---------- 协议端（转发） ----------

    /** 启动协议端（NapCat / Lagrange），成功后自动把 WS 地址填成 127.0.0.1 */
    fun startProto() {
        if (protoRunning) return
        if (!installer.linux.ready()) {
            showToast("先点「下载并安装运行环境」")
            return
        }
        ProtoManager.setPackId(installer.packId())
        val pack = installer.loadPack()
        if (pack == null) {
            showToast("找不到安装配置：${installer.packId()}")
            return
        }
        val ok = ProtoManager.start()
        if (!ok) {
            showToast("启动失败，看运行环境日志")
            return
        }
        ProtoManager.setWantRunning(true)
        showToast(
            if (pack.isProot) "协议端启动中，登录二维码会出现（约 20-60 秒）"
            else "协议端启动中"
        )
    }

    fun stopProto() = ProtoManager.stop()

    /** 强制重铺机器人代码（改过 JS / 升级后行为没变时用），人设和记忆保留 */
    fun reinstallBot() {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { env.reinstallBot() }
            showToast(if (ok) "机器人代码已更新" else "更新失败")
            if (ok && running) restart()
        }
    }

    // ---------- 表情包 ----------

    fun refreshEmojis() {
        emojis = with(env.emojiDir) {
            listFiles()?.filter {
                it.name.lowercase().let { n ->
                    n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") ||
                        n.endsWith(".gif") || n.endsWith(".webp")
                }
            }?.map { it.name }?.sorted() ?: emptyList()
        }
    }

    fun emojiFile(name: String): File = File(env.emojiDir, name)

    fun addEmoji(uri: Uri) {
        addPictureTo(env.emojiDir, uri) { refreshEmojis() }
    }

    fun deleteEmoji(name: String) {
        emojiFile(name).delete()
        refreshEmojis()
    }

    // ---------- 图片（image/） ----------

    fun refreshImages() {
        images = with(env.imageDir) {
            listFiles()?.filter {
                it.name.lowercase().let { n ->
                    n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") ||
                        n.endsWith(".gif") || n.endsWith(".webp")
                }
            }?.map { it.name }?.sorted() ?: emptyList()
        }
    }

    fun imageFile(name: String): File = File(env.imageDir, name)

    fun addImage(uri: Uri) {
        addPictureTo(env.imageDir, uri) { refreshImages() }
    }

    fun deleteImage(name: String) {
        imageFile(name).delete()
        refreshImages()
    }

    /** 把相册/文件里选中的图片拷进指定目录（多路兜底：内容流 / 文件描述符 / 资源描述符） */
    private fun addPictureTo(dir: File, uri: Uri, onDone: () -> Unit) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) { runCatching { copyImageIn(ctx, dir, uri) } }
            onDone()
            val r = res.getOrNull()
            when {
                r == null -> {
                    val msg = res.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "未知错误"
                    mediaDiag = "添加失败 — $msg"
                    showToast("添加失败：${res.exceptionOrNull()?.message ?: "读不到这张图"}")
                }
                r.second > 8L * 1024 * 1024 -> {
                    val n = dir.listFiles()?.count { it.isFile } ?: 0
                    mediaDiag = "已加 ${r.first}（${r.second / 1024}KB），超过 8MB，QQ 发不出去；目录共 $n 张"
                    showToast("已添加，但超过 8MB，QQ 发不出去")
                }
                else -> {
                    val n = dir.listFiles()?.count { it.isFile } ?: 0
                    mediaDiag = "已加 ${r.first}（${r.second / 1024}KB），目录共 $n 张"
                    showToast("已添加：${r.first}（共 $n 张）")
                }
            }
        }
    }

    /** 返回 (文件名, 字节数)；读不到抛异常 */
    private fun copyImageIn(ctx: android.content.Context, dir: File, uri: Uri): Pair<String, Long> {
        dir.mkdirs()
        // 扩展名：优先用 mime，取不到就看 uri 路径，最后按 png 兜底
        val mime = ctx.contentResolver.getType(uri)
        var ext = mime?.substringAfterLast('/')?.lowercase()
            ?.let { if (it == "jpg") "jpeg" else it }
        if (ext == null || ext !in setOf("png", "jpeg", "gif", "webp")) {
            ext = (uri.lastPathSegment ?: "").substringAfterLast('.', "").lowercase()
                .takeIf { it in setOf("png", "jpeg", "jpg", "gif", "webp") }?.let { if (it == "jpg") "jpeg" else it }
                ?: "png"
        }
        val dest = File(dir, "pic_${System.currentTimeMillis()}.$ext")
        val stream = ctx.contentResolver.openInputStream(uri)
            ?: ctx.contentResolver.openFileDescriptor(uri, "r")?.let { pfd ->
                java.io.FileInputStream(pfd.fileDescriptor)
            }
            ?: ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.let { afd ->
                java.io.FileInputStream(afd.fileDescriptor)
            }
            ?: java.io.FileInputStream(uri.path?.let { File(it) } ?: throw java.io.IOException("无法打开"))
        stream.use { input -> dest.outputStream().use { out -> input.copyTo(out) } }
        if (!dest.exists() || dest.length() == 0L) {
            dest.delete()
            throw java.io.IOException("拷贝后是空文件")
        }
        return dest.name to dest.length()
    }

    // ---------- 插件（skills/） ----------

    fun refreshSkills() {
        skills = Skills.list(env.botDir)
    }

    /** 从 zip 或单个 .mjs/.js 文件导入技能 */
    fun importSkill(uri: Uri) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val key = withContext(Dispatchers.IO) {
                runCatching {
                    val name = queryDisplayName(ctx, uri) ?: "skill_${System.currentTimeMillis()}.zip"
                    val base = File(name).name
                    if (base.endsWith(".mjs", true) || base.endsWith(".js", true)) {
                        // 单文件：直接放进 skills/ 目录
                        val safe = base.replace(Regex("[^A-Za-z0-9._-]"), "_")
                            .ifBlank { "skill_${System.currentTimeMillis()}.mjs" }
                        val dir = Skills.dir(env.botDir)
                        dir.mkdirs()
                        val dest = File(dir, safe)
                        ctx.contentResolver.openInputStream(uri)?.use { ins ->
                            dest.outputStream().use { out -> ins.copyTo(out) }
                        } ?: return@runCatching null
                        safe
                    } else {
                        ctx.contentResolver.openInputStream(uri)?.use { input ->
                            Skills.importZip(env.botDir, name, input)
                        }
                    }
                }.getOrNull()
            }
            refreshSkills()
            showToast(
                when {
                    key == null -> "导入失败：不是有效的技能文件"
                    else -> "已导入技能「$key」（最多 1 分钟生效）"
                }
            )
        }
    }

    // ---------- 说话方式语料（聊天记录 / 句子） ----------

    /**
     * 导入聊天记录或语料：解析成「一问一答」或「句子」，写 bot/data/style.json。
     * 机器人每条消息都会重新看一眼这个文件（按修改时间缓存），所以下一条消息就生效，
     * 不用重启；清空同理。
     */
    fun importStyle(uri: Uri) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val name = queryDisplayName(ctx, uri) ?: "语料文件"
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("读不到这个文件")
                    if (bytes.size > 3 * 1024 * 1024) {
                        throw IllegalStateException("文件超过 3MB，先剪一段再导入")
                    }
                    StyleCorpus.parseAndSave(env.botDir, name, decodeText(bytes))
                }
            }
            res.onSuccess { info ->
                styleInfo = info
                showToast(
                    "已导入 " + info.count + " 条" + (if (info.isDialog) "对话" else "句子") + "，下一条消息生效"
                )
            }.onFailure { e ->
                showToast("导入失败：" + (e.message ?: "格式没认出来"))
            }
        }
    }

    fun refreshStyle() {
        styleInfo = StyleCorpus.read(env.botDir)
    }

    fun clearStyle() {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { StyleCorpus.clear(env.botDir) }.getOrDefault(false) }
            refreshStyle()
            showToast(if (ok) "已清空语料" else "清空失败，文件可能被占用")
        }
    }

    /** 解码文本：UTF-16 BOM 按 UTF-16；UTF-8 解出大量乱码时按 GB18030 再试（老版 QQ 导出的 txt 常见） */
    private fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return runCatching { String(bytes, Charsets.UTF_16LE) }.getOrDefault("")
        }
        val utf8 = String(bytes, Charsets.UTF_8)
        val bad = utf8.count { it == '\uFFFD' }
        if (bad > 0 && bad * 100 >= utf8.length) {
            return runCatching { String(bytes, java.nio.charset.Charset.forName("GB18030")) }.getOrDefault(utf8)
        }
        return utf8
    }

    fun setSkillEnabled(key: String, enabled: Boolean) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { Skills.setEnabled(env.botDir, key, enabled) }
            refreshSkills()
            showToast(if (ok) (if (enabled) "已启用" else "已停用") else "操作失败")
        }
    }

    fun deleteSkill(key: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { Skills.delete(env.botDir, key) }
            refreshSkills()
            showToast("已删除插件")
        }
    }

    private fun queryDisplayName(ctx: android.content.Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0) c.getString(i) else null
            } else null
        }
    }.getOrNull()

    // ---------- 本地音色 / 联系人 / 链接库 / 素材标签 ----------

    /** bot/data 目录（contacts.json、links.json、media-tags.json 都放这） */
    private fun dataDir(): File = File(env.botDir, "data").apply { if (!exists()) mkdirs() }

    /** 扫 rootfs 里已安装的 piper 音色包 */
    fun refreshVoices() {
        localVoices = LocalTts.voices(getApplication<Application>())
    }

    /** 导入 piper 音色包：zip 里的 .onnx / .onnx.json 解到 rootfs /root/piper/voices/<zip 名>/ */
    fun importVoicePack(uri: Uri) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) {
                runCatching {
                    val zipName = queryDisplayName(ctx, uri) ?: "voice_${System.currentTimeMillis()}.zip"
                    val base = zipName.substringBeforeLast('.', zipName)
                    val safe = base.replace(Regex("[^\\p{L}\\p{N}_-]"), "_").take(40).ifBlank { "voice" }
                    val target = File(File(installer.linux.rootfsDir, "root/piper/voices"), safe).apply { mkdirs() }
                    val rootPath = target.canonicalPath + File.separator
                    var count = 0
                    ctx.contentResolver.openInputStream(uri)?.use { raw ->
                        ZipInputStream(raw.buffered()).use { zis ->
                            while (true) {
                                val e = zis.nextEntry ?: break
                                val fileName = e.name.replace('\\', '/').substringAfterLast('/')
                                if (!e.isDirectory &&
                                    (fileName.endsWith(".onnx", true) || fileName.endsWith(".onnx.json", true))
                                ) {
                                    val out = File(target, fileName)
                                    // zip 里可能带 ../，别写出目录
                                    if (out.canonicalPath.startsWith(rootPath)) {
                                        out.outputStream().use { o -> zis.copyTo(o) }
                                        count++
                                    }
                                }
                                zis.closeEntry()
                            }
                        }
                    }
                    if (count == 0) {
                        target.deleteRecursively()
                        null
                    } else {
                        safe
                    }
                }.getOrNull()
            }
            refreshVoices()
            showToast(if (name == null) "导入失败：zip 里没有 .onnx / .onnx.json" else "已导入音色「$name」")
        }
    }

    /** 从 bot/data/contacts.json 读最近聊过的人（字段 id/name/kind/last），按 last 倒序取前 50 */
    fun refreshContacts() {
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                val out = ArrayList<Pair<String, String>>()
                val lastMap = HashMap<String, String>()
                runCatching {
                    val f = File(dataDir(), "contacts.json")
                    val arr = JSONArray(if (f.exists()) f.readText() else "[]")
                    val rows = ArrayList<Triple<String, String, Long>>()
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val id = o.optString("id").trim()
                        if (id.isBlank()) continue
                        rows.add(Triple(id, o.optString("name").trim(), parseLast(o.opt("last"))))
                    }
                    rows.sortByDescending { it.third }
                    rows.take(50).forEach { (id, name, last) ->
                        out.add(id to name.ifBlank { id })
                        lastMap[id] = formatLast(last)
                    }
                }
                out to lastMap
            }
            contacts = res.first
            contactLast = res.second
        }
    }

    /** contacts.json 的 last 可能是毫秒/秒时间戳，也可能是 ISO 字符串，统一成毫秒（0 = 未知） */
    private fun parseLast(v: Any?): Long {
        if (v == null) return 0L
        if (v is Number) return v.toLong()
        val s = v.toString().trim()
        if (s.isEmpty()) return 0L
        s.toLongOrNull()?.let { return it }
        return runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                .parse(s.take(19).removeSuffix("Z"))?.time ?: 0L
        }.getOrDefault(0L)
    }

    /** 时间戳（秒或毫秒）→ 列表里显示的 MM-dd HH:mm；未知就空串 */
    private fun formatLast(ts: Long): String {
        if (ts <= 0L) return ""
        val ms = if (ts < 1_000_000_000_000L) ts * 1000 else ts
        return runCatching {
            java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(ms))
        }.getOrDefault("")
    }

    /** 读 bot/data/links.json（[{"title","url"}]） */
    fun refreshLinks() {
        viewModelScope.launch {
            links = withContext(Dispatchers.IO) { readLinks() }
        }
    }

    private fun readLinks(): List<Pair<String, String>> = runCatching {
        val f = File(dataDir(), "links.json")
        if (!f.exists()) return@runCatching emptyList()
        val arr = JSONArray(f.readText())
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url").trim()
            if (url.isBlank()) null else o.optString("title").trim().ifBlank { url } to url
        }
    }.getOrDefault(emptyList())

    private fun writeLinks(list: List<Pair<String, String>>) {
        val arr = JSONArray()
        list.forEach { (title, url) ->
            arr.put(JSONObject().put("title", title).put("url", url))
        }
        File(dataDir(), "links.json").writeText(arr.toString())
    }

    fun addLink(title: String, url: String) {
        val t = title.trim()
        val u = url.trim()
        if (u.isBlank()) {
            showToast("先填链接地址")
            return
        }
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            showToast("链接要以 http:// 或 https:// 开头")
            return
        }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val list = readLinks().filterNot { it.second == u }.toMutableList()
                    list.add(t.ifBlank { u } to u)
                    writeLinks(list)
                    true
                }.getOrDefault(false)
            }
            refreshLinks()
            showToast(if (ok) "链接已添加" else "添加失败")
        }
    }

    fun deleteLink(url: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { writeLinks(readLinks().filterNot { it.second == url }) }
            }
            refreshLinks()
            showToast("已删除链接")
        }
    }

    /** 加载 bot/data/media-tags.json → 编辑框文本（文件名=标签，每行一条） */
    fun loadMediaTags() {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    val f = File(dataDir(), "media-tags.json")
                    if (!f.exists()) return@runCatching ""
                    val o = JSONObject(f.readText())
                    o.keys().asSequence().sorted().joinToString("\n") { k -> "$k=${o.optString(k)}" }
                }.getOrDefault("")
            }
            mediaTagsText = text
        }
    }

    /** 保存编辑框文本 → bot/data/media-tags.json（{"文件名":"标签"}） */
    fun saveMediaTags(text: String) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val o = JSONObject()
                    text.lineSequence().forEach { line ->
                        val s = line.trim()
                        if (s.isEmpty() || s.startsWith("#")) return@forEach
                        val eq = s.indexOf('=')
                        if (eq <= 0) return@forEach
                        val name = s.substring(0, eq).trim()
                        if (name.isNotEmpty()) o.put(name, s.substring(eq + 1).trim())
                    }
                    File(dataDir(), "media-tags.json").writeText(o.toString())
                    true
                }.getOrDefault(false)
            }
            mediaTagsText = text
            showToast(if (ok) "标签已保存" else "保存失败")
        }
    }

    /** 把 assets/demo-skill.mjs 写成 bot/skills/demo-hello.mjs，给用户一个能直接改的样板（存在就覆盖） */
    fun importDemoSkill() {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val text = runCatching {
                        ctx.assets.open("demo-skill.mjs").bufferedReader().use { it.readText() }
                    }.getOrElse { DEMO_SKILL_FALLBACK }
                    File(Skills.dir(env.botDir), "demo-hello.mjs").writeText(text)
                    true
                }.getOrDefault(false)
            }
            refreshSkills()
            showToast(if (ok) "已导入示例技能（发「ping」试试）" else "导入失败")
        }
    }

    /** 把 assets/demo-process/ 写成 bot/skills/demo-process/，演示「进程技能」（跑在 Linux 环境里的 shell） */
    fun importDemoProcessSkill() {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val target = File(Skills.dir(env.botDir), "demo-process")
                    if (target.exists()) target.deleteRecursively()
                    target.mkdirs()
                    val files = ctx.assets.list("demo-process") ?: emptyArray()
                    if (files.isEmpty()) return@runCatching false
                    files.forEach { name ->
                        ctx.assets.open("demo-process/$name").use { ins ->
                            File(target, name).outputStream().use { out -> ins.copyTo(out) }
                        }
                    }
                    true
                }.getOrDefault(false)
            }
            refreshSkills()
            showToast(
                if (ok) "已导入示例进程技能（发「进程测试」试试，需要装过 Linux 环境）"
                else "导入失败"
            )
        }
    }

    // ---------- 今日概况 ----------

    /** 今天回了多少条、调用了多少次、花了多少钱 */
    fun todayStats(): TodayStats = Diagnostics.todayStats(env)

    // ---------- 提示 ----------

    fun showToast(msg: String) {
        toast = msg
        viewModelScope.launch {
            delay(1900)
            if (toast == msg) toast = null
        }
    }

    private companion object {
        /** assets 里那份 demo-skill.mjs 万一丢了，也能给出一样的样板（内容保持一致） */
        val DEMO_SKILL_FALLBACK = """
            export default {
              name: '示例技能',
              desc: '发「ping」回一声「在呢」',
              match(text) {
                return String(text || '').trim() === 'ping'
              },
              async handle({ text, convKey, args }) {
                return '在呢'
              },
            }
        """.trimIndent()
    }
}