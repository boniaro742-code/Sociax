"""Backend checks for Step 27: Notifications (plan section 9): six switchable groups + the in-app list.
Run:  cd backend && DEV_AUTH=1 python test_notifications.py      (or: pytest -q test_notifications.py)
Cloudinary and the AI providers are replaced by fakes, so no account or network is needed."""
import os, tempfile

os.environ["DEV_AUTH"] = "1"
os.environ["SOCIAX_DB"] = os.path.join(tempfile.mkdtemp(), "n.db")
import main as m  # noqa: E402
from fastapi import HTTPException  # noqa: E402

m.CLOUD_NAME, m.CLOUD_KEY, m.CLOUD_SECRET = "demo", "key", "secret"
m.asset_bytes = lambda pid: 1024
FAKE = {}
m.video_info = lambda pid: FAKE[pid]
m.destroy_video = lambda pid: None
m.group_ai_reply = lambda prompt, uid=None: ("42", "answered")
A, B, C = "dev-a", "dev-b", "dev-c"


def code(fn, *a, **k):
    m._rate.clear()
    try:
        fn(*a, **k); return 200
    except HTTPException as e:
        return e.status_code


def user(uid, name, private=False):
    m.onboard(m.Onboarding(display_name=name.title(), username=name, age=20), uid)
    if private:
        m.update_me(m.ProfileUpdate(is_private=True), uid)


def post(uid, n):
    m._rate.clear()
    return m.create_post(m.NewPost(public_id=f"users/{uid}/post_{n}", caption="hi", audience="public"), uid)["id"]


def short(uid, n):
    m._rate.clear()
    pid = f"users/{uid}/short_{n}"
    FAKE[pid] = {"bytes": 1024, "duration": 5.0, "format": "mp4"}
    return m.create_short(m.NewShort(public_id=pid), uid)["id"]


def types(uid):
    return [n["type"] for n in m.list_notifications(None, 30, uid)["notifications"]]


def unread(uid):
    return m.notification_unread(uid)["unread"]


def send(uid, cid, text):
    m._rate.clear()
    return m.send_message(cid, m.NewMessage(text=text), uid)


