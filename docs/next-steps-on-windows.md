# Windows 이관 후 진행 순서

> PLAN.md의 Stage 0~8 기준 **지금까지 실제로 끝난 것과 안 끝난 것**을 정리한 문서.
> `docs/windows-migration.md`가 "옮길 때 뭐가 다른가"라면, 이 문서는 "옮기고 나서 뭘 계속해야
> 하는가"다. 순서대로 진행하면 된다 — 뒷단계는 앞단계 산출물(파일, MCP 연결)을 전제로 한다.

---

## Stage 0 — Hermes 세우기 — ✅ **완료** (Windows 재현 2026-08-28)

Ubuntu(`kiki-server`)에서 먼저 다 해봤고, **이 Windows PC에서도 처음부터 다시 세워 검증 완료**했다.

실측 검증된 것:

| 항목 | 결과 |
|---|---|
| Hermes 네이티브 설치 | v0.20.6 (`install.ps1`, `-SkipSetup`으로 비대화형 설치) |
| `HERMES_HOME` | `C:\Users\ksy\AppData\Local\hermes` (**`~/.hermes` 아님** — §Windows 실측 참고) |
| Timely 백본 | `model.provider=custom` + `model.api_key`, `agent.max_turns=20` |
| 모델 왕복 | `/v1/chat/completions` 200, 산술 정답, 23초 |
| 툴 호출 | 파일 읽기 도구로 `PLAN.md` 첫 줄 정확히 반환, 8초 |
| `api_server` 툴셋 | `web, file, skills, todo, memory` 만 (browser도 추가로 끔) |
| API 서버 바인딩 | `172.30.1.101:8642` (Wi-Fi NIC 전용, 공인 IP 이더넷은 미바인딩) |
| 인증 | 키 없는 요청 401 거부 |
| 방화벽 | 폰에서 `/health` 200 도달 확인 |
| 게이트웨이 상시 기동 | `hermes gateway install` → 로그온 시작 항목 (`Hermes_Gateway.vbs`) |
| Calendar MCP | `npx.cmd -y @cocal/google-calendar-mcp`, 13개 도구 발견, `manage-accounts` 제외 |
| Google OAuth | CLI `auth` 경로로 동의 완료, 토큰 `C:\Users\ksy\.config\google-calendar-mcp\tokens.json` |
| **최종 합격선** | *"내일 오후 3시 치과 예약 캘린더에 넣어줘"* → **실제 Google 캘린더에 이벤트 생성**, 별도 요청으로 독립 확인 (Event ID `a590lvk7mthmerbqr53u2s5sd8`, 2026-08-29 15:00–16:00 KST) |

토큰 실측: 캘린더 MCP 등록 **전** prompt 9.5K → 등록 **후** 34~39K.

Windows 재현 과정에서 문서와 실제가 어긋난 지점, 새로 발견한 함정은
`docs/windows-migration.md` §7에 모아 놨다 — **다음 세션은 그것부터 읽을 것.**

---

## Stage 1 — MVP: 워치 발화 → 캘린더 — 🟡 워치 실기기만 남음

**끝난 것**: `android/` 3모듈 빌드, 폰 앱(`WearableListenerService`, 비스트리밍 채팅, 설정 화면,
"2문장 이내" 프리앰블), 실제 서버 왕복 검증(캘린더 등록·삭제 성공), 세션 연속성
(`X-Hermes-Session-Id`/`X-Hermes-Session-Key`), 앱 버그 3개 수정(cleartext, 네트워크 예외 크래시,
타임아웃) — 전부 `docs/windows-migration.md` §1.8 참고.

**Windows에서 추가로 끝난 것 (2026-08-28)**:
- 리포를 `C:\hermes`로 이동 (경로에 한글이 있으면 Gradle 유닛테스트가 깨진다 — §7.1)
- `.\gradlew.bat build` **BUILD SUCCESSFUL**, Kotlin 유닛테스트 **15개 통과**
  (app 7+2, shared 2+3, wear 1)
- `mcp-acad-assist` venv + `pip install -e ".[dev]"`, **pytest 18개 통과**
- `android/local.properties`에 `sdk.dir`/`hermes.serverUrl`/`hermes.apiKey` 주입 (§1.9)

