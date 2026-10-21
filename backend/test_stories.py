"""Backend checks for Stories (plan section 4) and their privacy rules.
Run:  cd backend && DEV_AUTH=1 python test_stories.py      (or: pytest -q test_stories.py)
Cloudinary is replaced by a fake, so no account or network is needed."""
import os, tempfile, time

os.environ["DEV_AUTH"] = "1"
os.environ["SOCIAX_DB"] = os.path.join(tempfile.mkdtemp(), "t.db")
import main as m  # noqa: E402
from fastapi import HTTPException  # noqa: E402

m.CLOUD_NAME, m.CLOUD_KEY, m.CLOUD_SECRET = "demo", "key", "secret"
SIZES = {}
m.asset_bytes = lambda pid: SIZES.get(pid, 0)

MB = 1024 * 1024
A, B, C, D = "dev-a", "dev-b", "dev-c", "dev-d"


def code(fn, *a, **k):
    m._rate.clear()
    try:
        fn(*a, **k); return 200
    except HTTPException as e:
        return e.status_code


def user(uid, name, private=False):
    m.onboard(m.Onboarding(display_name=name, username=name, age=20), uid)
    if private:
        m.update_me(m.ProfileUpdate(is_private=True), uid)


def story(uid, n, size=1 * MB, caption="hi"):
    pid = f"users/{uid}/story_{n}"
    SIZES[pid] = size
    m._rate.clear()
    return m.create_story(m.NewStory(public_id=pid, caption=caption), uid)


def used(uid):
    import sqlite3
    con = sqlite3.connect(m.DB_PATH)
    try:
        return con.execute("SELECT COALESCE(SUM(bytes),0) FROM media WHERE uid=?", (uid,)).fetchone()[0]
    finally:
        con.close()


def seen_by(uid):
    """Usernames whose stories this user can see."""
    return [g["username"] for g in m.list_stories(uid)["groups"]]


