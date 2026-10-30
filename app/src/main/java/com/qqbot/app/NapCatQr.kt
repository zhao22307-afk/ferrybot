package com.qqbot.app

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 从 NapCat 的 WebUI 拿「真实的 QQ 登录二维码」。
 *
 * 为什么要走 WebUI：NapCat 把二维码内容（一个 tx 登录 URL）通过 HTTP API 暴露出来，
 * 但终端里只有 ASCII 二维码，手机上看不清也不好扫。
 *
 * 鉴权（NapCat 4.x 的 SignToken）：
 *   hash = sha256(token + ".napcat")
 *   POST /api/auth/login {"hash": hash}  →  data.Credential（1 小时有效）
 *   之后请求带 Authorization: Bearer <Credential>
 *   取码：POST /api/QQLogin/GetQQLoginQrcode → data.qrcode 是一段字符串
 * 然后在本地用 ZXing 画成 PNG，UI 直接显示图片扫。
 */
object NapCatQr {

    data class Login(val credential: String, val ts: Long)

    /** 登录 WebUI，拿 Credential */
    fun login(port: Int, token: String): Login? {
        val hash = sha256Hex("$token.napcat")
        val body = JSONObject().put("hash", hash).toString()
        val res = http("POST", "http://127.0.0.1:$port/api/auth/login", body, null) ?: return null
        val cred = runCatching { JSONObject(res).optJSONObject("data")?.optString("credential").orEmpty() }.getOrDefault("")
            .ifBlank { runCatching { JSONObject(res).optJSONObject("data")?.optString("Credential").orEmpty() }.getOrDefault("") }
        if (cred.isBlank()) return null
        return Login(cred, System.currentTimeMillis())
    }

    /** 取二维码内容字符串 */
    fun qrContent(port: Int, credential: String): String? {
        val res = http("POST", "http://127.0.0.1:$port/api/QQLogin/GetQQLoginQrcode", "{}", credential) ?: return null
        return runCatching {
            val o = JSONObject(res)
            o.optJSONObject("data")?.optString("qrcode").orEmpty().ifBlank { o.optString("qrcode") }
        }.getOrNull()?.ifBlank { null }
    }

    /** 是否已经登录成功（返回 true 就停止轮询） */
    fun loggedIn(port: Int, credential: String): Boolean {
        val res = http("POST", "http://127.0.0.1:$port/api/QQLogin/CheckLoginStatus", "{}", credential) ?: return false
        return runCatching {
            val o = JSONObject(res)
            val code = o.optInt("code", -1)
            val status = o.optJSONObject("data")?.optInt("status", -1) ?: -1
            code == 0 && status == 0 // NapCat: status 0 = 已登录
        }.getOrDefault(false)
    }

    /** 把二维码内容画成 PNG，返回文件；失败返回 null */
    fun render(content: String, outFile: File, size: Int = 720): File? {
        return try {
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1,
            )
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
            val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
            for (y in 0 until matrix.height) {
                for (x in 0 until matrix.width) {
                    bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            outFile.parentFile?.mkdirs()
            FileOutputStream(outFile).use { o -> bmp.compress(Bitmap.CompressFormat.PNG, 100, o) }
            bmp.recycle()
            outFile
        } catch (e: Exception) {
            null
        }
    }

    private fun sha256Hex(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun http(method: String, url: String, body: String?, credential: String?): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 3000
                readTimeout = 5000
                doOutput = body != null && method != "GET"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "ferry-bot-android/1.0")
                if (!credential.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $credential")
            }
            if (body != null && method != "GET") {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}