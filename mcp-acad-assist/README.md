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

같은 `acad-assist` 콘솔 스크립트를 Hermes `config.yaml`에 **세 이름으로 나눠 등록**한다
(`acad-read`/`acad-write`/`cad-pipeline`) — 이유와 정확한 블록은
`hermes-config/config.yaml.example` 참고. 서버 하나에 `command: acad-assist`로만
등록하던 옛 방식은 MCP 트러스트 게이트가 도구 단위가 아니라 서버 단위로만 신뢰를
가르기 때문에 안 쓴다.

## 코드 관점 상태 — 전부 완성, 실 AutoCAD 검증만 남음

아래 세 개는 한때 "아직 안 채운 것"이었으나 이미 코드로 해결됐다(194개 pytest 전부
통과, `server.py`에서 18개 도구 확인됨) — 남은 건 실제 AutoCAD가 설치된 PC에서
결과가 기대대로 나오는지 확인하는 것뿐, 여기서 더 짤 코드는 없다:

- `capture.py` 용지 크기·배율·플롯 스타일 — `PlotToFile` 인자가 아니라
  `ActiveLayout.ConfigName`(PC3 플로터 구성)이 정한다는 걸 확인하고 `set_plot_config()`로
  구현함. 실 검증: 대상 PC의 플로터 구성 목록으로 실제 원하는 용지가 나오는지.
- `export.py`의 DXF `AcSaveAsType` — 하드코딩 대신 런타임 타입 라이브러리 조회 →
  실패 시 검증된 폴백(2018 세대)을 쓰는 `acad_constants.save_as_type`으로 구현함.
  실 검증: 대상 AutoCAD 버전이 2018 세대가 아니면 그 세대를 폴백 표에 추가해야 함
  (`docs/setup-cad-workstation.md` §7 참고).
- Hermes 2차 승인 게이트 — acad-assist 쪽 코드 작업이 아니라 **config.yaml 등록**
  문제였다: `acad-write` 블록을 `trust: untrusted`로 등록하면 게이트웨이의 MCP
  트러스트 게이트가 자동으로 폰에 승인을 띄운다(이미 `config.yaml.example`에 그렇게
  돼있음). 1차(도구 내부 `confirm=False/True`)는 이 리포에서 이미 완성.
  실 검증: 실제로 폰에 승인 다이얼로그가 뜨는지 확인만 남음
  (`docs/setup-cad-workstation.md` §6.1).

CAD 앱이 설치된 PC로 이 리포를 옮긴 뒤 이어서 할 절차 전체(vendor MCP 클론,
config.yaml 병합, 단계별 스모크 테스트)는 `docs/setup-cad-workstation.md`에 정리돼있다.
