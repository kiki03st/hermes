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
    assert target.parent == tmp_path.resolve()


def test_save_upload_returns_absolute_path_even_given_relative_inbox_dir(tmp_path, monkeypatch):
    # 실측(2026-08-29): UPLOAD_SERVER_INBOX_DIR을 상대경로("./uploads/inbox")로 띄우면
    # 응답 path도 상대경로였다 — 에이전트 프로세스는 cwd가 완전히 다른 별개 프로세스라
    # (예: "C:\\Users\\ksy") 그 상대경로를 자기 cwd 기준으로 찾다가 실패했다
    # (agent.log: "media file not found: 'uploads\\inbox\\...'"). 서버가 무조건
    # 절대경로를 돌려주게 고쳐서, 실행 시 상대경로를 넘겨도 안전해야 한다.
    monkeypatch.chdir(tmp_path)
    target = save_upload(Path("relative_inbox"), "photo.jpg", b"x")

    assert target.is_absolute()


def test_save_upload_distinct_calls_produce_distinct_files(tmp_path: Path):
    first = save_upload(tmp_path, "a.txt", b"1")
    second = save_upload(tmp_path, "a.txt", b"2")

    assert first != second
    assert first.exists() and second.exists()
