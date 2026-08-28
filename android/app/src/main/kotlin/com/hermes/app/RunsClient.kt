package com.hermes.app

import com.hermes.shared.HermesApi
import com.hermes.shared.HermesJson
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

sealed interface RunStartOutcome {
    data class Started(val runId: String) : RunStartOutcome
    data class Failure(val statusCode: Int, val message: String) : RunStartOutcome
}

sealed interface ApprovalOutcome {
    data class Resolved(val choice: String, val resolved: Int) : ApprovalOutcome
    data class Failure(val statusCode: Int, val message: String) : ApprovalOutcome
}

/** [sessionId]는 대화(단기 트랜스크립트) 스코프다 — Hermes 게이트웨이 소스
 * (`gateway/platforms/api_server.py`의 `_handle_runs`) 확인 결과, `/v1/runs`가 대화를
 * 이어가는 진짜 키는 이 body 필드다. **`previous_response_id`가 아니다** — 그 필드는
 * `/v1/responses` 엔드포인트 전용 별도 저장소(`_response_store`)를 찾는 것이라 여기 run_id를
 * 넣어봐야 그 저장소에 없어서 조용히 무시된다(실측 확인, 2026-08-29 — `previous_response_id`로
 * 처음 고쳤을 때도 게이트웨이 로그에 `history=0`이 계속 찍혀서 원인 재조사 후 정정).
 * 같은 [sessionId]를 계속 보내면 이어지고, 안 보내면(null) 매번 새 대화(서버가 `run_id`를
 * 세션 id로 대신 씀). */
@Serializable
private data class StartRunRequest(
    val input: String,
    @SerialName("session_id") val sessionId: String? = null,
)

@Serializable
private data class StartRunResponse(
    @SerialName("run_id") val runId: String? = null,
    val status: String? = null,
)

@Serializable
private data class ApprovalRequestBody(val choice: String, val all: Boolean = false)

/** 필드는 실측 확인(2026-08-28) — `POST /v1/runs/{id}/approval`을 이 PC에 뜬
 * 게이트웨이에 실제로 쳐서 `{"object":"hermes.run.approval_response","run_id",
 * "choice","resolved"}`를 그대로 받았다. */
@Serializable
private data class ApprovalResponseBody(
    @SerialName("run_id") val runId: String? = null,
    val choice: String? = null,
    val resolved: Int? = null,
)

/**
 * `/v1/runs` 계열 클라이언트 — 승인 게이트 2차(MCP 트러스트 게이트)는 **이 경로에만**
 * 있다(`/v1/chat/completions`엔 승인 세션이 없다 — 계획 §B). CAD 쓰기 도구를 폰에서
 * 승인받으려면 폰이 이 클라이언트로 대화를 시작해야 한다.
 *
 * 이 클래스가 다루는 프로토콜 전체(요청/응답 모양, SSE 이벤트 필드, 트러스트 게이트가
 * 실제로 승인을 요구하고 재개하는 왕복)는 이 PC에 실제로 띄운 Hermes 게이트웨이를
 * 상대로 실측 확인했다(2026-08-28) — 소스만 읽고 추측한 게 아니다. `RunEvent`의
 * 클래스 문서에 실측 로그 요약이 있다.
 */
class RunsClient(
    private val transport: HttpTransport,
    private val sse: SseTransport,
    private val serverUrl: () -> String,
    private val apiKey: () -> String,
) {
    fun startRun(input: String, sessionKey: String? = null, sessionId: String? = null): RunStartOutcome {
        val body = HermesJson.encodeToString(
            StartRunRequest.serializer(),
            StartRunRequest(input, sessionId),
        )
        val headers = authHeaders() + sessionKeyHeaders(sessionKey)
        val result = transport.postJson(baseUrl() + HermesApi.RUNS_PATH, headers, body)

        if (result.statusCode !in 200..299) {
            return RunStartOutcome.Failure(result.statusCode, result.body)
        }
        val runId = runCatching {
            HermesJson.decodeFromString(StartRunResponse.serializer(), result.body)
        }.getOrNull()?.runId
        return if (runId != null) {
            RunStartOutcome.Started(runId)
        } else {
            RunStartOutcome.Failure(result.statusCode, "run_id를 응답에서 못 찾음: ${result.body}")
        }
    }

    /**
     * [runId]의 SSE 이벤트를 [Flow]로. [sse]의 `open()`은 블로킹이고, 이 함수는 호출자의
     * 코루틴 컨텍스트에서 그대로 실행한다 — **어느 디스패처에서 돌릴지는 여기서 정하지
     * 않는다.** 실제 UI 코드에서 수집할 때 `.flowOn(Dispatchers.IO)`를 붙일 것.
     *
     * 이렇게 나눈 이유: 처음엔 `launch(Dispatchers.IO) { ... }`로 내부에서 직접 디스패치
     * 했었는데, JVM 단위테스트(`runTest` + 가짜 동기 전송)에서 가상 시간 스케줄러와
     * 실제 스레드가 뒤섞이며 `withTimeout`이 실제 작업이 끝나기도 전에 타임아웃을
     * 던지는 문제가 있었다(실측) — 디스패처 선택을 호출자에게 넘기니 테스트는 순수
     * 가상 시간으로, 프로덕션은 `flowOn`으로 각자 필요한 대로 돈다.
     */
    fun events(runId: String): Flow<RunEvent> = callbackFlow {
        val reader = SseFrameReader()
        var cancelConnection: (() -> Unit)? = null

        sse.open(
            url = baseUrl() + HermesApi.runEventsPath(runId),
            headers = authHeaders(),
            onConnected = { cancel -> cancelConnection = cancel },
            onLine = { line ->
                val payload = reader.feed(line)
                if (payload != null) {
                    RunEvent.parse(payload)?.let { trySend(it) }
                }
            },
            onError = { close(it) },
        )
        close()

        awaitClose { cancelConnection?.invoke() }
    }

    fun approve(runId: String, choice: String, resolveAll: Boolean = false): ApprovalOutcome {
        val body = HermesJson.encodeToString(
            ApprovalRequestBody.serializer(),
            ApprovalRequestBody(choice = choice, all = resolveAll),
        )
        val result = transport.postJson(baseUrl() + HermesApi.runApprovalPath(runId), authHeaders(), body)
        if (result.statusCode !in 200..299) {
            return ApprovalOutcome.Failure(result.statusCode, result.body)
        }
        val parsed = runCatching {
            HermesJson.decodeFromString(ApprovalResponseBody.serializer(), result.body)
        }.getOrNull()
        val choiceResult = parsed?.choice
        val resolved = parsed?.resolved
        return if (choiceResult != null && resolved != null) {
            ApprovalOutcome.Resolved(choiceResult, resolved)
        } else {
            ApprovalOutcome.Failure(result.statusCode, "승인 응답을 해석할 수 없습니다: ${result.body}")
        }
    }

    /** 실행 중인 run을 중단한다. 요청 바디가 필요 없어 빈 JSON 객체를 보낸다. */
    fun stop(runId: String): Boolean {
        val result = transport.postJson(baseUrl() + HermesApi.runStopPath(runId), authHeaders(), "{}")
        return result.statusCode in 200..299
    }

    private fun baseUrl(): String = serverUrl().trimEnd('/')

    private fun authHeaders(): Map<String, String> = mapOf("Authorization" to "Bearer ${apiKey()}")

    private fun sessionKeyHeaders(sessionKey: String?): Map<String, String> =
        if (sessionKey != null) mapOf(HermesApi.SESSION_KEY_HEADER to sessionKey) else emptyMap()
}
