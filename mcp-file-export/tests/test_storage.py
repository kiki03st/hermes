from pathlib import Path

from file_export.storage import sanitize_filename, save_text


def test_sanitize_filename_strips_directory_components():
    assert sanitize_filename("../../etc/passwd") == "passwd"
    assert sanitize_filename("C:\\Users\\x\\report.md") == "report.md"


def test_sanitize_filename_replaces_unsafe_characters():
    assert sanitize_filename("weird name?*.md") == "weird_name__.md"


def test_sanitize_filename_falls_back_when_empty():
    assert sanitize_filename("") == "file.txt"
    assert sanitize_filename("   ") == "file.txt"


def test_save_text_writes_utf8_content_and_returns_absolute_path(tmp_path: Path):
    target = save_text(tmp_path, "report.md", "# 제목\n한글 내용")

    assert target.exists()
    assert target.read_text(encoding="utf-8") == "# 제목\n한글 내용"
    assert target.is_absolute()


def test_save_text_uses_sanitized_filename_directly_no_uuid_prefix(tmp_path: Path):
    # 업로드 파일(save_upload)과 다르게 충돌 방지용 uuid 접두어를 안 붙인다 — 같은 이름으로
    # 다시 만들면 그냥 덮어쓴다(설계 결정: 리포트류는 최신 버전 하나만 있으면 됨, YAGNI).
    target = save_text(tmp_path, "report.md", "content")

    assert target.name == "report.md"


def test_save_text_overwrites_existing_file_with_same_name(tmp_path: Path):
    save_text(tmp_path, "report.md", "first")
    target = save_text(tmp_path, "report.md", "second")

    assert target.read_text(encoding="utf-8") == "second"


def test_save_text_returns_absolute_path_even_given_relative_output_dir(tmp_path, monkeypatch):
    # save_upload과 같은 실측 버그 방지(2026-08-29) — 항상 절대경로를 돌려줘야 한다.
    monkeypatch.chdir(tmp_path)
    target = save_text(Path("relative_out"), "report.md", "x")

    assert target.is_absolute()
