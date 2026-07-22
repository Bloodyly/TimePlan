from app.repos import app_settings


def test_default_settings_are_both_visible(client):
    conn = client.app.state.db
    settings = app_settings.get_display_settings(conn)
    assert settings == {"show_saturday": True, "show_sunday": True}


def test_update_display_settings_persists(client):
    conn = client.app.state.db
    updated = app_settings.update_display_settings(conn, False, True)
    assert updated == {"show_saturday": False, "show_sunday": True}
    assert app_settings.get_display_settings(conn) == {"show_saturday": False, "show_sunday": True}

    updated_again = app_settings.update_display_settings(conn, True, False)
    assert updated_again == {"show_saturday": True, "show_sunday": False}
