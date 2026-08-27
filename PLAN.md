# Hermes 클라이언트 + 건축 CAD 파이프라인

## Context

**Hermes Agent는 내가 만들 물건이 아니다.** [Nous Research가 만든 오픈소스 셀프호스팅 에이전트](https://github.com/NousResearch/hermes-agent) (MIT, 2026-02)로, 영구 메모리·자가생성 스킬·크론·메시징 게이트웨이·OpenAI 호환 API 서버를 이미 갖고 있다. 설치해서 쓴다.

내가 만들 것은 **그 앞에 붙는 것들**이다:
1. **갤럭시 워치 앱** — 음성으로 에이전트에 명령
2. **안드로이드 폰 앱** — 채팅·세션·이미지 확인·쓰기 승인, 그리고 워치의 네트워크 게이트웨이
3. **`acad-assist` MCP** — 기성 AutoCAD MCP에 없는 조회·수정·캡처·내보내기·승인 게이트
4. **Hermes 구성 + 파이프라인 스킬** — MCP 5개를 하나의 작업 흐름으로 묶는 설정

최종 목표 흐름:

```
"3×4m 원룸 평면 그리고 모델링해서 V-Ray로 렌더 걸어줘"
   AutoCAD (2D 도면)  →  SketchUp Pro (3D 모델)  →  3ds Max + V-Ray (렌더)
```

그리고 그 옆에 일상 기능 — 일정 추가, 웹 검색, 이미지 생성.

### 확정된 제약

| 항목 | 값 |
|---|---|
| 서버 | 내 PC (Windows 10 Pro, RAM 32GB), Hermes **네이티브 설치** |
| 1차 테스트망 | 같은 Wi-Fi 내부 IP → 이후 Cloudflare Tunnel |
| 워치 | Galaxy Watch 4+ (Wear OS), **폰 테더링 전제**. 서버 직접 호출 안 함 |
| 워치 입출력 | 입력 = 음성 STT / 출력 = 화면 텍스트 + TTS + 진동 + 알림 |
| 스트리밍 | 폰 = 스트리밍 / 워치 = 완성본만 |
| CAD | AutoCAD 학생판(정식 전 기능), SketchUp Pro, 3ds Max 2023~2027, V-Ray |
| 쓰기 승인 | CAD 쓰기 작업은 폰에서 승인 |
| **MVP 합격선** | **워치에 말하면 구글 캘린더에 일정이 들어간다** |

---

## 조사로 확정한 사실

### Hermes API 서버 (우리 앱이 붙을 표적)

`~/.hermes/.env` 에 `API_SERVER_ENABLED=true`, `API_SERVER_KEY=<토큰>` 넣고 `hermes gateway` 실행. 기본 `127.0.0.1:8642`. 모든 요청에 `Authorization: Bearer <API_SERVER_KEY>`.

| 엔드포인트 | 용도 | 우리 쓰임 |
|---|---|---|
| `GET /health` | 라이브니스 | 폰 설정화면 연결 테스트 |
| `POST /v1/chat/completions` | OpenAI 호환, SSE 스트리밍 + `hermes.tool.progress` 커스텀 이벤트 | 폰 채팅 |
| `POST /v1/runs` → `GET /v1/runs/{id}/events` (SSE) | 장시간 실행 + 도구·수명주기 이벤트 | **렌더 같은 장기 작업** |
| `POST /v1/runs/{id}/stop` | 중단 | 폰 취소 버튼 |
| `POST /v1/runs/{id}/approval` | 승인 게이트 해소 | **CAD 쓰기 승인** |
| `GET/POST /api/sessions`, `POST /api/sessions/{id}/chat/stream` | 세션 CRUD + SSE | 폰 세션 목록·이어하기 |
| `GET /api/jobs`, `POST /api/jobs` | 크론 잡 | 선택 단계 |
| `GET /v1/toolsets`, `GET /v1/skills` | 도구/스킬 조회 | 진단용 |

헤더: `X-Hermes-Session-Id`(대화 스코프), `X-Hermes-Session-Key`(장기 메모리 스코프).
동시 실행 상한 `max_concurrent_runs` 기본 10, 초과 시 **429**.

`API_SERVER_HOST=0.0.0.0` 으로 바꿔야 폰에서 LAN 접속된다.

### MCP 지원

`~/.hermes/config.yaml` 의 `mcp_servers` 에 등록. **stdio + HTTP/StreamableHTTP + SSE** 지원, **서버별 도구 필터링** 지원, `hermes mcp add --preset` CLI 있음.

### 붙일 MCP 목록

| 단계 | MCP | 방식 | 판정 |
|---|---|---|---|
| 2D 작도 | [`daobataotie/CAD-MCP`](https://github.com/daobataotie/CAD-MCP) **MIT** | pywin32 COM | 채택. `draw_line`·`draw_circle`·`draw_arc`·`draw_polyline`·`draw_rectangle`·`draw_text`·`draw_hatch`·`add_dimension`·`save_drawing`. **`process_command`는 필터로 차단**(SendCommand 비동기라 레이스) |
| 2D 보조 | **`acad-assist` (신규 구현)** | pywin32 COM | 조회·수정·캡처·내보내기·승인. CAD-MCP에 전부 없음 |
| 3D 모델링 | [`mhyrr/sketchup-mcp`](https://github.com/mhyrr/sketchup-mcp) | Ruby 확장 TCP 서버 + Python MCP | 채택. 컴포넌트·재질·씬 조회·선택 + **Ruby 임의 실행** |
| 렌더 | [`cl0nazepamm/3dsmax-mcp`](https://github.com/cl0nazepamm/3dsmax-mcp) **MIT** | 네이티브 C++ 브리지, stdio | 채택. 도구 151개(core 87). `render_scene`·`smart_import`·`merge_from_file`·`execute_maxscript`. **Max 2023–2027** |
| 일정 | [`nspady/google-calendar-mcp`](https://github.com/nspady/google-calendar-mcp) | stdio, OAuth | 채택. 다계정·다캘린더 |
| 웹 검색 | **Hermes 내장** (Brave/Tavily/Exa/SearXNG 등 8종) | — | 만들 필요 없음. API 키만 |
| 이미지 생성 | **Hermes 내장** | — | 만들 필요 없음 |

### AutoCAD 3D MCP은 만들지 않는다

앞서 AutoCAD ActiveX에 3D 솔리드가 다 있음을 확인했다 ([`AddExtrudedSolid`](https://help.autodesk.com/cloudhelp/2016/ENU/AutoCAD-ActiveX/files/GUID-B9DEA4C5-EDAA-4CC6-93B0-394D5991A0E6.htm), [`Boolean`](https://help.autodesk.com/cloudhelp/2019/ENU/AutoCAD-ActiveX-Reference/files/GUID-5D961150-1635-45ED-99CC-3C0222FDB2C3.htm)). **그런데 파이프라인이 확정되면서 필요가 사라졌다** — 3D는 SketchUp Pro가 맡는다. AutoCAD는 2D 도면 전담.

덤으로 앞서 걱정했던 **엔티티 핸들 공유 문제도 소멸했다**. 단계 사이 접합이 COM 객체 참조가 아니라 **파일(DWG → SKP → 렌더)** 이기 때문이다. 프로세스가 나뉘어도 상관없다.

### V-Ray 전용 MCP은 존재하지 않는다 — 필요도 없다

V-Ray는 3ds Max 안에서 도는 렌더러 플러그인이다. `execute_maxscript` 로 제어한다:

```maxscript
renderers.current = V_Ray()
rendOutputFilename = @"C:\hermes-projects\room01\03-render\persp_4k.png"
rendSaveFile = true
render vfb:false outputSize:[3840,2160]
```

다만 `3dsmax-mcp`의 `render_scene`은 뷰포트 수준이고 렌더-투-파일·렌더러 선택·렌더 설정 전용 도구가 **없다**. 프로덕션 렌더는 전부 MAXScript 경유가 된다. → **Stage 5에서 우리가 Hermes 스킬로 MAXScript 템플릿을 고정**해서 LLM이 매번 스크립트를 지어내지 않게 한다.

---

## 아키텍처

```
┌──────────────────────────────┐
│ Galaxy Watch (Wear OS 3+)      Kotlin + Compose for Wear
│  RecognizerIntent 음성 STT
│  결과: 화면 / TTS / 진동 / 알림
└─────────────┬────────────────┘
              │ Wearable Data Layer API (Bluetooth)
┌─────────────┴────────────────┐
│ Phone (Android)                Kotlin + Compose
│  · WearableListenerService  ← 앱 꺼져도 시스템이 깨움
│  · 채팅 UI (SSE 스트리밍 + 도구 진행 칩)
│  · 세션 목록 / 이미지 뷰어 / 승인 다이얼로그
│  · 설정: 서버 URL, API 키
└─────────────┬────────────────┘
              │ HTTPS + Bearer
              │ 1차: http://<LAN IP>:8642  →  이후: Cloudflare Tunnel
┌─────────────┴──────────────────────────────────────────────┐
│ PC — Windows 10, Hermes Agent (네이티브)                    │
│                                                            │
│  API 서버 :8642   내장: 웹검색 · 이미지생성 · TTS/STT       │
│                          메모리 · 스킬 · 크론                │
│                                                            │
│  mcp_servers:                                              │
│    ├ acad2d        CAD-MCP (COM)          ─┐               │
│    ├ acad-assist   신규 구현 (COM)         ─┴→ AutoCAD      │
│    ├ sketchup      sketchup-mcp (TCP)      ──→ SketchUp Pro │
│    ├ max3d         3dsmax-mcp (C++ bridge) ──→ 3ds Max+V-Ray│
│    └ calendar      google-calendar-mcp     ──→ Google       │
│                                                            │
│  프로젝트 폴더 규약으로 단계 간 파일 전달                    │
└────────────────────────────────────────────────────────────┘
```

### 단계 간 접합 규약 (파이프라인의 실제 뼈대)

MCP끼리 직접 대화하지 않는다. **약속된 폴더 구조로 파일을 넘긴다.** 이 규약을 Hermes 영구 메모리에 심고 스킬에 박아둔다.

```
C:\hermes-projects\<project>\
├─ 01-cad\      plan.dwg          AutoCAD 산출물
├─ 02-model\    model.skp
│               model.fbx         3ds Max로 넘길 때
├─ 03-render\   persp_4k.png
└─ meta.json    { project, stage, artifacts[], updated_at }
```

각 단계 도구는 자기 산출물 경로를 `meta.json` 에 기록한다. 다음 단계는 거기서 읽는다. 사람이 중간에 끼어들어 파일을 바꿔치기해도 흐름이 이어진다.

**접합부 주의:**
- AutoCAD → SketchUp: SketchUp **Pro** 의 DWG 임포터 사용. `model.import(path)` 를 Ruby로 호출
- SketchUp → 3ds Max: Max가 `.skp` 를 직접 임포트한다. `smart_import`(메시 폴더 일괄용)가 아니라 `execute_maxscript` 의 `importFile @"...model.skp" #noPrompt` 를 쓴다
- 단위계: AutoCAD mm ↔ SketchUp mm ↔ Max System Unit. **Stage 4 첫 작업이 단위 통일 확인**

### 도구 수 폭발 대응

3dsmax-mcp만 151개다. 전부 노출하면 LLM 도구 선택이 무너진다. Hermes의 **서버별 도구 필터링**으로 깎는다.

| 서버 | 원본 | 노출 |
|---|---|---|
| `acad2d` | 10 | 9 (`process_command` 차단) |
| `acad-assist` | — | 8 |
| `sketchup` | ~12 | ~12 |
| `max3d` | 151 (core 87) | **~25** (query·objects·materials·viewport·external files·scripting만) |
| `calendar` | ~8 | ~6 |
| 합계 | | **~60** |

---

## 우리가 만드는 것

### 1. `acad-assist` MCP (Python, stdio)

CAD-MCP이 못 하는 것만 채운다. 그리기는 안 한다.

| 도구 | 하는 일 |
|---|---|
| `acad_status` | 연결 상태, 열린 도면, 레이어 목록, 현재 단위 |
| `acad_query` | 엔티티 목록 — 핸들·타입·레이어·경계상자. 필터 지원 |
| `acad_get` | 핸들로 상세 조회 (좌표, 치수값, 속성) |
| `acad_modify` | move / copy / rotate / scale / offset / erase — **승인 필요** |
| `acad_layer` | 생성·전환·색상·동결 — **승인 필요** |
| `acad_capture` | 현재 뷰 PNG. `PublishToWeb PNG.pc3` 플로터로 출력 |
| `acad_export` | DWG / DXF / PDF 저장·내보내기 — **승인 필요** |
| `acad_purge_check` | 저장 전 점검 (미저장 변경, 잠긴 레이어) |

**승인 게이트 설계** — 두 겹으로 간다:
1. *1차(항상 동작)*: 쓰기 도구는 `confirm` 인자 기본 `false`. `false`면 **실행하지 않고** 무엇을 할지 요약 + 영향 엔티티 수 + 미리보기 PNG를 반환한다. 에이전트가 그걸 사용자에게 보여주고, 사용자가 "ㅇㅋ" 하면 `confirm=true` 로 재호출한다. Hermes 승인 API에 의존하지 않아 **무조건 동작**한다.
2. *2차(가능하면)*: Hermes의 `/v1/runs/{id}/approval` 을 폰 앱 승인 다이얼로그에 연결. Stage 3에서 실제 동작 여부 확인 후 채택.

**COM 취급 주의:**
- `win32com.client.Dispatch("AutoCAD.Application")` — AutoCAD가 안 떠 있으면 자동 실행됨
- COM은 STA다. MCP 서버는 **단일 워커 스레드에 모든 호출을 직렬화**한다
- AutoCAD 재시작/도면 닫힘 시 COM 포인터가 죽는다 → 호출마다 살아있는지 확인하고 죽었으면 재연결
- 좌표는 `VARIANT` 배열이어야 한다 (`pyautocad`/`pyacadcom`의 `aDouble` 헬퍼 방식 차용)
- 학생판은 DWG에 **교육용 플롯 스탬프**가 박힌다. 상업용 산출물로 못 씀 — 명시만 하고 넘어감

### 2. 폰 앱 (Kotlin + Jetpack Compose)

- 채팅: `POST /v1/chat/completions` SSE. `hermes.tool.progress` 이벤트를 도구 진행 칩으로 표시
- 장기 작업: `POST /v1/runs` → `/events` SSE 구독, 취소 버튼은 `/stop`
- 세션: `/api/sessions` 목록·이어하기·제목수정·삭제
- 이미지: CAD 캡처·SketchUp 뷰·V-Ray 렌더 결과 뷰어 (핀치 줌)
- 승인 다이얼로그: 미리보기 PNG + 영향 요약 + [승인] / [취소]
- 워치 릴레이: `WearableListenerService`
- 설정: 서버 URL, API 키, 연결 테스트

### 3. 워치 앱 (Kotlin + Compose for Wear)

- 메인 화면: 큰 마이크 버튼 하나
- `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` → 텍스트
- Data Layer로 폰에 전송, 진행 상태 수신 표시
- 결과: `ScalingLazyColumn` 텍스트 + `TextToSpeech` 낭독 + `VibrationEffect` + 알림
- 장기 작업이면 즉시 "렌더 거는 중" 표시 후 화면 닫아도 됨. 완료 시 알림으로 재호출

**Data Layer 경로:**

| 경로 | 방향 | 페이로드 |
|---|---|---|
| `/hermes/request` | 워치 → 폰 | `{req_id, text}` |
| `/hermes/status` | 폰 → 워치 | `{req_id, stage, label}` |
| `/hermes/response` | 폰 → 워치 | `{req_id, ok, text, has_image, run_id?}` |
| `/hermes/approval` | 폰 → 워치 | `{req_id, summary}` (승인은 폰에서만) |
| `/hermes/image` (Asset) | 폰 → 워치 | 축소 썸네일 |

---

## 리포지토리 구조

```
hermes/
├─ mcp-acad-assist/
│  ├─ pyproject.toml
│  ├─ src/acad_assist/
│  │  ├─ server.py        # MCP stdio 서버, 도구 등록
│  │  ├─ com.py           # COM 연결·재연결·STA 직렬화·VARIANT 헬퍼
│  │  ├─ query.py         # acad_status / acad_query / acad_get
│  │  ├─ modify.py        # acad_modify / acad_layer
│  │  ├─ capture.py       # PublishToWeb PNG.pc3 플롯
│  │  ├─ export.py        # DWG/DXF/PDF
│  │  └─ confirm.py       # 승인 게이트 (미리보기 → confirm)
│  └─ tests/              # COM 목(mock) 기반 단위 테스트
├─ android/
│  ├─ settings.gradle.kts
│  ├─ gradle/libs.versions.toml
│  ├─ shared/             # 데이터 모델, Data Layer 경로 상수
│  ├─ app/                # 폰
│  └─ wear/               # 워치
├─ hermes-config/
│  ├─ config.yaml.example # mcp_servers + 도구 필터 + 게이트웨이 설정
│  ├─ env.example
│  └─ skills/
│     └─ cad-pipeline.md  # 도면→모델→렌더 절차 스킬
├─ vendor/                # 기성 MCP 클론 (setup 스크립트로 받음)
└─ docs/
   └─ setup-windows.md    # AutoCAD/SketchUp/Max 애드온 설치 절차
```

### 안드로이드 빌드 환경 (확인 완료)

- Java 21 (설치됨) / Gradle **9.3.1** (`~/.gradle/wrapper/dists`에 이미 캐시됨 — 래퍼 재사용)
- AGP 9.1.1 기준 (`~/AndroidStudioProjects/exam_test` 와 동일)
- Android SDK: `~/AppData/Local/Android/Sdk`, platforms `android-36.1` 사용
- **`ANDROID_HOME` 미설정** → `android/local.properties` 에 `sdk.dir` 명시 필요

---

## 구현 단계

각 단계는 **끝나면 실제로 쓸 수 있는 상태**로 끝난다. 다음 단계 전 확인받는다.

### Stage 0 — Hermes 세우기 (앱 코드 0줄)
목적: **앱을 만들기 전에 에이전트가 먼저 일하게 한다.** 여기서 막히면 앱은 의미 없다.

1. Windows 네이티브 설치: `iex (irm https://hermes-agent.nousresearch.com/install.ps1)`
2. LLM 프로바이더 설정 (Anthropic / OpenRouter 중 택1), 웹 검색 백엔드 키, 이미지 생성 설정
3. API 서버: `API_SERVER_ENABLED=true`, `API_SERVER_KEY=<랜덤 32자>`, `API_SERVER_HOST=0.0.0.0`
4. `google-calendar-mcp` 등록 + Google OAuth 1회 통과
5. Windows 방화벽 8642 인바운드 허용 (사설망만)

**검증**: PC 터미널에서
```
curl -H "Authorization: Bearer $KEY" http://<LAN IP>:8642/health
curl -X POST .../v1/chat/completions -d '{"model":"hermes-agent","messages":[{"role":"user","content":"내일 오후 3시 치과 예약 캘린더에 넣어줘"}]}'
```
→ 구글 캘린더 웹에 이벤트가 실제로 보일 것. 폰 브라우저에서도 `/health` 접근될 것.

### Stage 1 — MVP: 워치 발화 → 캘린더 ★
목적: 워치→폰→Hermes 전 구간을 한 번 관통. 기능은 일정 하나뿐.

1. `android/` 3모듈 Gradle 프로젝트 생성 (`shared`/`app`/`wear`), `local.properties` 세팅
2. 폰: `WearableListenerService` + Hermes `/v1/chat/completions`(비스트리밍) 호출 + 최소 상태 화면 + 설정(URL/키)
3. 워치: 마이크 버튼 → STT → Data Layer 전송 → 결과 텍스트 + 진동 + TTS
4. `source=watch` 구분: 시스템 프롬프트 대신 **요청 메시지 앞에 "2문장 이내로 답해라" 프리앰블**을 폰이 붙인다 (Hermes는 프론트엔드 `instructions` 레이어링을 지원)

**검증**: 워치에 *"내일 오후 3시 치과 예약 잡아줘"* → 캘린더 확인 → 워치가 *"내일 15시 치과 예약 등록했어요"* 낭독 + 진동.

### Stage 2 — 폰 정식 앱
- SSE 스트리밍 채팅, 도구 진행 칩, 세션 목록·이어하기
- 이미지 뷰어 (이후 단계 결과물을 볼 통로를 미리 뚫는다)
- 승인 다이얼로그 뼈대
- 앱 강제종료 상태에서 워치 명령 동작 확인 (서비스 수명주기 + 삼성 배터리 최적화 예외 안내)

**검증**: 폰 다중 턴 대화 중 글자가 흘러나옴. 앱 스와이프 종료 후에도 워치 명령 동작.

### Stage 3 — AutoCAD 2D
1. `vendor/CAD-MCP` 클론, `config.yaml` 에 등록, **`process_command` 필터 차단**
2. `mcp-acad-assist` 구현 — COM 계층 → 조회 → 캡처 → 수정 → 내보내기 순
3. 승인 게이트 1차(도구 내부 `confirm`) 구현. Hermes `/v1/runs/{id}/approval` 실동작 확인해서 되면 2차도 연결
4. 프로젝트 폴더 규약을 Hermes 메모리에 저장

**검증**: 폰에서 *"3×4m 방 평면 그리고 벽 두께 200 표시해줘"* → AutoCAD에 도형 생성 → **승인 요청이 폰에 뜸** → 승인 → `01-cad/plan.dwg` 저장 → 캡처 PNG가 폰에 표시.

### Stage 4 — SketchUp Pro
1. `sketchup-mcp` Ruby 확장 설치, TCP 서버 기동, MCP 등록
2. **단위계 통일 검증** (AutoCAD mm ↔ SketchUp mm)
3. DWG 임포트 → 벽 돌출 → `.skp` 저장 → `.fbx` 내보내기 절차를 Ruby 스니펫으로 고정
4. 뷰 캡처 → 폰 표시

**검증**: *"방금 그 도면 3m 높이로 세워줘"* → `01-cad/plan.dwg` 를 임포트해 3D 벽 생성 → `02-model/model.skp` 저장 → 아이소메트릭 캡처가 폰에 도착.

### Stage 5 — 3ds Max + V-Ray
1. `3dsmax-mcp` 네이티브 브리지 설치 (Max 2023–2027), 도구 필터로 ~25개만 노출
2. **MAXScript 템플릿을 Hermes 스킬로 고정** — 임포트 / 카메라 / 조명 / V-Ray 설정 / 렌더-투-파일. LLM이 매번 지어내지 않게 한다
3. 렌더는 `POST /v1/runs` 로 실행. 폰이 `/events` SSE 구독, 워치엔 완료 알림 push
4. 렌더 시간이 길다 — 프리뷰(저해상도)와 파이널(고해상도) 두 프리셋

**검증**: 워치에 *"아까 모델 V-Ray로 렌더 걸어줘"* → 워치 "렌더 시작, 몇 분 걸려요" + 진동 → 화면 꺼도 됨 → 완료 시 워치 알림 + 진동 + TTS → 폰에서 `03-render/persp.png` 확인.

### Stage 6 — 파이프라인 통합 스킬
1~5단계를 한 문장으로 잇는다. `hermes-config/skills/cad-pipeline.md` 에 절차·폴더 규약·단계별 승인 지점·실패 시 되돌리기를 기술. Hermes 스킬로 등록.

**검증**: *"3×4m 원룸, 창 하나. 도면부터 렌더까지 해줘"* → 3단계가 순서대로 진행되고 **각 단계 전환마다 폰에 승인이 뜬다**. 중간에 [취소] 눌러도 앞 단계 산출물은 남는다.

### Stage 7 — 외부 접속 + 굳히기
- `cloudflared` 터널 → 공인 HTTPS URL. 폰 설정에서 URL만 교체
- API 키 회전, 요청 rate limit, 도구 실행 감사 로그
- Windows 작업 스케줄러로 `hermes gateway` 자동 시작
- 앱 3개(AutoCAD/SketchUp/Max) 미기동 시 해당 MCP만 우아하게 비활성

**검증**: Wi-Fi 끄고 LTE로 Stage 1·3 시나리오 재실행 → 동일 동작.

### Stage 8 — 선택
워치 타일/컴플리케이션 · Hermes 크론잡 UI · 워치 standalone · 렌더 큐 관리.

---

## 위험과 대응

| 위험 | 대응 |
|---|---|
| **`render_scene`이 프로덕션 렌더를 못 함** | MAXScript 템플릿을 Stage 5에서 스킬로 고정. LLM이 스크립트를 즉흥 생성하지 않게 함 |
| **CAD-MCP과 acad-assist가 같은 COM을 두드림** | 역할 분리(그리기 vs 조회·수정·저장) + `process_command` 차단으로 비동기 SendCommand 경로 제거 |
| **단위계 불일치** | Stage 4 첫 작업이 단위 검증. `meta.json` 에 단위 기록 |
| **도구 60개도 많음** | 실사용에서 오선택 나면 단계별 프로파일로 더 쪼갬 (Hermes 프로파일 기능) |
| **AutoCAD/SketchUp/Max 동시 상주 = 메모리** | 32GB면 됨. 단 렌더 중엔 다른 앱 유휴 상태 유지 |
| **학생판 교육용 플롯 스탬프** | DWG에 플래그가 박히고 전파됨. 상업용 산출물 불가 — 문서에 명시 |
| **COM 포인터 사망 (AutoCAD 재시작)** | 호출마다 생존 확인 + 자동 재연결 |
| **폰 앱이 죽어 워치 명령 씹힘** | `WearableListenerService`는 시스템이 깨움. Stage 2에서 강제종료 테스트를 명시적 검증 항목화 |
| **삼성 배터리 최적화가 서비스 종료** | 배터리 최적화 예외 등록 온보딩 |
| **공개 URL 노출** | 긴 랜덤 API 키 + rate limit + (선택) Cloudflare Access. `API_SERVER_HOST=0.0.0.0` 은 터널 뒤에서만 |
| **동시 실행 429** | `max_concurrent_runs` 상향 또는 폰에서 대기열 표시 |

---

## 전체 검증 시나리오 (완성 후)

1. PC에서 `hermes gateway` 기동 → 폰 설정화면 연결 테스트 초록
2. 워치: *"다음주 화요일 오후 2시 스터디 잡아줘"* → 캘린더 확인 → 워치 낭독
3. 폰: *"북유럽 목조주택 입면도 레퍼런스 찾아줘"* → 웹 검색 결과 + 이미지
4. 폰: *"3×4m 원룸 평면 그려줘"* → 승인 → AutoCAD 도면 + 캡처 확인
5. 폰: *"3m로 세워서 모델링해줘"* → 승인 → SketchUp 모델 + 뷰 확인
6. 워치: *"V-Ray로 렌더 걸어줘"* → 승인 → 대기 → 워치 알림 → 폰에서 렌더 확인
7. Wi-Fi 끄고 2·6번 재실행 → 동일 동작

**자동 테스트**: `mcp-acad-assist`는 COM 목 기반 pytest (도구 스키마·승인 게이트·좌표 변환·재연결 로직). 안드로이드는 Data Layer 릴레이와 SSE 파서 단위 테스트.
