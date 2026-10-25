"""Backend checks for Step 45: unified search (plan sections 19, 33, 40). Run:  cd backend && DEV_AUTH=1 python test_search.py
Checked: exact @username first, then username-starts-with, then name-starts-with, then contains; partial username;
usernames with "_" (it used to be deleted from the query); no results; blocked people; private accounts' content;
followers-only content; public posts; pagination of all three lists (no overlap, no gaps, ends with null, depth limit);
input validation (too long, control characters, unknown list); private groups are never found."""
import os, tempfile, time
from contextlib import closing

os.environ["DEV_AUTH"] = "1"
os.environ["SOCIAX_DB"] = os.path.join(tempfile.mkdtemp(), "s.db")
import main as m  # noqa: E402
from fastapi import HTTPException  # noqa: E402

now = int(time.time())
ME = "dev-me"
m.onboard(m.Onboarding(display_name="Viewer", username="viewer", age=20), ME)


def check(name, got, want):
    ok = got == want
    print(("PASS " if ok else "FAIL ") + name + ("" if ok else f"  (got {got!r}, wanted {want!r})"))
    assert ok, name


def user(uid, username, display):
    m.onboard(m.Onboarding(display_name=display, username=username, age=20), uid)


def S(q, type=None, offset=0, who=ME):
    m._rate.clear()
    return m.search(q, who, type, offset)


def names(r):
    return [u["username"] for u in r["users"]]


def code(q, **k):
    try:
        S(q, **k); return 200
    except HTTPException as e:
        return e.status_code


def post(uid, caption, audience="public"):
    with closing(m.db()) as con:
        con.execute("INSERT INTO posts(uid, caption, media_public_id, audience, created_at) VALUES (?,?,?,?,?)",
                    (uid, caption, "p_" + str(time.time_ns()), audience, now)); con.commit()


def short(uid, caption, audience="public"):
    with closing(m.db()) as con:
        con.execute("INSERT INTO shorts(uid, caption, media_public_id, duration, audience, comments_enabled, created_at) VALUES (?,?,?,?,?,?,?)",
                    (uid, caption, "s_" + str(time.time_ns()), 20, audience, 1, now)); con.commit()


# ---------- people: ranking ----------
user("u1", "sam", "Zed Person")             # exact @sam
user("u2", "sampson", "Alpha")              # username starts with sam
user("u3", "zz_sam_x", "Beta Sam")          # name has a WORD starting with sam (and username contains it)
user("u4", "bigsamurai", "Gamma")           # contains only
user("u5", "my_ab", "Under Score")            # username with an underscore
user("u6", "myab", "Plain ab")              # must NOT match the query "my_ab"
r = S("sam")
check("exact @username first, then starts-with, then name-word, then contains", names(r), ["@sam", "@sampson", "@zz_sam_x", "@bigsamurai"])
check("a leading @ and capitals are ignored", names(S("@SAM")), names(r))
check("partial username works", "@bigsamurai" in names(S("samur")), True)
check("underscore is a real character: my_ab finds my_ab and not myab", names(S("my_ab")), ["@my_ab"])
check("a literal % does not act as a wildcard", names(S("%%")), [])
check("no results: empty lists and null next", S("qqqqq"), {"users": [], "posts": [], "shorts": [], "next": {"users": None, "posts": None, "shorts": None}})
check("one character: empty", S("s")["users"], [])
check("I never find myself", "@viewer" in names(S("viewer")), False)

# ---------- people: blocked / pagination ----------
with closing(m.db()) as con:
    con.execute("INSERT INTO blocks(blocker, blocked) VALUES (?,?)", (ME, "u4")); con.commit()
check("someone I blocked is not found", "@bigsamurai" in names(S("sam")), False)
with closing(m.db()) as con:
    con.execute("DELETE FROM blocks"); con.commit()
    con.execute("INSERT INTO blocks(blocker, blocked) VALUES (?,?)", ("u2", ME)); con.commit()
