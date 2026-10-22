package me.rerere.rikkahub.data.classrecord

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Cookies are encrypted with a device-bound key, excluded from backups; passwords are never saved. */
internal class ClassRecordSessionStore(context: Context, fileName: String = "classrecord.session") {
    private val file = File(context.noBackupFilesDir, fileName)
    private val alias = "rikkahub.classrecord.session.v1"

    private fun key(createIfMissing: Boolean = true): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!createIfMissing) throw IOException("保存登录状态的设备密钥不可用，请解锁设备后重试。")
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    @Synchronized
    fun read(): String? {
        if (!file.isFile) return null
        return try {
            val parts = file.readText().split(':', limit = 2)
            require(parts.size == 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(createIfMissing = false), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
        } catch (error: Exception) {
            // A locked/unavailable Keystore is a retryable restore failure, not a missing login.
            // Leave both the encrypted file and the key untouched.
            throw IOException("无法读取保存的登录状态，请解锁设备后重试。", error)
        }
    }

    @Synchronized
    fun write(value: String?) {
        if (value == null) {
            check(!file.exists() || file.delete()) { "无法清除本机保存的登录状态，请重试。" }
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
        val temporary = File(file.parentFile, "${file.name}.pending")
        temporary.writeText(encoded)
        check(temporary.renameTo(file)) { "无法保存登录状态。" }
    }
}
