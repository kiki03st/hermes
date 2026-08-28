from pathlib import Path

from upload_server.storage import sanitize_filename, save_upload


def test_sanitize_filename_strips_directory_components():
    assert sanitize_filename("../../etc/passwd") == "passwd"
    assert sanitize_filename("C:\\Users\\x\\photo.jpg") == "photo.jpg"


def test_sanitize_filename_replaces_unsafe_characters():
    assert sanitize_filename('weird name?*.png') == "weird_name__.png"


def test_sanitize_filename_falls_back_when_empty():
    assert sanitize_filename("") == "file"
    assert sanitize_filename("   ") == "file"


def test_save_upload_writes_bytes_with_uuid_prefix(tmp_path: Path):
    target = save_upload(tmp_path, "photo.jpg", b"hello-bytes")

    assert target.exists()
    assert target.read_bytes() == b"hello-bytes"
    assert target.name.endswith("_photo.jpg")
    assert target.parent == tmp_path


def test_save_upload_distinct_calls_produce_distinct_files(tmp_path: Path):
    first = save_upload(tmp_path, "a.txt", b"1")
    second = save_upload(tmp_path, "a.txt", b"2")

    assert first != second
    assert first.exists() and second.exists()
