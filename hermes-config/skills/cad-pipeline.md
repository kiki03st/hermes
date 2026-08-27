---
name: cad-pipeline
description: 도면(AutoCAD) → 모델(SketchUp Pro) → 렌더(3ds Max + V-Ray)로 이어지는 건축 CAD 파이프라인의 폴더 규약과 단계별 절차. "평면 그려줘", "모델링해줘", "렌더 걸어줘" 요청이 오면 사용.
---

> **상태 (2026-08-28): 코드는 완성, 실행 검증은 대상 환경 몫.** AutoCAD/SketchUp/3ds Max가
> 하나도 없는 개발 PC에서 만들어졌다 — 스크립트 생성기·승인 게이트·MCP 등록은 전부 테스트로
> 고정했고(승인 게이트 2차는 이 PC의 라이브 게이트웨이에 실제로 왕복까지 확인했다), CAD 앱
> 자체와 맞물리는 부분(벽 임포트가 실제로 면을 만드는지, 카메라 기본값이 보기 좋은지 등)은
> 대상 환경에서 처음 확인해야 한다. `docs/setup-cad-workstation.md`의 체크리스트를 먼저 볼 것.

# CAD 파이프라인

## 폴더 규약 (모든 단계가 공유하는 접합부)

```
<HERMES_CAD_ROOT>\<project>\
├─ 01-cad\      plan.dwg
├─ 02-model\    model.skp / model.fbx
├─ 03-render\   persp_4k.png
└─ meta.json    { project, stage, units, artifacts[], updated_at }
```

루트는 `C:\hermes-projects` 하드코딩이 아니라 `HERMES_CAD_ROOT` 환경변수다(이식성 —
`config.yaml`의 `acad-read`/`acad-write` 등록에 있다). MCP끼리 직접 대화하지 않는다. 각
단계는 자기 산출물 경로를 `meta.json`에 기록하고, 다음 단계는 거기서 읽는다. 사람이 중간에
파일을 바꿔치기해도 흐름이 이어진다.

## 도구 목록 (실제 등록명, `mcp-acad-assist/src/acad_assist/server.py` 기준)

| 그룹 | 등록명(config.yaml) | trust | 도구 |
|---|---|---|---|
| AutoCAD 조회 | `acad-read` | full | `status`, `query`, `get`, `purge_check`, `capture` |
| AutoCAD 쓰기 | `acad-write` | untrusted (폰 승인 필요) | `modify`, `layer`, `export` |
| 파이프라인 생성기 | `cad-pipeline` | full | `sketchup_import_dwg_script`, `sketchup_extrude_walls_script`, `sketchup_save_skp_script`, `sketchup_export_fbx_script`, `sketchup_capture_iso_script`, `sketchup_unit_check_script`, `max_import_skp_script`, `max_setup_camera_script`, `max_setup_lighting_script`, `max_render_to_file_script` |
| AutoCAD 작도 | `acad2d`(CAD-MCP) | (미지정=full) | `draw_line`/`draw_circle`/`draw_arc`/`draw_ellipse`/`draw_polyline`/`draw_rectangle`/`draw_text`/`draw_hatch`/`add_dimension`/`save_drawing` (`process_command` 차단) |
| SketchUp | `sketchup` | full | sketchup-mcp 자체 도구(`create_component` 등) + **`eval_ruby`** |
| 3ds Max | `max3d` | untrusted | 3dsmax-mcp core 프로파일 중 선정된 것 + `execute_maxscript` |

**`cad-pipeline` 그룹의 10개 도구는 전부 텍스트 생성기다 — Ruby/MAXScript 문자열만 돌려주고
아무것도 실행하지 않는다.** 실제 실행은 `sketchup`의 `eval_ruby` 또는 `max3d`의
`execute_maxscript`로 별도 호출해야 한다. **생성기가 돌려준 문자열은 한 글자도 고치지 말고
그대로 그 인자로 넘길 것** — 골든 테스트가 고정한 계약(정확한 이스케이프, JSON 출력 형식)이
깨진다. 스니펫을 변형하거나 재작성하지 말 것.

## 단계

### 1. AutoCAD — 2D 도면 (`acad2d` + `acad-read`/`acad-write`)
1. 작도는 `acad2d`(CAD-MCP)의 `draw_*` 도구로.
2. 저장 전에 `acad-read`의 `purge_check`로 미저장 변경·잠긴/동결 레이어 확인.
3. `acad-write`의 `export`(`confirm=false`로 미리보기 → 승인 후 `confirm=true`)로
   `01-cad/plan.dwg` 저장. **`project=<project명>`을 넘기면 `meta.json`에 자동 등록된다**
   (`stage="cad", kind="dwg"`) — 다음 단계가 이걸로 찾는다.
4. `acad-read`의 `capture`로 캡처 PNG를 base64로 받아 사용자에게 보여준다.

