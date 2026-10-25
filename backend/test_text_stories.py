"""Backend checks for text-only stories (middle reference screen) and their privacy rules.
Run:  cd backend && DEV_AUTH=1 python test_text_stories.py      (or: pytest -q test_text_stories.py)
No Cloudinary account or network is needed."""
import os, sqlite3, tempfile

os.environ["DEV_AUTH"] = "1"
os.environ["SOCIAX_DB"] = os.path.join(tempfile.mkdtemp(), "t.db")
import main as m  # noqa: E402
from fastapi import HTTPException  # noqa: E402
from pydantic import ValidationError  # noqa: E402

m.CLOUD_NAME, m.CLOUD_KEY, m.CLOUD_SECRET = "demo", "key", "secret"
SIZES = {}
m.asset_bytes = lambda pid: SIZES.get(pid, 0)
A, B, C, D = "dev-a", "dev-b", "dev-c", "dev-d"


def code(fn, *a, **k):
    m._rate.clear()
    try:
        fn(*a, **k); return 200
    except HTTPException as e:
        return e.status_code
    except ValidationError:          # real pydantic refuses a bad body before the endpoint runs: same meaning as 422
        return 422


def user(uid, name, private=False):
    m.onboard(m.Onboarding(display_name=name, username=name, age=20), uid)
    if private:
        m.update_me(m.ProfileUpdate(is_private=True), uid)


def text_story(uid, text="Midnight drive through the blinding lights"):
    m._rate.clear()
    return m.create_text_story(m.NewTextStory(text=text), uid)


def used(uid):
    con = sqlite3.connect(m.DB_PATH)
    try:
        return con.execute("SELECT COALESCE(SUM(bytes),0) FROM media WHERE uid=?", (uid,)).fetchone()[0]
    finally:
        con.close()


def seen_by(uid):
    return [g["username"] for g in m.list_stories(uid)["groups"]]


def test_all():
    for u, n, p in [(A, "anna", False), (B, "ben", False), (C, "cara", True), (D, "dan", False)]:
        user(u, n, p)

    # create: kind is "text", no photo url, 24 hours, text is trimmed, uses no media quota
    s = text_story(A, "  Midnight drive  ")
    assert s["kind"] == "text" and s["image_url"] is None and s["caption"] == "Midnight drive" and s["mine"]
    assert s["expires_at"] - s["created_at"] == 24 * 3600
    assert used(A) == 0

    # empty / too long are refused, the exact maximum is allowed
    assert code(m.create_text_story, m.NewTextStory(text="   "), A) == 422
    assert code(lambda: m.create_text_story(m.NewTextStory(text="x" * (m.STORY_CAPTION_MAX + 1)), A)) == 422
    assert code(m.create_text_story, m.NewTextStory(text="x" * m.STORY_CAPTION_MAX), A) == 200

    # a photo story next to it is still "photo"
    SIZES[f"users/{A}/story_1"] = 1024
    m._rate.clear()
    photo = m.create_story(m.NewStory(public_id=f"users/{A}/story_1", caption="hi"), A)
    assert photo["kind"] == "photo" and photo["image_url"]
    kinds = {st["kind"] for g in m.list_stories(A)["groups"] for st in g["stories"]}
    assert kinds == {"text", "photo"}

    # privacy: only the owner and ACCEPTED followers; everybody else gets nothing / "not found"
    assert seen_by(B) == [] and seen_by(D) == []
    assert m.follow("anna", B)["status"] == "accepted"
    assert seen_by(B) == ["@anna"]
    assert seen_by(D) == []
    assert code(m.view_story, s["id"], B) == 200
    assert code(m.view_story, s["id"], D) == 404            # hidden = same answer as missing
    assert code(m.view_story, 999999, D) == 404

    # a private account: a pending follow request shows nothing
    cs = text_story(C)
    assert m.follow("cara", D)["status"] == "pending"
    assert seen_by(D) == [] and code(m.view_story, cs["id"], D) == 404

    # owner sees only a count of views (never who); others always see 0
    mine = next(st for g in m.list_stories(A)["groups"] if g["is_me"] for st in g["stories"] if st["id"] == s["id"])
    assert mine["view_count"] == 1
    theirs = next(st for g in m.list_stories(B)["groups"] if not g["is_me"] for st in g["stories"] if st["id"] == s["id"])
    assert theirs["view_count"] == 0

    # blocked people never see it
    m.block_user("anna", B)
    assert seen_by(B) == []

    # delete: only the owner; the photo story's quota is still given back, the text story leaves no media row
    assert code(m.delete_story, s["id"], B) == 404
    assert code(m.delete_story, s["id"], A) == 200
    assert code(m.delete_story, s["id"], A) == 404
    assert used(A) == 1024
    assert code(m.delete_story, photo["id"], A) == 200
    assert used(A) == 0


if __name__ == "__main__":
    test_all()
    print("text story checks passed")
