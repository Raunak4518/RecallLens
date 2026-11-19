package org.photolibrary.app.privacy

import android.util.Base64
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.HpkePublicKey
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.util.Bytes
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

internal const val MAX_RESULT_BYTES = 100_000

internal class WorkerKey(val id: String, private val raw: ByteArray) {
    init {
        require(raw.size == 32 && id == sha256(raw))
    }

    fun encrypt(message: ByteArray, info: ByteArray): ByteArray {
        HybridConfig.register()
        val parameters = HpkeParameters.builder()
            .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
            .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
            .setAeadId(HpkeParameters.AeadId.AES_256_GCM)
            .setVariant(HpkeParameters.Variant.NO_PREFIX)
            .build()
        val key = HpkePublicKey.create(parameters, Bytes.copyFrom(raw), null)
        val handle = KeysetHandle.newBuilder().addEntry(KeysetHandle.importKey(key).withRandomId().makePrimary()).build()
        return handle.getPrimitive(RegistryConfiguration.get(), HybridEncrypt::class.java).encrypt(message, info)
    }
}

internal class OcrRequest private constructor(
    private val id: String,
    private val workerId: String,
    private val binding: JSONObject,
    private val resultKey: ByteArray,
    val encoded: ByteArray,
) : AutoCloseable {
    private var consumed = false
    var modelProvenance: String? = null
        private set

    @Synchronized
    fun readResult(bytes: ByteArray): String {
        require(!consumed && bytes.size in 1..MAX_RESULT_BYTES)
        val envelope = JSONObject(bytes.decodeToString(throwOnInvalidSequence = true))
        require(envelope.getInt("v") == 1 && envelope.getString("job") == id && envelope.getString("worker") == workerId)
        val result = Base64.decode(envelope.getString("box"), Base64.NO_WRAP)
        require(result.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(resultKey, "AES"), GCMParameterSpec(128, result, 0, 12))
            updateAAD(wireInfo("result", workerId, id))
        }
        val clear = JSONObject(cipher.doFinal(result, 12, result.size - 12).decodeToString(throwOnInvalidSequence = true))
        for (key in binding.keys()) require(clear.getString(key) == binding.getString(key))
        val text = clear.getString("text")
        require(text.length <= 16_384)
        val model = if (clear.has("model")) clear.getJSONObject("model") else null
        model?.let {
            require(it.keys().asSequence().toSet() == setOf("engine", "version", "weights_sha256", "preprocessing"))
            for (field in it.keys()) require(it.getString(field).length in 1..256)
            require(it.getString("weights_sha256").matches(Regex("[0-9a-f]{64}")))
        }
        modelProvenance = model?.toString()
        close()
        return text
    }

    @Synchronized
    override fun close() {
        consumed = true
        resultKey.fill(0)
    }

    companion object {
        fun seal(approval: ApprovedExport, worker: WorkerKey): OcrRequest {
            require(approval.destination == worker.id && approval.task == EXPORT_TASK && approval.policy == EXPORT_POLICY)
            val artifact = approval.artifact
            val bytes = artifact.copyBytes()
            require(isVerifiedExport(bytes) && sha256(bytes) == artifact.digest)
            val resultKey = ByteArray(32).also(SecureRandom()::nextBytes)
            val binding = JSONObject()
                .put("mode", artifact.source.mode.name)
                .put("sha256", artifact.digest)
                .put("source", UUID.randomUUID().toString())
                .put("policy", approval.policy)
                .put("task", approval.task)
            val message = JSONObject(binding.toString()).put("png", b64(bytes)).put("return_key", b64(resultKey))
            return try {
                val box = worker.encrypt(message.toString().encodeToByteArray(), wireInfo("request", worker.id, approval.id))
                val encoded = JSONObject().put("v", 1).put("job", approval.id).put("worker", worker.id).put("box", b64(box))
                OcrRequest(approval.id, worker.id, binding, resultKey, encoded.toString().encodeToByteArray())
            } catch (error: Exception) {
                resultKey.fill(0)
                throw error
            }
        }
    }
}

private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

private fun wireInfo(direction: String, worker: String, job: String) =
    "photo-library:v1:ocr:$direction:$worker:$job".encodeToByteArray()
