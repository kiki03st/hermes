# MCP를 다른 로컬 환경에 연결하는 법

> 이 문서는 "MCP 연결이 뭘 의미하는지"부터 시작하는 개념 설명 + 빠른 절차다. CAD 3종
> (AutoCAD/SketchUp Pro/3ds Max)이 실제로 설치된 새 PC로 이 리포를 옮길 때 쓴다.
> 더 자세한 사전 조건·스모크 테스트 시나리오는 [`setup-cad-workstation.md`](./setup-cad-workstation.md)를,
> Hermes 게이트웨이 자체를 처음 세우는 거라면 [`setup-windows.md`](./setup-windows.md)를 볼 것.
> 이 문서는 그 둘 사이 — "게이트웨이는 이미 있고 CAD MCP만 새로 연결하고 싶다" — 를 다룬다.

## MCP 연결이 실제로 뭘 하는 건가

MCP "연결"은 네트워크로 어딘가에 접속하는 게 아니다. `%LOCALAPPDATA%\hermes\config.yaml`의
`mcp_servers.<이름>` 블록 하나가 **프로그램 하나를 자식 프로세스로 실행하는 설정**이다:

```yaml
mcp_servers:
  acad-read:
    command: "acad-assist"      # 실행할 명령
    trust: full                  # 승인 필요 여부
    tools:
      include: ["status", "query", "get", "purge_check", "capture"]  # 노출할 도구
```

Hermes 게이트웨이가 뜰 때(또는 `hermes gateway restart`) 이 설정을 읽고 각 MCP를 서브프로세스로
띄운다. "연결됐다"는 건 그 프로세스가 정상적으로 뜨고 stdio(표준입출력)로 게이트웨이와 통신이
됐다는 뜻이다 — `hermes mcp list`로 확인한다.

## 새 PC에 필요한 것

| 준비물 | 어디서 얻나 |
|---|---|
| 이 리포 전체 | `git clone` 또는 폴더 복사 |
| Hermes 게이트웨이 | [`setup-windows.md`](./setup-windows.md) (PC마다 새로 설치해야 함) |
| AutoCAD / SketchUp Pro / 3ds Max | 그 PC에 실제로 설치돼 있어야 함 |
| vendor MCP 3종 소스 | `vendor/README.md`대로 git clone |
| `acad-assist` (이 리포 자체 MCP) | 리포 안에 이미 있음 — `pip install -e`만 하면 됨 |

## 절차

### 1. vendor 클론

```powershell
mkdir C:\hermes-projects\vendor -Force
cd C:\hermes-projects\vendor

git clone https://github.com/daobataotie/CAD-MCP

git clone https://github.com/mhyrr/sketchup-mcp
cd sketchup-mcp
uv sync
cd ..

git clone https://github.com/cl0nazepamm/3dsmax-mcp
cd 3dsmax-mcp
uv sync
uv run python install.py
cd ..
```

`3dsmax-mcp`는 설치 스크립트가 3ds Max에 네이티브 브리지를 등록한다 — **설치 후 3ds Max를
재시작**해야 반영된다.

### 2. `acad-assist` 설치 (이 리포 자체 MCP)

```powershell
cd <리포경로>\mcp-acad-assist
python -m venv .venv
.venv\Scripts\python.exe -m pip install -e ".[dev]"
.venv\Scripts\python.exe -m pytest -v
```

pytest는 COM을 목(mock)으로 대체한 테스트라 **AutoCAD 없이도 전부 통과해야 한다** — 여기서
실패하면 CAD 앱 문제가 아니라 파이썬 환경 문제다.

### 3. `config.yaml`에 등록

`hermes-config/config.yaml.example`을 **`%LOCALAPPDATA%\hermes\config.yaml`에 병합**한다.
이 예제 파일에 이미 6개 서버 블록이 다 작성돼 있다:

