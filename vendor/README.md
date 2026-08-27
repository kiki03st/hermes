# vendor/

기성 MCP 서버 클론을 담는 폴더. 각 서브디렉터리는 별도 git 저장소이므로 이 리포에는 커밋하지 않고, 필요한 시점(해당 Stage)에 클론한다.

| 디렉터리 | 소스 | 용도 | 클론 시점 |
|---|---|---|---|
| `CAD-MCP/` | https://github.com/daobataotie/CAD-MCP | AutoCAD 2D 작도 (pywin32 COM) | Stage 3 |
| `sketchup-mcp/` | https://github.com/mhyrr/sketchup-mcp | SketchUp Pro 3D 모델링 (Ruby 확장 + TCP) | Stage 4 |
| `3dsmax-mcp/` | https://github.com/cl0nazepamm/3dsmax-mcp | 3ds Max + V-Ray 렌더 (네이티브 C++ 브리지) | Stage 5 |
| `google-calendar-mcp/` | https://github.com/nspady/google-calendar-mcp | Google 캘린더 (stdio, OAuth) | Stage 0 |

클론 방법 예시:

```bash
git clone https://github.com/daobataotie/CAD-MCP vendor/CAD-MCP
```

셋 다 Windows 전용 애플리케이션(AutoCAD/SketchUp/3ds Max)을 자동화하므로, 실제 클론·설치·동작 확인은 Windows PC에서 수행한다. 이 리포에는 각 MCP의 도구 스키마를 참조하기 위한 용도로만 필요 시 클론한다.

### 각 MCP 실행 방법 (README 확인, `hermes-config/config.yaml.example`에 반영됨)

| MCP | 실행 방식 | 비고 |
|---|---|---|
| CAD-MCP | `python <path>/src/server.py` | pip 설치형이 아니라 클론한 소스를 직접 실행 |
| sketchup-mcp | `uv run --directory <path> sketchup-mcp` | SketchUp에서 Extensions > MCP Server > Start Server로 Ruby 확장을 먼저 띄워야 함 (기본 TCP 127.0.0.1:9876) |
| 3dsmax-mcp | `uv run --directory <path> 3dsmax-mcp` | 클론 후 `uv sync && uv run python install.py`로 3ds Max에 브리지 등록 필요, 이후 Max 재시작 |
| google-calendar-mcp | `npx.cmd -y @cocal/google-calendar-mcp` | Windows에서는 `npx`가 아니라 `npx.cmd` (PATHEXT 미적용). `GOOGLE_OAUTH_CREDENTIALS` 필요 |

---

## 실측 정정 (2026-08-28, 실제 소스 조사 — 이 리포의 예전 기술이 3곳 틀렸었다)

`PLAN.md`와 `config.yaml.example`을 쓸 때는 각 MCP의 README/설명만 보고 추정한 부분이 있었다.
실제 소스(`server.py`, `main.rb`, `tools/*.py` 등)를 직접 읽어 확인한 결과, 다음이 달랐다.

### CAD-MCP — 도구 11개, `process_command`는 `SendCommand`를 쓰지 않는다

- 도구는 10개가 아니라 **11개**다. `draw_ellipse`가 있다 (`draw_line`, `draw_circle`, `draw_arc`,
  `draw_ellipse`, `draw_polyline`, `draw_rectangle`, `draw_text`, `draw_hatch`, `add_dimension`,
  `save_drawing`, `process_command`).
- **`process_command`는 `SendCommand`를 쓰지 않는다.** 리포 전체에 그 문자열이 없다. 실제로는
  `nlp_processor.py`의 **정규식 + 한/영 이중언어 키워드 파서**가 자연어를 10개 도형 명령 중
  하나로 분류해 같은 COM 메서드로 디스패치한다. `process_command`가 인식하는 `create_layer`는
  대응하는 MCP 도구가 따로 없는 죽은 분기다.
  이 리포는 원래 "`SendCommand`가 비동기라 레이스 컨디션 위험"을 근거로 이 도구를 필터
  차단했는데(`config.yaml.example`의 `tools.exclude: ["process_command"]`), **그 근거가
  사실이 아니었다.** 차단 자체는 유지한다 — LLM이 이미 하는 자연어 의도 해석을 정규식 파서가
  다시 하면서 오파싱 위험만 더하고 실익이 없기 때문이다. 근거만 정정한다.
- `save_drawing`은 `self.doc.SaveAs(file_path)` **인자 1개**만 넘긴다 — DWG/DXF 버전을 지정할
  방법이 없다. 버전이 있는 내보내기는 `mcp-acad-assist`의 `export` 도구가 담당한다(이미 그렇게
  설계돼 있었다).
- 연결은 `win32com.client.GetActiveObject(app_id)`를 **먼저** 시도하고, 없으면
  `Dispatch(app_id)`로 새로 띄운다(`startup_wait_time: 20`초 대기). `cad.type` 설정으로
  AutoCAD 외 GStarCAD(`GCAD.Application`)/ZWCAD(`ZWCAD.Application`)도 지원한다.
- 설정은 `src/config.json`을 **스크립트 자기 디렉터리에서** 읽는다 — 환경변수로 우회 못 한다.
- 마지막 커밋이 2025-07-21로, 13개월 넘게 갱신이 없다.

