# Windows 이관 후 진행 순서

> PLAN.md의 Stage 0~8 기준 **지금까지 실제로 끝난 것과 안 끝난 것**을 정리한 문서.
> `docs/windows-migration.md`가 "옮길 때 뭐가 다른가"라면, 이 문서는 "옮기고 나서 뭘 계속해야
> 하는가"다. 순서대로 진행하면 된다 — 뒷단계는 앞단계 산출물(파일, MCP 연결)을 전제로 한다.

---

## Stage 0 — Hermes 세우기 — ✅ 로직은 끝, Windows에서 재현만

Ubuntu(`kiki-server`)에서 실제로 다 해봤다: Hermes 설치, Timely 모델 연동(§3.1 windows-migration.md),
API 서버 활성화, Google Calendar OAuth, `/health`·`/v1/chat/completions` 실호출, 실제 캘린더 이벤트
생성/삭제까지 확인됨.

**Windows에서 할 일**: 새 환경이니 처음부터 재설계할 필요 없이 그대로 재현만 하면 된다.
1. `docs/setup-windows.md` 그대로 설치
2. `docs/timely_ai_api.md`의 §0 curl 검증부터 다시 (Windows PowerShell에서 curl 문법만 다름)
3. `docs/hermes-credentials.md` 체크리스트로 키 채우기
4. `docs/windows-migration.md` §3.1~3.5 — Timely 4줄 설정, `api_server` 툴셋 트리밍, 세션 헤더,
   systemd 대신 Windows 서비스/작업 스케줄러로 게이트웨이 상시 기동
5. Google OAuth는 Windows라 브라우저가 있으니 SSH 우회 없이 바로 됨

---

## Stage 1 — MVP: 워치 발화 → 캘린더 — 🟡 대부분 끝, 실기기만 남음

**끝난 것**: `android/` 3모듈 빌드, 폰 앱(`WearableListenerService`, 비스트리밍 채팅, 설정 화면,
"2문장 이내" 프리앰블), 실제 서버 왕복 검증(캘린더 등록·삭제 성공), 세션 연속성
(`X-Hermes-Session-Id`/`X-Hermes-Session-Key`), 앱 버그 3개 수정(cleartext, 네트워크 예외 크래시,
타임아웃) — 전부 `docs/windows-migration.md` §1.8 참고.

**안 끝난 것 (실기기 필수, Windows에서 해도 여전히 기기가 있어야 함)**:
1. 갤럭시 워치 실기기 페어링, 마이크 버튼 → STT → Data Layer 전송 실동작
2. 워치 TTS 낭독 + 진동 + 결과 화면 표시
3. PLAN.md Stage 1 검증 시나리오 그대로 재현: *"내일 오후 3시 치과 예약 잡아줘"* → 캘린더 확인 →
   워치가 *"내일 15시 치과 예약 등록했어요"* 낭독 + 진동

**참고**: 폰 앱 기본값(`android/local.properties`)에 Windows PC의 실제 LAN IP/API 키를 넣어야
설정 화면을 안 건드리고 바로 테스트 가능 (§1.9).

---

## Stage 2 — 폰 정식 앱 — ⬜ 시작 전

Stage 1은 의도적으로 "비스트리밍, 채팅 1회, 설정 화면"까지만 스코프였다. 아직 없는 것:

- SSE 스트리밍 채팅 (`POST /v1/chat/completions`를 스트리밍으로, `hermes.tool.progress` 이벤트를
  도구 진행 칩으로 표시)
- 세션 목록/이어하기/삭제 (`/api/sessions` CRUD)
- 이미지 뷰어 (핀치 줌) — Stage 3~5 결과물(캡처 PNG, 렌더 이미지)을 보여줄 통로, 미리 뚫어두는 게
  좋다고 PLAN.md가 명시
- 승인 다이얼로그 뼈대 (미리보기 PNG + 영향 요약 + [승인]/[취소]) — Stage 3의 1차 승인 게이트와
  연결될 UI
- 앱 강제종료 상태에서 워치 명령이 동작하는지 (서비스 수명주기), 삼성 배터리 최적화 예외 온보딩

**검증 기준**: 폰 다중 턴 대화 중 글자가 흘러나옴. 앱 스와이프 종료 후에도 워치 명령 동작.

---

## Stage 3 — AutoCAD 2D — 🟡 골격만, COM 실동작 전부 남음

**끝난 것**: `mcp-acad-assist` 패키지 골격(`server.py`/`com.py`/`query.py`/`modify.py`/`capture.py`/
`export.py`/`confirm.py`), COM을 목(mock)으로 대체한 pytest 18개 — 승인 게이트 로직·좌표 변환·
재연결 로직은 이미 검증됨. `hermes-config/config.yaml.example`에 `acad2d`/`acad-assist` 필터링
설정 초안도 있음.

**안 끝난 것 (전부 실제 AutoCAD 필요, 여기서부터가 진짜 Windows 이관의 이유)**:
1. `vendor/CAD-MCP` 클론 → `config.yaml`에 등록 → **`process_command` 도구를 필터로 반드시 차단**
   (PLAN.md: SendCommand 비동기라 레이스 컨디션 위험)
2. `capture.py`의 `PlotToFile` 실제 인자(용지 크기·배율·플롯 스타일) — 지금은 최소 골격뿐, 실
   AutoCAD ActiveX 문서/실기 테스트로 채워야 함
3. `export.py`의 DXF 저장용 `AcSaveAsType` 버전 상수 — 지금 `NotImplementedError`
4. 승인 게이트 1차(도구 내부 `confirm`)는 이미 구현됐으니 실제 AutoCAD로 스모크 테스트만. 2차
   (`/v1/runs/{id}/approval`)는 **여기서 실동작 여부를 처음 확인**해야 함 — 안 되면 1차만으로 진행
