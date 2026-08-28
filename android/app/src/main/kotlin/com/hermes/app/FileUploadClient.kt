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
) {
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
