package org.photolibrary.app.feasibility

import android.util.Base64
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.HpkePrivateKey
import com.google.crypto.tink.hybrid.HpkePublicKey
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.util.Bytes
import com.google.crypto.tink.util.SecretBytes
import java.security.GeneralSecurityException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HpkeInteropTest {
    @Test
    fun tinkOpensPythonFixtureAndBindsTheProcessingMode() {
        HybridConfig.register()
        val configuration = RegistryConfiguration.get()
        val parameters = HpkeParameters.builder()
            .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
            .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
            .setAeadId(HpkeParameters.AeadId.AES_256_GCM)
            .setVariant(HpkeParameters.Variant.NO_PREFIX)
            .build()
        val publicKey = HpkePublicKey.create(
            parameters,
            Bytes.copyFrom(Base64.decode("B6N8vBQgk8i3VdwbEOhstCY3StFqqFPtC9/AsrhtHHw=", Base64.DEFAULT)),
            null,
        )
        val fixtureKey = HpkePrivateKey.create(
            publicKey,
            SecretBytes.copyFrom(ByteArray(32) { (it + 1).toByte() }, InsecureSecretKeyAccess.get()),
        )
        val keyset = KeysetHandle.newBuilder()
            .addEntry(KeysetHandle.importKey(fixtureKey).withRandomId().makePrimary())
            .build()
        val context = "photo-library:v1:fixture-01:CLOUD_REDACTED".encodeToByteArray()
        val changedMode = "photo-library:v1:fixture-01:CLOUD_FULL".encodeToByteArray()
        val plaintext = "synthetic-approved-export".encodeToByteArray()
        val pythonFixture = Base64.decode(
            "tz9RUtuXeC7jd9OxFjaXTxsFoxNmXbbKbXbZRGAih3QTe2NWhiRZb2QAABD4KPv29WniuVyctWia9owNejTtPbpvLkh9AL4Vaw==",
            Base64.DEFAULT,
        )
        val decrypt = keyset.getPrimitive(configuration, HybridDecrypt::class.java)
        assertArrayEquals(plaintext, decrypt.decrypt(pythonFixture, context))
        assertThrows(GeneralSecurityException::class.java) { decrypt.decrypt(pythonFixture, changedMode) }

        val encrypt = keyset.publicKeysetHandle.getPrimitive(configuration, HybridEncrypt::class.java)
        val ciphertext = encrypt.encrypt(plaintext, context)
        assertArrayEquals(plaintext, decrypt.decrypt(ciphertext, context))
        Log.i("PhotoLibraryG0", "HPKE fixture from Android: ${Base64.encodeToString(ciphertext, Base64.NO_WRAP)}")
    }
}
