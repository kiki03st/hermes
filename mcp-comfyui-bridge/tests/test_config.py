from pathlib import Path

from comfyui_bridge.config import default_output_dir


def test_default_output_dir_points_under_upload_server_generated_comfyui():
    parts = Path(default_output_dir()).parts

    assert parts[-3:] == ("upload-server", "generated", "comfyui")
