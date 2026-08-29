# comfyui-bridge

폰 앱에서 로컬 ComfyUI로 이미지를 생성하는 좁은 MCP 서버. `generate_image` 함수 하나만
노출한다 — 터미널/코드실행 없이 ComfyUI의 REST API(127.0.0.1:8188)만 HTTP로 호출한다.

## 사전 조건

ComfyUI가 SD1.5 체크포인트(`v1-5-pruned-emaonly.safetensors`)와 함께 이미 떠있어야
한다:

```powershell
comfy launch --background
curl.exe http://127.0.0.1:8188/system_stats   # 확인
```

## 설치

```bash
cd mcp-comfyui-bridge
python -m pip install -e ".[dev]"
```

`hermes-config/config.yaml.example`의 `mcp_servers.comfyui-bridge` 블록을
`%LOCALAPPDATA%\hermes\config.yaml`에 병합한 뒤 `hermes gateway restart`.

## 환경변수 (전부 선택, 기본값 있음)

| 변수 | 기본값 |
|---|---|
| `COMFYUI_BASE_URL` | `http://127.0.0.1:8188` |
| `COMFYUI_TIMEOUT_SECONDS` | `120` |
| `COMFYUI_BRIDGE_OUTPUT_DIR` | `mcp-comfyui-bridge/generated/` |

## 테스트

```bash
python -m pytest -v
```

## 검증

```bash
hermes mcp list   # comfyui-bridge가 뜨는지
```

폰에서 "그림 그려줘" 요청 → `generated/` 안에 실제 PNG 파일이 생기는지 확인.
