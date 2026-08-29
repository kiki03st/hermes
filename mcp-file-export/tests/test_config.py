from pathlib import Path

from file_export.config import default_output_dir


def test_default_output_dir_points_under_upload_server_generated_files():
    parts = Path(default_output_dir()).parts

    assert parts[-3:] == ("upload-server", "generated", "files")