def test_all():
    user(A, "anna"); user(B, "ben"); user(C, "cara", private=True)

    # ---- defaults: every group is on, empty list ----
    assert m.me(A)["notifications"] == {k: True for k in m.NOTIF_CATEGORIES}
    assert types(A) == [] and unread(A) == 0

    # ---- follows ----
    m.follow("anna", B)                                    # public account: "Ben follows you"
    assert types(A) == ["follow"] and unread(A) == 1
    n = m.list_notifications(None, 30, A)["notifications"][0]
    assert n["username"] == "@ben" and n["display_name"] == "Ben" and n["category"] == "follows" and n["read"] is False
    m.unfollow("anna", B); m.follow("anna", B)             # the same person only once
    assert types(A) == ["follow"]
    m.follow("cara", A)                                    # private account: a request, then "accepted"
    assert types(C) == ["follow_request"]
    m.answer_follow_request("anna", "accept", C)
    assert types(A) == ["follow_accepted", "follow"]
    assert types(B) == []                                  # nothing for the one who acted

    # ---- likes / reactions / comments on a post, nothing for my own actions ----
    pid = post(A, 1)
    m.like(pid, B); m.like(pid, B)                         # twice = one notification
    m.react(pid, "wow", B)
    m.add_comment(pid, m.NewComment(text="nice"), B)
    m.like(pid, A)                                         # my own post
    assert types(A)[:3] == ["post_comment", "post_react", "post_like"] and types(A).count("post_like") == 1
    assert "nice" not in repr(m.list_notifications(None, 30, A))      # never the comment text

    # ---- short video group ----
    sid = short(A, 1)
    m.like_short(sid, B); m.react_short(sid, "haha", B)
    m.add_short_comment(sid, m.NewComment(text="wow"), B)
    assert types(A)[:3] == ["short_comment", "short_react", "short_like"]

    # ---- messages: one merged entry per chat while unread, never from a held message ----
    dm = m.start_dm(m.DmStart(username="@anna"), B)
    send(B, dm["id"], "hello"); send(B, dm["id"], "are you there?")
    msgs = [x for x in m.list_notifications(None, 30, A)["notifications"] if x["type"] == "message"]
    assert len(msgs) == 1 and msgs[0]["count"] == 2 and msgs[0]["ref"] == dm["id"]
    assert "hello" not in repr(msgs)
    m.mark_read(dm["id"], A)                               # opening the chat clears it
    assert [x for x in m.list_notifications(None, 30, A)["notifications"] if x["type"] == "message"][0]["read"] is True
    send(B, dm["id"], "again")                             # a new unread entry starts at 1
    assert [x for x in m.list_notifications(None, 30, A)["notifications"] if x["type"] == "message"][0]["count"] == 1

    m.update_me(m.ProfileUpdate(follow_first=True), C)     # "follow me first": the message waits -> no notification yet
    m.follow("cara", B)                                    # (B is already waiting as a request, only checks the held rule below)
    user("dev-d", "dan")
    m.update_me(m.ProfileUpdate(follow_first=True), "dev-d")
    dm2 = m.start_dm(m.DmStart(username="@dan"), B)
    send(B, dm2["id"], "hi dan")
    assert types("dev-d") == []
    m.follow("dan", B)                                     # public: accepted at once, waiting message is released
    assert "message" in types("dev-d")

    # ---- groups + AI ----
    g = m.create_group(m.NewGroup(name="Team", usernames=["@ben"], confirmed=True), A)
    assert types(B)[0] == "group_added" and m.list_notifications(None, 30, B)["notifications"][0]["ref"] == g["id"]
    m.set_group_ai(g["id"], m.AiToggle(enabled=True), A)
    send(B, g["id"], "@AI what is 6x7?")
    assert "ai_reply" in types(B) and "ai_reply" not in types(A)
    ai = [x for x in m.list_notifications(None, 30, B)["notifications"] if x["type"] == "ai_reply"][0]
    assert ai["username"] is None and ai["category"] == "ai"
    user("dev-e", "eve")
    m.add_member(g["id"], m.MemberAdd(username="@eve"), A)
    assert types("dev-e") == ["group_added"]

    # ---- switches: off = nothing new is written AND old ones are hidden, on again = they come back ----
    before = unread(A)
    m.update_me(m.ProfileUpdate(notifications={"likes_comments": False, "shorts": False}), A)
    prefs = m.me(A)["notifications"]
    assert prefs["likes_comments"] is False and prefs["shorts"] is False and prefs["follows"] is True
    assert not any(t.startswith(("post_", "short_")) for t in types(A))
    assert unread(A) < before                                              # hidden ones are not counted
    p2 = post(A, 2); m.like(p2, B)
    m.update_me(m.ProfileUpdate(notifications={"likes_comments": True, "shorts": True}), A)
    assert "post_like" in types(A) and types(A).count("post_like") == 1    # the new like was never written, the old one is back
    assert code(m.update_me, m.ProfileUpdate(notifications={"nonsense": True}), A) == 422
    assert m.me(A)["notifications"]["messages"] is True                     # other groups untouched

    # ---- read all / paging ----
    assert unread(A) > 0
    m.notifications_read(A)
    assert unread(A) == 0 and all(x["read"] for x in m.list_notifications(None, 30, A)["notifications"])
    page = m.list_notifications(None, 3, A)
    assert len(page["notifications"]) == 3 and page["next_before"] == page["notifications"][-1]["id"]
    nxt = m.list_notifications(page["next_before"], 3, A)["notifications"]
    assert nxt and all(x["id"] < page["next_before"] for x in nxt)

    # ---- privacy ----
    m.block_user("ben", A)                                                  # blocking wipes everything between us
    assert not any(x["username"] == "@ben" for x in m.list_notifications(None, 50, A)["notifications"])
    p3 = post(A, 3)
    assert code(m.like, p3, B) == 404                                       # blocked: cannot even see it
    assert not any(x["username"] == "@ben" for x in m.list_notifications(None, 50, A)["notifications"])
    m.unblock_user("ben", A)

    # ---- only my own list; deleting an account removes its notifications ----
    m.delete_account(B)
    assert not any(x["username"] == "@ben" for x in m.list_notifications(None, 50, A)["notifications"])
    print("PASS notifications")


if __name__ == "__main__":
    test_all()
