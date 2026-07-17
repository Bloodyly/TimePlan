import datetime

import pytest

from app import weeks


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


def test_get_week_endpoint(client, device_headers):
    r = client.get("/api/v1/weeks/2026-W31", headers=device_headers)
    assert r.status_code == 200
    body = r.json()
    assert body["week"] == {"id": "2026-W31", "status": "OPEN",
                            "revision": body["week"]["revision"]}
    assert len(body["dates"]) == 7
    assert body["entries"] == []
    assert client.get("/api/v1/weeks/quatsch", headers=device_headers).status_code == 422