```
acad2d       — AutoCAD 작도(CAD-MCP)
acad-read    — AutoCAD 조회(이 리포 acad-assist, 승인 불필요)
acad-write   — AutoCAD 쓰기(이 리포 acad-assist, 폰 승인 필요)
cad-pipeline — SketchUp/3ds Max로 넘길 스크립트 생성기(이 리포 acad-assist)
sketchup     — SketchUp 제어(sketchup-mcp)
max3d        — 3ds Max 제어(3dsmax-mcp)
```

할 일은 두 가지뿐이다:
1. 각 블록의 `args`에 있는 `C:\hermes-projects\vendor\...` 경로를 그 PC의 실제 클론
   위치로 바꾼다.
2. `acad-read`/`acad-write`의 `env.HERMES_CAD_ROOT`를 그 PC에서 CAD 산출물을 저장할
   실제 경로로 바꾼다(기본값 `C:\hermes-projects`).

**같은 `acad-assist` 명령이 세 번(`acad-read`/`acad-write`/`cad-pipeline`) 등록돼 있는 건
실수가 아니다** — Hermes v0.20.6의 알려진 버그 때문에 도구별 읽기전용 표시가 안 먹혀서,
서버 단위로 승인 정책을 나눈 것이다. 자세한 이유는 `mcp-acad-assist/src/acad_assist/server.py`
상단 주석과 `windows-migration.md` §7 참고.

### 4. 게이트웨이 재시작 + 확인

```powershell
hermes gateway restart
hermes mcp list
```

`acad-read`/`acad-write`/`cad-pipeline`/`sketchup`/`max3d`가 전부 `✓ enabled`로 나와야
한다. 하나라도 안 뜨면:
- `sketchup`이 안 뜬다 → SketchUp에서 서버를 아직 안 켰을 확률이 높다(아래 §5)
- `max3d`가 안 뜬다 → 3ds Max가 안 떠 있거나, 브리지 설치 후 재시작을 안 했을 확률
- `acad-read`/`acad-write`가 안 뜬다 → `acad-assist` 콘솔 스크립트 경로가 `config.yaml`의
  `command`와 안 맞을 확률 (venv 안의 `acad-assist.exe` 전체 경로를 직접 써도 된다)

### 5. 각 앱 쪽에서 매번 따로 해줘야 하는 것

| 앱 | 할 일 |
|---|---|
| AutoCAD | 그냥 켜두면 된다 — COM이 필요할 때 자동으로 붙는다(안 떠 있으면 자동 실행됨) |
| SketchUp | **켤 때마다** Extensions > MCP Server > Start Server를 수동 클릭 (자동 기동 안 함, 기본 TCP `127.0.0.1:9876`) |
| 3ds Max | 인스턴스가 여러 개 떠 있으면 **"MCP Claim This Max"** 매크로로 하나를 지정 — 안 하면 연결이 엉뚱한 창으로 간다 |

## 사전 점검 스크립트

수동으로 하나하나 확인하는 대신, 이 리포에 자동 점검 스크립트가 있다:

```powershell
powershell -ExecutionPolicy Bypass -File docs\verify-cad-workstation.ps1
```

CAD 앱 설치 여부, vendor 클론 여부, 툴체인(git/uv/python), `acad-assist` venv와 도구 개수(18개
나와야 함), 게이트웨이의 MCP 등록 상태, `HERMES_CAD_ROOT` 설정까지 한 번에 확인해서
`[OK]`/`[FAIL]`/`[WARN]`으로 보여준다. `[FAIL]`이 하나도 없으면 다음 단계(스모크 테스트)로
넘어가면 된다.

## 다음 단계

여기까지 됐으면 MCP는 "연결"된 것이다 — 실제로 도면이 그려지고 렌더가 나오는지는 별개
문제다. 단계별 스모크 테스트 순서(*"3×4m 방 평면 그리고 벽 두께 200 표시해줘"* 같은 실제
시나리오)는 [`setup-cad-workstation.md`](./setup-cad-workstation.md) §6을, 파이프라인
전체 흐름과 승인 게이트 동작 방식은 [`hermes-config/skills/cad-pipeline.md`](../hermes-config/skills/cad-pipeline.md)를 참고할 것.
