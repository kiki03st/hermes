# hermes-upload-server

폰 Hermes 앱과 파일을 양방향으로 주고받는 독립 서버. `hermes-agent` 게이트웨이와
완전히 별개 프로세스 — 게이트웨이 코드/설정을 전혀 건드리지 않는다.

- **업로드 (폰 → 서버)**: 폰에서 올린 이미지/파일을 이 컴퓨터 디스크에 저장한다.
  저장된 파일은 게이트웨이 쪽 에이전트가 이미 가진 `file`/`vision_analyze`
  툴로 읽는다(경로는 채팅 텍스트로 전달됨, 앱 쪽 구현 참고).
- **다운로드 (서버 → 폰)**: `comfyui-bridge` 등 생성기가
  `generated/<tool>/` 밑에 저장한 파일을 폰 앱이 받아와 채팅 버블에
  렌더링할 수 있게 서빙한다(설계 문서:
  `docs/superpowers/specs/2026-08-29-image-viewer-design.md`).

## 설치 및 실행 (수동 — 지금은 이게 정상)

**자동시작(로그온 시 작업 스케줄러)은 아직 안 만들어둠** — 외부 접속(Cloudflare
named tunnel, 도메인 확보되면) 도입할 때 게이트웨이 자동시작이랑 같이 묶어서 설정
예정. 그 전까지는 폰으로 파일/이미지 첨부를 쓰고 싶을 때마다 이 서버를 직접 켜야
한다.

```powershell
cd upload-server
python -m pip install -e ".[dev]"

# 필수: 폰 앱 설정 화면의 "API 키"와 반드시 동일한 값(같은 Bearer 키 재사용)
$env:UPLOAD_SERVER_API_KEY = "<게이트웨이 API_SERVER_KEY와 동일한 값>"
# Wi-Fi NIC 주소로 명시 바인딩 — 0.0.0.0으로 하면 공인 IP NIC에도 리스닝된다
$env:UPLOAD_SERVER_HOST = "172.30.1.101"
$env:UPLOAD_SERVER_INBOX_DIR = "C:\hermes\upload-server\uploads\inbox"
# 생성기(comfyui-bridge 등) 출력이 여기 밑 <tool>/ 폴더에 쌓이고, 다운로드
# 라우트(GET /generated/{tool}/{filename})가 이 밑만 서빙한다 (기본값도 동일)
$env:UPLOAD_SERVER_GENERATED_DIR = "C:\hermes\upload-server\generated"

python -m upload_server
```

(`hermes-upload-server` 명령도 동일하게 동작 — `pyproject.toml`의 entry point.)

## 엔드포인트

- `POST /upload` — multipart 파일 업로드. Bearer 인증. 응답: `{path, note}`.
- `GET /generated/{tool}/{filename}` — `UPLOAD_SERVER_GENERATED_DIR`/`{tool}`/
  `{filename}`을 그대로 서빙(경로 검증은 `storage.resolve_generated_path`,
  `..` 등 경로순회 시도는 403). Bearer 인증. 404는 파일 없음.

## 방화벽 (최초 1회만)

게이트웨이(8642)와 완전히 같은 패턴 — 관리자 PowerShell에서:

```powershell
New-NetFirewallRule -DisplayName "Hermes Upload Server (LAN only)" `
  -Direction Inbound -Protocol TCP -LocalPort 8643 -Action Allow `
  -Profile Any -LocalAddress 172.30.1.101 -RemoteAddress 172.30.1.0/24
```

**실측으로 확인된 함정** (2026-08-29, `docs/windows-migration.md` §7.9와 동일한
원인): 이 PC의 Wi-Fi NIC(`172.30.1.101`)는 `NetworkCategory`가 **Public**으로
분류돼 있고, `python.exe`(정확히는 이 서버를 실행하는
`...\Programs\Python\Python312\python.exe`)에 대한 Public **Block** 인바운드 룰이
이미 2개 걸려 있었다. Windows Firewall은 Block이 Allow를 항상 이기므로, 위 Allow
룰만 추가해서는 폰에서 여전히 연결이 안 됐다 — 아래로 그 Block 룰을 **비활성화**
(삭제 아님)해야 실제로 뚫린다:

```powershell
# 1. 확인
Get-NetFirewallRule -Direction Inbound -Action Block -Enabled True |
  Where-Object { ($_ | Get-NetFirewallApplicationFilter).Program -like '*python312\python.exe*' } |
  Select-Object DisplayName, Enabled

# 2. 비활성화
Get-NetFirewallRule -Direction Inbound -Action Block -Enabled True |
  Where-Object { ($_ | Get-NetFirewallApplicationFilter).Program -like '*python312\python.exe*' } |
  Disable-NetFirewallRule
```

## 테스트

```bash
python -m pytest -v
```
