package org.photolibrary.app.privacy

import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject

internal class CloudFailure : Exception()

internal interface CloudHttp {
    suspend fun exchange(connection: CloudConnection, method: String, path: String, body: ByteArray? = null): CloudReply
}

internal data class CloudReply(val status: Int, val body: ByteArray)

internal class UrlCloudHttp : CloudHttp {
    override suspend fun exchange(connection: CloudConnection, method: String, path: String, body: ByteArray?): CloudReply =
        suspendCancellableCoroutine { continuation ->
            val request = URI(connection.url + path).toURL().openConnection() as HttpURLConnection
            continuation.invokeOnCancellation { request.disconnect() }
            Dispatchers.IO.dispatch(continuation.context, Runnable {
                if (!continuation.isActive) return@Runnable
                try {
                    continuation.resumeWith(Result.success(perform(request, connection, method, body)))
                } catch (failure: Exception) {
                    continuation.resumeWith(Result.failure(failure))
                }
            })
        }

    private fun perform(request: HttpURLConnection, connection: CloudConnection, method: String, body: ByteArray?): CloudReply {
        request.requestMethod = method
        request.instanceFollowRedirects = false
        request.connectTimeout = 10_000
        request.readTimeout = 10_000
        request.setRequestProperty("Authorization", "Bearer ${connection.token}")
        request.setRequestProperty("Accept", "application/json")
        try {
            if (body != null) {
                request.doOutput = true
                request.setRequestProperty("Content-Type", "application/json")
                request.setFixedLengthStreamingMode(body.size)
                request.outputStream.use { it.write(body) }
            }
            val status = request.responseCode
            val input = if (status in 200..299) request.inputStream else request.errorStream
            val bytes = input?.use { source ->
                ByteArrayOutputStream().use { output ->
                    val chunk = ByteArray(8192)
                    while (true) {
                        val count = source.read(chunk)
                        if (count < 0) break
                        if (output.size() + count > 200_000) throw CloudFailure()
                        output.write(chunk, 0, count)
                    }
                    output.toByteArray()
                }
            } ?: byteArrayOf()
            return CloudReply(status, bytes)
        } finally {
            request.disconnect()
        }
    }
}

internal class CloudTransport(private val http: CloudHttp = UrlCloudHttp()) {
    suspend fun recognize(
        connection: CloudConnection,
        approval: ApprovedExport,
        request: OcrRequest,
        validate: () -> Unit,
        progress: suspend () -> Unit,
    ): String = withContext(Dispatchers.IO) {
        validate()
        val submitted = submit(connection, request, validate)
        checkStatus(submitted, approval.id)
        val deadline = SystemClock.elapsedRealtime() + 5 * 60_000
        while (true) {
            currentCoroutineContext().ensureActive()
            validate()
            val response = http.exchange(connection, "GET", "/v1/jobs/${approval.id}")
            if (response.status != 200) throw CloudFailure()
            val result = parse(response)
            checkStatus(result, approval.id)
            val expires = result.getDouble("expires_at") * 1000
            if (!expires.isFinite() || SystemClock.elapsedRealtime() >= deadline || System.currentTimeMillis() >= expires) throw CloudFailure()
            when (result.getString("state")) {
                "succeeded" -> {
                    val text = request.readResult(result.getJSONObject("result").toString().encodeToByteArray())
                    validate()
                    return@withContext text
                }
                "queued", "running" -> progress()
                else -> throw CloudFailure()
            }
            delay(500)
        }
        error("Unreachable")
    }

    private suspend fun submit(connection: CloudConnection, request: OcrRequest, validate: () -> Unit): JSONObject {
        var attempt = 0
        while (true) {
            try {
                currentCoroutineContext().ensureActive()
                validate()
                val response = http.exchange(connection, "POST", "/v1/jobs", request.encoded)
                if (response.status != 202) throw CloudFailure()
                return parse(response)
            } catch (error: IOException) {
                if (++attempt >= 2) throw CloudFailure()
            }
        }
    }

    suspend fun delete(connection: CloudConnection, job: String): Boolean = try {
        http.exchange(connection, "DELETE", "/v1/jobs/$job").status in setOf(200, 404, 410)
    } catch (_: IOException) {
        false
    } catch (_: CloudFailure) {
        false
    }

    private fun parse(reply: CloudReply): JSONObject = JSONObject(reply.body.decodeToString(throwOnInvalidSequence = true))

    private fun checkStatus(value: JSONObject, job: String) {
        if (value.getString("id") != job || value.getInt("protocol") != 1 || value.getString("task") != EXPORT_TASK) {
            throw CloudFailure()
        }
    }
}