**안 끝난 것 (워치 실기기 필수)**:
1. 갤럭시 워치 실기기 페어링, 마이크 버튼 → STT → Data Layer 전송 실동작
2. 워치 TTS 낭독 + 진동 + 결과 화면 표시
3. PLAN.md Stage 1 검증 시나리오 그대로 재현: *"내일 오후 3시 치과 예약 잡아줘"* → 캘린더 확인 →
   워치가 *"내일 15시 치과 예약 등록했어요"* 낭독 + 진동

**폰 단독 검증**: `.\gradlew.bat :app:installDebug`로 설치 후 폰 앱에서 왕복 확인 — 워치 없이
여기까지는 가능하다.

---

## Stage 2 — 폰 정식 앱 — 🟡 승인 다이얼로그·runs 스트리밍은 됨, 나머지는 아직

Stage 1은 의도적으로 "비스트리밍, 채팅 1회, 설정 화면"까지만 스코프였다. Stage 3~6 작업
중(2026-08-28) 승인 게이트 2차가 `/v1/runs`에만 있다는 게 실측으로 확인되면서, 그 경로에
필요한 만큼은 먼저 만들었다:

**끝난 것 (Stage 3~6 작업의 부산물)**:
- `RunsClient`(`POST /v1/runs`, `GET .../events` SSE, `POST .../approval`, `POST .../stop`) —
  **`/v1/chat/completions` 스트리밍이 아니라 `/v1/runs` 스트리밍**으로 만들었다. 원안이 원한
  건 아니지만 실시간 텍스트 표시는 똑같이 되고, 승인 게이트가 이 경로에만 있어서 어차피
  이쪽이 필요했다.
- 승인 다이얼로그 — `approval.request`의 `choices` 배열을 그대로 버튼으로 렌더(하드코딩
  없음). "뼈대"가 아니라 실제로 동작 확인됨(§Stage 3 참고).
- 도구 진행 표시 — `tool.started`/`tool.completed`/`reasoning.available` 이벤트를 상태
  줄로 표시.

**아직 없는 것**:
- `/v1/chat/completions` 자체의 SSE 스트리밍 (지금은 `/v1/runs` 경로로 대체됐지만, 원래
  Stage 1 채팅 섹션은 여전히 비스트리밍이다)
- 세션 목록/이어하기/삭제 (`/api/sessions` CRUD)
- 이미지 뷰어 — **못 만든 이유가 있다**: `/v1/runs` 이벤트 스트림이 도구 호출의 반환값(캡처
  PNG의 `image_base64` 등)을 아예 노출하지 않는다(실측 확인). 그 데이터가 폰까지 올 확정된
  통로가 없어서, 통로 없는 뷰어를 만드는 대신 미해결로 남겨뒀다 —
  `hermes-config/skills/cad-pipeline.md`의 "알려진 공백"과
  `android/app/src/main/kotlin/com/hermes/app/ui/RunsSection.kt` 문서 주석 참고.
- 앱 강제종료 상태에서 워치 명령이 동작하는지 (서비스 수명주기), 삼성 배터리 최적화 예외 온보딩

**검증 기준**: 폰 다중 턴 대화 중 글자가 흘러나옴(`/v1/runs`로 충족). 앱 스와이프 종료 후에도
워치 명령 동작(미검증).

---

## Stage 3 — AutoCAD 2D — 🟡 코드·승인 게이트 완성, COM 실동작만 남음

**끝난 것 (2026-08-28, `feat/stage3-6-cad-pipeline` 브랜치)**: `mcp-acad-assist` 8개 도구
전부 완성(`query.py`/`modify.py`/`capture.py`/`export.py`/`confirm.py`, 읽기 5/쓰기 3으로
분리해 `server.py`에 등록), COM을 목(mock)으로 대체한 pytest **194개**(계획 §A~D 전부).
`AcSaveAsType`은 런타임 타입 라이브러리 조회 + 검증된 폴백(하드코딩 아님), 좌표는 실제
VARIANT(`VT_ARRAY|VT_R8`)로 변환(pywin32가 이 PC에 있어서 래핑 자체는 검증됨), `capture`는
base64 PNG 반환, `export`는 `project=`로 `meta.json` 자동 등록.

