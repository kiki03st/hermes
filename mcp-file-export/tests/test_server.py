from __future__ import annotations

from pathlib import Path

from file_export import server


def test_export_file_saves_content_and_returns_path(monkeypatch, tmp_path):
    monkeypatch.setattr(server, "OUTPUT_DIR", str(tmp_path))

    result = server.save_document_for_user_to_view_on_phone(filename="report.md", content="# 제목\n내용")

    saved = Path(result["path"])
    assert saved.exists()
    assert saved.read_text(encoding="utf-8") == "# 제목\n내용"
    assert saved.parent == tmp_path.resolve()


def test_export_file_sanitizes_filename(monkeypatch, tmp_path):
    monkeypatch.setattr(server, "OUTPUT_DIR", str(tmp_path))

    result = server.save_document_for_user_to_view_on_phone(filename="../../etc/passwd.md", content="x")

    assert Path(result["path"]).name == "passwd.md"
