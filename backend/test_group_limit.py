"""Backend checks for Step 87 (plan v5.0.0 section 9): a group has at most 50 members IN TOTAL (the creator / admins included).
Run:  cd backend && DEV_AUTH=1 python test_group_limit.py      (or: pytest -q test_group_limit.py)
Rules checked: the limit is 50 total (not 50 + creator); creating with 50 other people is refused, with 49 it works (exactly 50 inside);
adding the 51st person is refused with a clear "full" message; one place frees up when someone leaves / is removed; a duplicate is refused;
two admins adding at the same moment cannot both take the last place; an older group that is already bigger than 50 keeps everyone but cannot grow;
group info tells the app the limit and whether the group is full."""
import os, tempfile, threading

os.environ["DEV_AUTH"] = "1"
os.environ["SOCIAX_DB"] = os.path.join(tempfile.mkdtemp(), "g.db")
import main as m  # noqa: E402
from contextlib import closing  # noqa: E402
from fastapi import HTTPException  # noqa: E402


def check(name, got, want):
    ok = got == want
    print(("PASS " if ok else "FAIL ") + name + ("" if ok else f"  (got {got!r}, wanted {want!r})"))
    assert ok, name


def code(fn, *a, **k):
    m._rate.clear()
    try:
        fn(*a, **k); return 200
    except HTTPException as e:
        return e.status_code


def detail(fn, *a, **k):
    m._rate.clear()
    try:
        fn(*a, **k); return ""
    except HTTPException as e:
        return str(e.detail)


OWNER, ADMIN2 = "dev-owner", "dev-admin2"
names = {}
for i in range(60):
    uid = f"dev-u{i}"
    names[uid] = f"person{i:02d}"
    m.onboard(m.Onboarding(display_name=f"P{i}", username=names[uid], age=20), uid)
m.onboard(m.Onboarding(display_name="Owner", username="owner", age=20), OWNER)
m.onboard(m.Onboarding(display_name="Admin2", username="admintwo", age=20), ADMIN2)
check("the limit is 50 TOTAL", m.GROUP_MAX_MEMBERS, 50)

others = [names[f"dev-u{i}"] for i in range(60)]
check("creating a group with 50 OTHER people (51 in total) is refused",
      code(m.create_group, m.NewGroup(name="Too big", usernames=others[:50], confirmed=True), OWNER), 422)
check("...and the message says the limit includes the creator",
      "including you" in detail(m.create_group, m.NewGroup(name="Too big", usernames=others[:50], confirmed=True), OWNER), True)
with closing(m.db()) as con:
    check("nothing was created by the refused request", con.execute("SELECT COUNT(*) FROM conversations WHERE kind='group'").fetchone()[0], 0)

m._rate.clear()
g = m.create_group(m.NewGroup(name="Exactly fifty", usernames=others[:48], confirmed=True), OWNER)    # owner + 48 = 49
gid = g["id"]
with closing(m.db()) as con:
    check("owner + 48 others = 49 members", con.execute("SELECT COUNT(*) FROM conv_members WHERE conv_id=?", (gid,)).fetchone()[0], 49)
info = m.group_info(gid, OWNER)
check("group info: limit 50, 49 inside, not full", (info["max_members"], info["member_count"], info["full"]), (50, 49, False))

check("a duplicate is refused (409)", code(m.add_member, gid, m.MemberAdd(username=others[0]), OWNER), 409)
check("the 50th member can be added", code(m.add_member, gid, m.MemberAdd(username=others[48]), OWNER), 200)
info = m.group_info(gid, OWNER)
check("group info: 50 inside = full", (info["member_count"], info["full"]), (50, True))
check("the 51st member is refused (422)", code(m.add_member, gid, m.MemberAdd(username=others[49]), OWNER), 422)
check("...with a clear 'full' message", "full" in detail(m.add_member, gid, m.MemberAdd(username=others[49]), OWNER), True)
with closing(m.db()) as con:
    check("still exactly 50 members", con.execute("SELECT COUNT(*) FROM conv_members WHERE conv_id=?", (gid,)).fetchone()[0], 50)

m._rate.clear()
m.remove_member(gid, others[48], OWNER)
check("after one person is removed there is a free place", code(m.add_member, gid, m.MemberAdd(username=others[49]), OWNER), 200)

# two admins add at the same moment: only one of them gets the last place
m._rate.clear()
m.remove_member(gid, others[49], OWNER)                          # 49 inside again
m._rate.clear()
m.add_member(gid, m.MemberAdd(username="admintwo"), OWNER)       # 50 inside again; one person leaves below so exactly ONE place is free for the race
m._rate.clear()
m.remove_member(gid, others[47], OWNER)                          # 49 inside, one place left
results = []


def add(who, name):
    m._rate.clear()
    try:
        m.add_member(gid, m.MemberAdd(username=name), who); results.append(200)
    except HTTPException as e:
        results.append(e.status_code)


t1 = threading.Thread(target=add, args=(OWNER, others[55])); t2 = threading.Thread(target=add, args=(OWNER, others[56]))
t1.start(); t2.start(); t1.join(); t2.join()
with closing(m.db()) as con:
    n = con.execute("SELECT COUNT(*) FROM conv_members WHERE conv_id=?", (gid,)).fetchone()[0]
check("two simultaneous adds: the group never goes over 50", n <= 50, True)
check("two simultaneous adds: exactly one succeeded, the other was refused", sorted(results), [200, 422])

# an older group that is already bigger than 50 keeps everyone and cannot grow
with closing(m.db()) as con:
    cur = con.execute("INSERT INTO conversations(kind, name, owner, created_at) VALUES ('group','Old big',?,1)", (OWNER,))
    old_id = cur.lastrowid
    con.execute("INSERT INTO conv_members(conv_id, uid, role, joined_at) VALUES (?,?,'owner',1)", (old_id, OWNER))
    for i in range(55):
        con.execute("INSERT INTO conv_members(conv_id, uid, role, joined_at) VALUES (?,?,'member',1)", (old_id, f"dev-u{i}"))
    con.commit()
check("an older group of 56 keeps all its members", m.group_info(old_id, OWNER)["member_count"], 56)
check("...but nobody new can be added", code(m.add_member, old_id, m.MemberAdd(username=others[58]), OWNER), 422)
print("ALL PASS")