**승인 게이트 2차를 이 PC에서 실측으로 완전히 왕복 검증했다** — 트러스트 게이트(`trust:
untrusted`)를 실제로 등록하고 쓰기 도구를 유도해 `approval.request` SSE가 뜨는 것,
`POST /v1/runs/{id}/approval`로 승인하는 것, 이후 도구가 COM 부재로 우아하게 실패하는 것까지
전부 확인했다 — **AutoCAD 없이 승인 게이트 자체는 끝났다.** 폰 다이얼로그도 실제로 이 흐름에
맞춰 동작한다(`RunsSection.kt`).

`hermes-config/config.yaml.example`에 `acad2d`(실측: 도구 11개, `process_command` 차단
근거 정정 — SendCommand 아니라 정규식 파서)/`acad-read`/`acad-write`/`cad-pipeline` 전부
반영 완료.

**안 끝난 것 (전부 실제 AutoCAD 필요)**:
1. `vendor/CAD-MCP` 클론 → 실제 설치·연결 확인 (§`docs/setup-cad-workstation.md`)
2. `capture.py`의 `PlotToFile` — 인자 자체는 맞다고 확인됐지만(2개뿐, §G), 실제 도면에서
   원하는 캡처가 나오는지는 미검증
3. `export.py`의 DXF 저장 — 코드는 완성됐지만 실 AutoCAD로 열리는 파일이 나오는지 미검증
4. 승인 게이트 1차(도구 내부 `confirm`)는 로직 완성, 실 AutoCAD로 스모크 테스트만 남음
5. 프로젝트 폴더 규약은 `projects.py`로 코드 완성(`meta.json` 스키마 확정) — 실사용 검증만 남음

**검증 기준**: 폰에서 *"3×4m 방 평면 그리고 벽 두께 200 표시해줘"* → AutoCAD에 도형 생성 →
**승인 요청이 폰에 뜸** → 승인 → `01-cad/plan.dwg` 저장 → 캡처 PNG가 폰에 표시(단, PNG를
실제로 폰에 "보여주는" 통로는 아직 없다 — Stage 2 참고).

---

## Stage 4 — SketchUp Pro — 🟡 스크립트 생성기 완성, 앱 연결·실동작만 남음

**끝난 것 (2026-08-28)**: `sketchup_scripts.py` — DWG 임포트/벽 압출/skp 저장/fbx 내보내기/
아이소 캡처/단위 점검 6개 Ruby 생성기, 골든 텍스트 테스트로 고정(15개). **단위계는 "통일
검증"이 아니라 "매 값마다 명시적 변환"으로 설계 변경** — 실측 결과 SketchUp Ruby API 내부
길이 단위가 인치라, mm 값을 Python에서 미리 계산하지 않고 SketchUp 자신의 `Numeric#mm`을
스크립트에 심는다(`3000.0.mm`). `config.yaml.example`의 sketchup 블록을 `uvx`(PyPI 고정,
`eval_ruby` 없을 수 있음) 대신 소스 클론으로 교체.

**안 끝난 것 (전부 실제 SketchUp 필요)**:
1. `vendor/sketchup-mcp` 클론·설치, SketchUp에서 "Start Server"로 TCP 9876 기동, MCP 등록
   확인 (§`docs/setup-cad-workstation.md`)
2. 벽 압출 스니펫의 가정("벽 레이어가 닫힌 면으로 임포트된다") 검증 — 실 DWG 도면 관례와
   맞는지 처음 확인
3. 뷰 캡처 → 폰 표시 — Stage 2와 동일한 이유로 통로 미해결(SSE가 도구 결과를 안 실어줌)

**검증 기준**: *"방금 그 도면 3m 높이로 세워줘"* → `01-cad/plan.dwg` 임포트해 3D 벽 생성 →
`02-model/model.skp` 저장 → 아이소메트릭 캡처(폰 표시는 통로 미해결로 보류).

---

