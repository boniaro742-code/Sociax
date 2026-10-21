"""Backend checks for "Report a problem" (Step 28). Run:  cd backend && DEV_AUTH=1 python test_problem_report.py"""
import os, tempfile
from contextlib import closing

os.environ["DEV_AUTH"] = "1"
os.environ["SOCIAX_DB"] = os.path.join(tempfile.mkdtemp(), "t.db")
import main as m  # noqa: E402
from fastapi import HTTPException  # noqa: E402

A, B = "dev-a", "dev-b"
for uid, name in ((A, "alice"), (B, "bob")):
    m.onboard(m.Onboarding(display_name=name, username=name, age=20), uid)


def ok(msg): print("PASS", msg)
def rows(kind="problem"):
    with closing(m.db()) as con:
        return con.execute("SELECT reporter, target_id, reason FROM reports WHERE target_type=?", (kind,)).fetchall()
def send(uid, text, ver="0.33.0"):
    m._rate.clear()
    return m.report_problem(m.ProblemReport(text=text, app_version=ver), uid)
def refused(fn, code):
    try:
        fn()
    except HTTPException as e:
        assert e.status_code == code, (e.status_code, code)
        return
    raise AssertionError("expected HTTP %d" % code)

assert send(A, "  The feed does not refresh after I log in.  ") == {"ok": True}
r = rows()
assert len(r) == 1 and r[0][0] == A and r[0][1] == "0.33.0" and r[0][2] == "The feed does not refresh after I log in."
ok("a problem report is saved (trimmed, with the app version)")

refused(lambda: send(A, "too short"), 400)
refused(lambda: send(A, "          "), 400)
assert len(rows()) == 1
ok("texts shorter than 10 characters are refused and not saved")

assert send(A, "No version sent from this old app build.", None) == {"ok": True}
assert rows()[-1][1] == "app"
ok("a missing app version is stored as 'app'")

m._rate.clear()
for i in range(5):
    m.report_problem(m.ProblemReport(text="Rate limit check number %d" % i), B)
refused(lambda: m.report_problem(m.ProblemReport(text="One more report than allowed"), B), 429)
ok("rate limit: 5 problem reports per hour")

# content reports (posts / shorts) are a different kind and are never touched by problem reports
with closing(m.db()) as con:
    con.execute("INSERT INTO reports(reporter, target_type, target_id, reason, created_at) VALUES (?,?,?,?,?)", (B, "post", "1", "Spam", 1))
    con.commit()

m._rate.clear()
m.delete_account(B)
assert not [x for x in rows() if x[0] == B]
assert [x for x in rows() if x[0] == A]
assert len(rows("post")) == 1
ok("deleting an account removes its own problem reports; other people's reports and content reports stay")

print("ALL PASS")
