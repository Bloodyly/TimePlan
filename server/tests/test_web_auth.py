def test_root_redirects_to_login_without_session(client):
    r = client.get("/", follow_redirects=False)
    assert r.status_code == 303
    assert r.headers["location"] == "/login"


def test_login_wrong_password(client):
    r = client.post("/login", data={"password": "falsch"})
    assert r.status_code == 200
    assert "Passwort falsch" in r.text


def test_login_logout_roundtrip(client):
    r = client.post("/login", data={"password": "pw"}, follow_redirects=False)
    assert r.status_code == 303
    r2 = client.get("/", follow_redirects=False)
    assert r2.status_code == 303
    assert r2.headers["location"].startswith("/week/")
    client.get("/logout")
    assert client.get("/", follow_redirects=False).headers["location"] == "/login"