## Stage 5 — 3ds Max + V-Ray — 🟡 스크립트 생성기 완성, 앱 연결·실동작만 남음

**끝난 것 (2026-08-28)**: `maxscripts.py` — skp 임포트/카메라/조명/V-Ray 렌더 4개 MAXScript
생성기, JSON 왕복 실제 검증(이중 이스케이프 산수 포함) 25개 테스트. **PLAN.md 원안의 렌더
스니펫이 자기모순이라 동작 안 했던 것을 여기서 발견·정정** — `render()`/`render_scene` 둘 다
Render Setup 대화상자 설정을 무시하므로, `rendSaveFile`/`rendOutputFilename`/`renderWidth`/
`renderHeight` 글로벌을 직접 설정하고 `max quick render`로 트리거하는 경로(경로 2)만 V-Ray
출력 설정을 존중한다. V-Ray 렌더러는 `V_Ray*` 패턴 매칭으로 버전 무관하게 찾는다(버전 고정
클래스명 아님). 렌더 완료는 자기 보고가 아니라 출력 파일 존재로 검증 후 실패 시 `throw`.
`config.yaml.example`의 max3d 블록을 `MCP_TOOL_PROFILE=core` + 실측 도구 이름으로 교체
(151개 중 core 87개, 예전 "~25개" 추정은 근거 없었음 — `execute_maxscript`는 core에 있어
문제없음).

승인 게이트 2차도 `max3d`(`trust: untrusted`)에 그대로 적용된다 — Stage 3와 같은 메커니즘.

**안 끝난 것 (전부 실제 3ds Max + V-Ray 필요)**:
1. `vendor/3dsmax-mcp` 클론·설치(`uv sync && uv run python install.py` → Max 재시작), 도구
   이름 최종 확인(`hermes mcp list`) — 조사 기반 추정이지 실행 중 인스턴스로 검증된 적 없음
2. 카메라/조명 기본값이 실제 장면에서 쓸 만한지 검증
3. V-Ray 실제 렌더링, 출력 파일 검증
4. 렌더 완료 워치 알림 — `render_automations`가 이 용도에 맞지만 `full` 프로파일 필요(지금
   `core`만 켜짐), 필요시 추가

**검증 기준**: 워치에 *"아까 모델 V-Ray로 렌더 걸어줘"* → 워치 "렌더 시작, 몇 분 걸려요" + 진동 →
화면 꺼도 됨 → 완료 시 워치 알림 + 진동 + TTS(미구현) → 폰에서 `03-render/persp_4k.png` 확인
(폰 표시는 통로 미해결로 보류, Stage 2 참고).

---

## Stage 6 — 파이프라인 통합 스킬 — 🟡 문서 완성, 실행 검증만 남음

**끝난 것 (2026-08-28)**: `hermes-config/skills/cad-pipeline.md` 완성 — 실제 등록된 도구
이름 표, 각 단계 절차, 두 겹 승인(1차/2차)이 폰에서 다른 정보를 보여준다는 실측 제약, 실패 시
되돌리기 절차, "알려진 공백" 목록(meta.json 자동 등록 안 됨, 이미지 뷰어 통로 없음 등 — 값을
지어내지 않고 명시). 자기모순이던 MAXScript 렌더 스니펫도 정정된 버전으로 교체.
`docs/setup-cad-workstation.md`(대상 환경 설치 순서) + `docs/verify-cad-workstation.ps1`
(사전 점검 스크립트, ASCII 전용) 신설.

**안 끝난 것**: 실제 CAD 3종으로 전체 파이프라인 관통 검증. 지금까지 나온 코드·문서가 전부
맞는 가정 위에 서 있는지는 이 스모크 테스트가 처음 확인해준다.

**검증 기준**: *"3×4m 원룸, 창 하나. 도면부터 렌더까지 해줘"* → 3단계 순서 진행, **각 단계 전환
마다 폰에 승인** 뜸. 중간에 [거부] 눌러도 앞 단계 산출물은 남음.

---

## Stage 7 — 외부 접속 + 굳히기 — 🟡 임시 버전만 있음

