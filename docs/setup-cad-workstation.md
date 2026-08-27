# Stage 3~6 — CAD 3종이 설치된 대상 환경에서 이어받기

> **대상 독자**: 이 리포를 AutoCAD/SketchUp Pro/3ds Max가 실제로 설치된 Windows PC로
> 옮겨 받는 사람(또는 AI 에이전트). 여기 적힌 코드는 전부 그 앱들이 하나도 없는 개발 PC에서
> 작성됐다 — Python/Kotlin 로직은 테스트로 고정했지만(`mcp-acad-assist`의 pytest,
> Android의 JUnit — 전부 통과 확인됨), **COM/Ruby/MAXScript가 실제 앱과 맞물리는 부분은
> 여기서 한 번도 실행해본 적이 없다.** "빌드가 통과한다"와 "기능이 된다"는 다른 말이다 —
> `windows-migration.md`가 Stage 0 이관 때 이미 강조한 원칙을 여기서도 그대로 지킨다.

## 0. 시작하기 전에

이 문서는 Stage 0(Hermes 게이트웨이, `docs/setup-windows.md`)이 이미 끝났다는 전제다 —
안 끝났으면 그것부터. 이 PC가 Stage 0을 새로 하는 PC라면 그 문서부터 순서대로.

## 1. CAD 3종 설치 확인

| 앱 | 확인 방법 |
|---|---|
| AutoCAD | 실행해서 아무 도면이나 열어본다. 학생판이면 DWG에 교육용 플롯 스탬프가 박히고 전파된다 — 상업용 산출물로 못 쓴다(PLAN.md 확인 사항, 학생판 쓰는 중이면 알고 있을 것). |
| SketchUp Pro | **Pro** 여야 한다 — 무료판(Make)엔 FBX 내보내기·`eval_ruby`가 필요로 하는 확장 설치 경로가 없다. |
| 3ds Max | 2023–2027 (3dsmax-mcp 요구사항). V-Ray가 별도 라이선스로 설치돼 있어야 렌더 스크립트가 실제로 동작한다 — `select_vray_renderer_snippet()`이 `V_Ray*` 패턴으로 렌더러 클래스를 찾는데, V-Ray 자체가 없으면 못 찾고 `throw`로 실패한다(의도된 동작 — 조용히 다른 렌더러로 넘어가지 않는다). |

## 2. vendor MCP 3종 클론·설치

`vendor/README.md`에 실측 정정 사항(도구 개수, 실행 방식, 알려진 버그)이 자세히 있다 —
여기서는 설치 순서만.

```powershell
mkdir C:\hermes-projects\vendor -Force
cd C:\hermes-projects\vendor

git clone https://github.com/daobataotie/CAD-MCP
cd CAD-MCP
pip install -r requirements.txt
cd ..

git clone https://github.com/mhyrr/sketchup-mcp
# PyPI(uvx) 대신 소스를 쓴다 — PyPI 0.1.17엔 eval_ruby 가 없을 수 있다(vendor/README.md).
cd sketchup-mcp
uv sync
cd ..

git clone https://github.com/cl0nazepamm/3dsmax-mcp
cd 3dsmax-mcp
uv sync
uv run python install.py
cd ..
```

`3dsmax-mcp`는 설치 후 **3ds Max를 재시작**해야 브리지가 로드된다.

## 3. 각 앱 쪽 사전 조건

- **CAD-MCP**: `vendor/CAD-MCP/src/config.json`을 확인 — `cad.type`이 `"autocad"`인지
  (기본값). 이 파일은 스크립트 자기 디렉터리에서 읽으므로 환경변수로 못 바꾼다.
- **SketchUp**: Extensions > Extension Manager로 `.rbz`를 설치(빌드 방법은 sketchup-mcp
  리포 README 참고) → SketchUp 재시작 → **Extensions > MCP Server > Start Server**
  (메뉴가 자동 기동하지 않는다, 기본 TCP `127.0.0.1:9876`). SketchUp을 켤 때마다 수동으로
  Start Server를 눌러야 한다는 뜻 — 자동화하려면 SketchUp 시작 시 실행되는 Ruby 스크립트를
  따로 만들어야 한다(이번 범위 밖).
