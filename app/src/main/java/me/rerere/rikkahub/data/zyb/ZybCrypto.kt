package me.rerere.rikkahub.data.zyb

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 作业帮错题本协议的加解密/签名/编码工具（复刻 zyb_errorbook.py）。
 *
 * AES-128-CBC/PKCS5：key=会话密钥[0:16] 的 ASCII 字节，iv=会话密钥[16:32] 的 ASCII 字节。
 * 签名：SHA1(userId & ts & token & body)。
 * 请求体：Gson setPrettyPrinting（2 空格缩进，": " 分隔），与服务端签名校验一致。
 */
object ZybCrypto {

    fun aesEncrypt(plaintext: String, sessionKey: String): String {
        val key = SecretKeySpec(sessionKey.substring(0, 16).toByteArray(Charsets.US_ASCII), "AES")
        val iv = IvParameterSpec(sessionKey.substring(16, 32).toByteArray(Charsets.US_ASCII))
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key, iv)
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    fun aesDecrypt(b64: String, sessionKey: String): String {
        val key = SecretKeySpec(sessionKey.substring(0, 16).toByteArray(Charsets.US_ASCII), "AES")
        val iv = IvParameterSpec(sessionKey.substring(16, 32).toByteArray(Charsets.US_ASCII))
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, key, iv)
        val decrypted = cipher.doFinal(Base64.decode(b64, Base64.DEFAULT))
        return String(decrypted, Charsets.UTF_8)
    }

    fun md5Lower(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun sha1Hex(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** JSON 字符串转义（与 Python json.dumps ensure_ascii=False 一致：不转义 '/'，非 ASCII 原样）。 */
    private fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * 生成与 Python json.dumps(obj, ensure_ascii=False, indent=2) 一致的扁平对象字符串。
     * 值为 String 或 Int/Long；顺序即插入顺序（用于稳定签名）。
     */
    fun prettyFlatObject(entries: List<Pair<String, Any>>): String {
        if (entries.isEmpty()) return "{}"
        val sb = StringBuilder()
        sb.append("{\n")
        entries.forEachIndexed { i, (k, v) ->
            sb.append("  \"").append(escape(k)).append("\": ")
            when (v) {
                is String -> sb.append('"').append(escape(v)).append('"')
                is Int, is Long -> sb.append(v.toString())
                is Boolean -> sb.append(if (v) "true" else "false")
                else -> sb.append('"').append(escape(v.toString())).append('"')
            }
            if (i != entries.lastIndex) sb.append(',')
            sb.append('\n')
        }
        sb.append("}")
        return sb.toString()
    }
}
