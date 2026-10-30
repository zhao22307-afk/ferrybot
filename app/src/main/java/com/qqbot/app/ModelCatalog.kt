package com.qqbot.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 跟模型服务 / QQ 开放平台打交道的小工具：
 *
 * - [fetchModels]  按用户填的「API 地址 + API Key」拉可用模型列表（OpenAI 兼容的 GET {base}/models）
 * - [checkQqToken] 用 AppID/AppSecret 换一次 access_token，用来在界面上做自检
 *
 * 全部是阻塞调用，只能放在 IO 线程里跑。
 */
object ModelCatalog {

    /** 默认接口地址（千问 MaaS 兼容模式） */
    const val DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"

    /** 拉模型列表；抛异常时 message 里带 HTTP 码和响应片段，方便直接显示给用户 */
    fun fetchModels(baseUrl: String, apiKey: String): List<String> {
        val base = baseUrl.trim().trimEnd('/')
        require(base.startsWith("http://") || base.startsWith("https://")) {
            "地址要以 http:// 或 https:// 开头"
        }
        require(apiKey.isNotBlank()) { "先填 API Key" }

        val conn = (URL("$base/models").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            val body = readBody(conn, code)
            if (code !in 200..299) {
                throw IllegalStateException(httpHint(code) + " " + body.take(160))
            }
            val ids = parseModelIds(body)
            if (ids.isEmpty()) throw IllegalStateException("接口没返回模型列表：${body.take(160)}")
            return ids
        } finally {
            conn.disconnect()
        }
    }

    /** 换一次 access_token，正常返回一句人话 */
    fun checkQqToken(appId: String, appSecret: String): String {
        val conn = (URL("https://api.bot.qq.com/app/getAppAccessToken")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            val payload = JSONObject().apply {
                put("appId", appId.trim().toLongOrNull() ?: appId.trim())
                put("clientSecret", appSecret.trim())
            }.toString()
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val body = readBody(conn, code)
            if (code !in 200..299) return "连 api.bot.qq.com 失败：HTTP $code ${body.take(80)}"

            val root = runCatching { JSONObject(body) }.getOrNull()
                ?: return "返回看不懂：${body.take(80)}"
            val bizCode = root.optInt("code", 0)
            val msg = root.optString("message")
            val token = root.optString("access_token")
            return when {
                bizCode == 0 && token.isNotBlank() -> "正常（access_token 已获取）"
                bizCode == 10004 || msg.contains("不存在") -> "AppID 不对：$bizCode $msg"
                else -> "被拒绝：$bizCode $msg"
            }
        } catch (e: Exception) {
            return "连不上 api.bot.qq.com：${e.message?.take(80)}"
        } finally {
            conn.disconnect()
        }
    }

    // ---------- 解析 ----------

    /** 兼容几种常见返回：[{id:..}] / {data:[{id:..}]} / {models:[..]} / {data:{models:[..]}} */
    private fun parseModelIds(body: String): List<String> {
        val out = LinkedHashSet<String>()
        val text = body.trim()
        runCatching {
            if (text.startsWith("[")) {
                collect(JSONArray(text), out)
            } else {
                val root = JSONObject(text)
                val arr = root.optJSONArray("data")
                    ?: root.optJSONArray("models")
                    ?: root.optJSONArray("result")
                when {
                    arr != null -> collect(arr, out)
                    else -> root.optJSONObject("data")?.optJSONArray("models")?.let { collect(it, out) }
                }
            }
        }
        return out.filter { it.isNotBlank() }.sorted()
    }

    private fun collect(arr: JSONArray, out: MutableSet<String>) {
        for (i in 0 until arr.length()) {
            when (val e = arr.opt(i)) {
                is String -> out.add(e.trim())
                is JSONObject -> {
                    val id = e.optString("id")
                        .ifBlank { e.optString("name") }
                        .ifBlank { e.optString("model") }
                    if (id.isNotBlank()) out.add(id.trim())
                }
            }
        }
    }

    private fun readBody(conn: HttpURLConnection, code: Int): String = runCatching {
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
    }.getOrDefault("")

    private fun httpHint(code: Int): String = when (code) {
        400 -> "请求被拒（400，地址可能不对）"
        401, 403 -> "Key 不对或没权限（$code）"
        404 -> "地址不对（404，确认填到 /v1 为止）"
        in 500..599 -> "服务端出错（$code）"
        else -> "HTTP $code"
    }
}