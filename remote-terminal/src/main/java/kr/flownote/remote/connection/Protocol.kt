package kr.flownote.remote.connection

import org.json.JSONObject
import java.util.UUID

object Protocol {
    fun message(type: String, payload: JSONObject, requestId: String = UUID.randomUUID().toString()): String =
        JSONObject().put("version", 1).put("type", type).put("requestId", requestId).put("payload", payload).toString()

    fun decode(text: String): JSONObject {
        require(text.toByteArray(Charsets.UTF_8).size <= 256 * 1024) { "출력 메시지가 너무 큽니다." }
        val message = JSONObject(text)
        require(message.getInt("version") == 1) { "지원하지 않는 프로토콜 버전입니다." }
        val payload = message.getJSONObject("payload")
        when (message.getString("type")) {
            "hello.result" -> {
                UUID.fromString(payload.getString("hostId"))
                require(payload.getInt("protocolVersion") == 1)
            }
            "terminal.created", "terminal.attached", "terminal.closed", "terminal.resized", "terminal.exited", "terminal.output" -> {
                UUID.fromString(payload.getString("sessionId"))
                if (message.getString("type") == "terminal.output") {
                    require(payload.getLong("seq") > 0)
                    payload.getString("data")
                }
            }
            "error" -> { payload.getString("code"); payload.getString("message") }
            else -> error("알 수 없는 서버 메시지입니다.")
        }
        return message
    }

    fun inputChunks(text: String): List<String> {
        val result = mutableListOf<String>()
        val chunk = StringBuilder()
        var bytes = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val part = String(Character.toChars(codePoint))
            val size = part.toByteArray(Charsets.UTF_8).size
            // JSON escaping control characters can multiply the wire size by six.
            if (bytes + size > 8 * 1024) { result.add(chunk.toString()); chunk.setLength(0); bytes = 0 }
            chunk.append(part); bytes += size; index += Character.charCount(codePoint)
        }
        if (chunk.isNotEmpty()) result.add(chunk.toString())
        return result
    }
}
