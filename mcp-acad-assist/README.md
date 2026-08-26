# acad-assist

CAD-MCP(그리기 전담)에 없는 조회·수정·캡처·내보내기·승인 게이트를 채우는 MCP stdio 서버. PLAN.md 참고.

## 개발 (Linux/이 서버에서 가능한 범위)

Windows 없이도 COM을 목(mock)으로 대체해 비즈니스 로직(승인 게이트, 조회 필터, 재연결)을 검증할 수 있다.

```bash
python3 -m pip install --user -e ".[dev]"
python3 -m pytest -v
```

`pywin32`는 `sys_platform == 'win32'`일 때만 설치되고, `com.py`의 `Win32AcadPort`도
Windows가 아니면 인스턴스화 시 즉시 에러를 낸다 — 실제 COM 연결은 Windows 전용이다.

## 실행 (Windows, AutoCAD 설치된 PC)

```bash
pip install -e ".[dev]"
acad-assist   # stdio MCP 서버 기동
```

Hermes `config.yaml`의 `mcp_servers.acad-assist`에 `command: acad-assist` 로 등록한다.

## 아직 안 채운 것 (Stage 3에서 실 AutoCAD로 확정)

- `capture.py`: `PlotToFile` 용지 크기·배율·플롯 스타일
- `export.py`: DXF 저장 시 `AcSaveAsType` 정확한 버전 상수
- Hermes `/v1/runs/{id}/approval` 연동(2차 승인 게이트) — 1차(도구 내부 `confirm`)는 완성
