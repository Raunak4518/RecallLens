package org.photolibrary.app.index

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class IndexKey(file: File, private val alias: String) {
    private val wrapped = AtomicFile(file)
    private val context = "photo-library:ocr-index-key:1:$alias".encodeToByteArray()

    fun load(databasePresent: Boolean): ByteArray {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val envelope = try {
            wrapped.openRead().use { input ->
                if (input.channel.size() != ENVELOPE_BYTES.toLong()) throw PhotoIndexUnavailable()
                input.readBytes()
            }
        } catch (failure: FileNotFoundException) {
            if (databasePresent || wrapped.baseFile.exists()) throw PhotoIndexUnavailable(failure)
            return create(store)
        }
        if (envelope.size != ENVELOPE_BYTES || envelope[0] != FORMAT_VERSION) throw PhotoIndexUnavailable()
        val key = store.getKey(alias, null) as? SecretKey ?: throw PhotoIndexUnavailable()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, envelope, 1, NONCE_BYTES))
        cipher.updateAAD(context)
        return cipher.doFinal(envelope, 1 + NONCE_BYTES, envelope.size - 1 - NONCE_BYTES)
    }

    private fun create(store: KeyStore): ByteArray {
        val key = (store.getKey(alias, null) as? SecretKey) ?: generateWrappingKey()
        val passphrase = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(context)
            val nonce = cipher.iv
            if (nonce.size != NONCE_BYTES) throw PhotoIndexUnavailable()
            val output = wrapped.startWrite()
            try {
                output.write(FORMAT_VERSION.toInt())
                output.write(nonce)
                output.write(cipher.doFinal(passphrase))
                wrapped.finishWrite(output)
            } catch (error: Exception) {
                wrapped.failWrite(output)
                throw error
            }
            return passphrase
        } catch (error: Exception) {
            passphrase.fill(0)
            throw error
        }
    }

    private fun generateWrappingKey(): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
        init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        generateKey()
    }

    private companion object {
        const val FORMAT_VERSION: Byte = 1
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        const val ENVELOPE_BYTES = 1 + NONCE_BYTES + KEY_BYTES + TAG_BITS / 8
    }
}