- **3ds Max**: 여러 인스턴스가 떠 있으면 **"MCP Claim This Max"** 매크로로 하나를 지정할
  것 — 안 하면 연결이 엉뚱한 창으로 간다. `%LOCALAPPDATA%\3dsmax-mcp\mcp_config.ini`의
  `safe_mode`는 기본 `true`인데, **샌드박스가 아니다** — 대소문자 무시 부분문자열
  블록리스트이고 네이티브 핸들러(`delete_objects`, `render_scene` 등)는 우회한다. 명명
  파이프가 기본 ACL이라 같은 사용자 권한의 아무 프로세스나 3ds Max를 조종할 수 있다는 뜻 —
  이 PC를 신뢰 경계 안에서만 쓸 것(Stage 7에서 외부 노출할 때 다시 검토).

## 4. Hermes 설정 반영

```powershell
# HERMES_CAD_ROOT — 하드코딩된 C:\hermes-projects 가 아니라 이 환경변수가 실제 루트를 정한다.
# config.yaml.example 의 acad-read/acad-write 블록에 이미 env 로 박혀 있다 — 이 PC 경로에
# 맞게 바꿀 것.

# acad-assist 설치 (COM 조회·수정·캡처·내보내기·승인 게이트, SketchUp/Max 스크립트 생성기)
cd <이 리포 경로>\mcp-acad-assist
python -m venv .venv
.venv\Scripts\python.exe -m pip install -e ".[dev]"
.venv\Scripts\python.exe -m pytest -v   # 18(Stage 3) + 신규분 — 전부 통과해야 함, 실 AutoCAD 없이도

# server.py 의 import 가 실제로 되는지 (mcp SDK 버전에 따라 엔트리포인트가 다를 수 있다)
.venv\Scripts\python.exe -c "from acad_assist import server; print(len(server.mcp._tool_manager.list_tools()), 'tools')"
# → 18 tools 가 나와야 한다
```

`hermes-config/config.yaml.example`을 `%LOCALAPPDATA%\hermes\config.yaml`에 병합 —
`acad2d`/`acad-read`/`acad-write`/`cad-pipeline`/`sketchup`/`max3d` 블록 전부. 경로
플레이스홀더(`C:\hermes-projects\vendor\...`)를 이 PC의 실제 클론 위치로 바꿀 것.

```powershell
hermes gateway restart
hermes mcp list
```

`acad-read`/`acad-write`/`cad-pipeline`/`sketchup`/`max3d`가 전부 `✓ enabled`로 나와야
한다. `max3d`가 연결 안 되면 3ds Max가 떠 있는지, 브리지가 로드됐는지(재시작 여부) 먼저
확인.

## 5. 도구 이름 최종 확인 (여기서만 가능한 것)

`hermes mcp list --detail` 또는 Hermes TUI에서 `max3d`의 실제 도구 이름을 확인해
`config.yaml`의 `tools.include`와 대조한다. `config.yaml.example`의 목록은 3dsmax-mcp
소스 조사를 근거로 했지만 **실행 중인 인스턴스로 최종 검증된 적은 없다** — 이름이 하나라도
안 맞으면 그 도구만 조용히 안 뜨니(에러 아님), 파이프라인이 그 도구를 쓰려다 "그런 도구
없음"으로 막히는 시점에야 알아차리게 된다. 미리 대조해둘 것.

## 6. 단계별 스모크 테스트 (PLAN.md 검증 시나리오 그대로)

**순서대로**, 앞 단계가 성공해야 다음으로 넘어간다.

### 6.1 AutoCAD 단독
폰(또는 curl로 `/v1/chat/completions`)에서: *"3×4m 방 평면 그리고 벽 두께 200 표시해줘"*
- CAD-MCP의 `draw_*`로 도형이 실제로 그려지는지
- `acad-write`의 `export(confirm=false)` 미리보기 → 승인 → `export(confirm=true)`로
  `01-cad/plan.dwg` 저장되는지
