package kr.flownote.remote.connection

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.flownote.remote.security.pinnedClient
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PairingClient {
    suspend fun redeem(invitation: PairingInvitation, name: String): HostProfile = withContext(Dispatchers.IO) {
        invitation.checked()
        val client = pinnedClient(invitation.profile("pairing-validation-token", name)).newBuilder()
            .readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
        val url = invitation.endpoint.replaceFirst("wss://", "https://").toHttpUrl().newBuilder().encodedPath("/v1/pair").build()
        val request = Request.Builder().url(url).post(JSONObject().put("pairingCode", invitation.code).toString().toRequestBody("application/json".toMediaType())).build()
        try {
            suspendCancellableCoroutine<HostProfile> { continuation ->
                val call = client.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(IOException("PC 등록에 연결하지 못했습니다. 주소와 인증서 지문을 확인하세요."))
                    }
                    override fun onResponse(call: Call, response: Response) {
                        runCatching {
                            response.use {
                                require(response.code == 200) { if (response.code == 401) "등록 QR이 만료되었거나 이미 사용되었습니다." else "PC 등록 요청이 거부되었습니다 (${response.code})." }
                                val source = response.body?.source() ?: error("빈 등록 응답입니다.")
                                source.request(4097)
                                require(source.buffer.size <= 4096) { "등록 응답이 너무 큽니다." }
                                val json = JSONObject(source.buffer.readUtf8())
                                require(json.get("version") == 1) { "지원하지 않는 등록 응답입니다." }
                                invitation.profile(json.getString("token"), name)
                            }
                        }.onSuccess { if (continuation.isActive) continuation.resume(it) }
                            .onFailure { if (continuation.isActive) continuation.resumeWithException(
                                if (it is IllegalArgumentException) it else IOException("PC 등록 응답을 처리하지 못했습니다.")) }
                    }
                })
            }
        } finally {
            // Closing a pooled TLS socket can send close_notify and must stay off the UI thread.
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
    }
}
