"""Chapa (payments) client. The ONLY place that talks to Chapa.
CHAPA_SECRET_KEY lives in backend/.env (server only) - never in the Android app.
TEST MODE ONLY: a live key (not CHASECK_TEST... / CHAPA_TEST_...) is refused unless the owner sets CHAPA_ALLOW_LIVE=1.
Endpoints are from Chapa's official docs (developer.chapa.co):
  POST https://api.chapa.co/v1/transaction/initialize
  GET  https://api.chapa.co/v1/transaction/verify/<tx_ref>"""
import os

import httpx

BASE = "https://api.chapa.co/v1"


class ChapaError(Exception):
    """Chapa could not be reached, refused the request, or is not configured."""


def _key() -> str:
    key = os.getenv("CHAPA_SECRET_KEY", "").strip()
    if not key:
        raise ChapaError("CHAPA_SECRET_KEY is not set")
    # Test keys: older "CHASECK_TEST-..." and the newer dashboard keys "CHAPA_TEST_PRIV_...".
    if not key.startswith(("CHASECK_TEST", "CHAPA_TEST_")) and os.getenv("CHAPA_ALLOW_LIVE") != "1":
        raise ChapaError("Live Chapa key refused: Sociax is in TEST mode until the owner approves live payments")
    return key


def configured() -> bool:
    try:
        _key()
        return True
    except ChapaError:
        return False


def _call(method: str, path: str, **kw) -> dict:
    try:
        r = httpx.request(method, BASE + path, headers={"Authorization": f"Bearer {_key()}"}, timeout=20, **kw)
        data = r.json()
    except ChapaError:
        raise
    except Exception:  # network error, bad JSON ... (never print the key)
        raise ChapaError("Could not reach Chapa")
    if not isinstance(data, dict):
        raise ChapaError("Unexpected answer from Chapa")
    data["_http_status"] = r.status_code
    return data


def initialize(payload: dict) -> dict:
    """payload: amount, currency, email, first_name, last_name, tx_ref, (return_url), (customization)."""
    return _call("POST", "/transaction/initialize", json=payload)


def verify(tx_ref: str) -> dict:
    return _call("GET", f"/transaction/verify/{tx_ref}")