### 2. SketchUp Pro — 3D 모델 (`cad-pipeline` 생성기 + `sketchup`의 `eval_ruby`)
1. **단위 확인이 첫 작업**(Stage 4 확정 항목): `sketchup_unit_check_script()`를 불러 받은
   텍스트를 그대로 `eval_ruby`에 넣는다. SketchUp Ruby API 내부 길이 단위는 **인치**다 —
   AutoCAD가 mm라고 SketchUp도 mm인 게 아니다. 그래서 이후 모든 치수 스니펫은 mm 값을 그대로
   받아서 **SketchUp 자신의 `Numeric#mm` 변환 메서드를 스크립트 안에 심는다**
   (`3000.0.mm`처럼) — Python에서 인치로 미리 계산하지 않는다. 단위 불일치가 발견돼도
   **자동 보정하지 않는다** — 사용자에게 알리고 멈춘다(fail-loud, `units.py`의 설계와 동일한
   원칙).
2. `sketchup_import_dwg_script(dwg_path)`로 `01-cad/plan.dwg`를 불러오는 Ruby를 받아
   `eval_ruby`로 실행. 임포트 전후 엔티티 수를 자체 비교해 실패를 감지한다.
3. `sketchup_extrude_walls_script(height_mm, layer="walls")`로 벽 압출. **가정**: 벽
   레이어의 닫힌 폴리라인/해치가 DWG 임포트 시 SketchUp 면으로 들어온다는 전제다 — 벽이
   열린 중심선으로만 그려진 도면이면 `faces_extruded: 0`이 나온다. 이 경우 도면 쪽 벽
   표현 방식부터 확인할 것(대상 환경에서 처음 확인해야 하는 항목).
4. `sketchup_save_skp_script(output_path)`로 `02-model/model.skp` 저장.
5. 3ds Max로 넘길 계획이면 `sketchup_export_fbx_script(output_path)`도 실행 — **선택**
   산출물이다(Max는 `.skp`를 직접 임포트하는 게 확정안이라 FBX가 없어도 파이프라인은
   안 끊긴다). FBX는 SketchUp **Pro** 전용 기능이다.
6. `sketchup_capture_iso_script(output_path)`로 아이소메트릭 캡처.
7. **`meta.json` 등록은 아직 자동화 안 됨** — 생성기가 `projects.register_artifact`를
   직접 호출하지 않는다(Ruby 실행 결과를 acad-assist가 볼 방법이 없어서). 지금은 이 단계
   완료 후 acad-assist에 별도 산출물 등록 도구가 필요하다는 게 확인된 실제 공백이다 —
   대상 환경 작업 시 추가할 것.

### 3. 3ds Max + V-Ray — 렌더 (`cad-pipeline` 생성기 + `max3d`의 `execute_maxscript`)
1. `max_import_skp_script(skp_path)`로 `.skp`를 직접 임포트(FBX 아님)하는 MAXScript를 받아
   `execute_maxscript`로 실행. 임포트 전후 오브젝트 수를 자체 비교해 실패를 감지한다.
2. 필요하면 `max_setup_camera_script(position, target)` / `max_setup_lighting_script()`로
   기본 카메라·조명을 만든다 — **대상 환경에서 확인 필요**: 이 기본값(화각 45도, 위치 계산
   없음 — 호출자가 도면 크기에 맞춰 좌표를 넘겨야 함, Skylight+Omni 조합)이 실제 장면에서
   보기 좋은 구도를 주는지는 검증 전이다.
3. `max_render_to_file_script(output_path, preset)`로 V-Ray 렌더 스크립트를 받아
   `execute_maxscript`로 실행.
   - **`render_scene` 도구도 MAXScript `render()` 함수도 쓰지 않는다** — 둘 다 Render
     Setup 대화상자 설정을 무시한다(실측 확인, PLAN.md 원안 스니펫이 이 이유로
     자기모순이었다 — 정정 완료). 대신 `rendSaveFile`/`rendOutputFilename`/`renderWidth`/
     `renderHeight` 글로벌을 직접 설정하고 `max quick render`로 트리거하는 경로만 V-Ray의
     출력 설정을 존중한다.
   - V-Ray 렌더러는 `rendererClass.classes`를 `V_Ray*` 패턴으로 찾아 인스턴스화한다 —
     버전 고정 클래스명을 안 쓰므로 V-Ray SDK가 올라가도 안 깨진다.
   - `preset="preview"`(960×540, 빠른 확인용) / `preset="final"`(3840×2160) 둘 중 선택.
     명시적 `width`/`height`로 덮어쓸 수도 있다.
   - 렌더 완료는 출력 파일이 실제로 생겼는지로 검증한다(자기 보고 아님) — 없으면 MAXScript가
     직접 `throw`해서 `execute_maxscript`의 에러 경로로 실패가 올라간다.
4. 렌더는 오래 걸린다 — **`POST /v1/runs`로 실행하고 진행 상황은 `/events` SSE로
   구독한다.** 워치엔 완료 알림을 push한다(3dsmax-mcp의 `render_automations`가 이 용도에
   맞지만 `full` 프로파일이 필요하다 — 지금 `max3d`는 `core`만 켜져 있어 미포함, 필요해지면
   `config.yaml.example`의 `MCP_TOOL_PROFILE`을 `full`로 올리고 도구를 추가할 것).

