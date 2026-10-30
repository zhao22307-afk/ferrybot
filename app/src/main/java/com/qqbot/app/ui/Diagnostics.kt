package com.qqbot.app.ui

import com.qqbot.app.EnvManager
import com.qqbot.app.ModelCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自检 + 今日统计。纯逻辑、不持有界面状态，这样 AppViewModel 只需要转发。
 */
object Diagnostics {

    /** 用当前表单的凭证跑一遍连通性检查，一行一条结论 */
    suspend fun selfCheck(form: FormState): List<String> = withContext(Dispatchers.IO) {
        val out = ArrayList<String>()

        out.add(
            if (form.channel == "onebot") {
                val u = form.onebotUrl.trim()
                when {
                    u.isBlank() -> "真实QQ：还没填 OneBot 地址"
                    !(u.startsWith("ws://") || u.startsWith("wss://")) -> "真实QQ：地址要以 ws:// 开头"
                    else -> "真实QQ：地址格式没问题（能不能连上看日志）"
                }
            } else if (form.appId.isBlank() || form.appSecret.isBlank()) {
                "QQ 凭证：没填全"
            } else {
                "QQ 凭证：" + ModelCatalog.checkQqToken(form.appId, form.appSecret)
            }
        )

        val apiKey = form.apiKey.trim()
        val base = form.baseUrl.trim()
        if (apiKey.isBlank() || base.isBlank()) {
            out.add("模型接口：没填全")
        } else {
            val r = runCatching { ModelCatalog.fetchModels(base, apiKey) }
            out.add(
                r.fold(
                    onSuccess = { "模型接口：正常，可选 ${it.size} 个模型" },
                    onFailure = { "模型接口：${it.message ?: "失败"}" },
                )
            )
        }
        out
    }

    /** 今天回了多少条、调用了多少次、花了多少钱 */
    fun todayStats(env: EnvManager): TodayStats {
        var replies = 0
        var calls = 0
        var cost = 0.0

        // 日志里数「回复[」的行（日志带日期前缀，可判今天）
        runCatching {
            val lf = env.logFile
            if (lf.exists()) {
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val prefix = today.substring(5) // 日志格式是 yyyy/MM/dd
                lf.readLines(Charsets.UTF_8).forEach { line ->
                    if (line.contains("回复[") && line.contains(prefix.replace("-", "/"))) replies++
                }
            }
        }

        // usage.json 拿调用次数和 tokens
        runCatching {
            val uf = File(env.botDir, "data/usage.json")
            if (uf.exists()) {
                val root = org.json.JSONObject(uf.readText(Charsets.UTF_8))
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val d = root.optJSONObject("days")?.optJSONObject(today)
                if (d != null) {
                    calls = d.optInt("calls", 0)
                    val prompt = d.optLong("prompt", 0)
                    val completion = d.optLong("completion", 0)
                    cost = prompt / 1_000_000.0 * 0.2 + completion / 1_000_000.0 * 0.8
                }
            }
        }
        return TodayStats(replies, calls, cost)
    }
}