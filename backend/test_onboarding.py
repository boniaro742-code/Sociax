"""Backend checks for persistent login / one-time onboarding (plan v3.10.0).
Run:  cd backend && DEV_AUTH=1 python test_onboarding.py      (or: pytest -q test_onboarding.py)
No Cloudinary account or network is needed."""
import os, tempfile

os.environ["DEV_AUTH"] = "1"
os.environ["SOCIAX_DB"] = os.path.join(tempfile.mkdtemp(), "t.db")
import main as m  # noqa: E402
from fastapi import HTTPException  # noqa: E402

A, B = "dev-a", "dev-b"


def code(fn, *a, **k):
    m._rate.clear()
    try:
        fn(*a, **k); return 200
    except HTTPException as e:
        return e.status_code


# a new account: nothing saved yet -> the app must show onboarding; after ONE successful save -> onboarded
assert m.me(A) == {"onboarded": False}
r = m.onboard(m.Onboarding(display_name="Alice A", username="alice", age=20, source="Other"), A)
assert r["username"] == "@alice" and "already_onboarded" not in r
first = m.me(A)
assert first["onboarded"] is True and first["display_name"] == "Alice A" and first["username"] == "@alice"

# the person edits the profile; later the app is closed and opened again: /users/me answers the same, nothing is reset
m.update_me(m.ProfileUpdate(display_name="Alice Edited", bio="my bio", is_private=True), A)
saved = m.me(A)
assert saved["display_name"] == "Alice Edited" and saved["bio"] == "my bio" and saved["is_private"] is True
for _ in range(3):
    assert m.me(A) == saved                                     # opening the app any number of times changes nothing

# the first answer was lost and the phone sends the SAME onboarding again (or a double tap): not an error, not a second profile,
# and above all it must NOT overwrite the saved name / age / privacy with what the form says
r2 = m.onboard(m.Onboarding(display_name="Alice A", username="@Alice", age=19), A)
assert r2["username"] == "@alice" and r2["already_onboarded"] is True
assert m.me(A) == saved
import sqlite3
con = sqlite3.connect(m.DB_PATH)
assert con.execute("SELECT COUNT(*) FROM users WHERE uid=?", (A,)).fetchone()[0] == 1
assert con.execute("SELECT age FROM users WHERE uid=?", (A,)).fetchone()[0] == 20       # the saved age was not replaced
con.close()

# another @username for an account that already has one is still refused, and nothing changes
assert code(m.onboard, m.Onboarding(display_name="Other", username="someone_else", age=20), A) == 409
assert m.me(A) == saved

# an @username used by somebody else is refused for a NEW account, and that account stays "not onboarded" (it can try again)
assert code(m.onboard, m.Onboarding(display_name="Bob", username="alice", age=20), B) == 409
assert m.me(B) == {"onboarded": False}
assert code(m.onboard, m.Onboarding(display_name="Bob", username="bobby", age=14), B) == 403      # under 15: nothing saved
assert m.me(B) == {"onboarded": False}
assert m.onboard(m.Onboarding(display_name="Bob", username="bobby", age=21), B)["username"] == "@bobby"
assert m.me(B)["onboarded"] is True and m.me(A) == saved                                          # two accounts never mix

# startup never sends empty values: an update with NO fields (or only the language) keeps name, bio and privacy
m.update_me(m.ProfileUpdate(), A)
assert m.me(A) == saved
m.update_me(m.ProfileUpdate(language="fr"), A)
after = m.me(A)
assert after["display_name"] == "Alice Edited" and after["bio"] == "my bio" and after["is_private"] is True and after["language"] == "fr"
# a blank name is refused by the server itself (it can never replace the saved one)
try:
    m.ProfileUpdate(display_name="")
    raise AssertionError("an empty display name must be refused")
except ValueError:
    pass
assert m.me(A)["display_name"] == "Alice Edited"

print("test_onboarding: all checks passed")
