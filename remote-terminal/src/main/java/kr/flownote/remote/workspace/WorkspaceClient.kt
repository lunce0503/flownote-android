package kr.flownote.remote.workspace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.flownote.remote.connection.HostProfile
import kr.flownote.remote.security.pinnedClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class WorkspaceFailure(val status: Int, message: String) : IOException(message)

class WorkspaceClient(private val profile: HostProfile) {
    suspend fun call(operation: String, input: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        require(operation in setOf("list", "read", "write"))
        val client = pinnedClient(profile).newBuilder().readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
        try {
            val url = profile.endpoint.replaceFirst("wss://", "https://").toHttpUrl().newBuilder().encodedPath("/v1/workspace/$operation").build()
            val request = Request.Builder().url(url).header("Authorization", "Bearer ${profile.token}")
                .post(input.toString().toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw WorkspaceFailure(response.code, when (response.code) {
                    401 -> "기기 인증에 실패했습니다. PC 등록 정보를 확인하세요."
                    403 -> "이 파일은 프로젝트에서 열 수 없습니다."
                    404 -> "파일이 없거나 Host가 코드 편집을 지원하지 않습니다. Host 0.4.0 이상을 확인하세요."
                    409 -> "PC에서 파일이 변경되어 저장하지 않았습니다. 초안을 복사하거나 다시 불러온 뒤 수정하세요."
                    413 -> "파일 또는 폴더가 너무 큽니다. 파일은 256 KiB까지 지원합니다."
                    415 -> "UTF-8 텍스트 파일만 열 수 있습니다."
                    else -> "파일 요청에 실패했습니다 (${response.code}). 저장 결과를 확인한 뒤 다시 시도하세요."
                })
                val source = response.body?.source() ?: throw IOException("빈 파일 응답입니다.")
                source.request(2L * 1024 * 1024 + 1)
                require(source.buffer.size <= 2L * 1024 * 1024) { "파일 응답이 너무 큽니다." }
                JSONObject(source.buffer.readUtf8()).also { require(it.get("version") == 1) }
            }
        } catch (failure: WorkspaceFailure) { throw failure }
        catch (failure: Exception) { throw IOException(if (operation == "write") "저장 결과를 확인하지 못했습니다. PC 파일을 확인한 뒤 다시 시도하세요." else "PC 파일에 연결하지 못했습니다. 연결 상태를 확인하세요.", failure) }
        finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }
}