check("someone who blocked me is not found either", "@sampson" in names(S("sam")), False)
with closing(m.db()) as con:
    con.execute("DELETE FROM blocks"); con.commit()

for i in range(45):
    user(f"pg{i:02d}", f"pager{i:02d}", f"Pager {i:02d}")
p1 = S("pager", "users")
check("page 1: 20 people, next = 20, other lists empty", (len(p1["users"]), p1["next"]["users"], p1["posts"], p1["next"]["posts"]), (20, 20, [], None))
p2 = S("pager", "users", 20)
p3 = S("pager", "users", 40)
allnames = names(p1) + names(p2) + names(p3)
check("pages: 20 + 20 + 5, last next is null", (len(p2["users"]), p2["next"]["users"], len(p3["users"]), p3["next"]["users"]), (20, 40, 5, None))
check("pagination: no overlap, no gaps, still sorted", (len(set(allnames)), allnames == sorted(allnames)), (45, True))
check("all lists on the first call each carry their own next", set(S("pager")["next"].keys()), {"users", "posts", "shorts"})

# ---------- posts / shorts: privacy, ranking, pagination ----------
user("w1", "writer", "Writer")
user("w2", "secretwriter", "Secret Writer")
m.update_me(m.ProfileUpdate(is_private=True), "w2")
post("w1", "my sunset walk")                # word starts with the query -> ranks first
post("w1", "presunset party")               # only contains it inside a word
post("w1", "sunset for my followers", "followers")
post("w2", "sunset at a private account")
r = S("sunset", "posts")
caps = [p["caption"] for p in r["posts"]]
check("public posts only: followers-only and private-account posts are not found", sorted(caps), ["my sunset walk", "presunset party"])
check("word-start match ranks before an inside-word match", caps, ["my sunset walk", "presunset party"])
for i in range(27):
    post("w1", f"pagepost {i}")
    short("w1", f"pageclip {i}")
a = S("pagepost", "posts"); b = S("pagepost", "posts", 20)
ids = [p["id"] for p in a["posts"]] + [p["id"] for p in b["posts"]]
check("posts pagination: 20 + 7, no overlap, newest first", (len(a["posts"]), a["next"]["posts"], len(b["posts"]), b["next"]["posts"], len(set(ids)), ids == sorted(ids, reverse=True)),
      (20, 20, 7, None, 27, True))
a = S("pageclip", "shorts"); b = S("pageclip", "shorts", 20)
check("short video pagination: 20 + 7", (len(a["shorts"]), a["next"]["shorts"], len(b["shorts"]), b["next"]["shorts"]), (20, 20, 7, None))
check("a page past the end is empty with null next", (S("pagepost", "posts", 100)["posts"], S("pagepost", "posts", 100)["next"]["posts"]), ([], None))
check("depth limit: offset is capped at 200 and never loops forever", S("pager", "users", 5000)["next"]["users"], None)
check("negative offset is treated as 0", names(S("pager", "users", -5)), names(p1))

# ---------- validation ----------
check("50 characters are fine", code("a" * 50), 200)
check("51 characters are refused (422)", code("a" * 51), 422)
check("control characters are refused (422)", code("sam\x00x"), 422)
check("a newline inside the text is refused (422)", code("sam\nx"), 422)
check("unknown list name is refused (422)", code("sam", type="groups"), 422)
check("spaces around the text are ignored", names(S("   sam   ")), names(S("sam")))

# ---------- private groups are never found ----------
m._rate.clear()
g = m.create_group(m.NewGroup(name="Hidden Lounge", usernames=["writer"], confirmed=True), ME)
check("a private group is not found by its name", "Hidden Lounge" in str(S("hidden lounge")) or "Hidden Lounge" in str(S("lounge", who="w1")), False)
check("... nor by a member who is NOT in it", "Hidden Lounge" in str(S("hidden", who="u1")), False)
print("test_search: all checks passed")
