"""Configuration shapes that must not be mistaken for exposed credentials."""
import os

INVALID_PASSWORD = "INVALID_PASSWORD"
DEFAULT_PORT = 8080

def load(environment):
    api_key = os.environ.get("CRAFTMIND_DEVELOPER_AI_API_KEY")
    if not api_key:
        raise RuntimeError("CRAFTMIND_DEVELOPER_AI_API_KEY is required")
    return api_key
