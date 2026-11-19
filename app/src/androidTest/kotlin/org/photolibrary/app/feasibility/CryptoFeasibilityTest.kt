package org.photolibrary.app.feasibility

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CryptoFeasibilityTest {
    @Test
    fun androidAesGcmMatchesPythonAndRejectsSubstitution() {
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val nonce = ByteArray(12) { it.toByte() }
        val context = "photo-library:v1:request:fixture-01:CLOUD_REDACTED:epoch-0".encodeToByteArray()
        val input = "synthetic-approved-export".encodeToByteArray()
        val encrypted = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
            updateAAD(context)
            doFinal(input)
        }
        assertEquals(
            "347bb86fad80b672ee6cf6fbc19b171be6b2aa51880b300e4c699cf78cddc46226c8c99b419125ade6",
            encrypted.joinToString("") { "%02x".format(it.toInt() and 0xff) },
        )
        assertArrayEquals(input, decrypt(key, nonce, context, encrypted))
        val changedMode = "photo-library:v1:request:fixture-01:CLOUD_FULL:epoch-0".encodeToByteArray()
        assertThrows(AEADBadTagException::class.java) { decrypt(key, nonce, changedMode, encrypted) }
        val damaged = encrypted.clone().apply { this[0] = (this[0].toInt() xor 1).toByte() }
        assertThrows(AEADBadTagException::class.java) { decrypt(key, nonce, context, damaged) }
    }

    @Test
    fun keystoreKeyWrapsWithoutExportAndFailsClosedAfterDeletion() {
        val alias = "photo-library-g0-${UUID.randomUUID()}"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            val key = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setKeySize(256)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build(),
                )
                generateKey()
            }
            assertNull(key.encoded)
            val keyInfo = SecretKeyFactory.getInstance("AES", "AndroidKeyStore")
                .getKeySpec(key, KeyInfo::class.java) as KeyInfo
            if (Build.VERSION.SDK_INT >= 31) {
                Log.i("PhotoLibraryG0", "Emulator Keystore security level: ${keyInfo.securityLevel}")
            }
            val dataKey = ByteArray(32).also(SecureRandom()::nextBytes)
            val context = "photo-library:v1:test-key-wrap".encodeToByteArray()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(context)
            val wrapped = cipher.doFinal(dataKey)
            val restored = store.getKey(alias, null) as SecretKey
            assertArrayEquals(dataKey, decrypt(restored, cipher.iv, context, wrapped))
            store.deleteEntry(alias)
            assertNull(store.getKey(alias, null))
        } finally {
            store.deleteEntry(alias)
        }
    }

    private fun decrypt(key: SecretKey, nonce: ByteArray, context: ByteArray, ciphertext: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
            updateAAD(context)
            doFinal(ciphertext)
        }
}
