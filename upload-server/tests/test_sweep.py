import os
import time
from pathlib import Path

from upload_server.sweep import sweep_once


def _touch_with_age(path: Path, days_old: float) -> None:
    path.write_bytes(b"x")
    old_time = time.time() - days_old * 86400
    os.utime(path, (old_time, old_time))


def test_sweep_removes_files_older_than_retention(tmp_path: Path):
    old_file = tmp_path / "old.jpg"
    _touch_with_age(old_file, days_old=15)

    removed = sweep_once(tmp_path, retention_days=14)

    assert old_file in removed
    assert not old_file.exists()


def test_sweep_keeps_recent_files(tmp_path: Path):
    recent_file = tmp_path / "recent.jpg"
    _touch_with_age(recent_file, days_old=1)

    removed = sweep_once(tmp_path, retention_days=14)

    assert removed == []
    assert recent_file.exists()


def test_sweep_ignores_files_outside_inbox_dir(tmp_path: Path):
    inbox = tmp_path / "inbox"
    inbox.mkdir()
    outside = tmp_path / "kept" / "important.jpg"
    outside.parent.mkdir()
    _touch_with_age(outside, days_old=30)

    removed = sweep_once(inbox, retention_days=14)

    assert removed == []
    assert outside.exists()


def test_sweep_on_missing_dir_returns_empty(tmp_path: Path):
    missing = tmp_path / "does-not-exist"

    assert sweep_once(missing, retention_days=14) == []
