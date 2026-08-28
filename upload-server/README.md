# hermes-upload-server

폰 Hermes 앱에서 이미지/파일을 받아 이 컴퓨터 디스크에 저장하는 독립 서버.
`hermes-agent` 게이트웨이와 완전히 별개 프로세스 — 게이트웨이 코드/설정을 전혀
건드리지 않는다. 저장된 파일은 게이트웨이 쪽 에이전트가 이미 가진 `file`/
`vision_analyze` 툴로 읽는다(경로는 채팅 텍스트로 전달됨, 앱 쪽 구현 참고).

## 설치 및 실행

```bash
cd upload-server
python -m pip install -e ".[dev]"

# 필수: 폰 앱이 쓰는 것과 같은 API_SERVER_KEY
set UPLOAD_SERVER_API_KEY=<게이트웨이 API_SERVER_KEY와 동일한 값>
# 선택 (기본값은 config.py 참고: host=0.0.0.0, port=8643, retention=14일, max=100MB)
set UPLOAD_SERVER_HOST=172.30.1.101
set UPLOAD_SERVER_INBOX_DIR=C:\hermes\uploads\inbox

hermes-upload-server
```

`docs/setup-windows.md`의 게이트웨이 방화벽 설정과 동일한 패턴으로, 이 서버의
포트(기본 8643)도 폰이 붙는 Wi-Fi NIC 주소로만 인바운드를 열어야 한다 — 공인 IP
NIC는 열지 않는다.

## 테스트

```bash
python -m pytest -v
```
