from pathlib import Path

from upload_server.storage import resolve_generated_path, sanitize_filename, save_upload


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


def test_resolve_generated_path_returns_path_inside_root(tmp_path: Path):
    (tmp_path / "comfyui").mkdir()
    (tmp_path / "comfyui" / "a.png").write_bytes(b"x")

    target = resolve_generated_path(tmp_path, "comfyui", "a.png")

    assert target == (tmp_path / "comfyui" / "a.png").resolve()


def test_resolve_generated_path_blocks_traversal_via_tool(tmp_path: Path):
    # sanitize_filename은 슬래시가 있는 경로에서 마지막 구성요소만 남기지만(예:
    # "../../etc/passwd" -> "passwd"), 슬래시 없이 단독으로 오는 ".." 자체는 그대로
    # 통과시킨다(실측: Path("..").name == "..", 빈 문자열이 아니라서 "file" 대체 규칙이
    # 안 걸림). 그래서 여기 있는 .resolve()+is_relative_to() 검증이 이 케이스를 막는
    # 유일한 방어선이다 — defense-in-depth가 장식이 아니라 필수임을 이 테스트가 보여준다.
    assert resolve_generated_path(tmp_path, "..", "a.png") is None


def test_resolve_generated_path_blocks_traversal_via_filename(tmp_path: Path):
    target = resolve_generated_path(tmp_path, "comfyui", "../../etc/passwd")

    assert target is not None
    assert target.name == "passwd"
    assert target.parent.name == "comfyui"
