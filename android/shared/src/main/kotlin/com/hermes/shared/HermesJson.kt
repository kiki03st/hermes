package com.hermes.shared

import kotlinx.serialization.json.Json

/** 앱/워치가 공유하는 단일 JSON 설정 — 인코더마다 다른 설정을 쓰면 필드 하나 어긋나도
 * 조용히 깨진다. */
val HermesJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
