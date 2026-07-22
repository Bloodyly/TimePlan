import datetime

import pytest

from app import weeks
from app.repos import app_settings


def test_week_dates_iso():
    dates = weeks.week_dates("2026-W31")
    assert dates[0] == datetime.date(2026, 7, 27)
    assert dates[6] == datetime.date(2026, 8, 2)


def test_adjacent_over_year_boundary():
    assert weeks.adjacent_week_id("2026-W01", -1) == "2025-W52"
    assert weeks.adjacent_week_id("2025-W52", 1) == "2026-W01"


def test_parse_week_id_invalid():
    with pytest.raises(ValueError):
        weeks.parse_week_id("2026-31")
    with pytest.raises(ValueError):
        weeks.parse_week_id("2026-W60")


def test_cell_id_roundtrip():
    cid = weeks.make_cell_id("2026-W31", "w-abc12345", datetime.date(2026, 7, 30))
    assert cid == "2026-W31_w-abc12345_2026-07-30"
    assert weeks.parse_cell_id(cid) == ("2026-W31", "w-abc12345", "2026-07-30")
    with pytest.raises(ValueError):
        weeks.parse_cell_id("2026-W31_w-abc12345_2026-09-01")  # Datum nicht in KW31


def test_visible_week_dates_both_shown(client):
    conn = client.app.state.db
    dates = weeks.visible_week_dates(conn, "2026-W31")
    assert len(dates) == 7
    assert dates[-1] == datetime.date(2026, 8, 2)  # So


def test_visible_week_dates_saturday_hidden(client):
    conn = client.app.state.db
    app_settings.update_display_settings(conn, False, True)
    dates = weeks.visible_week_dates(conn, "2026-W31")
    assert datetime.date(2026, 8, 1) not in dates  # Sa fehlt
    assert datetime.date(2026, 8, 2) in dates       # So bleibt
    assert len(dates) == 6


def test_visible_week_dates_both_hidden(client):
    conn = client.app.state.db
    app_settings.update_display_settings(conn, False, False)
    dates = weeks.visible_week_dates(conn, "2026-W31")
    assert len(dates) == 5
    assert dates[-1] == datetime.date(2026, 7, 31)  # Fr


def test_visible_week_dates_sunday_hidden(client):
    conn = client.app.state.db
    app_settings.update_display_settings(conn, True, False)
    dates = weeks.visible_week_dates(conn, "2026-W31")
    assert datetime.date(2026, 8, 1) in dates       # Sa bleibt
    assert datetime.date(2026, 8, 2) not in dates   # So fehlt
    assert len(dates) == 6


def test_get_week_endpoint(client, device_headers):
    r = client.get("/api/v1/weeks/2026-W31", headers=device_headers)
    assert r.status_code == 200
    body = r.json()
    assert body["week"] == {"id": "2026-W31", "status": "OPEN",
                            "revision": body["week"]["revision"]}
    assert len(body["dates"]) == 7
    assert body["entries"] == []
    assert client.get("/api/v1/weeks/quatsch", headers=device_headers).status_code == 422


def test_get_week_endpoint_dates_shortened_when_weekend_hidden(client, device_headers):
    app_settings.update_display_settings(client.app.state.db, False, False)
    r = client.get("/api/v1/weeks/2026-W31", headers=device_headers)
    assert r.status_code == 200
    body = r.json()
    assert len(body["dates"]) == 5
    assert body["dates"][-1] == "2026-07-31"
