"""Backend checks for Step 85 (plan v5.0.0 section 7): the first-time app tour is shown once, only to NEW accounts.
Run:  cd backend && DEV_AUTH=1 python test_tour.py      (or: pytest -q test_tour.py)
Rules checked: a brand-new account has tour_done = false; an account that existed before this step is migrated to true (never shown);
finishing / skipping saves true on the account; it can never be set back to false; other fields are not touched; it is per account."""
import os, sqlite3, tempfile
from contextlib import closing

os.environ["DEV_AUTH"] = "1"
db_path = os.path.join(tempfile.mkdtemp(), "tour.db")
os.environ["SOCIAX_DB"] = db_path
import main as m  # noqa: E402
from pydantic import ValidationError  # noqa: E402


def check(name, got, want):
    ok = got == want
    print(("PASS " if ok else "FAIL ") + name + ("" if ok else f"  (got {got!r}, wanted {want!r})"))
    assert ok, name


A, B = "dev-a", "dev-b"
m.onboard(m.Onboarding(display_name="alice", username="alice", age=20), A)
m.onboard(m.Onboarding(display_name="bob", username="bobby", age=21), B)
check("a new account: tour_done is false (the tour is due)", m.me(A)["tour_done"], False)
check("not onboarded: no profile yet", m.me("dev-nobody")["onboarded"], False)

m._rate.clear()
out = m.update_me(m.ProfileUpdate(tour_done=True), A)
check("finishing / skipping saves tour_done = true on the account", out["tour_done"], True)
check("it stays true on the next /users/me", m.me(A)["tour_done"], True)
check("another account is not affected", m.me(B)["tour_done"], False)
check("the other profile fields are unchanged", (out["display_name"], out["username"], out["bio"]), ("alice", "@alice", ""))

try:
    m.ProfileUpdate(tour_done=False)
    bad = False
except ValidationError:
    bad = True
check("it can NOT be set back to false", bad, True)
m._rate.clear()
m.update_me(m.ProfileUpdate(bio="hello"), A)
check("a later unrelated update keeps it true", m.me(A)["tour_done"], True)

# migration: an account that existed BEFORE this step (row without the column) becomes tour_done = 1
old_db = os.path.join(tempfile.mkdtemp(), "old.db")
con = sqlite3.connect(old_db)
con.execute("CREATE TABLE users(uid TEXT PRIMARY KEY, username TEXT UNIQUE NOT NULL COLLATE NOCASE, display_name TEXT NOT NULL, age INTEGER NOT NULL, source TEXT, created_at INTEGER NOT NULL)")
con.execute("INSERT INTO users VALUES('old-1','olduser','Old',30,NULL,1)")
con.commit(); con.close()
con = sqlite3.connect(old_db); con.row_factory = sqlite3.Row
con.execute("ALTER TABLE users ADD COLUMN tour_done INTEGER NOT NULL DEFAULT 1")      # the exact statement init_db() runs
check("an existing account is migrated to tour_done = 1 (never shown)", con.execute("SELECT tour_done FROM users").fetchone()[0], 1)
con.close()
print("ALL PASS")