- **여기서 2차 승인(폰 다이얼로그)도 뜨는지 확인** — `acad-write`가 `trust: untrusted`라
  뜨는 게 정상이다. 안 뜨면 config 등록이 잘못됐다는 뜻.
- `acad-read`의 `capture`로 캡처 PNG가 base64로 돌아오는지 (폰 UI엔 아직 안 보인다 —
  `cad-pipeline.md`의 "알려진 공백" 참고, 이건 별도 작업)

### 6.2 AutoCAD → SketchUp 접합
*"방금 그 도면 3m 높이로 세워줘"*
- `sketchup_unit_check_script()` 결과 확인 — SketchUp 쪽 길이 단위 설정이 뭔지 실측
- `sketchup_import_dwg_script`로 DWG가 실제로 들어오는지, 엔티티 수가 늘었는지
- `sketchup_extrude_walls_script`로 벽이 실제로 압출되는지 — **`faces_extruded: 0`이
  나오면** 벽 레이어가 닫힌 면이 아니라 열린 선으로만 그려졌다는 뜻. `cad-pipeline.md`의
  가정이 이 도면과 안 맞는 경우다 — 도면 쪽에서 벽을 닫힌 폴리라인/해치로 그리도록 바꾸거나,
  extrude 스니펫이 면이 아니라 선을 오프셋+실체화하는 방식으로 다시 설계해야 한다.
- `sketchup_save_skp_script`로 `02-model/model.skp` 저장

### 6.3 SketchUp → 3ds Max 접합
- `max_import_skp_script`로 `.skp`가 실제로 들어오는지 (FBX 아님)
- 단위·스케일이 맞는지 육안 확인 (3m 벽이 3m로 보이는지 — mm 변환 체인 전체의 최종 확인점)

### 6.4 렌더
*"아까 모델 V-Ray로 렌더 걸어줘"*
- V-Ray가 실제로 설치돼 있어야 `select_vray_renderer_snippet`이 렌더러를 찾는다
- `max_render_to_file_script(preset="preview")`로 먼저 저해상도 확인 → `preset="final"`
- `03-render/persp_4k.png`가 실제로 생기는지, 열어서 장면이 의도대로 나왔는지
- 카메라/조명 기본값이 마음에 안 들면 `max_setup_camera_script`/`max_setup_lighting_script`
  호출 부분을 스킬에서 조정 — 좌표는 도면 크기에 맞춰 호출자가 계산해서 넘기는 구조다

### 6.5 전체 파이프라인
*"3×4m 원룸, 창 하나. 도면부터 렌더까지 해줘"* — 6.1~6.4가 전부 끝난 뒤 마지막에.
각 단계 전환마다 폰에 승인이 뜨는지, 중간에 [거부]를 눌러도 앞 단계 산출물이 남아있는지 확인.

## 7. 여기서 발견하면 바로잡을 것들 (예상되는 항목)

- `max3d`의 `tools.include` 이름 오탈자 (§5)
- SketchUp 벽 압출 가정이 실제 도면 관례와 안 맞는 경우 (§6.2)
- 카메라/조명 기본값 조정 (§6.4)
- V-Ray 버전에 따라 `rendererClass.classes` 매칭이 예상과 다르게 동작하는 경우 —
  `select_vray_renderer_snippet()`이 찾은 클래스 이름을 로그로 확인해서 패턴이 맞는지 재검증
- `AcSaveAsType`이 런타임 조회로 잘 찾아지는지 (`acad_constants.lookup_runtime`) — 실패하면
  2018 세대 폴백만 있으므로, 이 AutoCAD 버전이 그것과 다른 세대를 요구하면 검증된 폴백 표에
  세대를 추가해야 한다(추측하지 말고 `win32com.client.constants`를 직접 덤프해서 확인할 것)
