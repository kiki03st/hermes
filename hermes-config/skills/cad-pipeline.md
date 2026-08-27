---
name: cad-pipeline
description: 도면(AutoCAD) → 모델(SketchUp Pro) → 렌더(3ds Max + V-Ray)로 이어지는 건축 CAD 파이프라인의 폴더 규약과 단계별 절차. "평면 그려줘", "모델링해줘", "렌더 걸어줘" 요청이 오면 사용.
---

> **상태: 초안.** Stage 3~6에서 실제 CAD 앱으로 각 단계를 검증하며 채워 넣는다.
> 지금은 PLAN.md의 아키텍처·접합 규약을 스킬 형식으로 옮겨 놓은 뼈대다.

# CAD 파이프라인

## 폴더 규약 (모든 단계가 공유하는 접합부)

```
C:\hermes-projects\<project>\
├─ 01-cad\      plan.dwg
├─ 02-model\    model.skp / model.fbx
├─ 03-render\   persp_4k.png
└─ meta.json    { project, stage, artifacts[], updated_at }
```

MCP끼리 직접 대화하지 않는다. 각 단계는 자기 산출물 경로를 `meta.json`에 기록하고,
다음 단계는 거기서 읽는다. 사람이 중간에 파일을 바꿔치기해도 흐름이 이어진다.

## 단계

### 1. AutoCAD — 2D 도면 (`acad2d` + `acad-assist`)
- 작도는 `acad2d`(CAD-MCP)의 `draw_*` 도구로.
- 저장 전에 `acad-assist`의 `purge_check`로 미저장 변경·잠긴 레이어 확인.
- `acad-assist`의 `export`(confirm 승인 필요)로 `01-cad/plan.dwg` 저장.
- `acad-assist`의 `capture`로 캡처 PNG를 사용자에게 보여준다.

### 2. SketchUp Pro — 3D 모델 (`sketchup`)
- `01-cad/plan.dwg`를 SketchUp Pro의 DWG 임포터로 불러온다(`model.import(path)`를 Ruby로).
- 벽 돌출 등 3D화 작업 후 `02-model/model.skp` 저장.
- 3ds Max로 넘길 경우 `02-model/model.fbx`도 내보낸다.
- **단위계 확인**: AutoCAD가 mm면 SketchUp도 mm로 맞춰졌는지 첫 작업으로 검증한다 (Stage 4 확정 항목).

### 3. 3ds Max + V-Ray — 렌더 (`max3d`)
- `.skp`는 Max가 직접 임포트한다: `execute_maxscript`로
  `importFile @"...model.skp" #noPrompt`.
- 렌더-투-파일·렌더러 선택은 `render_scene`으로 안 되고 `execute_maxscript` 경유:
  ```maxscript
  renderers.current = V_Ray()
  rendOutputFilename = @"C:\hermes-projects\<project>\03-render\persp_4k.png"
  rendSaveFile = true
  render vfb:false outputSize:[3840,2160]
  ```
- 프리뷰(저해상도)와 파이널(고해상도) 두 프리셋을 구분해 쓴다.
- 렌더는 오래 걸리므로 `POST /v1/runs`로 실행하고 진행 상황은 `/events` SSE로 구독한다.

## 각 단계 전환 시 승인

각 단계의 쓰기 작업(도면 저장, 모델 저장, 렌더 실행)은 폰에서 승인을 받는다.
중간에 취소해도 이전 단계 산출물은 폴더에 남아 있으므로 되돌릴 필요가 없다 — 다음 시도 때
그 산출물부터 다시 시작하면 된다.

## TODO (Stage 3~6에서 채울 것)

- [ ] 각 단계 실패 시 되돌리기 절차 구체화
- [ ] 단위계 불일치 발견 시 자동 보정 여부 결정
- [ ] max3d 도구 필터 목록(`hermes-config/config.yaml.example`)을 실제 설치 후 검증한 이름으로 보정
