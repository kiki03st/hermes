"""SD1.5 txt2img 워크플로 템플릿 — comfyui 스킬의 workflows/sd15_txt2img.json을 그대로
파이썬 상수로 옮기고, prompt/negative/seed/해상도만 주입한다. 그래프 배선(노드 번호,
class_type, 노드 간 연결)은 절대 바꾸지 않는다 — 바뀌면 ComfyUI가 이 그래프를 다르게
해석한다."""

from __future__ import annotations

import random
from typing import Any

_CHECKPOINT_NAME = "v1-5-pruned-emaonly.safetensors"

# 32비트 부호 없는 정수 범위 — ComfyUI의 시드 필드가 기대하는 범위(comfyui 스킬 문서
# 확인: -1은 스킬 스크립트의 전처리일 뿐 ComfyUI 서버 자체 관례가 아니라서, 여기서
# 직접 랜덤 정수를 굴린다).
_MAX_SEED = 2**32 - 1


def build_workflow(
    prompt: str,
    negative_prompt: str = "",
    seed: int | None = None,
    width: int = 512,
    height: int = 512,
) -> dict[str, Any]:
    resolved_seed = seed if seed is not None else random.randint(0, _MAX_SEED)

    return {
        "3": {
            "class_type": "KSampler",
            "inputs": {
                "seed": resolved_seed,
                "steps": 20,
                "cfg": 8.0,
                "sampler_name": "euler",
                "scheduler": "normal",
                "denoise": 1.0,
                "model": ["4", 0],
                "positive": ["6", 0],
                "negative": ["7", 0],
                "latent_image": ["5", 0],
            },
        },
        "4": {
            "class_type": "CheckpointLoaderSimple",
            "inputs": {"ckpt_name": _CHECKPOINT_NAME},
        },
        "5": {
            "class_type": "EmptyLatentImage",
            "inputs": {"width": width, "height": height, "batch_size": 1},
        },
        "6": {
            "class_type": "CLIPTextEncode",
            "inputs": {"text": prompt, "clip": ["4", 1]},
        },
        "7": {
            "class_type": "CLIPTextEncode",
            "inputs": {"text": negative_prompt, "clip": ["4", 1]},
        },
        "8": {
            "class_type": "VAEDecode",
            "inputs": {"samples": ["3", 0], "vae": ["4", 2]},
        },
        "9": {
            "class_type": "SaveImage",
            "inputs": {"filename_prefix": "hermes", "images": ["8", 0]},
        },
    }
