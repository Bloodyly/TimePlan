from .. import db


def get_display_settings(conn) -> dict:
    row = conn.execute(
        "SELECT show_saturday, show_sunday FROM app_settings WHERE id=1"
    ).fetchone()
    return {"show_saturday": bool(row["show_saturday"]),
            "show_sunday": bool(row["show_sunday"])}


def update_display_settings(conn, show_saturday: bool, show_sunday: bool,
                            actor=("web", "web-admin")) -> dict:
    with db.tx(conn):
        revision = db.record_change(conn, "app_settings", "1", "updated")
        conn.execute(
            "UPDATE app_settings SET show_saturday=?, show_sunday=?, revision=?"
            " WHERE id=1",
            (int(show_saturday), int(show_sunday), revision),
        )
        db.audit(conn, actor[0], actor[1], "updated", "app_settings", "1", None)
    return get_display_settings(conn)
