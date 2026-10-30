package com.qqbot.app

import java.net.HttpURLConnection
import java.net.URL

/**
 * 声音复刻（音色克隆）：用一段公网可访问的录音，在 TTS 服务商那边创建一个自定义音色。
 *
 * 走的是 DashScope 的 voice-enrollment 接口：
 *   POST {base}/services/audio/tts/customization
 *   {"model":"voice-enrollment",
 *    "input":{"action":"create_voice","target_model":"qwen-audio-3.1-tts-flash",
 *             "prefix":"myvoice","url":"https://.../my-voice.wav"}}
 *   → {"output":{"voice_id":"qwen-audio-3.1-tts-flash-myvoice-xxxx"}}
 *
 * 拿到 voice_id 之后，合成还是走普通的语音合成接口（model=复刻模型，voice=voice_id），
 * 所以 App 里「语音 → 方案=云端复刻」只需要填 voice_id + 模型名。
 */
object VoiceEnroll {

    const val DEFAULT_BASE = "https://dashscope.aliyuncs.com/api/v1"
    const val DEFAULT_TARGET_MODEL = "qwen-audio-3.1-tts-flash"

    /**
     * @return (成功?, voice_id 或给人看的错误说明)
     */
    fun create(
        base: String,
        key: String,
        audioUrl: String,
        prefix: String,
        targetModel: String,
    ): Pair<Boolean, String> {
        if (key.isBlank()) {
            return false to "没填语音 Key。复刻要用有权限的 Key：「对话 → 语音 → TTS Key」（留空时用「模型」里的 Key）"
        }
        val url = audioUrl.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return false to "录音得是公网可访问的 http(s) 链接（服务商要去下载它，手机本机的文件它拿不到）"
        }
        val b = base.trim().trimEnd('/').ifBlank { DEFAULT_BASE }
        val model = targetModel.trim().ifBlank { DEFAULT_TARGET_MODEL }
        val pre = prefix.trim().ifBlank { "myvoice" }
        val body =
            """{"model":"voice-enrollment","input":{"action":"create_voice",""" +
                """"target_model":"${esc(model)}","prefix":"${esc(pre)}","url":"${esc(url)}"}}"""
        return try {
            val conn = URL("$b/services/audio/tts/customization").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                return false to "创建音色失败：HTTP $code ${text.take(400)}"
            }
            val id = Regex("\"voice_id\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1).orEmpty()
            if (id.isBlank()) {
                return false to "接口返回里没有 voice_id：${text.take(400)}"
            }
            true to id
        } catch (e: Exception) {
            (false to "创建音色失败：${e.javaClass.simpleName} ${e.message ?: ""}").let {
                it.first to it.second.take(300)
            }
        }
    }

    private fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
}