Ubuntu에서 Cloudflare **quick tunnel**(`cloudflared tunnel --url`)로 외부 접속 자체는 검증했지만,
이건 재시작마다 URL이 바뀌고 SLA가 없는 테스트용이다. Windows에서 정식으로:

1. Cloudflare **named tunnel** + 도메인 (또는 Tailscale) — quick tunnel 교체
2. API 키 회전 절차, 요청 rate limit, 도구 실행 감사 로그
3. ~~게이트웨이 자동 시작~~ — ✅ 2026-08-28 완료. `hermes gateway install`이 Windows에서
   작업 스케줄러를 시도하고, UAC 승인이 없으면 시작 폴더(`Hermes_Gateway.vbs`)로 폴백한다.
   지금은 폴백 경로. 승격된 창에서 다시 돌리면 작업 스케줄러로 올라간다 — §7.7
4. AutoCAD/SketchUp/Max 중 미기동 앱이 있어도 해당 MCP만 우아하게 비활성되는지 확인

**검증 기준**: Wi-Fi 끄고 LTE로 Stage 1·3 시나리오 재실행 → 동일 동작.

---

## Stage 8 — 선택 — ⬜ 시작 전

워치 타일/컴플리케이션, Hermes 크론잡 UI, 워치 standalone, 렌더 큐 관리. 필수 아님.

---

## 요약 표

| Stage | 상태 | 남은 핵심 작업 |
|---|---|---|
| 0. Hermes 세우기 | ✅ | **없음 — Windows 재현 완료 (2026-08-28)** |
| 1. MVP 캘린더 | 🟡 | 워치 실기기 테스트만 (빌드·테스트·폰 경로는 Windows에서 통과) |
| 2. 폰 정식 앱 | 🟡 | runs 스트리밍·승인 다이얼로그는 완료. SSE 채팅(chat/completions), 세션 목록, 이미지 뷰어(통로 미해결) 남음 |
| 3. AutoCAD 2D | 🟡 | **코드·승인 게이트 2차 전부 완료(실측 검증됨).** 실 AutoCAD 스모크 테스트만 남음 |
| 4. SketchUp | 🟡 | 스크립트 생성기 완료. 앱 연결·실동작 검증만 남음 |
| 5. 3ds Max+V-Ray | 🟡 | 스크립트 생성기 완료(렌더 경로 정정됨). 앱 연결·실동작 검증만 남음 |
| 6. 파이프라인 스킬 | 🟡 | 문서 완성. 실 CAD 3종으로 전체 관통 검증만 남음 |
| 7. 외부 접속 굳히기 | 🟡 | named tunnel/Tailscale, 감사로그 (자동시작은 완료) |
| 8. 선택 기능 | ⬜ | 필수 아님 |

**2026-08-28 CAD 파이프라인 작업 요약**: `feat/stage3-6-cad-pipeline` 브랜치에서 Stage 3~6
코드를 전부 작성했다 — mcp-acad-assist 8도구 + 스크립트 생성기 10개(총 18개 MCP 도구),
Android RunsClient·승인 다이얼로그, 문서 3종(`cad-pipeline.md`/`setup-cad-workstation.md`/
`verify-cad-workstation.ps1`). Python 194 tests + Kotlin 44 tests, 전부 그린. **승인 게이트
2차는 AutoCAD 없이 이 PC에서 실측으로 완전히 왕복 검증했다** — 남은 건 전부 "AutoCAD/
SketchUp/3ds Max가 실제로 설치된 환경에서 처음 실행해봐야 아는 것"뿐이다
(`docs/setup-cad-workstation.md` §6 스모크 테스트 순서대로 진행할 것). 이 작업 중 발견한
실측 제약 하나가 스코프를 바꿨다: `/v1/runs` 이벤트 스트림이 도구 호출의 인자·반환값을
노출하지 않아서, 1차 미리보기를 2차 승인과 같이 보여줄 수 없고 이미지 뷰어도 못 만들었다 —
`cad-pipeline.md`의 "알려진 공백" 참고.

각 단계 끝날 때마다 이 표를 갱신할 것 — "무엇이 실제로 끝났는지"가 다음 세션(또는 다음 AI
에이전트)이 헷갈리지 않을 가장 중요한 정보다.
