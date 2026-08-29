from comfyui_bridge.workflow import build_workflow


def test_build_workflow_injects_positive_and_negative_prompt():
    wf = build_workflow("a red cabin", negative_prompt="blurry")

    assert wf["6"]["inputs"]["text"] == "a red cabin"
    assert wf["7"]["inputs"]["text"] == "blurry"


def test_build_workflow_defaults_negative_prompt_to_empty_string():
    wf = build_workflow("a cat")

    assert wf["7"]["inputs"]["text"] == ""


def test_build_workflow_uses_explicit_seed():
    wf = build_workflow("a cat", seed=42)

    assert wf["3"]["inputs"]["seed"] == 42


def test_build_workflow_randomizes_seed_when_omitted():
    seeds = {build_workflow("a cat")["3"]["inputs"]["seed"] for _ in range(20)}

    assert len(seeds) > 1  # 20번 중 전부 같은 값일 확률은 무시할 수준


def test_build_workflow_uses_default_resolution():
    wf = build_workflow("a cat")

    assert wf["5"]["inputs"]["width"] == 512
    assert wf["5"]["inputs"]["height"] == 512


def test_build_workflow_accepts_custom_resolution():
    wf = build_workflow("a cat", width=768, height=768)

    assert wf["5"]["inputs"]["width"] == 768
    assert wf["5"]["inputs"]["height"] == 768


def test_build_workflow_keeps_checkpoint_and_graph_wiring_fixed():
    wf = build_workflow("a cat")

    assert wf["4"]["inputs"]["ckpt_name"] == "v1-5-pruned-emaonly.safetensors"
    assert wf["9"]["class_type"] == "SaveImage"
    assert wf["9"]["inputs"]["images"] == ["8", 0]
