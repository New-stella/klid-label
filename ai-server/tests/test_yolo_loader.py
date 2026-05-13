"""yolo_loader._resolve_weights_path / _load_yolo fallback 테스트 (Phase 1).

Phase 1 (모델 업그레이드 + conf 기본값 하향) 의 핵심 변경:
- primary 가중치 (`yolov8m.pt`) 부재 시 `YOLO_WEIGHTS_FALLBACK_PATH` 로 자동 대체
- 둘 다 부재 시 mock 응답 (mock_reason=weights_missing)
"""

from __future__ import annotations

import os
from pathlib import Path
from typing import Any

import pytest

from app.config import reload_settings
from app.models import yolo_loader


@pytest.fixture(autouse=True)
def _reset_loader_state():
    """매 테스트마다 yolo_loader 싱글톤 + 캐시 초기화."""
    yolo_loader.reset_yolo_model()
    yolo_loader.reset_yolo_trackers()
    yield
    yolo_loader.reset_yolo_model()
    yolo_loader.reset_yolo_trackers()


def _write_dummy_pt(path: Path) -> None:
    """가중치 파일 존재 시뮬레이션 — 실제 ultralytics 로드는 monkeypatch 로 차단."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"\x00\x00\x00\x00")


def test_resolve_weights_path_primary_있으면_primary_반환(tmp_path, monkeypatch) -> None:
    primary = tmp_path / "weights" / "yolov8m.pt"
    fallback = tmp_path / "weights" / "yolov8n.pt"
    _write_dummy_pt(primary)
    _write_dummy_pt(fallback)

    monkeypatch.setenv("AI_MOCK_MODE", "false")
    monkeypatch.setenv("YOLO_WEIGHTS_PATH", str(primary))
    monkeypatch.setenv("YOLO_WEIGHTS_FALLBACK_PATH", str(fallback))
    reload_settings()

    path, reason = yolo_loader._resolve_weights_path()
    assert path == str(primary)
    assert reason is None


def test_resolve_weights_path_primary_부재_fallback_있으면_fallback_사용(
    tmp_path, monkeypatch
) -> None:
    primary = tmp_path / "weights" / "yolov8m.pt"  # 미생성
    fallback = tmp_path / "weights" / "yolov8n.pt"
    _write_dummy_pt(fallback)

    monkeypatch.setenv("AI_MOCK_MODE", "false")
    monkeypatch.setenv("YOLO_WEIGHTS_PATH", str(primary))
    monkeypatch.setenv("YOLO_WEIGHTS_FALLBACK_PATH", str(fallback))
    reload_settings()

    path, reason = yolo_loader._resolve_weights_path()
    assert path == str(fallback)
    assert reason is not None
    assert "primary missing" in reason
    assert str(primary) in reason


def test_resolve_weights_path_둘_다_부재면_weights_missing(tmp_path, monkeypatch) -> None:
    primary = tmp_path / "weights" / "yolov8m.pt"
    fallback = tmp_path / "weights" / "yolov8n.pt"

    monkeypatch.setenv("AI_MOCK_MODE", "false")
    monkeypatch.setenv("YOLO_WEIGHTS_PATH", str(primary))
    monkeypatch.setenv("YOLO_WEIGHTS_FALLBACK_PATH", str(fallback))
    reload_settings()

    path, reason = yolo_loader._resolve_weights_path()
    # weights_missing 사유 + 경로는 primary 유지 (호출자가 mock 처리)
    assert reason == "weights_missing"
    assert path == str(primary)


def test_yolo_loader_primary_가중치_있으면_정상_로드(tmp_path, monkeypatch) -> None:
    """primary 가중치 존재 시 ultralytics YOLO 로드 호출, mock_reason=None."""
    primary = tmp_path / "weights" / "yolov8m.pt"
    _write_dummy_pt(primary)

    monkeypatch.setenv("AI_MOCK_MODE", "false")
    monkeypatch.setenv("YOLO_WEIGHTS_PATH", str(primary))
    monkeypatch.setenv("YOLO_WEIGHTS_FALLBACK_PATH", str(tmp_path / "weights" / "yolov8n.pt"))
    reload_settings()

    # ultralytics YOLO 를 stub 으로 대체 (실제 가중치 로드 회피)
    loaded_path: dict[str, Any] = {}

    class _FakeYolo:
        def __init__(self, path: str) -> None:
            loaded_path["path"] = path

    import sys
    import types

    fake_module = types.ModuleType("ultralytics")
    fake_module.YOLO = _FakeYolo  # type: ignore[attr-defined]
    monkeypatch.setitem(sys.modules, "ultralytics", fake_module)

    model = yolo_loader.get_yolo_model()
    assert model is not None
    assert isinstance(model, _FakeYolo)
    assert loaded_path["path"] == str(primary)
    assert yolo_loader.get_yolo_mock_reason() is None


def test_yolo_loader_primary_부재_fallback_경로_사용(tmp_path, monkeypatch, caplog) -> None:
    """primary 부재 + fallback 있으면 fallback 으로 로드 + WARN 로그."""
    primary = tmp_path / "weights" / "yolov8m.pt"  # 미생성
    fallback = tmp_path / "weights" / "yolov8n.pt"
    _write_dummy_pt(fallback)

    monkeypatch.setenv("AI_MOCK_MODE", "false")
    monkeypatch.setenv("YOLO_WEIGHTS_PATH", str(primary))
    monkeypatch.setenv("YOLO_WEIGHTS_FALLBACK_PATH", str(fallback))
    reload_settings()

    loaded_path: dict[str, Any] = {}

    class _FakeYolo:
        def __init__(self, path: str) -> None:
            loaded_path["path"] = path

    import sys
    import types

    fake_module = types.ModuleType("ultralytics")
    fake_module.YOLO = _FakeYolo  # type: ignore[attr-defined]
    monkeypatch.setitem(sys.modules, "ultralytics", fake_module)

    import logging

    with caplog.at_level(logging.WARNING, logger=yolo_loader.logger.name):
        model = yolo_loader.get_yolo_model()

    assert model is not None
    assert loaded_path["path"] == str(fallback)
    assert yolo_loader.get_yolo_mock_reason() is None
    # WARN 로그에 fallback 사유 포함 확인
    assert any("fallback" in r.message for r in caplog.records)


def test_yolo_loader_둘_다_부재면_mock_reason_weights_missing(tmp_path, monkeypatch) -> None:
    primary = tmp_path / "weights" / "yolov8m.pt"
    fallback = tmp_path / "weights" / "yolov8n.pt"

    monkeypatch.setenv("AI_MOCK_MODE", "false")
    monkeypatch.setenv("YOLO_WEIGHTS_PATH", str(primary))
    monkeypatch.setenv("YOLO_WEIGHTS_FALLBACK_PATH", str(fallback))
    reload_settings()

    model = yolo_loader.get_yolo_model()
    assert model is None
    assert yolo_loader.get_yolo_mock_reason() == "weights_missing"


def test_create_fresh_tracker_primary_부재_fallback_사용(tmp_path, monkeypatch) -> None:
    """_create_fresh_tracker 도 _resolve_weights_path 를 따라 fallback 사용."""
    primary = tmp_path / "weights" / "yolov8m.pt"  # 미생성
    fallback = tmp_path / "weights" / "yolov8n.pt"
    _write_dummy_pt(fallback)

    monkeypatch.setenv("AI_MOCK_MODE", "false")
    monkeypatch.setenv("YOLO_WEIGHTS_PATH", str(primary))
    monkeypatch.setenv("YOLO_WEIGHTS_FALLBACK_PATH", str(fallback))
    reload_settings()

    loaded_path: dict[str, Any] = {}

    class _FakeYolo:
        def __init__(self, path: str) -> None:
            loaded_path["path"] = path

    import sys
    import types

    fake_module = types.ModuleType("ultralytics")
    fake_module.YOLO = _FakeYolo  # type: ignore[attr-defined]
    monkeypatch.setitem(sys.modules, "ultralytics", fake_module)

    instance = yolo_loader._create_fresh_tracker()
    assert isinstance(instance, _FakeYolo)
    assert loaded_path["path"] == str(fallback)
