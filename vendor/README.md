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
| sketchup-mcp | `uvx sketchup-mcp` (PyPI 배포) | SketchUp Pro에서 Extensions > SketchupMCP > Start Server로 Ruby 확장을 먼저 띄워야 함 (기본 TCP 9876) |
| 3dsmax-mcp | `uv run --directory <path> 3dsmax-mcp` | 클론 후 `uv sync && uv run python install.py`로 3ds Max에 브리지 등록 필요 |
| google-calendar-mcp | `npx @cocal/google-calendar-mcp` | `GOOGLE_OAUTH_CREDENTIALS` 필요, 자체 `ENABLED_TOOLS` 환경변수로도 도구 제한 가능 |