def test_all():
    for u, n, p in [(A, "anna", False), (B, "ben", False), (C, "cara", True), (D, "dan", False)]:
        user(u, n, p)

    # --- signing accepts the new purpose, with the photo size limit ---
    assert m.sign_upload(m.SignRequest(purpose="story", size_bytes=2 * MB), A)["public_id"].startswith(f"users/{A}/story_")
    assert code(m.sign_upload, m.SignRequest(purpose="story", size_bytes=11 * MB), A) == 413

    # --- create: only my own upload, only a real file, once ---
    s1 = story(A, 1)
    assert s1["mine"] and s1["image_url"].startswith("https://res.cloudinary.com/demo/image/upload/")
    assert s1["expires_at"] - s1["created_at"] == 24 * 3600
    SIZES[f"users/{B}/story_9"] = MB
    assert code(m.create_story, m.NewStory(public_id=f"users/{B}/story_9"), A) == 403          # someone else's upload
    assert code(m.create_story, m.NewStory(public_id=f"users/{A}/post_9"), A) == 403           # wrong kind of upload
    assert code(m.create_story, m.NewStory(public_id=f"users/{A}/story_404"), A) == 413        # not really uploaded (size 0)
    assert code(m.create_story, m.NewStory(public_id=f"users/{A}/story_1"), A) == 409          # same upload twice
    SIZES[f"users/{A}/story_big"] = 11 * MB
    assert code(m.create_story, m.NewStory(public_id=f"users/{A}/story_big"), A) == 413

    # --- quota is counted on the server (150 MB combined media) ---
    assert used(A) == MB
    SIZES[f"users/{A}/story_fill"] = 10 * MB
    for i in range(14):
        story(A, f"f{i}", size=10 * MB)           # 140 MB + 1 MB = 141 MB used
    assert code(m.create_story, m.NewStory(public_id=f"users/{A}/story_fill"), A) == 413   # 151 MB > 150 MB

    # --- visibility: nobody sees a story without an ACCEPTED follow ---
    assert seen_by(A)[:1] == ["@anna"]                       # my own first
    assert "@anna" not in seen_by(B) and "@anna" not in seen_by(D)
    m.follow("anna", B)                                      # public account: accepted at once
    assert "@anna" in seen_by(B)
    assert "@anna" not in seen_by(D)                         # D does not follow
    sid = m.list_stories(B)["groups"][0]["stories"][0]["id"]
    assert code(m.view_story, sid, D) == 404                 # hidden = same answer as missing
    assert code(m.view_story, 99999, B) == 404

    # --- private account: pending request shows nothing, accepted shows the story ---
    cs = story(C, 1)
    m.follow("cara", D)
    assert m.follow_requests(C)["requests"]                  # D is pending
    assert "@cara" not in seen_by(D)
    assert code(m.view_story, cs["id"], D) == 404
    m.answer_follow_request("dan", "accept", C)
    assert "@cara" in seen_by(D)
    # public search never reveals stories (search returns users and posts only)
    assert set(m.search("cara", D).keys()) == {"users", "posts", "shorts"}

    # --- blocking hides stories both ways and drops the follow ---
    m.block_user("anna", B)
    assert "@anna" not in seen_by(B)
    assert code(m.view_story, sid, B) == 404
    m.unblock_user("anna", B)
    assert "@anna" not in seen_by(B)                         # block removed the follow, so it stays hidden
    m.follow("anna", B)
    assert "@anna" in seen_by(B)

    # --- views: counted once per person, only the owner sees the number, never my own view ---
    assert code(m.view_story, sid, B) == 200 and code(m.view_story, sid, B) == 200
    mine = next(g for g in m.list_stories(A)["groups"] if g["is_me"])["stories"]
    assert [x for x in mine if x["id"] == sid][0]["view_count"] == 1
    assert code(m.view_story, sid, A) == 200
    assert [x for x in next(g for g in m.list_stories(A)["groups"] if g["is_me"])["stories"] if x["id"] == sid][0]["view_count"] == 1
    b_group = next(g for g in m.list_stories(B)["groups"] if g["username"] == "@anna")
    assert all(x["view_count"] == 0 for x in b_group["stories"])      # viewers never see the count
    assert [x for x in b_group["stories"] if x["id"] == sid][0]["seen"] is True

    # --- ordering: me first, then unseen before seen ---
    m.follow("dan", B)
    story(D, 1)
    for g in m.list_stories(B)["groups"]:
        if g["username"] == "@dan":
            for st in g["stories"]:
                m.view_story(st["id"], B)                    # B has now seen all of Dan's stories, not all of Anna's
    groups = [g for g in m.list_stories(B)["groups"] if not g["is_me"]]
    assert [g["username"] for g in groups] == ["@anna", "@dan"]      # unseen ring first, seen last

    # --- delete: only the owner, and the quota comes back ---
    before = used(A)
    assert code(m.delete_story, sid, B) == 404
    assert code(m.delete_story, sid, A) == 200
    assert used(A) == before - MB
    assert code(m.view_story, sid, B) == 404

    # --- expiry: expired stories vanish and give their quota back ---
    e = story(D, 2)
    import sqlite3
    con = sqlite3.connect(m.DB_PATH); con.execute("UPDATE stories SET expires_at=? WHERE id=?", (int(time.time()) - 1, e["id"])); con.commit(); con.close()
    before_d = used(D)
    assert all(x["id"] != e["id"] for g in m.list_stories(D)["groups"] for x in g["stories"])
    assert used(D) == before_d - MB
    assert code(m.view_story, e["id"], D) == 404

    # --- caption limit and rate limit ---
    try:
        m.NewStory(public_id="x", caption="a" * 201)
        raise AssertionError("caption limit missing")
    except AssertionError:
        raise
    except Exception:
        pass                                                 # pydantic rejects captions over 200 characters
    m._rate.clear()
    hit = 0
    for i in range(30):
        pid = f"users/{D}/story_r{i}"; SIZES[pid] = 1024
        try:
            m.create_story(m.NewStory(public_id=pid), D)
        except HTTPException as ex:
            hit += ex.status_code == 429
    assert hit >= 1
    print("ALL STORY CHECKS PASSED")


if __name__ == "__main__":
    test_all()
