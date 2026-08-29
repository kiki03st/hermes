from __future__ import annotations

import pytest
import requests

from comfyui_bridge.client import (
    ComfyUIExecutionError,
    ComfyUITimeoutError,
    ComfyUIUnavailableError,
    download_output_image,
    submit_prompt,
    wait_for_completion,
)


class _FakeResponse:
    def __init__(self, status_code: int = 200, json_body: dict | None = None, content: bytes = b""):
        self.status_code = status_code
        self._json_body = json_body or {}
        self.content = content

    def json(self):
        return self._json_body


class _FakeHttp:
    def __init__(self, post_response=None, get_responses=None):
        self.post_response = post_response
        self.get_responses = list(get_responses or [])
        self.get_calls: list[str] = []
        self.post_calls: list[tuple[str, dict]] = []

    def post(self, url, json=None, **kwargs):
        self.post_calls.append((url, json))
        return self.post_response

    def get(self, url, **kwargs):
        self.get_calls.append(url)
        return self.get_responses.pop(0)


def test_submit_prompt_returns_prompt_id():
    http = _FakeHttp(post_response=_FakeResponse(200, {"prompt_id": "abc-123", "node_errors": {}}))

    prompt_id = submit_prompt("http://127.0.0.1:8188", {"3": {}}, http=http)

    assert prompt_id == "abc-123"
    assert http.post_calls[0][0] == "http://127.0.0.1:8188/prompt"


def test_submit_prompt_raises_on_node_errors():
    http = _FakeHttp(post_response=_FakeResponse(200, {"prompt_id": "abc", "node_errors": {"3": ["bad seed"]}}))

    with pytest.raises(ComfyUIExecutionError):
        submit_prompt("http://127.0.0.1:8188", {"3": {}}, http=http)


def test_submit_prompt_raises_unavailable_on_connection_error():
    class _RaisingHttp:
        def post(self, url, json=None, **kwargs):
            raise requests.exceptions.ConnectionError("refused")

    with pytest.raises(ComfyUIUnavailableError):
        submit_prompt("http://127.0.0.1:8188", {"3": {}}, http=_RaisingHttp())


def test_wait_for_completion_polls_until_done():
    pending = _FakeResponse(200, {"abc": {"status": {"status_str": "", "completed": False}}})
    done = _FakeResponse(
        200,
        {
            "abc": {
                "status": {"status_str": "success", "completed": True},
                "outputs": {"9": {"images": [{"filename": "hermes_00001_.png", "subfolder": "", "type": "output"}]}},
            }
        },
    )
    http = _FakeHttp(get_responses=[pending, done])

    result = wait_for_completion("http://127.0.0.1:8188", "abc", poll_interval_seconds=0, http=http)

    assert result["status"]["completed"] is True


def test_wait_for_completion_raises_execution_error_on_failure():
    failed = _FakeResponse(200, {"abc": {"status": {"status_str": "error", "completed": True}, "outputs": {}}})
    http = _FakeHttp(get_responses=[failed])

    with pytest.raises(ComfyUIExecutionError):
        wait_for_completion("http://127.0.0.1:8188", "abc", poll_interval_seconds=0, http=http)


def test_wait_for_completion_raises_timeout(monkeypatch):
    import time

    pending = _FakeResponse(200, {"abc": {"status": {"status_str": "", "completed": False}}})
    http = _FakeHttp(get_responses=[pending] * 3)
    clock = iter([0.0, 0.5, 10.0])
    monkeypatch.setattr(time, "monotonic", lambda: next(clock))

    with pytest.raises(ComfyUITimeoutError):
        wait_for_completion("http://127.0.0.1:8188", "abc", timeout_seconds=1.0, poll_interval_seconds=0, http=http)


def test_download_output_image_returns_bytes():
    http = _FakeHttp()
    http.get_responses = [_FakeResponse(200, content=b"\x89PNG...")]

    data = download_output_image("http://127.0.0.1:8188", "hermes_00001_.png", "", "output", http=http)

    assert data == b"\x89PNG..."
    assert "filename=hermes_00001_.png" in http.get_calls[0]
