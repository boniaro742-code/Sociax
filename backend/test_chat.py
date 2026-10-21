"""Backend checks for private chat, groups and AI Join (plan sections 6, 7, 11).
Run:  cd backend && DEV_AUTH=1 python test_chat.py      (or: pytest -q test_chat.py)"""
import os, tempfile

os.environ["DEV_AUTH"] = "1"
os.environ["SOCIAX_DB"] = os.path.join(tempfile.mkdtemp(), "c.db")
import main as m  # noqa: E402
from fastapi import HTTPException  # noqa: E402

A, B, C, D, E = "dev-a", "dev-b", "dev-c", "dev-d", "dev-e"


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


def send(uid, cid, text):
    m._rate.clear()
    return m.send_message(cid, m.NewMessage(text=text), uid)


def texts(uid, cid):
    return [x["text"] for x in m.get_messages(cid, None, 100, uid)["messages"]]


def test_all():
    for u, n, p in [(A, "anna", False), (B, "ben", False), (C, "cara", True), (D, "dan", False), (E, "eve", False)]:
        user(u, n, p)

    # ---------- one-to-one ----------
    dm = m.start_dm(m.DmStart(username="@ben"), A)
    assert dm["kind"] == "dm" and dm["title"] == "Ben"
    assert m.start_dm(m.DmStart(username="anna"), B)["id"] == dm["id"] == m.start_dm(m.DmStart(username="ben"), A)["id"]
    assert code(m.start_dm, m.DmStart(username="anna"), A) == 422                    # not yourself
    assert code(m.start_dm, m.DmStart(username="cara"), A) == 403                    # private account, not a follower
    m.follow("cara", A); assert code(m.start_dm, m.DmStart(username="cara"), A) == 403   # request still pending
    m.answer_follow_request("anna", "accept", C); assert code(m.start_dm, m.DmStart(username="cara"), A) == 200
    assert code(m.start_dm, m.DmStart(username="nobody"), A) == 404

    send(A, dm["id"], "  hello Ben  "); send(B, dm["id"], "hi Anna")
    assert texts(A, dm["id"]) == ["hello Ben", "hi Anna"]
    chats = m.list_chats(B)["chats"]
    b_dm = [c for c in chats if c["id"] == dm["id"]][0]
    assert b_dm["unread"] == 0 and b_dm["title"] == "Anna" and b_dm["last_text"] == "hi Anna"   # sending marks your own chat read
    assert [c for c in m.list_chats(A)["chats"] if c["id"] == dm["id"]][0]["unread"] == 1   # Ben's reply
    m.mark_read(dm["id"], A); assert [c for c in m.list_chats(A)["chats"] if c["id"] == dm["id"]][0]["unread"] == 0
    first = m.get_messages(dm["id"], None, 100, A)["messages"][0]["id"]
    assert [x["text"] for x in m.get_messages(dm["id"], first, 50, A)["messages"]] == ["hi Anna"]   # polling: only newer
    assert m.get_messages(dm["id"], None, 100, A)["messages"][0]["mine"] is True

    # outsiders can never read, write or mark a chat: always the same 404
    for fn, args in [(m.get_messages, (dm["id"], None, 50, D)), (m.send_message, (dm["id"], m.NewMessage(text="x"), D)),
                     (m.mark_read, (dm["id"], D))]:
        assert code(fn, *args) == 404
    assert code(send, A, dm["id"], "   ") == 422 and code(send, A, dm["id"], "x" * 2001) == 422

    # blocking hides the chat and stops messages, both directions
    m.block_user("ben", A)
    assert dm["id"] not in [c["id"] for c in m.list_chats(A)["chats"]] and dm["id"] not in [c["id"] for c in m.list_chats(B)["chats"]]
    assert code(send, B, dm["id"], "hey") == 404 and code(send, A, dm["id"], "hey") == 404
    assert code(m.start_dm, m.DmStart(username="anna"), B) == 404
    m.unblock_user("ben", A)
    assert code(send, B, dm["id"], "back again") == 200

    # ---------- groups ----------
    assert code(m.create_group, m.NewGroup(name="Team", usernames=["ben"], confirmed=False), A) == 422   # needs the final OK
    n_before = len(m.list_chats(A)["chats"])
    assert code(m.create_group, m.NewGroup(name="Team", usernames=["ben", "dan"], confirmed=True), E) == 200  # eve owns one
    # a private non-follower cannot be added -> nothing is created at all
    m.update_me(m.ProfileUpdate(is_private=True), D)
    assert code(m.create_group, m.NewGroup(name="Nope", usernames=["ben", "dan"], confirmed=True), A) == 403
    assert len(m.list_chats(A)["chats"]) == n_before
    m.update_me(m.ProfileUpdate(is_private=False), D)
    g = m.create_group(m.NewGroup(name="Secret Club", usernames=["ben", "@ben", "anna"], confirmed=True), A)
    gid = g["id"]; assert g["kind"] == "group" and g["my_role"] == "owner"
    info = m.group_info(gid, A)
    assert [(x["username"], x["role"]) for x in info["members"]] == [("@anna", "owner"), ("@ben", "member")]
    assert info["ai_enabled"] is False                                           # AI is OFF by default

    # groups are invisible to outsiders and to search
    assert code(m.group_info, gid, D) == 404 and code(m.get_messages, gid, None, 50, D) == 404
    assert code(send, D, gid, "let me in") == 404
    res = m.search("secret", D); assert res["users"] == [] and res["posts"] == []
    assert "Secret Club" not in str(m.search("club", A)) and "Secret Club" not in str(m.search("anna", D))
    assert code(m.group_info, 999, A) == 404                                      # missing == hidden

    # roles
    assert code(m.add_member, gid, m.MemberAdd(username="dan"), B) == 403         # plain member cannot add
    assert code(m.add_member, gid, m.MemberAdd(username="dan"), A) == 200
    assert code(m.add_member, gid, m.MemberAdd(username="dan"), A) == 409
    m.block_user("eve", A); assert code(m.add_member, gid, m.MemberAdd(username="eve"), A) == 403; m.unblock_user("eve", A)
    assert code(m.set_role, gid, "ben", m.RoleBody(role="admin"), B) == 403       # only the owner changes roles
    assert code(m.set_role, gid, "ben", m.RoleBody(role="admin"), A) == 200
    assert code(m.add_member, gid, m.MemberAdd(username="eve"), B) == 200         # admin may add
    assert code(m.remove_member, gid, "anna", B) == 403                            # admin cannot remove the owner
    assert code(m.remove_member, gid, "dan", B) == 200                             # admin removes a member
    assert code(m.get_messages, gid, None, 50, D) == 404                           # removed -> no access any more
    assert code(m.remove_member, gid, "eve", D) == 404

    # ---------- AI Join ----------
    calls = []
    def spy(prompt, uid=None):
        calls.append(prompt); return "42", "answered"
    m.group_ai_reply = spy
    assert send(A, dm["id"], "@AI what is this?")["ai"] == "private_chat" and calls == []   # never in 1-to-1 chats
    assert send(A, gid, "@AI hello?")["ai"] == "disabled" and calls == []                  # off by default: silent
    assert code(m.set_group_ai, gid, m.AiToggle(enabled=True), E) == 403                   # plain member cannot enable
    assert m.set_group_ai(gid, m.AiToggle(enabled=True), B)["ai_enabled"] is True          # admin can
    sysmsgs = [x for x in m.get_messages(gid, None, 100, A)["messages"] if x["kind"] == "system" and "turned AI on" in x["text"]]
    assert len(sysmsgs) == 1                                                                # everyone is told
    m.set_group_ai(gid, m.AiToggle(enabled=True), B)
    assert len([x for x in m.get_messages(gid, None, 100, A)["messages"] if "turned AI on" in x["text"]]) == 1   # no duplicates
    send(A, gid, "private plan: secret surprise party")
    assert send(A, gid, "just chatting, no mention") ["ai"] is None and calls == []         # no @AI -> AI stays silent
    assert send(A, gid, "mail me at bob@ai.com")["ai"] is None and calls == []
    r = send(E, gid, "@AI  what is 6x7?")
    assert r["ai"] == "answered" and calls == ["what is 6x7?"]                              # gets ONLY the invoking message
    assert "secret" not in calls[0]
    kinds = [(x["kind"], x["text"]) for x in m.get_messages(gid, None, 100, A)["messages"]][-2:]
    assert kinds == [("user", "@AI  what is 6x7?"), ("ai", "42")]
    m.group_ai_reply = lambda p, uid=None: (None, "not_connected")
    assert send(A, gid, "@ai again")["ai"] == "not_connected"                                # no provider yet: honest status
    m.set_group_ai(gid, m.AiToggle(enabled=False), A)
    calls.clear(); m.group_ai_reply = spy
    assert send(A, gid, "@AI are you there")["ai"] == "disabled" and calls == []            # admin switched it off: stops
    assert any("turned AI off" in t for t in texts(A, gid))

    # ---------- leaving / deleting ----------
    assert code(m.delete_group, gid, B) == 403
    m.remove_member(gid, "anna", A)                                                          # owner leaves -> ownership passes on
    assert m.group_info(gid, B)["my_role"] == "owner"
    assert code(m.get_messages, gid, None, 50, A) == 404
    m.remove_member(gid, "eve", E); m.remove_member(gid, "ben", B)                           # last person leaves -> group is gone
    assert code(m.group_info, gid, B) == 404
    g2 = m.create_group(m.NewGroup(name="Tmp", usernames=[], confirmed=True), A)["id"]
    assert code(m.delete_group, g2, A) == 200 and code(m.group_info, g2, A) == 404

    # ---------- rate limit ----------
    m._rate.clear()
    hit = 0
    for i in range(70):
        try: m.send_message(dm["id"], m.NewMessage(text=f"spam {i}"), A)
        except HTTPException as e: hit += e.status_code == 429
    assert hit >= 5
    print("ALL CHAT / GROUP / AI-JOIN CHECKS PASSED")


if __name__ == "__main__":
    test_all()