## 승인 — 두 겹이고, 폰이 보는 정보가 다르다

1. **1차 (도구 내부 `confirm`)**: `acad-write`의 `modify`/`layer`/`export`가
   `confirm=false`(기본)면 실행 없이 요약·영향 수·(옵션)미리보기 이미지를 반환한다. 에이전트가
   그 내용을 대화로 보여주고, 사용자가 승인하면 `confirm=true`로 재호출한다. Hermes API에
   의존하지 않아 무조건 동작한다.
2. **2차 (MCP 트러스트 게이트, `/v1/runs`에만 존재)**: `acad-write`/`max3d`는
   `trust: untrusted`로 등록돼 있어서, 그 서버의 쓰기 도구가 실제로 호출되면(1차를 이미
   통과한 뒤든, 에이전트가 1차를 건너뛰고 바로 `confirm=true`로 부르든 상관없이) Hermes가
   **RPC 발사 전에** SSE로 `approval.request`를 보내고 실행을 멈춘다. 폰이
   `POST /v1/runs/{id}/approval`로 응답해야 재개된다.

> **실측으로 확인된 제약**: `/v1/runs/{id}/events`는 도구 호출의 인자·반환값을 노출하지
> 않는다 — `tool.completed` 이벤트는 `{tool, duration, error}`뿐이다. 그래서 **폰의 2차 승인
> 다이얼로그는 1차 미리보기(요약·영향 수·PNG)를 같이 볼 수 없다** — 트러스트 게이트가 만드는
> generic 텍스트(`command`/`description`)와 `choices` 버튼만 보인다. 두 승인이 완전히
> 분리된 정보로 동작한다는 뜻이다. 에이전트는 1차 결과를 **대화 텍스트로** 먼저 설명해두는 게
> 폰에서 뭘 승인하는지 파악하는 유일한 방법이다.

각 단계의 쓰기 작업(도면 저장, 모델 저장, 렌더 실행)은 이 두 승인을 거친다. 중간에 취소해도
이전 단계 산출물은 폴더에 남아 있으므로 되돌릴 필요가 없다 — 다음 시도 때 그 산출물부터 다시
시작하면 된다. `meta.json`의 `artifacts[]`가 각 단계에서 실제로 뭐가 만들어졌는지 기록이라
"어디서부터 다시 하면 되는지"를 그걸 보고 판단할 수 있다.

## 실패 시 되돌리기

- **AutoCAD 쓰기 실패(1차 미리보기 후 미승인, 또는 실행 중 에러)**: `modify`는
  `StartUndoMark`/`EndUndoMark`로 배치를 묶는다 — AutoCAD에서 Undo 한 번으로 되돌릴 수
  있다. 파일 자체는 아직 저장 전이라 디스크 상태는 그대로다.
- **SketchUp/3ds Max 스크립트 실행 실패**: 생성기 스크립트는 실패를 감지하면(오브젝트 수
  불변, 렌더 출력 파일 없음) `throw`로 명시적으로 실패를 알린다 — 부분 진행 상태가 남을 수
  있으니(예: 임포트는 됐는데 압출 전에 실패) 재시도 전에 `sketchup_unit_check_script`나
  씬 조회로 현재 상태를 먼저 확인할 것.
- **어느 단계든**: 이전 단계 산출물은 `meta.json`에 남아 있다. 처음부터 다시 하지 말고 마지막
  성공 지점부터 재시도한다.

## 알려진 공백 (대상 환경 작업 목록)

- [x] ~~단위계 불일치 발견 시 자동 보정 여부 결정~~ — **fail-loud로 확정.** 자동 보정 안 함.
- [x] ~~max3d 도구 필터 목록을 실제 이름으로 보정~~ — `config.yaml.example`에 반영 완료
      (조사 기반). 단, 최종 확정은 대상 환경에서 `hermes mcp list`로.
- [ ] SketchUp/Max 산출물을 `meta.json`에 자동 등록하는 도구가 없다 — 지금은 AutoCAD
      쪽(`export`의 `project=`)만 된다. SketchUp/Max는 Ruby/MAXScript 실행 결과를
      acad-assist가 볼 방법이 없어서 별도 설계가 필요하다.
- [ ] 캡처 PNG·렌더 결과를 폰이 실제로 "보는" 통로가 없다 — `/v1/runs` 이벤트가 도구
      결과를 안 실어준다. `docs/setup-cad-workstation.md`와
      `android/app/src/main/kotlin/com/hermes/app/ui/RunsSection.kt`의 문서 주석에
      같은 문제가 기록돼 있다.
- [ ] 카메라/조명 기본값(`max_setup_camera_script`/`max_setup_lighting_script`)이 실제
      장면에서 쓸 만한지 검증 전.
- [ ] 벽 압출의 "닫힌 면이 이미 있다" 가정이 실제 DWG 도면 관례와 맞는지 검증 전.