5. 프로젝트 폴더 규약(`D:\hermes-projects\<project>\{01-cad,02-model,03-render}\`, `meta.json`)을
   Hermes 영구 메모리에 저장

**검증 기준**: 폰에서 *"3×4m 방 평면 그리고 벽 두께 200 표시해줘"* → AutoCAD에 도형 생성 →
**승인 요청이 폰에 뜸** → 승인 → `01-cad/plan.dwg` 저장 → 캡처 PNG가 폰에 표시.

---

## Stage 4 — SketchUp Pro — ⬜ 시작 전

1. `vendor/sketchup-mcp` (Ruby 확장) 설치, SketchUp에서 "Start Server"로 TCP 9876 기동, MCP 등록
2. **단위계 통일 검증부터** (AutoCAD mm ↔ SketchUp mm) — PLAN.md가 Stage 4 첫 작업으로 못박음
3. DWG 임포트(SketchUp Pro `model.import(path)`) → 벽 돌출 → `.skp` 저장 → `.fbx` 내보내기를 Ruby
   스니펫으로 고정
4. 뷰 캡처 → 폰 표시

**검증 기준**: *"방금 그 도면 3m 높이로 세워줘"* → `01-cad/plan.dwg` 임포트해 3D 벽 생성 →
`02-model/model.skp` 저장 → 아이소메트릭 캡처가 폰에 도착.

---

## Stage 5 — 3ds Max + V-Ray — ⬜ 시작 전

1. `vendor/3dsmax-mcp` (네이티브 C++ 브리지, Max 2023–2027) 설치, 도구 필터로 151개 중 ~25개만
   노출 (PLAN.md "도구 수 폭발 대응" 표 — query·objects·materials·viewport·external files·
   scripting 카테고리만)
2. **MAXScript 템플릿을 Hermes 스킬로 고정** — 임포트/카메라/조명/V-Ray 설정/렌더-투-파일. LLM이
   매번 스크립트를 즉흥 생성하지 않게. `render_scene`은 뷰포트 수준이라 프로덕션 렌더는 전부
   `execute_maxscript` 경유가 됨 (PLAN.md 확인됨)
3. 렌더는 `POST /v1/runs` → `/events` SSE로 (Stage 2의 스트리밍/runs 클라이언트가 먼저 필요) —
   워치엔 완료 알림 push
4. 프리뷰(저해상도)/파이널(고해상도) 두 프리셋

**검증 기준**: 워치에 *"아까 모델 V-Ray로 렌더 걸어줘"* → 워치 "렌더 시작, 몇 분 걸려요" + 진동 →
화면 꺼도 됨 → 완료 시 워치 알림 + 진동 + TTS → 폰에서 `03-render/persp.png` 확인.

---

## Stage 6 — 파이프라인 통합 스킬 — 🟡 초안만

`hermes-config/skills/cad-pipeline.md`에 폴더 규약·MAXScript 렌더 스니펫 초안은 있지만, Stage
3~5가 실제로 끝나야 절차·단계별 승인 지점·실패 시 되돌리기를 정확히 채울 수 있다. Stage 5까지
끝난 뒤 마지막에 정리.

**검증 기준**: *"3×4m 원룸, 창 하나. 도면부터 렌더까지 해줘"* → 3단계 순서 진행, **각 단계 전환
마다 폰에 승인** 뜸. 중간에 [취소] 눌러도 앞 단계 산출물은 남음.

---

## Stage 7 — 외부 접속 + 굳히기 — 🟡 임시 버전만 있음

Ubuntu에서 Cloudflare **quick tunnel**(`cloudflared tunnel --url`)로 외부 접속 자체는 검증했지만,
이건 재시작마다 URL이 바뀌고 SLA가 없는 테스트용이다. Windows에서 정식으로:

1. Cloudflare **named tunnel** + 도메인 (또는 Tailscale) — quick tunnel 교체
2. API 키 회전 절차, 요청 rate limit, 도구 실행 감사 로그
3. Windows 작업 스케줄러(또는 `hermes gateway install`)로 게이트웨이 자동 시작 — §3.4 참고
4. AutoCAD/SketchUp/Max 중 미기동 앱이 있어도 해당 MCP만 우아하게 비활성되는지 확인

**검증 기준**: Wi-Fi 끄고 LTE로 Stage 1·3 시나리오 재실행 → 동일 동작.

---

## Stage 8 — 선택 — ⬜ 시작 전

워치 타일/컴플리케이션, Hermes 크론잡 UI, 워치 standalone, 렌더 큐 관리. 필수 아님.

---

## 요약 표

| Stage | 상태 | 남은 핵심 작업 |
|---|---|---|
| 0. Hermes 세우기 | ✅ | Windows에서 재현만 |
| 1. MVP 캘린더 | 🟡 | 워치 실기기 테스트만 |
| 2. 폰 정식 앱 | ⬜ | SSE 스트리밍, 세션 목록, 이미지 뷰어, 승인 다이얼로그 |
| 3. AutoCAD 2D | 🟡 | CAD-MCP 등록, COM 실동작(capture/export), 승인 게이트 2차 |
| 4. SketchUp | ⬜ | 전체 |
| 5. 3ds Max+V-Ray | ⬜ | 전체 |
| 6. 파이프라인 스킬 | 🟡 | Stage 3~5 완료 후 마무리 |
| 7. 외부 접속 굳히기 | 🟡 | named tunnel/Tailscale, 자동시작, 감사로그 |
| 8. 선택 기능 | ⬜ | 필수 아님 |

각 단계 끝날 때마다 이 표를 갱신할 것 — "무엇이 실제로 끝났는지"가 다음 세션(또는 다음 AI
에이전트)이 헷갈리지 않을 가장 중요한 정보다.
