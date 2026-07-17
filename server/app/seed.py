"""Testdaten: python -m app.seed  (nutzt TIMEPLAN_DB)."""
from . import db
from .config import load_settings
from .repos import workers

MONTEURE = [
    ("101", "Albrecht"), ("104", "Bergmann"), ("112", "Conrad"),
    ("118", "Dietrich"), ("125", "Ebert"), ("131", "Falk"),
    ("136", "Grimm"), ("140", "Hartmann"), ("147", "Ilgner"),
    ("152", "Jansen"), ("158", "Kaiser"), ("163", "Lindner"),
    ("169", "Martens"), ("174", "Nowak"), ("180", "Ostermann"),
]
AZUBIS = [
    ("501", "Petersen"), ("502", "Quandt"), ("503", "Richter"),
    ("504", "Sommer"), ("505", "Thiel"), ("506", "Ulrich"), ("507", "Vogel"),
]


def main() -> None:
    settings = load_settings()
    conn = db.connect(settings.db_path)
    db.init_db(conn)
    if conn.execute("SELECT COUNT(*) AS n FROM workers").fetchone()["n"]:
        print("workers vorhanden – kein Seed")
        return
    for number, name in MONTEURE:
        workers.create_worker(conn, number, name, "monteur")
    for number, name in AZUBIS:
        workers.create_worker(conn, number, name, "azubi")
    print(f"Seed ok: {len(MONTEURE)} Monteure, {len(AZUBIS)} Azubis")


if __name__ == "__main__":
    main()
