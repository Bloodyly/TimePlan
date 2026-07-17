import os
from dataclasses import dataclass


def _parse_device_tokens(raw: str) -> dict[str, str]:
    tokens: dict[str, str] = {}
    for pair in raw.split(","):
        device_id, _, token = pair.strip().partition(":")
        if device_id and token:
            tokens[device_id] = token
    return tokens


@dataclass(frozen=True)
class Settings:
    db_path: str
    secret_key: str
    admin_password: str
    device_tokens: dict
    max_entry_bytes: int
    max_monteure: int = 15
    max_azubis: int = 10


def load_settings() -> Settings:
    return Settings(
        db_path=os.environ.get("TIMEPLAN_DB", "./data/timeplan.db"),
        secret_key=os.environ.get("TIMEPLAN_SECRET_KEY", "dev-secret-change-me"),
        admin_password=os.environ.get("TIMEPLAN_ADMIN_PASSWORD", "admin"),
        device_tokens=_parse_device_tokens(os.environ.get("TIMEPLAN_DEVICE_TOKENS", "")),
        max_entry_bytes=int(os.environ.get("TIMEPLAN_MAX_ENTRY_BYTES", "2097152")),
    )