### sketchup-mcp — 도구 10개, README가 stale, `uvx`(PyPI) 대신 소스 클론

- README/PyPI 설명이 낡았다. `get_scene_info`는 **없고**, `get_selected_components`가 아니라
  **`get_selection`**이다. 실제 10개 도구: `create_component`, `delete_component`,
  `transform_component`, `get_selection`, `set_material`, `export_scene`,
  `create_mortise_tenon`, `create_dovetail`, `create_finger_joint`, **`eval_ruby`**.
- **PyPI 배포판은 0.1.17(2025-03-13)로 git HEAD보다 13개월 낡았고 `eval_ruby`가 없을 수
  있다.** 우리 `sketchup_scripts.py`가 만드는 Ruby는 전부 `eval_ruby`로 들어가야 하므로,
  `config.yaml.example`은 `uvx sketchup-mcp`(PyPI 고정) 대신 소스를 클론해서
  `uv run --directory <path> sketchup-mcp`로 최신 코드를 쓴다.
- README가 MIT 라이선스라고 적고 있지만 **`LICENSE` 파일이 리포에 없고 GitHub API도
  `license: null`을 보고한다.**
- SketchUp 쪽 사전 조건은 `.rbz` 확장 설치 → SketchUp 재시작 →
  **Extensions > MCP Server > Start Server**(메뉴가 자동 기동하지 않음) → TCP
  `127.0.0.1:9876`. 파이썬 쪽 소켓 수신 타임아웃은 15초.
- **단위계가 전혀 다뤄지지 않는다.** SketchUp Ruby API의 내부 길이 단위는 인치다. 파이썬
  도구(`create_component` 등)로 raw float를 넘기면 mm 값이 인치로 오해석된다 — 3000(mm 의도)을
  주면 3000인치(76.2m)가 된다. 그래서 이 파이프라인은 그 파이썬 도구들을 안 쓰고 `eval_ruby`로
  직접 Ruby를 실행해, SketchUp 자신의 `Numeric#mm` 변환 메서드로 정확히 변환한다
  (`sketchup_scripts.py` 모듈 docstring 참고).

### 3dsmax-mcp — 도구 151개(기본)/87개(core), 24개 추정 목록은 틀렸다

- **151개(기본 프로파일 `full`) / 87개(`core`)** — 우리 예전 추정 "~24개"는 카테고리 이름만
  보고 짐작한 숫자였다. `MCP_TOOL_PROFILE` 환경변수로 `core`/`full`을 고른다.
  `config.yaml.example`은 `env: { MCP_TOOL_PROFILE: "core" }`로 87개까지 먼저 깎고, 그 위에
  `tools.include`로 실제 필요한 것만 남긴다.
- `render`/`render_automations` 모듈은 **`core`에 없다.** 하지만 우리 렌더 경로가 쓰는
  `execute_maxscript`(=`execute` 모듈)는 `core`에 있다 — 그래서 `core`로 충분하다.
- **`render_scene`은 뷰포트 캡처가 아니라 실제 프로덕션 렌더다** (활성 렌더러 — V-Ray를
  지정했다면 V-Ray로 렌더한다). 진짜 한계는 다른 데 있다: 이 도구도, 그 밑에서 실행되는
  MAXScript `render()`도 **Render Setup 대화상자의 설정을 무시한다** —
  `rendOutputFilename`/`rendSaveFile`/프레임 범위/렌더 엘리먼트/V-Ray 출력 설정이 반영 안
  되고, `render_scene`은 `width`/`height`/`output_path`만 받는다. 뷰포트를 그대로 캡처하는
  도구는 따로 `capture_viewport`다. V-Ray 프로덕션 렌더(출력 설정을 존중하는 경로)는
  `execute_maxscript`로 Render Setup 글로벌 변수를 직접 설정해야 한다 —
  `maxscripts.py`의 `render_to_file_script` 참고.
- `render_automations`는 렌더를 **쏘지 않는다.** 다음 렌더에 완료 신호를 무장(arm)해두고
  완료되면 신호 파일을 쓰는 리스너다(트리거가 아니다). 워치 완료 알림에 맞는 도구지만
  `full` 프로파일이 필요하다.
- 브랜치는 `main`이 아니라 **`master`**다. 요구사항: **Python 3.12+**, `uv`,
  **3ds Max 2023–2027**. 설치: 클론 후 `uv sync && uv run python install.py` → **Max 재시작
  필수**. 공용 설정 `%LOCALAPPDATA%\3dsmax-mcp\mcp_config.ini` (`safe_mode` 기본 true).
- **`safe_mode`는 샌드박스가 아니다** — 대소문자 무시 부분문자열 블록리스트이고, 네이티브
  핸들러(`delete_objects`, `render_scene` 등)는 필터를 아예 우회한다. 통신은 명명 파이프이고
  기본 ACL이라 같은 사용자 권한의 아무 프로세스나 3ds Max를 조종할 수 있다 — 인터넷에 노출된
  경로가 아니라 로컬 신뢰 경계 안에서만 쓸 것.
- Max 인스턴스가 여러 개 떠 있으면 **"MCP Claim This Max"** 매크로로 하나를 지정해야 연결이
  엉뚱한 창으로 안 간다.
- 마지막 푸시 2026-08-04, 활발히 유지되는 중.
