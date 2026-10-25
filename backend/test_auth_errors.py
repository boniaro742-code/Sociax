"""Step 51: the server tells "I cannot check sign-ins right now" (503: the app waits / retries and shows the cause) apart from
"this token is bad" (401: sign in again). Before, a missing Firebase service-account file also answered 401 and the phone looped.
Run:  cd backend && DEV_AUTH=1 python test_auth_errors.py"""
import os, sys, tempfile, types

os.environ["DEV_AUTH"] = "1"
os.environ["SOCIAX_DB"] = os.path.join(tempfile.mkdtemp(), "t.db")
import main as m  # noqa: E402
from fastapi import HTTPException  # noqa: E402


def status(token):
    try:
        return m.current_user("Bearer " + token if token is not None else None)
    except HTTPException as e:
        return e.status_code


class InvalidIdTokenError(ValueError):      # what firebase_admin raises for a bad / expired token
    pass


class CertificateFetchError(Exception):     # what it raises when Google's public keys cannot be fetched
    pass


outcome = {"raise": None, "uid": "u1"}


def verify_id_token(_token):
    if outcome["raise"]:
        raise outcome["raise"]
    return {"uid": outcome["uid"]}


fake = types.ModuleType("firebase_admin")
fake.auth = types.SimpleNamespace(verify_id_token=verify_id_token)
saved = sys.modules.get("firebase_admin")
saved_init = m._init_firebase
sys.modules["firebase_admin"] = fake
try:
    m._init_firebase = lambda: None
    assert status(None) == 401, "no token -> 401"
    assert status("dev-a") == "dev-a", "DEV_AUTH test tokens still work"
    assert status("good-token") == "u1", "a good token gives the uid"
    print("PASS no token = 401; test token and good token still work")

    outcome["raise"] = InvalidIdTokenError("bad")
    assert status("bad-token") == 401, "a bad token -> 401 (sign in again)"
    outcome["raise"] = CertificateFetchError("no keys")
    assert status("any-token") == 503, "Google keys unreachable -> 503, NOT 401"
    print("PASS bad token = 401 (sign in again); Google keys unreachable = 503 (server problem, not the person's)")

    def broken():
        raise RuntimeError("no credentials file")
    m._init_firebase = broken
    outcome["raise"] = None
    assert status("good-token") == 503, "service account missing -> 503, NOT 401"
    print("PASS missing Firebase service account = 503 (the owner must fix the server, the person is not logged out in a loop)")
finally:
    m._init_firebase = saved_init
    if saved is None:
        sys.modules.pop("firebase_admin", None)
    else:
        sys.modules["firebase_admin"] = saved
print("ALL PASS test_auth_errors")
