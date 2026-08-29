package com.hermes.app

import com.hermes.shared.HermesJson
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.serialization.Serializable

sealed interface UploadOutcome {
    data class Success(val path: String, val note: String) : UploadOutcome
    data class Failure(val statusCode: Int, val message: String) : UploadOutcome
}

sealed interface DownloadOutcome {
    data class Success(val bytes: ByteArray) : DownloadOutcome
    data class Failure(val statusCode: Int, val message: String) : DownloadOutcome
}

/** [ChatConversationState]가 가짜 다운로드 클라이언트로 유닛테스트할 수 있게 뽑아낸
 * 인터페이스(`HttpTransport`/`SseTransport`와 같은 패턴) — 실제 구현은 [FileUploadClient]
 * 하나뿐이다(업로드/다운로드 둘 다 같은 서버, 같은 인증). */
interface MediaDownloadClient {
    fun downloadGenerated(tool: String, filename: String): DownloadOutcome
}

@Serializable
private data class UploadResponseBody(
    val path: String? = null,
    val note: String? = null,
    val error: String? = null,
)

/**
 * 별도 업로드 서버(`upload-server/`, 게이트웨이와 무관한 독립 프로세스)에 파일을
 * multipart/form-data로 올린다. 인증은 게이트웨이가 쓰는 것과 동일한 Bearer API
 * 키를 재사용한다(설계 문서 §보안 고려 — 같은 신뢰 경계, LAN 전용, 1인 사용).
 */
class FileUploadClient(
    private val uploadServerUrl: () -> String,
    private val apiKey: () -> String,
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 120_000,
) : MediaDownloadClient {
    /** `upload-server`의 `GET /generated/{tool}/{filename}`을 호출한다 — [tool]/[filename]은
     * 서버가 이미 `sanitize_filename`으로 정리한 이름에서 온 값이라(`ChatReducer.parseGeneratedMediaPath`
     * 참고) URL 인코딩 없이 그대로 이어붙인다(안전한 문자셋만 나온다는 서버 쪽 보장에 기댐). */
    override fun downloadGenerated(tool: String, filename: String): DownloadOutcome {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL("${uploadServerUrl().trimEnd('/')}/generated/$tool/$filename")
                .openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.setRequestProperty("Authorization", "Bearer ${apiKey()}")

            val status = connection.responseCode
            if (status !in 200..299) {
                val errText = connection.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
                return DownloadOutcome.Failure(status, errText.ifBlank { "다운로드 실패" })
            }
            DownloadOutcome.Success(connection.inputStream.use { it.readBytes() })
        } catch (e: Exception) {
            DownloadOutcome.Failure(0, "네트워크 오류: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            connection?.disconnect()
        }
    }

    fun upload(fileName: String, mimeType: String, bytes: ByteArray): UploadOutcome {
        val boundary = "HermesUpload-${UUID.randomUUID()}"
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(uploadServerUrl().trimEnd('/') + "/upload")
                .openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer ${apiKey()}")
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

            connection.outputStream.use { out -> writeMultipartBody(out, boundary, fileName, mimeType, bytes) }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
            parseResponse(status, text)
        } catch (e: Exception) {
            UploadOutcome.Failure(0, "네트워크 오류: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseResponse(status: Int, text: String): UploadOutcome {
        val parsed = runCatching {
            HermesJson.decodeFromString(UploadResponseBody.serializer(), text)
        }.getOrNull()

        if (status !in 200..299) {
            return UploadOutcome.Failure(status, parsed?.error ?: text.ifBlank { "업로드 실패" })
        }
        val path = parsed?.path
            ?: return UploadOutcome.Failure(status, "응답에서 path를 찾을 수 없음: $text")
        return UploadOutcome.Success(path, parsed.note ?: "")
    }

    private fun writeMultipartBody(
        out: OutputStream,
        boundary: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ) {
        val crlf = "\r\n"
        val safeName = fileName.substringAfterLast('/').substringAfterLast('\\').replace("\"", "")
        val header = "--$boundary$crlf" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"$safeName\"$crlf" +
            "Content-Type: ${mimeType.ifBlank { "application/octet-stream" }}$crlf$crlf"
        out.write(header.toByteArray(StandardCharsets.UTF_8))
        out.write(bytes)
        out.write("$crlf--$boundary--$crlf".toByteArray(StandardCharsets.UTF_8))
    }
}
