import pathlib
import sys

import pytest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))


@pytest.fixture()
def client(tmp_path, monkeypatch):
    monkeypatch.setenv("TIMEPLAN_DB", str(tmp_path / "test.db"))
    monkeypatch.setenv("TIMEPLAN_DEVICE_TOKENS", "tablet-01:testtoken")
    monkeypatch.setenv("TIMEPLAN_ADMIN_PASSWORD", "pw")
    monkeypatch.setenv("TIMEPLAN_SECRET_KEY", "test-secret")
    from fastapi.testclient import TestClient

    from app.main import create_app

    with TestClient(create_app()) as c:
        yield c


@pytest.fixture()
def device_headers():
    return {"Authorization": "Bearer testtoken"}
