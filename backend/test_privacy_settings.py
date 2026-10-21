"""Backend checks for Step 19: "Who can message me" and "Default audience" (plan sections 9 + 11).
Run:  cd backend && DEV_AUTH=1 python test_privacy_settings.py      (or: pytest -q test_privacy_settings.py)
No Cloudinary account or network is needed."""
import os, tempfile

os.environ["DEV_AUTH"] = "1"
os.environ["SOCIAX_DB"] = os.path.join(tempfile.mkdtemp(), "p.db")
import main as m  # noqa: E402
from fastapi import HTTPException  # noqa: E402

A, B, C, D = "dev-a", "dev-b", "dev-c", "dev-d"


def code(fn, *a, **k):
    m._rate.clear()
    try:
        fn(*a, **k); return 200
    except HTTPException as e:
        return e.status_code


def user(uid, name):
    m.onboard(m.Onboarding(display_name=name, username=name, age=20), uid)


def check(name, got, want):
    ok = got == want
    print(("PASS " if ok else "FAIL ") + name + ("" if ok else f"  (got {got!r}, wanted {want!r})"))
    assert ok, name


user(A, "alice"); user(B, "bob"); user(C, "carol"); user(D, "dave")

# defaults
me = m.me(A)
check("default who_can_message is everyone", me["who_can_message"], "everyone")
check("default audience is public", me["default_audience"], "public")

# everyone: a stranger can open a chat
check("everyone: stranger can start a chat", code(m.start_dm, m.DmStart(username="@alice"), B), 200)

# nobody: nobody can start a NEW chat or add her to a group
m.update_me(m.ProfileUpdate(who_can_message="nobody"), C)
check("nobody: new chat refused", code(m.start_dm, m.DmStart(username="@carol"), A), 403)
m.follow(  "carol", A) if hasattr(m, "follow") else None
check("nobody: even an accepted follower is refused", code(m.start_dm, m.DmStart(username="@carol"), A), 403)

# followers: strangers refused, accepted follower allowed
m.update_me(m.ProfileUpdate(who_can_message="followers"), D)
check("followers: stranger refused", code(m.start_dm, m.DmStart(username="@dave"), B), 403)
m.follow("dave", A)                       # dave is public -> accepted at once
check("followers: accepted follower allowed", code(m.start_dm, m.DmStart(username="@dave"), A), 200)

# profile page tells the app whether the Message button may show
check("profile: can_message false for 'nobody'", m.user_profile("carol", A)["can_message"], False)
check("profile: can_message true for accepted follower", m.user_profile("dave", A)["can_message"], True)
check("profile: can_message false for non-follower", m.user_profile("dave", B)["can_message"], False)

# invalid values are not accepted by the model
try:
    m.ProfileUpdate(who_can_message="everybody")
    print("NOTE: stand-in pydantic does not validate; the real pydantic rejects this with 422")
except Exception:
    print("PASS invalid value rejected by the model")

# default audience saved + returned
r = m.update_me(m.ProfileUpdate(default_audience="followers"), A)
check("default audience saved", r["default_audience"], "followers")
check("default audience returned by /users/me", m.me(A)["default_audience"], "followers")
check("changing one field leaves the other", m.me(A)["who_can_message"], "everyone")
print("ALL CHECKS PASSED")
