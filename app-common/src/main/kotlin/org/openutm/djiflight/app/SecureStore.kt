package org.openutm.djiflight.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A small secret (the device's provisioning, which holds its client secret) encrypted with an AES-GCM
 * key that never leaves the Android Keystore. A copied app-data folder can't be decrypted elsewhere.
 */
class SecureStore(context: Context, name: String) {
    private val file = File(context.filesDir, "$name.enc")
    private val alias = "openutm-$name"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    @Synchronized
    fun write(text: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(text.toByteArray(Charsets.UTF_8))
        val tmp = File(file.path + ".tmp")
        tmp.outputStream().use { out ->
            out.write(c.iv.size)
            out.write(c.iv)
            out.write(ct)
        }
        check(tmp.renameTo(file)) { "could not save $file" }
    }

    @Synchronized
    fun read(): String? = runCatching {
        if (!file.isFile) return null
        val bytes = file.readBytes()
        val ivLen = bytes[0].toInt()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 1, ivLen))
        String(c.doFinal(bytes, 1 + ivLen, bytes.size - 1 - ivLen), Charsets.UTF_8)
    }.getOrNull()

    @Synchronized
    fun clear() {
        file.delete()
    }
}
