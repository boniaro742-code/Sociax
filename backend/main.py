"""Sociax backend (FastAPI).
Step 1: Firebase token verification, unique @username, onboarding stored server-side.
All AI provider keys live HERE (env vars), never in the Android app."""
import hashlib
import os
import re
import sqlite3
import threading
import time
from contextlib import closing
from typing import Literal

from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

try:  # reads backend/.env (secrets live there, never in the app or the repository)
    from dotenv import load_dotenv
    load_dotenv()
except ImportError:
    pass

import chapa_client
import ai_providers  # after load_dotenv so it sees XAI_API_KEY / GEMINI_API_KEY

app = FastAPI(title="Sociax API", version="0.34.0")

# Cloudinary credentials: server-side only (env vars). The Android app never sees the secret.
CLOUD_NAME = os.getenv("CLOUDINARY_CLOUD_NAME")
CLOUD_KEY = os.getenv("CLOUDINARY_API_KEY")
CLOUD_SECRET = os.getenv("CLOUDINARY_API_SECRET")
AVATAR_MAX_BYTES = 5 * 1024 * 1024
POST_MAX_BYTES = 10 * 1024 * 1024
# Technical safety cap for ONE short clip (the plan sets no per-file value; the 150 MB / 600 MB total quota still applies).
SHORT_MAX_BYTES = 100 * 1024 * 1024
SHORT_ALLOWED_SECONDS = (20, 25)       # the user picks 20 or 25 s; 25 s is the hard maximum
DURATION_TOLERANCE = 0.5               # video containers round durations slightly; anything above 25.5 s is refused

_rate: dict = {}


def rate_limit(uid: str, key: str, limit: int, window_s: int):
    """Tiny in-memory limiter (per process). Use Redis when you run several servers."""
    now = time.time()
    hits = [t for t in _rate.get((uid, key), []) if now - t < window_s]
    if len(hits) >= limit:
        raise HTTPException(429, "Too many requests. Please slow down.")
    hits.append(now)
    _rate[(uid, key)] = hits

# ---- Limit errors (plan section 8): the app shows "upgrade or wait" from the machine-readable `code` ----
def media_full_error() -> HTTPException:
    """Media quota (150 MB free / 600 MB App Pro) reached. The app offers the separate App Pro plan."""
    return HTTPException(413, {"code": "media_full",
                               "message": "Media storage is full. Free up space or upgrade to App Pro."})


def ai_limit_error(reset_in_seconds: int) -> HTTPException:
    """Free AI allowance used up. The app offers AI Pro now, or waiting for the 48-hour reset."""
    return HTTPException(429, {"code": "ai_limit", "reset_in_seconds": int(reset_in_seconds),
                               "message": f"Free AI limit reached. Resets in {reset_in_seconds // 3600 + 1} h."})


# ---- Plan constants (v3.1) ----
MAX_SHORT_SECONDS = 25
MIN_AGE = 15
FREE_MEDIA_MB = 150
APP_PRO_MEDIA_MB = 600
AI_RESET_SECONDS = 48 * 3600
AI_PRO_MULTIPLIER = 5
FREE_AI_REQUESTS = int(os.getenv("FREE_AI_REQUESTS", "10"))  # TEMPORARY testing value: the plan has NOT decided the free amount yet

DB_PATH = os.getenv("SOCIAX_DB", "sociax.db")
# DEV_AUTH=1 lets you test without Firebase using the token "dev-<anything>". NEVER enable in production.
DEV_AUTH = os.getenv("DEV_AUTH") == "1"

USERNAME_RE = re.compile(r"^[a-z0-9_.]{3,20}$")
SOURCES = {"TikTok", "YouTube", "Facebook", "Instagram", "Google Search", "Friend/Family", "AI Assistant", "Other"}

_firebase_ready = False


def _init_firebase():
    global _firebase_ready
    if _firebase_ready:
        return
    import firebase_admin
    if not firebase_admin._apps:
        firebase_admin.initialize_app()  # uses GOOGLE_APPLICATION_CREDENTIALS
    _firebase_ready = True


def db():
    con = sqlite3.connect(DB_PATH)
    con.row_factory = sqlite3.Row
    return con


def init_db():
    with closing(db()) as con:
        con.execute(
            """CREATE TABLE IF NOT EXISTS users(
                uid TEXT PRIMARY KEY,
                username TEXT UNIQUE NOT NULL COLLATE NOCASE,
                display_name TEXT NOT NULL,
                age INTEGER NOT NULL,
                source TEXT,
                created_at INTEGER NOT NULL)"""
        )
        cols = {r["name"] for r in con.execute("PRAGMA table_info(users)")}
        if "bio" not in cols:
            con.execute("ALTER TABLE users ADD COLUMN bio TEXT NOT NULL DEFAULT ''")
        if "avatar_public_id" not in cols:
            con.execute("ALTER TABLE users ADD COLUMN avatar_public_id TEXT")
        if "is_private" not in cols:
            con.execute("ALTER TABLE users ADD COLUMN is_private INTEGER NOT NULL DEFAULT 0")
        if "who_can_message" not in cols:     # Step 19: 'everyone' | 'followers' | 'nobody'
            con.execute("ALTER TABLE users ADD COLUMN who_can_message TEXT NOT NULL DEFAULT 'everyone'")
        if "follow_first" not in cols:        # Step 22: "Follow me first" prompt for people who message me
            con.execute("ALTER TABLE users ADD COLUMN follow_first INTEGER NOT NULL DEFAULT 0")
        if "show_last_seen" not in cols:      # Step 25: online / last seen (on = I show mine AND may see others')
            con.execute("ALTER TABLE users ADD COLUMN show_last_seen INTEGER NOT NULL DEFAULT 1")
        if "last_seen_at" not in cols:        # Step 25: last app heartbeat (0 = nothing recorded)
            con.execute("ALTER TABLE users ADD COLUMN last_seen_at INTEGER NOT NULL DEFAULT 0")
        if "default_audience" not in cols:    # Step 19: audience pre-selected for new posts / shorts
            con.execute("ALTER TABLE users ADD COLUMN default_audience TEXT NOT NULL DEFAULT 'public'")
        if "notif_off" not in cols:           # Step 27: comma-separated notification groups I switched OFF (empty = all on)
            con.execute("ALTER TABLE users ADD COLUMN notif_off TEXT NOT NULL DEFAULT ''")
        mcols = {r["name"] for r in con.execute("PRAGMA table_info(messages)")}
        if mcols and "held" not in mcols:     # Step 22: 1 = waiting until the sender follows the recipient
            con.execute("ALTER TABLE messages ADD COLUMN held INTEGER NOT NULL DEFAULT 0")
        con.executescript(
            """
            CREATE TABLE IF NOT EXISTS follows(
                follower TEXT NOT NULL, followee TEXT NOT NULL,
                status TEXT NOT NULL,            -- 'accepted' | 'pending'
                created_at INTEGER NOT NULL,
                PRIMARY KEY(follower, followee));
            CREATE TABLE IF NOT EXISTS blocks(
                blocker TEXT NOT NULL, blocked TEXT NOT NULL, PRIMARY KEY(blocker, blocked));
            CREATE TABLE IF NOT EXISTS posts(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uid TEXT NOT NULL, caption TEXT NOT NULL DEFAULT '',
                media_public_id TEXT NOT NULL,
                audience TEXT NOT NULL DEFAULT 'public',   -- 'public' | 'followers'
                created_at INTEGER NOT NULL);
            CREATE INDEX IF NOT EXISTS posts_uid ON posts(uid, id);
            CREATE TABLE IF NOT EXISTS likes(
                post_id INTEGER NOT NULL, uid TEXT NOT NULL, PRIMARY KEY(post_id, uid));
            CREATE TABLE IF NOT EXISTS bookmarks(
                post_id INTEGER NOT NULL, uid TEXT NOT NULL, PRIMARY KEY(post_id, uid));
            -- Emoji reactions (reference screens). The heart is the normal "like"; these are the extra ones.
            CREATE TABLE IF NOT EXISTS post_reactions(
                post_id INTEGER NOT NULL, uid TEXT NOT NULL, kind TEXT NOT NULL, PRIMARY KEY(post_id, uid, kind));
            CREATE TABLE IF NOT EXISTS comments(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                post_id INTEGER NOT NULL, uid TEXT NOT NULL, text TEXT NOT NULL, created_at INTEGER NOT NULL);
            -- Free AI allowance: one row per user, window restarts every 48 h (plan v3.1).
            CREATE TABLE IF NOT EXISTS ai_usage(
                uid TEXT PRIMARY KEY, window_start INTEGER NOT NULL, used INTEGER NOT NULL DEFAULT 0);
            CREATE TABLE IF NOT EXISTS reports(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                reporter TEXT NOT NULL, target_type TEXT NOT NULL, target_id TEXT NOT NULL,
                reason TEXT NOT NULL, created_at INTEGER NOT NULL);
            """
        )
        con.executescript(
            """
            CREATE TABLE IF NOT EXISTS shorts(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uid TEXT NOT NULL, caption TEXT NOT NULL DEFAULT '',
                media_public_id TEXT NOT NULL, duration REAL NOT NULL,
                audience TEXT NOT NULL DEFAULT 'public',     -- 'public' | 'followers'
                comments_enabled INTEGER NOT NULL DEFAULT 1,
                created_at INTEGER NOT NULL);
            CREATE INDEX IF NOT EXISTS shorts_uid ON shorts(uid, id);
            CREATE TABLE IF NOT EXISTS short_likes(
                short_id INTEGER NOT NULL, uid TEXT NOT NULL, PRIMARY KEY(short_id, uid));
            CREATE TABLE IF NOT EXISTS short_comments(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                short_id INTEGER NOT NULL, uid TEXT NOT NULL, text TEXT NOT NULL, created_at INTEGER NOT NULL);
            -- Emoji reactions + save on Short Video (reference screen). The heart stays the normal "like".
            CREATE TABLE IF NOT EXISTS short_reactions(
                short_id INTEGER NOT NULL, uid TEXT NOT NULL, kind TEXT NOT NULL, PRIMARY KEY(short_id, uid, kind));
            CREATE TABLE IF NOT EXISTS short_bookmarks(
                short_id INTEGER NOT NULL, uid TEXT NOT NULL, PRIMARY KEY(short_id, uid));
            """
        )
        con.executescript(
            """
            CREATE TABLE IF NOT EXISTS conversations(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT NOT NULL,                 -- 'dm' | 'group'
                name TEXT,                          -- groups only
                owner TEXT,                         -- group creator
                ai_enabled INTEGER NOT NULL DEFAULT 0,
                dm_key TEXT UNIQUE,                 -- "uidA|uidB" (sorted) for one-to-one chats
                created_at INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS conv_members(
                conv_id INTEGER NOT NULL, uid TEXT NOT NULL,
                role TEXT NOT NULL DEFAULT 'member',  -- owner | admin | member
                last_read_id INTEGER NOT NULL DEFAULT 0, joined_at INTEGER NOT NULL,
                PRIMARY KEY(conv_id, uid));
            CREATE INDEX IF NOT EXISTS conv_members_uid ON conv_members(uid);
            CREATE TABLE IF NOT EXISTS messages(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                conv_id INTEGER NOT NULL, uid TEXT,       -- NULL for system / AI messages
                kind TEXT NOT NULL DEFAULT 'user',        -- 'user' | 'system' | 'ai'
                text TEXT NOT NULL, created_at INTEGER NOT NULL,
                held INTEGER NOT NULL DEFAULT 0);        -- Step 22: 1 = hidden from the recipient until the sender follows them
            CREATE INDEX IF NOT EXISTS messages_conv ON messages(conv_id, id);
            """
        )
        con.execute(
            """CREATE TABLE IF NOT EXISTS media(
                public_id TEXT PRIMARY KEY,
                uid TEXT NOT NULL,
                kind TEXT NOT NULL,
                bytes INTEGER NOT NULL,
                created_at INTEGER NOT NULL)"""
        )
        # Step 27: in-app notifications (one row per event; "message" rows are merged per chat while unread)
        con.execute(
            """CREATE TABLE IF NOT EXISTS notifications(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uid TEXT NOT NULL,                    -- who receives it
                actor TEXT,                           -- who caused it (NULL = the AI)
                type TEXT NOT NULL,
                ref INTEGER NOT NULL DEFAULT 0,       -- post / short / chat / group id
                count INTEGER NOT NULL DEFAULT 1,
                created_at INTEGER NOT NULL,
                read INTEGER NOT NULL DEFAULT 0)""")
        con.execute("CREATE INDEX IF NOT EXISTS notifications_uid ON notifications(uid, id)")
        # Step 24: files waiting to be deleted on Cloudinary (see "Cloudinary clean-up queue" below)
        con.execute(
            """CREATE TABLE IF NOT EXISTS cloud_trash(
                public_id TEXT PRIMARY KEY,
                resource_type TEXT NOT NULL,          -- 'image' | 'video'
                queued_at INTEGER NOT NULL,
                tries INTEGER NOT NULL DEFAULT 0)"""
        )
        con.commit()


init_db()


def current_user(authorization: str = Header(None)) -> str:
    """Returns the verified user id (uid) or raises 401."""
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, "Missing token")
    token = authorization[7:]
    if DEV_AUTH and token.startswith("dev-"):
        return token
    try:
        _init_firebase()
        from firebase_admin import auth
        return auth.verify_id_token(token)["uid"]
    except Exception:
        raise HTTPException(401, "Invalid or expired token")


@app.get("/health")
def health():
    return {"ok": True}


def avatar_url(public_id: str | None) -> str | None:
    if not public_id or not CLOUD_NAME:
        return None
    return (f"https://res.cloudinary.com/{CLOUD_NAME}/image/upload/"
            f"c_fill,g_face,w_256,h_256,f_auto,q_auto/{public_id}")


def profile_dict(row) -> dict:
    with closing(db()) as c:
        uid = row["uid"]
        followers = c.execute("SELECT COUNT(*) FROM follows WHERE followee=? AND status='accepted'", (uid,)).fetchone()[0]
        following = c.execute("SELECT COUNT(*) FROM follows WHERE follower=? AND status='accepted'", (uid,)).fetchone()[0]
        posts = c.execute("SELECT COUNT(*) FROM posts WHERE uid=?", (uid,)).fetchone()[0]
    return {
        "onboarded": True,
        "username": "@" + row["username"],
        "display_name": row["display_name"],
        "bio": row["bio"],
        "avatar_url": avatar_url(row["avatar_public_id"]),
        "is_private": bool(row["is_private"]),
        "who_can_message": row["who_can_message"], "default_audience": row["default_audience"],
        "follow_first": bool(row["follow_first"]), "show_last_seen": bool(row["show_last_seen"]),
        "notifications": notif_prefs(row),
        "followers": followers, "following": following, "posts": posts,
    }


@app.get("/users/me")
def me(uid: str = Depends(current_user)):
    with closing(db()) as con:
        row = con.execute("SELECT * FROM users WHERE uid=?", (uid,)).fetchone()
    return profile_dict(row) if row else {"onboarded": False}


class ProfileUpdate(BaseModel):
    display_name: str | None = Field(default=None, min_length=1, max_length=50)
    username: str | None = Field(default=None, max_length=40)     # Step 29: change my unique @username
    bio: str | None = Field(default=None, max_length=150)
    is_private: bool | None = None
    who_can_message: Literal["everyone", "followers", "nobody"] | None = None
    default_audience: Literal["public", "followers"] | None = None
    follow_first: bool | None = None
    show_last_seen: bool | None = None
    notifications: dict[str, bool] | None = None      # Step 27: {"follows": false, ...} (only the groups given change)


@app.patch("/users/me")
def update_me(body: ProfileUpdate, uid: str = Depends(current_user)):
    with closing(db()) as con:
        if not con.execute("SELECT 1 FROM users WHERE uid=?", (uid,)).fetchone():
            raise HTTPException(404, "Finish onboarding first")
        if body.username is not None:       # Step 29 (plan section 9: Account > username). Checked first: nothing else is saved if it fails.
            new_name = body.username.strip().lstrip("@").lower()
            if not USERNAME_RE.match(new_name):
                raise HTTPException(422, "Username must be 3-20 characters: a-z, 0-9, _ or .")
            if new_name != user_row(con, uid)["username"].lower():
                rate_limit(uid, "username", 5, 3600)
                try:
                    con.execute("UPDATE users SET username=? WHERE uid=?", (new_name, uid))
                except sqlite3.IntegrityError:
                    raise HTTPException(409, "That @username is already taken")
        if body.display_name is not None:
            con.execute("UPDATE users SET display_name=? WHERE uid=?", (body.display_name.strip(), uid))
        if body.bio is not None:
            con.execute("UPDATE users SET bio=? WHERE uid=?", (body.bio.strip(), uid))
        if body.is_private is not None:
            con.execute("UPDATE users SET is_private=? WHERE uid=?", (int(body.is_private), uid))
        if body.who_can_message is not None:
            con.execute("UPDATE users SET who_can_message=? WHERE uid=?", (body.who_can_message, uid))
        if body.default_audience is not None:
            con.execute("UPDATE users SET default_audience=? WHERE uid=?", (body.default_audience, uid))
        if body.follow_first is not None:
            con.execute("UPDATE users SET follow_first=? WHERE uid=?", (int(body.follow_first), uid))
            if not body.follow_first:
                release_all_held(con, uid)        # no follow needed any more: waiting messages appear
        if body.notifications is not None:
            bad = [k for k in body.notifications if k not in NOTIF_CATEGORIES]
            if bad:
                raise HTTPException(422, "Unknown notification group")
            off = {c for c in (user_row(con, uid)["notif_off"] or "").split(",") if c}
            for k, on in body.notifications.items():
                (off.discard if on else off.add)(k)
            con.execute("UPDATE users SET notif_off=? WHERE uid=?",
                        (",".join(c for c in NOTIF_CATEGORIES if c in off), uid))
        if body.show_last_seen is not None:
            con.execute("UPDATE users SET show_last_seen=? WHERE uid=?", (int(body.show_last_seen), uid))
            if not body.show_last_seen:
                con.execute("UPDATE users SET last_seen_at=0 WHERE uid=?", (uid,))   # switched off: nothing stays recorded
        con.commit()
        row = con.execute("SELECT * FROM users WHERE uid=?", (uid,)).fetchone()
    return profile_dict(row)


# ---------------- Notifications (Step 27, plan section 9: Notifications) ----------------
# Six groups the user can switch on / off: messages, follows, likes_comments, group_activity, shorts, ai.
# The server only WRITES a notification when the receiver's group is on; it never notifies about my own actions, never
# between people who blocked each other, and a notification never contains message text (only who + what kind).
# There is no push yet (that needs Firebase Cloud Messaging): the app reads this list (bell on Home).
NOTIF_CATEGORIES = ("messages", "follows", "likes_comments", "group_activity", "shorts", "ai")
NOTIF_TYPE_CATEGORY = {
    "message": "messages",
    "follow": "follows", "follow_request": "follows", "follow_accepted": "follows",
    "post_like": "likes_comments", "post_react": "likes_comments", "post_comment": "likes_comments",
    "group_added": "group_activity",
    "short_like": "shorts", "short_react": "shorts", "short_comment": "shorts",
    "ai_reply": "ai",
}
NOTIF_ONCE = {"follow", "post_like", "post_react", "short_like", "short_react"}   # the same person + thing only once
NOTIF_KEEP = 200                                                                    # newest notifications kept per person


def _off_set(row) -> set:
    return {c for c in (row["notif_off"] or "").split(",") if c}


def notif_prefs(row) -> dict:
    off = _off_set(row)
    return {c: c not in off for c in NOTIF_CATEGORIES}


def notify(con, recipient: str, actor: str | None, ntype: str, ref: int = 0):
    """Writes one notification (the caller commits). Silently does nothing when it must not be shown."""
    if actor and recipient == actor:
        return
    r = con.execute("SELECT notif_off FROM users WHERE uid=?", (recipient,)).fetchone()
    if not r or NOTIF_TYPE_CATEGORY[ntype] in (r["notif_off"] or "").split(","):
        return
    if actor and is_blocked(con, recipient, actor):
        return
    count = 1
    if ntype == "message":                      # one entry per chat while unread: "3 new messages"
        old = con.execute("SELECT id, count FROM notifications WHERE uid=? AND type='message' AND ref=? AND read=0",
                          (recipient, ref)).fetchone()
        if old:
            count = old["count"] + 1
            con.execute("DELETE FROM notifications WHERE id=?", (old["id"],))
    elif ntype in NOTIF_ONCE and con.execute(
            "SELECT 1 FROM notifications WHERE uid=? AND actor=? AND type=? AND ref=?",
            (recipient, actor, ntype, ref)).fetchone():
        return
    con.execute("INSERT INTO notifications(uid, actor, type, ref, count, created_at) VALUES (?,?,?,?,?,?)",
                (recipient, actor, ntype, ref, count, int(time.time())))
    con.execute("DELETE FROM notifications WHERE uid=? AND id NOT IN "
                "(SELECT id FROM notifications WHERE uid=? ORDER BY id DESC LIMIT ?)", (recipient, recipient, NOTIF_KEEP))


def _notify_owner(table: str, item_id: int, actor: str, ntype: str):
    """Tell the owner of a post / short that someone liked, reacted to or commented on it (the caller already checked access)."""
    with closing(db()) as con:
        r = con.execute(f"SELECT uid FROM {table} WHERE id=?", (item_id,)).fetchone()
        if r:
            notify(con, r["uid"], actor, ntype, item_id)
            con.commit()


def _hidden_types(row) -> list:
    off = _off_set(row)
    return [t for t, c in NOTIF_TYPE_CATEGORY.items() if c in off]


def _unread_notifications(con, uid: str, row) -> int:
    hidden = _hidden_types(row)
    sql, args = "SELECT COUNT(*) FROM notifications WHERE uid=? AND read=0", [uid]
    if hidden:
        sql += f" AND type NOT IN ({','.join('?' * len(hidden))})"; args += hidden
    return con.execute(sql, args).fetchone()[0]


@app.get("/notifications")
def list_notifications(before: int | None = None, limit: int = 30, uid: str = Depends(current_user)):
    limit = max(1, min(limit, 50))
    with closing(db()) as con:
        row = user_row(con, uid)
        if not row:
            raise HTTPException(404, "Finish onboarding first")
        hidden = _hidden_types(row)          # groups I switched off are hidden (old entries too)
        where, args = ["n.uid=?"], [uid]
        if hidden:
            where.append(f"n.type NOT IN ({','.join('?' * len(hidden))})"); args += hidden
        if before:
            where.append("n.id<?"); args.append(before)
        rows = con.execute(
            f"""SELECT n.*, u.username, u.display_name, u.avatar_public_id FROM notifications n
                LEFT JOIN users u ON u.uid=n.actor WHERE {' AND '.join(where)} ORDER BY n.id DESC LIMIT ?""",
            args + [limit]).fetchall()
        unread = _unread_notifications(con, uid, row)
    items = [{"id": r["id"], "type": r["type"], "category": NOTIF_TYPE_CATEGORY[r["type"]], "ref": r["ref"],
              "count": r["count"], "created_at": r["created_at"], "read": bool(r["read"]),
              "username": ("@" + r["username"]) if r["username"] else None,
              "display_name": r["display_name"], "avatar_url": avatar_url(r["avatar_public_id"])} for r in rows]
    return {"notifications": items, "unread": unread,
            "next_before": rows[-1]["id"] if len(rows) == limit else None}


@app.get("/notifications/unread-count")
def notification_unread(uid: str = Depends(current_user)):
    with closing(db()) as con:
        row = user_row(con, uid)
        return {"unread": _unread_notifications(con, uid, row) if row else 0}


@app.post("/notifications/read")
def notifications_read(uid: str = Depends(current_user)):
    with closing(db()) as con:
        con.execute("UPDATE notifications SET read=1 WHERE uid=? AND read=0", (uid,))
        con.commit()
    return {"ok": True}


# ---------------- Online / last seen (Step 25, plan section 9: Privacy & Security) ----------------
# The app sends a small "I am here" heartbeat while it is open. Rules (all on the server):
#  * Reciprocal: if I switch "Online / last seen" off, nothing of mine is recorded AND I do not see anybody else's.
#  * Only people who already have a one-to-one chat together can see it. Never shown between people who blocked each other,
#    for a missing user, or when nothing was recorded yet (the answer is the same `null` for every reason).
#  * Online = heartbeat in the last 90 seconds; otherwise the time of the last heartbeat is shown.
ONLINE_WINDOW_S = 90


def presence_of(con, me_uid: str, peer_uid: str) -> dict | None:
    me_row, peer = user_row(con, me_uid), user_row(con, peer_uid)
    if not me_row or not peer or me_uid == peer_uid:
        return None
    if not me_row["show_last_seen"] or not peer["show_last_seen"] or not peer["last_seen_at"]:
        return None
    if is_blocked(con, me_uid, peer_uid):
        return None
    key = "|".join(sorted([me_uid, peer_uid]))       # only people I already have a one-to-one chat with
    if not con.execute("SELECT 1 FROM conversations WHERE dm_key=?", (key,)).fetchone():
        return None
    return {"online": int(time.time()) - peer["last_seen_at"] <= ONLINE_WINDOW_S, "last_seen_at": peer["last_seen_at"]}


@app.post("/presence")
def heartbeat(uid: str = Depends(current_user)):
    rate_limit(uid, "presence", 30, 60)
    with closing(db()) as con:
        row = user_row(con, uid)
        if not row:
            raise HTTPException(404, "Finish onboarding first")
        if row["show_last_seen"]:
            con.execute("UPDATE users SET last_seen_at=? WHERE uid=?", (int(time.time()), uid))
            con.commit()
    return {"ok": True, "recording": bool(row["show_last_seen"])}


@app.get("/presence/{username}")
def get_presence(username: str, uid: str = Depends(current_user)):
    with closing(db()) as con:
        target = con.execute("SELECT uid FROM users WHERE username=?", (username.lstrip("@"),)).fetchone()
        p = presence_of(con, uid, target["uid"]) if target else None
    return {"presence": p}          # null = hidden (the app shows nothing); same answer for every reason


# ---------------- Cloudinary signed uploads ----------------
def user_limit_mb(uid: str) -> int:
    """Combined media allowance: 150 MB free, 600 MB while a VERIFIED Sociax App Pro payment is active (plan v3.1).
    App Pro is separate from AI Pro: buying one never unlocks the other."""
    return APP_PRO_MEDIA_MB if is_app_pro(uid) else FREE_MEDIA_MB


def used_bytes(con, uid: str, exclude_kind: str | None = None) -> int:
    if exclude_kind:
        r = con.execute("SELECT COALESCE(SUM(bytes),0) FROM media WHERE uid=? AND kind!=?", (uid, exclude_kind))
    else:
        r = con.execute("SELECT COALESCE(SUM(bytes),0) FROM media WHERE uid=?", (uid,))
    return int(r.fetchone()[0])


def cloud_sign(params: dict) -> str:
    to_sign = "&".join(f"{k}={params[k]}" for k in sorted(params))
    return hashlib.sha1((to_sign + CLOUD_SECRET).encode()).hexdigest()


def require_cloudinary():
    if not (CLOUD_NAME and CLOUD_KEY and CLOUD_SECRET):
        raise HTTPException(503, "Cloudinary is not configured on the server")


# ---------------- Cloudinary clean-up queue (Step 24) ----------------
# Every file that leaves Sociax (deleted post / short / story, expired story, replaced profile photo, deleted account)
# is written into the table `cloud_trash` in the SAME database transaction that removes it. A small worker then deletes
# it on Cloudinary. A failed try is repeated later (every 10 minutes and after every deletion), so a short Cloudinary
# problem never leaves a file behind. After CLOUD_TRASH_MAX_TRIES failed tries the entry is dropped.
CLOUD_TRASH_MAX_TRIES = 8
_trash_lock = threading.Lock()


def trash_media(con, public_id, kind: str):
    """Queue one file for deletion on Cloudinary. kind = the `media.kind` value (short = video, others = image)."""
    if not public_id:
        return
    rtype = "video" if kind == "short" else "image"
    con.execute("INSERT OR IGNORE INTO cloud_trash(public_id, resource_type, queued_at, tries) VALUES (?,?,?,0)",
                (public_id, rtype, int(time.time())))


def _cloud_destroy(public_id: str, rtype: str) -> bool:
    """True when Cloudinary says the file is gone (ok) or was never there (not found)."""
    import httpx
    p = {"public_id": public_id, "timestamp": int(time.time()), "invalidate": "true"}
    r = httpx.post(f"https://api.cloudinary.com/v1_1/{CLOUD_NAME}/{rtype}/destroy",
                   data={**p, "api_key": CLOUD_KEY, "signature": cloud_sign(p)}, timeout=20)
    if r.status_code != 200:
        return False
    return r.json().get("result") in ("ok", "not found")


def empty_cloud_trash(limit: int = 25) -> int:
    """Deletes queued files on Cloudinary. Returns how many were removed from the queue."""
    if not (CLOUD_NAME and CLOUD_KEY and CLOUD_SECRET):
        return 0
    if not _trash_lock.acquire(blocking=False):      # another worker is already busy
        return 0
    removed = 0
    try:
        with closing(db()) as con:
            rows = con.execute("SELECT public_id, resource_type, tries FROM cloud_trash ORDER BY queued_at LIMIT ?",
                               (limit,)).fetchall()
            for r in rows:
                try:
                    ok = _cloud_destroy(r["public_id"], r["resource_type"])
                except Exception:
                    ok = False
                if ok or r["tries"] + 1 >= CLOUD_TRASH_MAX_TRIES:
                    con.execute("DELETE FROM cloud_trash WHERE public_id=?", (r["public_id"],))
                    removed += 1 if ok else 0
                else:
                    con.execute("UPDATE cloud_trash SET tries=tries+1 WHERE public_id=?", (r["public_id"],))
            con.commit()
    finally:
        _trash_lock.release()
    return removed


def kick_cloud_cleanup():
    """Start the clean-up in the background so the app never waits for Cloudinary."""
    threading.Thread(target=empty_cloud_trash, daemon=True).start()


def _cloud_cleanup_loop():
    while True:
        time.sleep(600)
        try:
            empty_cloud_trash(100)
        except Exception:
            pass


threading.Thread(target=_cloud_cleanup_loop, daemon=True).start()


class SignRequest(BaseModel):
    purpose: Literal["avatar", "post", "short", "story"]
    size_bytes: int


@app.post("/media/sign-upload")
def sign_upload(body: SignRequest, uid: str = Depends(current_user)):
    require_cloudinary()
    rate_limit(uid, "sign", 30, 3600)
    max_bytes = {"avatar": AVATAR_MAX_BYTES, "post": POST_MAX_BYTES, "short": SHORT_MAX_BYTES,
                 "story": POST_MAX_BYTES}[body.purpose]
    if body.size_bytes <= 0 or body.size_bytes > max_bytes:
        what = "Video" if body.purpose == "short" else "Photo"
        raise HTTPException(413, f"{what} must be under {max_bytes // (1024 * 1024)} MB")
    with closing(db()) as con:
        # an avatar replaces the old one, so the old avatar does not count against the quota
        exclude = "avatar" if body.purpose == "avatar" else None
        if used_bytes(con, uid, exclude) + body.size_bytes > user_limit_mb(uid) * 1024 * 1024:
            raise media_full_error()
    ts = int(time.time())
    public_id = f"users/{uid}/{body.purpose}_{ts}"
    formats = "mp4,mov,webm,3gp,mkv" if body.purpose == "short" else "jpg,jpeg,png,webp"
    params = {"allowed_formats": formats, "public_id": public_id, "timestamp": ts}
    return {**params, "cloud_name": CLOUD_NAME, "api_key": CLOUD_KEY, "signature": cloud_sign(params)}


class AvatarConfirm(BaseModel):
    public_id: str


@app.post("/users/me/avatar")
def confirm_avatar(body: AvatarConfirm, uid: str = Depends(current_user)):
    """Called after the app uploaded to Cloudinary. The server verifies the asset itself;
    it does not trust sizes or URLs sent by the client."""
    require_cloudinary()
    import httpx
    if not body.public_id.startswith(f"users/{uid}/avatar_") or ".." in body.public_id:
        raise HTTPException(403, "Not your upload")
    try:
        r = httpx.get(
            f"https://api.cloudinary.com/v1_1/{CLOUD_NAME}/resources/image/upload/{body.public_id}",
            auth=(CLOUD_KEY, CLOUD_SECRET), timeout=15)
    except httpx.HTTPError:
        raise HTTPException(502, "Could not verify the upload")
    if r.status_code == 404:
        raise HTTPException(422, "Upload not found")
    if r.status_code != 200:
        raise HTTPException(502, "Could not verify the upload")
    size = int(r.json().get("bytes", 0))
    if size <= 0 or size > AVATAR_MAX_BYTES:
        raise HTTPException(413, "Profile photo must be under 5 MB")

    with closing(db()) as con:
        row = con.execute("SELECT avatar_public_id FROM users WHERE uid=?", (uid,)).fetchone()
        if not row:
            raise HTTPException(404, "Finish onboarding first")
        old = row["avatar_public_id"]
        if used_bytes(con, uid, "avatar") + size > user_limit_mb(uid) * 1024 * 1024:
            raise media_full_error()
        if old:
            con.execute("DELETE FROM media WHERE public_id=?", (old,))
            trash_media(con, old, "avatar")
        con.execute("INSERT OR REPLACE INTO media(public_id, uid, kind, bytes, created_at) VALUES (?,?,?,?,?)",
                    (body.public_id, uid, "avatar", size, int(time.time())))
        con.execute("UPDATE users SET avatar_public_id=? WHERE uid=?", (body.public_id, uid))
        con.commit()
        profile = profile_dict(con.execute("SELECT * FROM users WHERE uid=?", (uid,)).fetchone())

    if old:  # the replaced photo is deleted on Cloudinary by the clean-up queue
        kick_cloud_cleanup()
    return profile


class Onboarding(BaseModel):
    display_name: str = Field(min_length=1, max_length=50)
    username: str
    age: int
    source: str | None = None


@app.post("/users/onboard")
def onboard(body: Onboarding, uid: str = Depends(current_user)):
    username = body.username.lstrip("@").lower()
    if body.age < MIN_AGE:
        raise HTTPException(403, "Sociax is for ages 15+")
    if not USERNAME_RE.match(username):
        raise HTTPException(422, "Username must be 3-20 characters: a-z, 0-9, _ or .")
    source = body.source if body.source in SOURCES else None
    with closing(db()) as con:
        if con.execute("SELECT 1 FROM users WHERE uid=?", (uid,)).fetchone():
            raise HTTPException(409, "Already onboarded")
        try:
            con.execute(
                "INSERT INTO users(uid, username, display_name, age, source, created_at) VALUES (?,?,?,?,?,?)",
                (uid, username, body.display_name.strip(), body.age, source, int(time.time())),
            )
            con.commit()
        except sqlite3.IntegrityError:
            raise HTTPException(409, "That @username is already taken")
    return {"username": "@" + username}


class ShortMeta(BaseModel):
    duration_seconds: float
    size_mb: float
    mime: str


@app.post("/media/validate-short")
def validate_short(meta: ShortMeta, uid: str = Depends(current_user)):
    if meta.duration_seconds > MAX_SHORT_SECONDS:
        raise HTTPException(422, "Clip longer than 25s: trim it first")
    if not meta.mime.startswith("video/"):
        raise HTTPException(415, "Unsupported file type")
    if meta.size_mb * 1024 * 1024 > SHORT_MAX_BYTES:
        raise HTTPException(413, f"Video must be under {SHORT_MAX_BYTES // (1024 * 1024)} MB")
    with closing(db()) as con:
        if used_bytes(con, uid) + meta.size_mb * 1024 * 1024 > user_limit_mb(uid) * 1024 * 1024:
            raise media_full_error()
    return {"ok": True}  # advisory only: the real check happens again in POST /shorts on the uploaded file


@app.get("/quota/media")
def media_quota(uid: str = Depends(current_user)):
    with closing(db()) as con:
        used = used_bytes(con, uid)
    return {"limit_mb": user_limit_mb(uid), "pro_limit_mb": APP_PRO_MEDIA_MB,
            "used_mb": round(used / (1024 * 1024), 2),
            "app_pro": is_app_pro(uid), "app_pro_expires_at": app_pro_expires_at(uid) or None}


def ai_limit(uid: str) -> int:
    return FREE_AI_REQUESTS * (AI_PRO_MULTIPLIER if is_ai_pro(uid) else 1)  # Pro only after a VERIFIED Chapa payment


def ai_state(con, uid: str) -> dict:
    now = int(time.time())
    r = con.execute("SELECT window_start, used FROM ai_usage WHERE uid=?", (uid,)).fetchone()
    if not r or now - r["window_start"] >= AI_RESET_SECONDS:
        return {"used": 0, "limit": ai_limit(uid), "reset_in_seconds": AI_RESET_SECONDS, "fresh": True}
    return {"used": r["used"], "limit": ai_limit(uid),
            "reset_in_seconds": r["window_start"] + AI_RESET_SECONDS - now, "fresh": False}


def ai_take(con, uid: str):
    """Reserve one AI request on the SERVER (never trust numbers from the app) or raise 429."""
    st = ai_state(con, uid)
    if st["used"] >= st["limit"]:
        raise ai_limit_error(st["reset_in_seconds"])
    now = int(time.time())
    if st["fresh"]:
        con.execute("INSERT INTO ai_usage(uid, window_start, used) VALUES (?,?,1) "
                    "ON CONFLICT(uid) DO UPDATE SET window_start=excluded.window_start, used=1", (uid, now))
    else:
        con.execute("UPDATE ai_usage SET used=used+1 WHERE uid=?", (uid,))
    con.commit()


def ai_refund(con, uid: str):
    con.execute("UPDATE ai_usage SET used=MAX(used-1,0) WHERE uid=?", (uid,))
    con.commit()


@app.get("/quota/ai")
def ai_quota(uid: str = Depends(current_user)):
    with closing(db()) as con:
        st = ai_state(con, uid)
    return {"reset_in_seconds": st["reset_in_seconds"], "pro_multiplier": AI_PRO_MULTIPLIER,
            "free_requests": st["limit"] or None, "used": st["used"], "remaining": max(st["limit"] - st["used"], 0),
            "ai_pro": is_ai_pro(uid), "ai_pro_expires_at": ai_pro_expires_at(uid) or None}


@app.get("/ai/status")
def ai_status_endpoint(uid: str = Depends(current_user)):
    return {"providers": ai_providers.configured()}


class AiAsk(BaseModel):
    prompt: str = Field(min_length=1, max_length=2000)


@app.post("/ai/ask")
def ai_ask(body: AiAsk, uid: str = Depends(current_user)):
    """Private question to Sociax AI (Grok leads, Gemini assists). Nothing from chats is sent."""
    rate_limit(uid, "ai", 10, 60)
    if not any(ai_providers.configured().values()):
        raise HTTPException(503, "AI is not connected yet")
    with closing(db()) as con:
        ai_take(con, uid)
        try:
            answer, provider = ai_providers.ask(body.prompt)
        except ai_providers.AiUnavailable:
            ai_refund(con, uid)
            raise HTTPException(502, "AI could not answer right now. Please try again.")
        st = ai_state(con, uid)
    return {"answer": answer, "provider": provider, "used": st["used"], "limit": st["limit"],
            "reset_in_seconds": st["reset_in_seconds"]}


# =====================================================================
# Step 3: posts, feed, likes, comments, follow, search, report, block
# Every read goes through VISIBLE so private accounts / followers-only
# posts / blocked users are enforced on the SERVER, not in the app.
# =====================================================================
FOLLOW_ACCEPTED = "EXISTS(SELECT 1 FROM follows f WHERE f.follower=:me AND f.followee=p.uid AND f.status='accepted')"
NOT_BLOCKED = ("NOT EXISTS(SELECT 1 FROM blocks b WHERE (b.blocker=:me AND b.blocked=p.uid) "
               "OR (b.blocker=p.uid AND b.blocked=:me))")
VISIBLE = (f"(p.uid=:me OR ({NOT_BLOCKED} AND ((p.audience='public' AND u.is_private=0) OR {FOLLOW_ACCEPTED})))")

REACTION_KINDS = ("wow", "haha", "sad")   # 😮 😂 😢   (the heart is the existing like)
_REACT_COLS = ",\n".join(
    f"  (SELECT COUNT(*) FROM post_reactions x WHERE x.post_id=p.id AND x.kind='{k}') AS r_{k},\n"
    f"  EXISTS(SELECT 1 FROM post_reactions x WHERE x.post_id=p.id AND x.kind='{k}' AND x.uid=:me) AS m_{k}"
    for k in REACTION_KINDS)

POST_SELECT = f"""
SELECT p.*, u.username, u.display_name, u.avatar_public_id, u.is_private,
  (SELECT COUNT(*) FROM likes l WHERE l.post_id=p.id) AS like_count,
  (SELECT COUNT(*) FROM comments c WHERE c.post_id=p.id) AS comment_count,
  EXISTS(SELECT 1 FROM likes l WHERE l.post_id=p.id AND l.uid=:me) AS liked,
  EXISTS(SELECT 1 FROM bookmarks b WHERE b.post_id=p.id AND b.uid=:me) AS saved,
  (SELECT f.status FROM follows f WHERE f.follower=:me AND f.followee=p.uid) AS follow_status,
{_REACT_COLS}
FROM posts p JOIN users u ON u.uid=p.uid
"""


def image_url(public_id: str) -> str | None:
    if not CLOUD_NAME:
        return None
    return f"https://res.cloudinary.com/{CLOUD_NAME}/image/upload/c_limit,w_1080,f_auto,q_auto/{public_id}"


def post_dict(r, me: str) -> dict:
    return {
        "id": r["id"], "username": "@" + r["username"], "display_name": r["display_name"],
        "avatar_url": avatar_url(r["avatar_public_id"]), "caption": r["caption"],
        "image_url": image_url(r["media_public_id"]), "audience": r["audience"],
        "created_at": r["created_at"], "like_count": r["like_count"], "comment_count": r["comment_count"],
        "liked": bool(r["liked"]), "saved": bool(r["saved"]),
        "follow_status": "self" if r["uid"] == me else (r["follow_status"] or "none"),
        "reactions": {k: {"count": r[f"r_{k}"], "mine": bool(r[f"m_{k}"])} for k in REACTION_KINDS},
    }


def visible_post(con, post_id: int, me: str):
    row = con.execute(f"{POST_SELECT} WHERE p.id=:id AND {VISIBLE}", {"me": me, "id": post_id}).fetchone()
    if not row:
        raise HTTPException(404, "Post not found")  # same answer for hidden and missing posts
    return row


def user_by_name(con, username: str):
    row = con.execute("SELECT * FROM users WHERE username=?", (username.lstrip("@").lower(),)).fetchone()
    if not row:
        raise HTTPException(404, "User not found")
    return row


def is_blocked(con, a: str, b: str) -> bool:
    return con.execute("SELECT 1 FROM blocks WHERE (blocker=? AND blocked=?) OR (blocker=? AND blocked=?)",
                       (a, b, b, a)).fetchone() is not None


def asset_bytes(public_id: str) -> int:
    """Ask Cloudinary (server to server) how big the uploaded file really is."""
    require_cloudinary()
    import httpx
    try:
        r = httpx.get(f"https://api.cloudinary.com/v1_1/{CLOUD_NAME}/resources/image/upload/{public_id}",
                      auth=(CLOUD_KEY, CLOUD_SECRET), timeout=15)
    except httpx.HTTPError:
        raise HTTPException(502, "Could not verify the upload")
    if r.status_code == 404:
        raise HTTPException(422, "Upload not found")
    if r.status_code != 200:
        raise HTTPException(502, "Could not verify the upload")
    return int(r.json().get("bytes", 0))


class NewPost(BaseModel):
    public_id: str
    caption: str = Field(default="", max_length=2200)
    audience: Literal["public", "followers"] = "public"


@app.post("/posts")
def create_post(body: NewPost, uid: str = Depends(current_user)):
    rate_limit(uid, "post", 20, 3600)
    if not body.public_id.startswith(f"users/{uid}/post_") or ".." in body.public_id:
        raise HTTPException(403, "Not your upload")
    size = asset_bytes(body.public_id)
    if size <= 0 or size > POST_MAX_BYTES:
        raise HTTPException(413, "Photo must be under 10 MB")
    with closing(db()) as con:
        if not con.execute("SELECT 1 FROM users WHERE uid=?", (uid,)).fetchone():
            raise HTTPException(404, "Finish onboarding first")
        if con.execute("SELECT 1 FROM media WHERE public_id=?", (body.public_id,)).fetchone():
            raise HTTPException(409, "Already posted")
        if used_bytes(con, uid) + size > user_limit_mb(uid) * 1024 * 1024:
            raise media_full_error()
        con.execute("INSERT INTO media(public_id, uid, kind, bytes, created_at) VALUES (?,?,?,?,?)",
                    (body.public_id, uid, "post", size, int(time.time())))
        cur = con.execute("INSERT INTO posts(uid, caption, media_public_id, audience, created_at) VALUES (?,?,?,?,?)",
                          (uid, body.caption.strip(), body.public_id, body.audience, int(time.time())))
        con.commit()
        row = con.execute(f"{POST_SELECT} WHERE p.id=:id", {"me": uid, "id": cur.lastrowid}).fetchone()
    return post_dict(row, uid)


@app.delete("/posts/{post_id}")
def delete_post(post_id: int, uid: str = Depends(current_user)):
    with closing(db()) as con:
        row = con.execute("SELECT * FROM posts WHERE id=? AND uid=?", (post_id, uid)).fetchone()
        if not row:
            raise HTTPException(404, "Post not found")
        for t in ("likes", "bookmarks", "comments", "post_reactions"):
            con.execute(f"DELETE FROM {t} WHERE post_id=?", (post_id,))
        con.execute("DELETE FROM posts WHERE id=?", (post_id,))
        con.execute("DELETE FROM media WHERE public_id=?", (row["media_public_id"],))  # frees quota
        trash_media(con, row["media_public_id"], "post")
        con.commit()
    kick_cloud_cleanup()
    return {"ok": True}


@app.get("/feed")
def feed(tab: Literal["for_you", "following"] = "for_you", before: int | None = None,
         limit: int = 20, uid: str = Depends(current_user)):
    limit = max(1, min(limit, 50))
    where = [VISIBLE]
    if tab == "following":
        where.append(f"(p.uid=:me OR {FOLLOW_ACCEPTED})")
    if before:
        where.append("p.id < :before")
    sql = f"{POST_SELECT} WHERE {' AND '.join(where)} ORDER BY p.id DESC LIMIT :limit"
    with closing(db()) as con:
        rows = con.execute(sql, {"me": uid, "before": before, "limit": limit}).fetchall()
    return {"posts": [post_dict(r, uid) for r in rows]}


def _toggle(table: str, post_id: int, uid: str, on: bool):
    with closing(db()) as con:
        visible_post(con, post_id, uid)
        if on:
            con.execute(f"INSERT OR IGNORE INTO {table}(post_id, uid) VALUES (?,?)", (post_id, uid))
        else:
            con.execute(f"DELETE FROM {table} WHERE post_id=? AND uid=?", (post_id, uid))
        con.commit()
    return {"ok": True}


@app.post("/posts/{post_id}/like")
def like(post_id: int, uid: str = Depends(current_user)):
    out = _toggle("likes", post_id, uid, True)
    _notify_owner("posts", post_id, uid, "post_like")
    return out


@app.delete("/posts/{post_id}/like")
def unlike(post_id: int, uid: str = Depends(current_user)):
    return _toggle("likes", post_id, uid, False)


@app.post("/posts/{post_id}/react/{kind}")
def react(post_id: int, kind: Literal["wow", "haha", "sad"], uid: str = Depends(current_user)):
    """Same visibility rules as like: hidden and missing posts both answer 404."""
    with closing(db()) as con:
        visible_post(con, post_id, uid)
        con.execute("INSERT OR IGNORE INTO post_reactions(post_id, uid, kind) VALUES (?,?,?)", (post_id, uid, kind))
        con.commit()
    _notify_owner("posts", post_id, uid, "post_react")
    return {"ok": True}


@app.delete("/posts/{post_id}/react/{kind}")
def unreact(post_id: int, kind: Literal["wow", "haha", "sad"], uid: str = Depends(current_user)):
    with closing(db()) as con:
        visible_post(con, post_id, uid)
        con.execute("DELETE FROM post_reactions WHERE post_id=? AND uid=? AND kind=?", (post_id, uid, kind))
        con.commit()
    return {"ok": True}


@app.post("/posts/{post_id}/bookmark")
def bookmark(post_id: int, uid: str = Depends(current_user)):
    return _toggle("bookmarks", post_id, uid, True)


@app.delete("/posts/{post_id}/bookmark")
def unbookmark(post_id: int, uid: str = Depends(current_user)):
    return _toggle("bookmarks", post_id, uid, False)


class NewComment(BaseModel):
    text: str = Field(min_length=1, max_length=500)


@app.get("/posts/{post_id}/comments")
def list_comments(post_id: int, uid: str = Depends(current_user)):
    with closing(db()) as con:
        visible_post(con, post_id, uid)
        rows = con.execute(
            """SELECT c.id, c.text, c.created_at, u.username, u.display_name, u.avatar_public_id
               FROM comments c JOIN users u ON u.uid=c.uid
               WHERE c.post_id=? AND NOT EXISTS(SELECT 1 FROM blocks b WHERE
                 (b.blocker=? AND b.blocked=c.uid) OR (b.blocker=c.uid AND b.blocked=?))
               ORDER BY c.id ASC LIMIT 200""", (post_id, uid, uid)).fetchall()
    return {"comments": [{"id": r["id"], "text": r["text"], "created_at": r["created_at"],
                          "username": "@" + r["username"], "display_name": r["display_name"],
                          "avatar_url": avatar_url(r["avatar_public_id"])} for r in rows]}


@app.post("/posts/{post_id}/comments")
def add_comment(post_id: int, body: NewComment, uid: str = Depends(current_user)):
    rate_limit(uid, "comment", 30, 600)
    with closing(db()) as con:
        visible_post(con, post_id, uid)
        con.execute("INSERT INTO comments(post_id, uid, text, created_at) VALUES (?,?,?,?)",
                    (post_id, uid, body.text.strip(), int(time.time())))
        con.commit()
    _notify_owner("posts", post_id, uid, "post_comment")
    return {"ok": True}


class Report(BaseModel):
    reason: str = Field(min_length=1, max_length=500)


@app.post("/posts/{post_id}/report")
def report_post(post_id: int, body: Report, uid: str = Depends(current_user)):
    rate_limit(uid, "report", 20, 3600)
    with closing(db()) as con:
        visible_post(con, post_id, uid)
        con.execute("INSERT INTO reports(reporter, target_type, target_id, reason, created_at) VALUES (?,?,?,?,?)",
                    (uid, "post", str(post_id), body.reason.strip(), int(time.time())))
        con.commit()
    return {"ok": True}


class ProblemReport(BaseModel):
    text: str = Field(min_length=1, max_length=1000)
    app_version: str | None = Field(default=None, max_length=20)


@app.post("/feedback")
def report_problem(body: ProblemReport, uid: str = Depends(current_user)):
    """Settings > Help & legal > Report a problem (plan section 9). Saved with the other reports for review.
    The text is never written to the logs. It is deleted together with the account."""
    text = body.text.strip()
    if len(text) < 10:
        raise HTTPException(400, "Please describe the problem in at least 10 characters.")
    rate_limit(uid, "problem", 5, 3600)
    version = (body.app_version or "app").strip()[:20] or "app"
    with closing(db()) as con:
        con.execute("INSERT INTO reports(reporter, target_type, target_id, reason, created_at) VALUES (?,?,?,?,?)",
                    (uid, "problem", version, text, int(time.time())))
        con.commit()
    return {"ok": True}


# ---------------- follow / block ----------------
def _release(con, conv_id: int, sender: str, recipient: str):
    """Make the sender's waiting messages visible to the recipient AND unread again.
    (Follow acceptance and reading are separate states: showing them never counts as reading them.)"""
    first = con.execute("SELECT MIN(id) FROM messages WHERE conv_id=? AND uid=? AND held=1", (conv_id, sender)).fetchone()[0]
    if first is None:
        return
    con.execute("UPDATE messages SET held=0 WHERE conv_id=? AND uid=? AND held=1", (conv_id, sender))
    notify(con, recipient, sender, "message", conv_id)          # Step 27: the waiting messages now appear
    con.execute("UPDATE conv_members SET last_read_id=MIN(last_read_id, ?) WHERE conv_id=? AND uid=?",
                (first - 1, conv_id, recipient))


def release_held(con, sender: str, recipient: str):
    """The sender is now an accepted follower of the recipient: their waiting messages are released."""
    key = "|".join(sorted([sender, recipient]))
    c = con.execute("SELECT id FROM conversations WHERE dm_key=?", (key,)).fetchone()
    if c:
        _release(con, c["id"], sender, recipient)


def release_all_held(con, recipient: str):
    """The recipient switched the prompt off: everything waiting for them is released."""
    for c in con.execute("SELECT c.id FROM conversations c JOIN conv_members m ON m.conv_id=c.id AND m.uid=? "
                         "WHERE c.kind='dm'", (recipient,)).fetchall():
        other = con.execute("SELECT uid FROM conv_members WHERE conv_id=? AND uid!=?", (c["id"], recipient)).fetchone()
        if other:
            _release(con, c["id"], other["uid"], recipient)


@app.post("/users/{username}/follow")
def follow(username: str, uid: str = Depends(current_user)):
    with closing(db()) as con:
        target = user_by_name(con, username)
        if target["uid"] == uid:
            raise HTTPException(422, "You cannot follow yourself")
        if is_blocked(con, uid, target["uid"]):
            raise HTTPException(404, "User not found")
        status = "pending" if target["is_private"] else "accepted"
        fresh = con.execute("INSERT OR IGNORE INTO follows(follower, followee, status, created_at) VALUES (?,?,?,?)",
                            (uid, target["uid"], status, int(time.time()))).rowcount == 1
        con.commit()
        current = con.execute("SELECT status FROM follows WHERE follower=? AND followee=?",
                              (uid, target["uid"])).fetchone()["status"]
        if current == "accepted":
            release_held(con, uid, target["uid"])
        if fresh:                                  # Step 27: "X follows you" / "X wants to follow you"
            notify(con, target["uid"], uid, "follow" if current == "accepted" else "follow_request")
        con.commit()
    return {"status": current}


@app.delete("/users/{username}/follow")
def unfollow(username: str, uid: str = Depends(current_user)):
    with closing(db()) as con:
        target = user_by_name(con, username)
        con.execute("DELETE FROM follows WHERE follower=? AND followee=?", (uid, target["uid"]))
        con.commit()
    return {"status": "none"}


@app.get("/follow-requests")
def follow_requests(uid: str = Depends(current_user)):
    with closing(db()) as con:
        rows = con.execute(
            """SELECT u.username, u.display_name, u.avatar_public_id FROM follows f
               JOIN users u ON u.uid=f.follower WHERE f.followee=? AND f.status='pending'
               ORDER BY f.created_at DESC LIMIT 100""", (uid,)).fetchall()
    return {"requests": [{"username": "@" + r["username"], "display_name": r["display_name"],
                          "avatar_url": avatar_url(r["avatar_public_id"])} for r in rows]}


@app.post("/follow-requests/{username}/{action}")
def answer_follow_request(username: str, action: Literal["accept", "decline"], uid: str = Depends(current_user)):
    with closing(db()) as con:
        other = user_by_name(con, username)
        if action == "accept":
            accepted = con.execute("UPDATE follows SET status='accepted' WHERE follower=? AND followee=? AND status='pending'",
                                   (other["uid"], uid)).rowcount
            release_held(con, other["uid"], uid)
            if accepted:                           # Step 27: tell the requester
                notify(con, other["uid"], uid, "follow_accepted")
        else:
            con.execute("DELETE FROM follows WHERE follower=? AND followee=? AND status='pending'",
                        (other["uid"], uid))
        con.commit()
    return {"ok": True}


@app.post("/users/{username}/block")
def block_user(username: str, uid: str = Depends(current_user)):
    with closing(db()) as con:
        target = user_by_name(con, username)
        if target["uid"] == uid:
            raise HTTPException(422, "You cannot block yourself")
        con.execute("INSERT OR IGNORE INTO blocks(blocker, blocked) VALUES (?,?)", (uid, target["uid"]))
        # blocking removes follows in both directions
        con.execute("DELETE FROM follows WHERE (follower=? AND followee=?) OR (follower=? AND followee=?)",
                    (uid, target["uid"], target["uid"], uid))
        con.execute("DELETE FROM notifications WHERE (uid=? AND actor=?) OR (uid=? AND actor=?)",
                    (uid, target["uid"], target["uid"], uid))      # Step 27: nothing between blocked people
        con.commit()
    return {"ok": True}


@app.delete("/users/{username}/block")
def unblock_user(username: str, uid: str = Depends(current_user)):
    with closing(db()) as con:
        target = user_by_name(con, username)
        con.execute("DELETE FROM blocks WHERE blocker=? AND blocked=?", (uid, target["uid"]))
        con.commit()
    return {"ok": True}


# ---------------- other people's profile pages ----------------
def _blocked_me(con, me: str, other: str) -> bool:
    return con.execute("SELECT 1 FROM blocks WHERE blocker=? AND blocked=?", (other, me)).fetchone() is not None


@app.get("/users/{username}")
def user_profile(username: str, uid: str = Depends(current_user)):
    """Profile page of anyone. Rules (plan sections 4 + 11), all enforced HERE:
    - someone who blocked me, or a missing user: the same "not found";
    - someone I blocked: only name + photo, so I can unblock;
    - private account and I am not an accepted follower: no posts and no post count."""
    with closing(db()) as con:
        t = user_by_name(con, username)
        if t["uid"] == uid:
            d = profile_dict(t)
            d.update(is_me=True, follow_status="self", follows_me=False, can_view_posts=True,
                     can_message=False, i_blocked=False)
            return d
        if _blocked_me(con, uid, t["uid"]):
            raise HTTPException(404, "User not found")
        base = {"onboarded": True, "username": "@" + t["username"], "display_name": t["display_name"],
                "avatar_url": avatar_url(t["avatar_public_id"]), "is_private": bool(t["is_private"]),
                "is_me": False}
        if con.execute("SELECT 1 FROM blocks WHERE blocker=? AND blocked=?", (uid, t["uid"])).fetchone():
            base.update(bio="", followers=0, following=0, posts=None, follow_status="none", follows_me=False,
                        can_view_posts=False, can_message=False, i_blocked=True)
            return base
        st = con.execute("SELECT status FROM follows WHERE follower=? AND followee=?", (uid, t["uid"])).fetchone()
        status = st["status"] if st else "none"
        can_view = (not t["is_private"]) or status == "accepted"
        d = profile_dict(t)
        base.update(bio=d["bio"], followers=d["followers"], following=d["following"])
        if can_view:
            base["posts"] = con.execute(
                "SELECT COUNT(*) FROM posts p JOIN users u ON u.uid=p.uid WHERE p.uid=:t AND " + VISIBLE,
                {"me": uid, "t": t["uid"]}).fetchone()[0]
        else:
            base["posts"] = None            # a private account does not even reveal how many posts it has
        base.update(
            follow_status=status, can_view_posts=can_view, i_blocked=False,
            follows_me=con.execute("SELECT 1 FROM follows WHERE follower=? AND followee=? AND status='accepted'",
                                   (t["uid"], uid)).fetchone() is not None,
            can_message=can_contact(con, uid, t))
        return base


@app.get("/users/{username}/posts")
def user_posts(username: str, before: int | None = None, limit: int = 30, uid: str = Depends(current_user)):
    """Posts of one person: exactly what VISIBLE allows me to see (private accounts, followers-only, blocks)."""
    limit = max(1, min(limit, 50))
    with closing(db()) as con:
        t = user_by_name(con, username)
        if t["uid"] != uid and is_blocked(con, uid, t["uid"]):
            raise HTTPException(404, "User not found")
        where = [VISIBLE, "p.uid=:target"]
        if before:
            where.append("p.id < :before")
        rows = con.execute(f"{POST_SELECT} WHERE {' AND '.join(where)} ORDER BY p.id DESC LIMIT :limit",
                           {"me": uid, "target": t["uid"], "before": before, "limit": limit}).fetchall()
    return {"posts": [post_dict(r, uid) for r in rows]}


# ---------------- search ----------------
@app.get("/search")
def search(q: str, uid: str = Depends(current_user)):
    """People by @username/display name (unless blocked) + PUBLIC posts + PUBLIC short videos (caption match).
    Private accounts, followers-only content and blocked people never appear. Groups are never searchable (plan section 6)."""
    rate_limit(uid, "search", 60, 60)
    q = q.strip().lstrip("@").lower()
    if len(q) < 2:
        return {"users": [], "posts": [], "shorts": []}
    like_q = "%" + q.replace("%", "").replace("_", "") + "%"
    with closing(db()) as con:
        urows = con.execute(
            """SELECT u.uid, u.username, u.display_name, u.avatar_public_id, u.is_private,
                 (SELECT f.status FROM follows f WHERE f.follower=:me AND f.followee=u.uid) AS follow_status
               FROM users u WHERE (u.username LIKE :q OR LOWER(u.display_name) LIKE :q) AND u.uid!=:me
                 AND NOT EXISTS(SELECT 1 FROM blocks b WHERE (b.blocker=:me AND b.blocked=u.uid)
                   OR (b.blocker=u.uid AND b.blocked=:me))
               ORDER BY (u.username=:exact) DESC, u.username LIMIT 20""",
            {"me": uid, "q": like_q, "exact": q}).fetchall()
        prows = con.execute(
            f"""{POST_SELECT} WHERE p.audience='public' AND u.is_private=0 AND {NOT_BLOCKED}
                AND LOWER(p.caption) LIKE :q ORDER BY p.id DESC LIMIT 20""",
            {"me": uid, "q": like_q}).fetchall()
        srows = con.execute(
            f"""{SHORT_SELECT} WHERE p.audience='public' AND u.is_private=0 AND {NOT_BLOCKED}
                AND LOWER(p.caption) LIKE :q ORDER BY p.id DESC LIMIT 20""",
            {"me": uid, "q": like_q}).fetchall()
    return {
        "users": [{"username": "@" + r["username"], "display_name": r["display_name"],
                   "avatar_url": avatar_url(r["avatar_public_id"]), "is_private": bool(r["is_private"]),
                   "follow_status": r["follow_status"] or "none"} for r in urows],
        "posts": [post_dict(r, uid) for r in prows],
        "shorts": [short_dict(r, uid) for r in srows],
    }


# =====================================================================
# Step 4: Short Video (plan section 5). Max 25 s, enforced HERE on the file
# that was really uploaded to Cloudinary (never on numbers sent by the app).
# Privacy reuses the exact same VISIBLE rule as posts (alias p = shorts, u = users).
# =====================================================================
_SHORT_REACT_COLS = ",\n".join(
    f"  (SELECT COUNT(*) FROM short_reactions x WHERE x.short_id=p.id AND x.kind='{k}') AS r_{k},\n"
    f"  EXISTS(SELECT 1 FROM short_reactions x WHERE x.short_id=p.id AND x.kind='{k}' AND x.uid=:me) AS m_{k}"
    for k in REACTION_KINDS)

SHORT_SELECT = f"""
SELECT p.*, u.username, u.display_name, u.avatar_public_id, u.is_private,
  (SELECT COUNT(*) FROM short_likes l WHERE l.short_id=p.id) AS like_count,
  (SELECT COUNT(*) FROM short_comments c WHERE c.short_id=p.id) AS comment_count,
  EXISTS(SELECT 1 FROM short_likes l WHERE l.short_id=p.id AND l.uid=:me) AS liked,
  (SELECT COUNT(*) FROM short_bookmarks b WHERE b.short_id=p.id) AS save_count,
  EXISTS(SELECT 1 FROM short_bookmarks b WHERE b.short_id=p.id AND b.uid=:me) AS saved,
  (SELECT f.status FROM follows f WHERE f.follower=:me AND f.followee=p.uid) AS follow_status,
{_SHORT_REACT_COLS}
FROM shorts p JOIN users u ON u.uid=p.uid
"""


def video_url(public_id: str, low: bool = False) -> str | None:
    if not CLOUD_NAME:
        return None
    t = "c_limit,w_480,q_auto:low,f_mp4" if low else "c_limit,w_720,q_auto,f_mp4"
    return f"https://res.cloudinary.com/{CLOUD_NAME}/video/upload/{t}/{public_id}.mp4"


def poster_url(public_id: str) -> str | None:
    if not CLOUD_NAME:
        return None
    return f"https://res.cloudinary.com/{CLOUD_NAME}/video/upload/so_0,c_limit,w_480,f_jpg/{public_id}.jpg"


def short_dict(r, me: str) -> dict:
    return {
        "id": r["id"], "username": "@" + r["username"], "display_name": r["display_name"],
        "avatar_url": avatar_url(r["avatar_public_id"]), "caption": r["caption"],
        "video_url": video_url(r["media_public_id"]), "video_url_low": video_url(r["media_public_id"], low=True),
        "poster_url": poster_url(r["media_public_id"]), "duration": r["duration"],
        "audience": r["audience"], "comments_enabled": bool(r["comments_enabled"]),
        "created_at": r["created_at"], "like_count": r["like_count"], "comment_count": r["comment_count"],
        "liked": bool(r["liked"]), "saved": bool(r["saved"]), "save_count": r["save_count"],
        "follow_status": "self" if r["uid"] == me else (r["follow_status"] or "none"),
        "reactions": {k: {"count": r[f"r_{k}"], "mine": bool(r[f"m_{k}"])} for k in REACTION_KINDS},
    }


def visible_short(con, short_id: int, me: str):
    row = con.execute(f"{SHORT_SELECT} WHERE p.id=:id AND {VISIBLE}", {"me": me, "id": short_id}).fetchone()
    if not row:
        raise HTTPException(404, "Short not found")  # same answer for hidden and missing
    return row


def video_info(public_id: str) -> dict:
    """Ask Cloudinary (server to server) for the real size, duration and format of the uploaded video."""
    require_cloudinary()
    import httpx
    try:
        r = httpx.get(f"https://api.cloudinary.com/v1_1/{CLOUD_NAME}/resources/video/upload/{public_id}",
                      auth=(CLOUD_KEY, CLOUD_SECRET), timeout=20)
    except httpx.HTTPError:
        raise HTTPException(502, "Could not verify the upload")
    if r.status_code == 404:
        raise HTTPException(422, "Upload not found")
    if r.status_code != 200:
        raise HTTPException(502, "Could not verify the upload")
    return r.json()


def destroy_video(public_id: str):
    """Best-effort removal of a video from Cloudinary (rejected or deleted clips)."""
    try:
        import httpx
        ts = int(time.time())
        p = {"public_id": public_id, "timestamp": ts}
        httpx.post(f"https://api.cloudinary.com/v1_1/{CLOUD_NAME}/video/destroy",
                   data={**p, "api_key": CLOUD_KEY, "signature": cloud_sign(p)}, timeout=15)
    except Exception:
        pass


class NewShort(BaseModel):
    public_id: str
    caption: str = Field(default="", max_length=2200)
    audience: Literal["public", "followers"] = "public"
    comments_enabled: bool = True
    chosen_seconds: Literal[20, 25] = 25      # the 20 s / 25 s choice made by the user


@app.post("/shorts")
def create_short(body: NewShort, uid: str = Depends(current_user)):
    rate_limit(uid, "short", 10, 3600)
    if not body.public_id.startswith(f"users/{uid}/short_") or ".." in body.public_id:
        raise HTTPException(403, "Not your upload")
    info = video_info(body.public_id)
    size = int(info.get("bytes", 0))
    duration = float(info.get("duration") or 0)
    fmt = str(info.get("format", "")).lower()
    reject = None
    if size <= 0 or size > SHORT_MAX_BYTES:
        reject = (413, f"Video must be under {SHORT_MAX_BYTES // (1024 * 1024)} MB")
    elif duration <= 0:
        reject = (422, "Could not read the video length")
    elif duration > body.chosen_seconds + DURATION_TOLERANCE or duration > MAX_SHORT_SECONDS + DURATION_TOLERANCE:
        reject = (422, f"Clip is longer than {body.chosen_seconds} seconds. Trim it first.")
    elif fmt not in {"mp4", "mov", "webm", "3gp", "mkv"}:
        reject = (415, "Unsupported video type")
    if reject:
        destroy_video(body.public_id)          # a rejected clip is never published and never kept
        raise HTTPException(*reject)
    with closing(db()) as con:
        if not con.execute("SELECT 1 FROM users WHERE uid=?", (uid,)).fetchone():
            raise HTTPException(404, "Finish onboarding first")
        if con.execute("SELECT 1 FROM media WHERE public_id=?", (body.public_id,)).fetchone():
            raise HTTPException(409, "Already published")
        if used_bytes(con, uid) + size > user_limit_mb(uid) * 1024 * 1024:
            destroy_video(body.public_id)
            raise media_full_error()
        con.execute("INSERT INTO media(public_id, uid, kind, bytes, created_at) VALUES (?,?,?,?,?)",
                    (body.public_id, uid, "short", size, int(time.time())))
        cur = con.execute(
            "INSERT INTO shorts(uid, caption, media_public_id, duration, audience, comments_enabled, created_at) "
            "VALUES (?,?,?,?,?,?,?)",
            (uid, body.caption.strip(), body.public_id, duration, body.audience, int(body.comments_enabled), int(time.time())))
        con.commit()
        row = con.execute(f"{SHORT_SELECT} WHERE p.id=:id", {"me": uid, "id": cur.lastrowid}).fetchone()
    return short_dict(row, uid)


@app.get("/shorts/feed")
def shorts_feed(before: int | None = None, limit: int = 10, uid: str = Depends(current_user)):
    limit = max(1, min(limit, 30))
    where = [VISIBLE]
    if before:
        where.append("p.id < :before")
    sql = f"{SHORT_SELECT} WHERE {' AND '.join(where)} ORDER BY p.id DESC LIMIT :limit"
    with closing(db()) as con:
        rows = con.execute(sql, {"me": uid, "before": before, "limit": limit}).fetchall()
    return {"shorts": [short_dict(r, uid) for r in rows]}


@app.get("/shorts/saved")
def saved_shorts(before: int | None = None, limit: int = 30, uid: str = Depends(current_user)):
    """Step 21: short videos I saved. Same VISIBLE rule as the feed: a saved short of someone who went private,
    changed it to followers-only, or blocked me quietly disappears from my list."""
    limit = max(1, min(limit, 50))
    where = [VISIBLE, "EXISTS(SELECT 1 FROM short_bookmarks s WHERE s.short_id=p.id AND s.uid=:me)"]
    if before:
        where.append("p.id < :before")
    sql = f"{SHORT_SELECT} WHERE {' AND '.join(where)} ORDER BY p.id DESC LIMIT :limit"
    with closing(db()) as con:
        rows = con.execute(sql, {"me": uid, "before": before, "limit": limit}).fetchall()
    return {"shorts": [short_dict(r, uid) for r in rows]}


@app.delete("/shorts/{short_id}")
def delete_short(short_id: int, uid: str = Depends(current_user)):
    with closing(db()) as con:
        row = con.execute("SELECT * FROM shorts WHERE id=? AND uid=?", (short_id, uid)).fetchone()
        if not row:
            raise HTTPException(404, "Short not found")
        con.execute("DELETE FROM short_likes WHERE short_id=?", (short_id,))
        con.execute("DELETE FROM short_comments WHERE short_id=?", (short_id,))
        con.execute("DELETE FROM short_reactions WHERE short_id=?", (short_id,))
        con.execute("DELETE FROM short_bookmarks WHERE short_id=?", (short_id,))
        con.execute("DELETE FROM shorts WHERE id=?", (short_id,))
        con.execute("DELETE FROM media WHERE public_id=?", (row["media_public_id"],))   # frees quota
        trash_media(con, row["media_public_id"], "short")
        con.commit()
    kick_cloud_cleanup()
    return {"ok": True}


@app.post("/shorts/{short_id}/like")
def like_short(short_id: int, uid: str = Depends(current_user)):
    with closing(db()) as con:
        visible_short(con, short_id, uid)
        con.execute("INSERT OR IGNORE INTO short_likes(short_id, uid) VALUES (?,?)", (short_id, uid))
        con.commit()
    _notify_owner("shorts", short_id, uid, "short_like")
    return {"ok": True}


@app.delete("/shorts/{short_id}/like")
def unlike_short(short_id: int, uid: str = Depends(current_user)):
    with closing(db()) as con:
        visible_short(con, short_id, uid)
        con.execute("DELETE FROM short_likes WHERE short_id=? AND uid=?", (short_id, uid))
        con.commit()
    return {"ok": True}


@app.post("/shorts/{short_id}/react/{kind}")
def react_short(short_id: int, kind: Literal["wow", "haha", "sad"], uid: str = Depends(current_user)):
    """Same visibility rules as like: hidden and missing shorts both answer 404."""
    with closing(db()) as con:
        visible_short(con, short_id, uid)
        con.execute("INSERT OR IGNORE INTO short_reactions(short_id, uid, kind) VALUES (?,?,?)", (short_id, uid, kind))
        con.commit()
    _notify_owner("shorts", short_id, uid, "short_react")
    return {"ok": True}


@app.delete("/shorts/{short_id}/react/{kind}")
def unreact_short(short_id: int, kind: Literal["wow", "haha", "sad"], uid: str = Depends(current_user)):
    with closing(db()) as con:
        visible_short(con, short_id, uid)
        con.execute("DELETE FROM short_reactions WHERE short_id=? AND uid=? AND kind=?", (short_id, uid, kind))
        con.commit()
    return {"ok": True}


@app.post("/shorts/{short_id}/bookmark")
def bookmark_short(short_id: int, uid: str = Depends(current_user)):
    with closing(db()) as con:
        visible_short(con, short_id, uid)
        con.execute("INSERT OR IGNORE INTO short_bookmarks(short_id, uid) VALUES (?,?)", (short_id, uid))
        con.commit()
    return {"ok": True}


@app.delete("/shorts/{short_id}/bookmark")
def unbookmark_short(short_id: int, uid: str = Depends(current_user)):
    with closing(db()) as con:
        visible_short(con, short_id, uid)
        con.execute("DELETE FROM short_bookmarks WHERE short_id=? AND uid=?", (short_id, uid))
        con.commit()
    return {"ok": True}


@app.get("/shorts/{short_id}/comments")
def list_short_comments(short_id: int, uid: str = Depends(current_user)):
    with closing(db()) as con:
        row = visible_short(con, short_id, uid)
        if not row["comments_enabled"]:
            return {"comments": [], "comments_enabled": False}
        rows = con.execute(
            """SELECT c.id, c.text, c.created_at, u.username, u.display_name, u.avatar_public_id
               FROM short_comments c JOIN users u ON u.uid=c.uid
               WHERE c.short_id=? AND NOT EXISTS(SELECT 1 FROM blocks b WHERE
                 (b.blocker=? AND b.blocked=c.uid) OR (b.blocker=c.uid AND b.blocked=?))
               ORDER BY c.id ASC LIMIT 200""", (short_id, uid, uid)).fetchall()
    return {"comments_enabled": True,
            "comments": [{"id": r["id"], "text": r["text"], "created_at": r["created_at"],
                          "username": "@" + r["username"], "display_name": r["display_name"],
                          "avatar_url": avatar_url(r["avatar_public_id"])} for r in rows]}


@app.post("/shorts/{short_id}/comments")
def add_short_comment(short_id: int, body: NewComment, uid: str = Depends(current_user)):
    rate_limit(uid, "comment", 30, 600)
    with closing(db()) as con:
        row = visible_short(con, short_id, uid)
        if not row["comments_enabled"]:
            raise HTTPException(403, "Comments are turned off for this video")
        con.execute("INSERT INTO short_comments(short_id, uid, text, created_at) VALUES (?,?,?,?)",
                    (short_id, uid, body.text.strip(), int(time.time())))
        con.commit()
    _notify_owner("shorts", short_id, uid, "short_comment")
    return {"ok": True}


@app.post("/shorts/{short_id}/report")
def report_short(short_id: int, body: Report, uid: str = Depends(current_user)):
    rate_limit(uid, "report", 20, 3600)
    with closing(db()) as con:
        visible_short(con, short_id, uid)
        con.execute("INSERT INTO reports(reporter, target_type, target_id, reason, created_at) VALUES (?,?,?,?,?)",
                    (uid, "short", str(short_id), body.reason.strip(), int(time.time())))
        con.commit()
    return {"ok": True}


# =====================================================================
# Step 5: private chat, groups, AI Join (plan sections 6 and 11)
# - Every endpoint checks membership on the SERVER; a non-member always gets the same
#   "not found", so the existence of a private chat / group is never revealed.
# - Groups are never returned by /search.
# - AI never sees one-to-one chats. In a group it only runs when an admin enabled it AND
#   a member typed @AI, and it receives only that one message (no history).
# - Message text is never written to logs.
# =====================================================================
MAX_MESSAGE_CHARS = 2000
GROUP_MAX_MEMBERS = 100          # technical safety cap; the plan has not decided a group size limit
AI_MENTION = re.compile(r"(?<![\w@])@ai\b", re.IGNORECASE)


def group_ai_reply(prompt: str, uid: str) -> tuple[str | None, str]:
    """Calls Grok/Gemini from the backend. Receives ONLY the message that mentioned @AI (no history).
    Counts against the asking member's free AI allowance. Returns (answer, status)."""
    if not any(ai_providers.configured().values()):
        return None, "not_connected"
    with closing(db()) as con:
        try:
            ai_take(con, uid)
        except HTTPException:
            return None, "quota"
        try:
            answer, _provider = ai_providers.ask(prompt)
        except ai_providers.AiUnavailable:
            ai_refund(con, uid)
            return None, "error"
    return answer, "answered"


def user_row(con, uid: str):
    return con.execute("SELECT * FROM users WHERE uid=?", (uid,)).fetchone()


def can_contact(con, me: str, target) -> bool:
    """May `me` start a chat with / add `target`?  Not blocked either way, then the target's own choice:
    who_can_message = 'nobody' -> no; 'followers' -> only accepted followers; 'everyone' -> anyone, except that
    a private account still needs an accepted follower."""
    if target["uid"] == me or is_blocked(con, me, target["uid"]):
        return False
    rule = target["who_can_message"]
    if rule == "nobody":
        return False
    if rule == "everyone" and not target["is_private"]:
        return True
    return con.execute("SELECT 1 FROM follows WHERE follower=? AND followee=? AND status='accepted'",
                       (me, target["uid"])).fetchone() is not None


def dm_peer_uid(con, conv_id: int, me: str) -> str | None:
    r = con.execute("SELECT uid FROM conv_members WHERE conv_id=? AND uid!=?", (conv_id, me)).fetchone()
    return r["uid"] if r else None


def open_conv(con, conv_id: int, me: str):
    """Returns (conversation row, my role) or raises the one generic 404."""
    row = con.execute(
        "SELECT c.*, m.role AS my_role FROM conversations c JOIN conv_members m "
        "ON m.conv_id=c.id AND m.uid=? WHERE c.id=?", (me, conv_id)).fetchone()
    if not row:
        raise HTTPException(404, "Chat not found")
    if row["kind"] == "dm":
        peer = dm_peer_uid(con, conv_id, me)
        if peer is None or is_blocked(con, me, peer):
            raise HTTPException(404, "Chat not found")
    return row, row["my_role"]


def add_system_message(con, conv_id: int, text: str, kind: str = "system") -> int:
    cur = con.execute("INSERT INTO messages(conv_id, uid, kind, text, created_at) VALUES (?,NULL,?,?,?)",
                      (conv_id, kind, text, int(time.time())))
    return cur.lastrowid


# Step 22: a message that is "held" (recipient asked senders to follow first) is invisible to the recipient.
SEEN = "(held=0 OR uid=?)"


def message_dict(con, r, me: str) -> dict:
    d = {"id": r["id"], "kind": r["kind"], "text": r["text"], "created_at": r["created_at"],
         "mine": r["uid"] == me and r["kind"] == "user",
         "held": bool(r["held"]) and r["uid"] == me}          # only the sender is told: "waiting for your follow"
    if r["kind"] == "user" and r["uid"]:
        u = user_row(con, r["uid"])
        if u:
            d.update({"username": "@" + u["username"], "display_name": u["display_name"],
                      "avatar_url": avatar_url(u["avatar_public_id"])})
    return d


def conv_summary(con, c, me: str) -> dict:
    last = con.execute(f"SELECT * FROM messages WHERE conv_id=? AND {SEEN} ORDER BY id DESC LIMIT 1", (c["id"], me)).fetchone()
    my = con.execute("SELECT last_read_id, role FROM conv_members WHERE conv_id=? AND uid=?", (c["id"], me)).fetchone()
    unread = con.execute(f"SELECT COUNT(*) FROM messages WHERE conv_id=? AND id>? AND (uid IS NULL OR uid!=?) AND {SEEN}",
                         (c["id"], my["last_read_id"], me, me)).fetchone()[0]
    out = {"id": c["id"], "kind": c["kind"], "unread": unread, "my_role": my["role"],
           "last_text": (last["text"][:80] if last else ""), "last_kind": last["kind"] if last else None,
           "last_at": last["created_at"] if last else c["created_at"],
           "last_id": last["id"] if last else 0, "ai_enabled": bool(c["ai_enabled"])}
    if c["kind"] == "dm":
        peer = user_row(con, dm_peer_uid(con, c["id"], me) or "")
        out.update({"title": peer["display_name"] if peer else "Deleted user",
                    "username": ("@" + peer["username"]) if peer else None,
                    "avatar_url": avatar_url(peer["avatar_public_id"]) if peer else None,
                    "presence": presence_of(con, me, peer["uid"]) if peer else None})
    else:
        out.update({"title": c["name"], "username": None, "avatar_url": None})
    return out


@app.get("/chats")
def list_chats(uid: str = Depends(current_user)):
    with closing(db()) as con:
        rows = con.execute(
            "SELECT c.* FROM conversations c JOIN conv_members m ON m.conv_id=c.id AND m.uid=?", (uid,)).fetchall()
        out = []
        for c in rows:
            if c["kind"] == "dm":
                peer = dm_peer_uid(con, c["id"], uid)
                if peer is None or is_blocked(con, uid, peer):
                    continue                                   # blocked chats disappear for both people
            out.append(conv_summary(con, c, uid))
    out.sort(key=lambda x: (x["last_at"], x["id"]), reverse=True)
    return {"chats": out[:100]}


class DmStart(BaseModel):
    username: str


@app.post("/chats/dm")
def start_dm(body: DmStart, uid: str = Depends(current_user)):
    with closing(db()) as con:
        if not user_row(con, uid):
            raise HTTPException(404, "Finish onboarding first")
        target = user_by_name(con, body.username)
        if target["uid"] == uid:
            raise HTTPException(422, "You cannot message yourself")
        if is_blocked(con, uid, target["uid"]):
            raise HTTPException(404, "User not found")
        key = "|".join(sorted([uid, target["uid"]]))
        c = con.execute("SELECT * FROM conversations WHERE dm_key=?", (key,)).fetchone()
        if not c:
            if not can_contact(con, uid, target):
                raise HTTPException(403, "You can't message this account. If it is private or limits messages to followers, follow them first and wait for approval.")
            now = int(time.time())
            cur = con.execute("INSERT INTO conversations(kind, dm_key, created_at) VALUES ('dm',?,?)", (key, now))
            for u in (uid, target["uid"]):
                con.execute("INSERT INTO conv_members(conv_id, uid, role, joined_at) VALUES (?,?,'member',?)",
                            (cur.lastrowid, u, now))
            con.commit()
            c = con.execute("SELECT * FROM conversations WHERE id=?", (cur.lastrowid,)).fetchone()
        return conv_summary(con, c, uid)


@app.get("/chats/{conv_id}/messages")
def get_messages(conv_id: int, after: int | None = None, limit: int = 50, uid: str = Depends(current_user)):
    limit = max(1, min(limit, 100))
    with closing(db()) as con:
        c, _ = open_conv(con, conv_id, uid)
        if after is not None:
            rows = con.execute(f"SELECT * FROM messages WHERE conv_id=? AND id>? AND {SEEN} ORDER BY id ASC LIMIT ?",
                               (conv_id, after, uid, limit)).fetchall()
        else:
            rows = list(reversed(con.execute(f"SELECT * FROM messages WHERE conv_id=? AND {SEEN} ORDER BY id DESC LIMIT ?",
                                             (conv_id, uid, limit)).fetchall()))
        msgs = [message_dict(con, r, uid) for r in rows]
        return {"messages": msgs, "conversation": conv_summary(con, c, uid)}


class NewMessage(BaseModel):
    text: str = Field(min_length=1, max_length=MAX_MESSAGE_CHARS)


@app.post("/chats/{conv_id}/messages")
def send_message(conv_id: int, body: NewMessage, uid: str = Depends(current_user)):
    rate_limit(uid, "msg", 60, 60)
    text = body.text.strip()
    if not text or len(text) > MAX_MESSAGE_CHARS:
        raise HTTPException(422, f"Message must be 1-{MAX_MESSAGE_CHARS} characters")
    ai_status = None
    ai_wanted = False
    with closing(db()) as con:
        c, _ = open_conv(con, conv_id, uid)
        held = 0
        if c["kind"] == "dm":                    # Step 22: "Follow me first" - the message waits, it is NOT marked as read
            peer = user_row(con, dm_peer_uid(con, conv_id, uid) or "")
            if peer and peer["follow_first"] and not con.execute(
                    "SELECT 1 FROM follows WHERE follower=? AND followee=? AND status='accepted'",
                    (uid, peer["uid"])).fetchone():
                held = 1
        cur = con.execute("INSERT INTO messages(conv_id, uid, kind, text, created_at, held) VALUES (?,?, 'user', ?, ?, ?)",
                          (conv_id, uid, text, int(time.time()), held))
        mid = cur.lastrowid
        con.execute("UPDATE conv_members SET last_read_id=? WHERE conv_id=? AND uid=?", (mid, conv_id, uid))
        if AI_MENTION.search(text):
            if c["kind"] == "dm":
                ai_status = "private_chat"          # AI never reads or answers in one-to-one chats
            elif not c["ai_enabled"]:
                ai_status = "disabled"              # no admin enabled AI: it must stay silent
            else:
                ai_wanted = True                    # the answer is fetched after the message is saved
        if not held:                                # Step 27: tell the other people (never the text, only "new messages")
            for m_row in con.execute("SELECT uid FROM conv_members WHERE conv_id=? AND uid!=?", (conv_id, uid)).fetchall():
                notify(con, m_row["uid"], uid, "message", conv_id)
        con.commit()
        if ai_wanted:
            answer, ai_status = group_ai_reply(AI_MENTION.sub("", text).strip(), uid)   # ONLY this message
            if answer:
                add_system_message(con, conv_id, answer[:4000], kind="ai")
                notify(con, uid, None, "ai_reply", conv_id)      # Step 27: tell the person who asked
                con.commit()
        row = con.execute("SELECT * FROM messages WHERE id=?", (mid,)).fetchone()
        out = {"message": message_dict(con, row, uid), "ai": ai_status}
        if held:
            out["follow_prompt"] = {"username": "@" + peer["username"], "display_name": peer["display_name"]}
        return out


@app.post("/chats/{conv_id}/read")
def mark_read(conv_id: int, uid: str = Depends(current_user)):
    with closing(db()) as con:
        open_conv(con, conv_id, uid)
        last = con.execute(f"SELECT COALESCE(MAX(id),0) FROM messages WHERE conv_id=? AND {SEEN}", (conv_id, uid)).fetchone()[0]
        con.execute("UPDATE conv_members SET last_read_id=? WHERE conv_id=? AND uid=?", (last, conv_id, uid))
        con.execute("UPDATE notifications SET read=1 WHERE uid=? AND ref=? AND type IN ('message','ai_reply')",
                    (uid, conv_id))                  # Step 27: opening the chat clears its notifications
        con.commit()
    return {"ok": True}


# ---------------- groups ----------------
class NewGroup(BaseModel):
    name: str = Field(min_length=1, max_length=50)
    usernames: list[str] = []
    confirmed: bool = False        # the app shows a summary first; the server refuses without the final OK


@app.post("/groups")
def create_group(body: NewGroup, uid: str = Depends(current_user)):
    rate_limit(uid, "group", 10, 3600)
    name = body.name.strip()
    if not name or len(name) > 50:
        raise HTTPException(422, "Group name must be 1-50 characters")
    if not body.confirmed:
        raise HTTPException(422, "Please confirm the group summary first")
    with closing(db()) as con:
        me = user_row(con, uid)
        if not me:
            raise HTTPException(404, "Finish onboarding first")
        targets, seen = [], {uid}
        for u in body.usernames:                          # validate EVERYONE before creating anything
            t = user_by_name(con, u)
            if t["uid"] in seen:
                continue
            if not can_contact(con, uid, t):
                raise HTTPException(403, f"@{t['username']} can't be added to your group")
            seen.add(t["uid"]); targets.append(t)
        if len(targets) + 1 > GROUP_MAX_MEMBERS:
            raise HTTPException(422, f"A group can have at most {GROUP_MAX_MEMBERS} members")
        now = int(time.time())
        cur = con.execute("INSERT INTO conversations(kind, name, owner, created_at) VALUES ('group',?,?,?)",
                          (name, uid, now))
        cid = cur.lastrowid
        con.execute("INSERT INTO conv_members(conv_id, uid, role, joined_at) VALUES (?,?,'owner',?)", (cid, uid, now))
        for t in targets:
            con.execute("INSERT INTO conv_members(conv_id, uid, role, joined_at) VALUES (?,?,'member',?)",
                        (cid, t["uid"], now))
        add_system_message(con, cid, f"@{me['username']} created the group \"{name}\"")
        for t in targets:                                  # Step 27: "X added you to a group"
            notify(con, t["uid"], uid, "group_added", cid)
        con.commit()
        c = con.execute("SELECT * FROM conversations WHERE id=?", (cid,)).fetchone()
        return conv_summary(con, c, uid)


def open_group(con, group_id: int, uid: str):
    c, role = open_conv(con, group_id, uid)
    if c["kind"] != "group":
        raise HTTPException(404, "Chat not found")
    return c, role


@app.get("/groups/{group_id}")
def group_info(group_id: int, uid: str = Depends(current_user)):
    with closing(db()) as con:
        c, role = open_group(con, group_id, uid)
        rows = con.execute(
            """SELECT u.username, u.display_name, u.avatar_public_id, m.role FROM conv_members m
               JOIN users u ON u.uid=m.uid WHERE m.conv_id=?
               ORDER BY CASE m.role WHEN 'owner' THEN 0 WHEN 'admin' THEN 1 ELSE 2 END, u.username""",
            (group_id,)).fetchall()
    return {"id": c["id"], "name": c["name"], "ai_enabled": bool(c["ai_enabled"]), "my_role": role,
            "members": [{"username": "@" + r["username"], "display_name": r["display_name"],
                         "avatar_url": avatar_url(r["avatar_public_id"]), "role": r["role"]} for r in rows]}


class MemberAdd(BaseModel):
    username: str


@app.post("/groups/{group_id}/members")
def add_member(group_id: int, body: MemberAdd, uid: str = Depends(current_user)):
    rate_limit(uid, "addmember", 60, 3600)
    with closing(db()) as con:
        c, role = open_group(con, group_id, uid)
        if role not in ("owner", "admin"):
            raise HTTPException(403, "Only the group owner or an admin can add members")
        t = user_by_name(con, body.username)
        if con.execute("SELECT 1 FROM conv_members WHERE conv_id=? AND uid=?", (group_id, t["uid"])).fetchone():
            raise HTTPException(409, "Already in the group")
        if not can_contact(con, uid, t):
            raise HTTPException(403, f"@{t['username']} can't be added to your group")
        n = con.execute("SELECT COUNT(*) FROM conv_members WHERE conv_id=?", (group_id,)).fetchone()[0]
        if n + 1 > GROUP_MAX_MEMBERS:
            raise HTTPException(422, f"A group can have at most {GROUP_MAX_MEMBERS} members")
        con.execute("INSERT INTO conv_members(conv_id, uid, role, joined_at) VALUES (?,?,'member',?)",
                    (group_id, t["uid"], int(time.time())))
        me = user_row(con, uid)
        add_system_message(con, group_id, f"@{t['username']} was added by @{me['username']}")
        notify(con, t["uid"], uid, "group_added", group_id)
        con.commit()
    return {"ok": True}


@app.delete("/groups/{group_id}/members/{username}")
def remove_member(group_id: int, username: str, uid: str = Depends(current_user)):
    with closing(db()) as con:
        c, role = open_group(con, group_id, uid)
        t = user_by_name(con, username)
        trow = con.execute("SELECT role FROM conv_members WHERE conv_id=? AND uid=?", (group_id, t["uid"])).fetchone()
        if not trow:
            raise HTTPException(404, "Member not found")
        if t["uid"] == uid:                                           # leaving the group
            con.execute("DELETE FROM conv_members WHERE conv_id=? AND uid=?", (group_id, uid))
            rest = con.execute("SELECT uid FROM conv_members WHERE conv_id=? ORDER BY "
                               "CASE role WHEN 'admin' THEN 0 ELSE 1 END, joined_at LIMIT 1", (group_id,)).fetchone()
            if not rest:                                              # last person left: the group is gone
                con.execute("DELETE FROM messages WHERE conv_id=?", (group_id,))
                con.execute("DELETE FROM conversations WHERE id=?", (group_id,))
            else:
                if role == "owner":                                   # ownership passes on, never lost
                    con.execute("UPDATE conv_members SET role='owner' WHERE conv_id=? AND uid=?", (group_id, rest["uid"]))
                    con.execute("UPDATE conversations SET owner=? WHERE id=?", (rest["uid"], group_id))
                add_system_message(con, group_id, f"@{t['username']} left the group")
            con.commit()
            return {"ok": True}
        allowed = role == "owner" or (role == "admin" and trow["role"] == "member")
        if not allowed:
            raise HTTPException(403, "You are not allowed to remove this member")
        con.execute("DELETE FROM conv_members WHERE conv_id=? AND uid=?", (group_id, t["uid"]))
        me = user_row(con, uid)
        add_system_message(con, group_id, f"@{t['username']} was removed by @{me['username']}")
        con.commit()
    return {"ok": True}


class RoleBody(BaseModel):
    role: Literal["admin", "member"]


@app.put("/groups/{group_id}/members/{username}/role")
def set_role(group_id: int, username: str, body: RoleBody, uid: str = Depends(current_user)):
    with closing(db()) as con:
        c, role = open_group(con, group_id, uid)
        if role != "owner":
            raise HTTPException(403, "Only the group owner can change roles")
        t = user_by_name(con, username)
        trow = con.execute("SELECT role FROM conv_members WHERE conv_id=? AND uid=?", (group_id, t["uid"])).fetchone()
        if not trow or trow["role"] == "owner":
            raise HTTPException(404, "Member not found")
        con.execute("UPDATE conv_members SET role=? WHERE conv_id=? AND uid=?", (body.role, group_id, t["uid"]))
        con.commit()
    return {"ok": True}


class AiToggle(BaseModel):
    enabled: bool


@app.put("/groups/{group_id}/ai")
def set_group_ai(group_id: int, body: AiToggle, uid: str = Depends(current_user)):
    """AI Join. Off by default. Only the owner/an admin can switch it, and everyone is told."""
    with closing(db()) as con:
        c, role = open_group(con, group_id, uid)
        if role not in ("owner", "admin"):
            raise HTTPException(403, "Only the group owner or an admin can turn AI on or off")
        if bool(c["ai_enabled"]) != body.enabled:
            con.execute("UPDATE conversations SET ai_enabled=? WHERE id=?", (int(body.enabled), group_id))
            me = user_row(con, uid)
            if body.enabled:
                add_system_message(con, group_id,
                    f"@{me['username']} turned AI on for this group. AI answers only when someone types @AI, "
                    "and it only sees that one message.")
            else:
                add_system_message(con, group_id, f"@{me['username']} turned AI off. AI no longer responds in this group.")
            con.commit()
    return {"ai_enabled": body.enabled}


@app.delete("/groups/{group_id}")
def delete_group(group_id: int, uid: str = Depends(current_user)):
    with closing(db()) as con:
        c, role = open_group(con, group_id, uid)
        if role != "owner":
            raise HTTPException(403, "Only the group owner can delete the group")
        con.execute("DELETE FROM messages WHERE conv_id=?", (group_id,))
        con.execute("DELETE FROM conv_members WHERE conv_id=?", (group_id,))
        con.execute("DELETE FROM conversations WHERE id=?", (group_id,))
        con.commit()
    return {"ok": True}


# =====================================================================
# Step 6: Stories (plan section 4 "Stories"). Photo stories that disappear after 24 hours.
# Privacy (stricter than posts, on purpose): a story is shown ONLY to its owner and to people who
# follow the owner with an ACCEPTED follow; blocked people never see it; public search never shows stories.
# =====================================================================
STORY_TTL_SECONDS = 24 * 3600
STORY_CAPTION_MAX = 200
# alias p = stories, u = users (so the same FOLLOW_ACCEPTED / NOT_BLOCKED snippets as posts can be reused)
STORY_VISIBLE = f"(p.uid=:me OR ({NOT_BLOCKED} AND {FOLLOW_ACCEPTED}))"


def init_stories_db():
    with closing(db()) as con:
        con.executescript(
            """
            CREATE TABLE IF NOT EXISTS stories(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uid TEXT NOT NULL, caption TEXT NOT NULL DEFAULT '',
                media_public_id TEXT NOT NULL,
                created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL);
            CREATE INDEX IF NOT EXISTS stories_uid ON stories(uid, id);
            CREATE TABLE IF NOT EXISTS story_views(
                story_id INTEGER NOT NULL, uid TEXT NOT NULL, viewed_at INTEGER NOT NULL,
                PRIMARY KEY(story_id, uid));
            """
        )
        con.commit()


init_stories_db()


def purge_expired_stories(con):
    """Expired stories are removed and their quota is given back."""
    rows = con.execute("SELECT id, media_public_id FROM stories WHERE expires_at<=?", (int(time.time()),)).fetchall()
    for r in rows:
        con.execute("DELETE FROM story_views WHERE story_id=?", (r["id"],))
        con.execute("DELETE FROM stories WHERE id=?", (r["id"],))
        if r["media_public_id"]:
            con.execute("DELETE FROM media WHERE public_id=?", (r["media_public_id"],))
            trash_media(con, r["media_public_id"], "story")
    if rows:
        con.commit()
        kick_cloud_cleanup()


def story_dict(r, me: str) -> dict:
    mine = r["uid"] == me
    is_text = r["media_public_id"] == ""      # a text-only story has no photo (the text is stored in "caption")
    return {
        "id": r["id"], "kind": "text" if is_text else "photo",
        "image_url": None if is_text else image_url(r["media_public_id"]), "caption": r["caption"],
        "created_at": r["created_at"], "expires_at": r["expires_at"], "mine": mine,
        "seen": bool(r["seen"]),
        # only the owner sees how many people viewed it (never who, to keep it simple and private)
        "view_count": int(r["view_count"]) if mine else 0,
    }


class NewStory(BaseModel):
    public_id: str
    caption: str = Field(default="", max_length=STORY_CAPTION_MAX)


class NewTextStory(BaseModel):
    text: str = Field(min_length=1, max_length=STORY_CAPTION_MAX)


STORY_SELECT = """
SELECT p.*, u.username, u.display_name, u.avatar_public_id,
  EXISTS(SELECT 1 FROM story_views v WHERE v.story_id=p.id AND v.uid=:me) AS seen,
  (SELECT COUNT(*) FROM story_views v WHERE v.story_id=p.id) AS view_count
FROM stories p JOIN users u ON u.uid=p.uid
"""


@app.post("/stories")
def create_story(body: NewStory, uid: str = Depends(current_user)):
    rate_limit(uid, "story", 20, 3600)
    if not body.public_id.startswith(f"users/{uid}/story_") or ".." in body.public_id:
        raise HTTPException(403, "Not your upload")
    size = asset_bytes(body.public_id)
    if size <= 0 or size > POST_MAX_BYTES:
        raise HTTPException(413, "Photo must be under 10 MB")
    now = int(time.time())
    with closing(db()) as con:
        purge_expired_stories(con)
        if not con.execute("SELECT 1 FROM users WHERE uid=?", (uid,)).fetchone():
            raise HTTPException(404, "Finish onboarding first")
        if con.execute("SELECT 1 FROM media WHERE public_id=?", (body.public_id,)).fetchone():
            raise HTTPException(409, "Already posted")
        if used_bytes(con, uid) + size > user_limit_mb(uid) * 1024 * 1024:
            raise media_full_error()
        con.execute("INSERT INTO media(public_id, uid, kind, bytes, created_at) VALUES (?,?,?,?,?)",
                    (body.public_id, uid, "story", size, now))
        cur = con.execute(
            "INSERT INTO stories(uid, caption, media_public_id, created_at, expires_at) VALUES (?,?,?,?,?)",
            (uid, body.caption.strip(), body.public_id, now, now + STORY_TTL_SECONDS))
        con.commit()
        row = con.execute(f"{STORY_SELECT} WHERE p.id=:id", {"me": uid, "id": cur.lastrowid}).fetchone()
    return story_dict(row, uid)


@app.post("/stories/text")
def create_text_story(body: NewTextStory, uid: str = Depends(current_user)):
    """Text-only story (middle reference screen). No file, so no media quota is used.
    Same 24 hour life and the same privacy rules as photo stories (STORY_VISIBLE)."""
    rate_limit(uid, "story", 20, 3600)
    text = body.text.strip()
    if not text:
        raise HTTPException(422, "Write something first")
    if len(text) > STORY_CAPTION_MAX:
        raise HTTPException(422, f"Text can have at most {STORY_CAPTION_MAX} characters")
    now = int(time.time())
    with closing(db()) as con:
        purge_expired_stories(con)
        if not con.execute("SELECT 1 FROM users WHERE uid=?", (uid,)).fetchone():
            raise HTTPException(404, "Finish onboarding first")
        cur = con.execute(
            "INSERT INTO stories(uid, caption, media_public_id, created_at, expires_at) VALUES (?,?,?,?,?)",
            (uid, text, "", now, now + STORY_TTL_SECONDS))
        con.commit()
        row = con.execute(f"{STORY_SELECT} WHERE p.id=:id", {"me": uid, "id": cur.lastrowid}).fetchone()
    return story_dict(row, uid)


@app.get("/stories")
def list_stories(uid: str = Depends(current_user)):
    """Story rings for the Home screen: my own first, then people I follow (unseen first, newest first)."""
    with closing(db()) as con:
        purge_expired_stories(con)
        rows = con.execute(
            f"{STORY_SELECT} WHERE p.expires_at>:now AND {STORY_VISIBLE} ORDER BY p.id ASC",
            {"me": uid, "now": int(time.time())}).fetchall()
    groups: dict = {}
    for r in rows:
        g = groups.setdefault(r["uid"], {
            "username": "@" + r["username"], "display_name": r["display_name"],
            "avatar_url": avatar_url(r["avatar_public_id"]), "is_me": r["uid"] == uid, "stories": []})
        g["stories"].append(story_dict(r, uid))
    for g in groups.values():
        g["all_seen"] = g["is_me"] or all(st["seen"] for st in g["stories"])
        g["latest_at"] = g["stories"][-1]["created_at"]
    ordered = sorted(groups.values(), key=lambda g: (not g["is_me"], g["all_seen"], -g["latest_at"]))
    return {"groups": ordered}


@app.post("/stories/{story_id}/view")
def view_story(story_id: int, uid: str = Depends(current_user)):
    with closing(db()) as con:
        row = con.execute(
            f"SELECT p.uid FROM stories p JOIN users u ON u.uid=p.uid WHERE p.id=:id AND p.expires_at>:now AND {STORY_VISIBLE}",
            {"me": uid, "id": story_id, "now": int(time.time())}).fetchone()
        if not row:
            raise HTTPException(404, "Story not found")   # same answer for hidden, expired and missing stories
        if row["uid"] != uid:
            con.execute("INSERT OR IGNORE INTO story_views(story_id, uid, viewed_at) VALUES (?,?,?)",
                        (story_id, uid, int(time.time())))
            con.commit()
    return {"ok": True}


@app.delete("/stories/{story_id}")
def delete_story(story_id: int, uid: str = Depends(current_user)):
    with closing(db()) as con:
        row = con.execute("SELECT * FROM stories WHERE id=? AND uid=?", (story_id, uid)).fetchone()
        if not row:
            raise HTTPException(404, "Story not found")
        con.execute("DELETE FROM story_views WHERE story_id=?", (story_id,))
        con.execute("DELETE FROM stories WHERE id=?", (story_id,))
        if row["media_public_id"]:
            con.execute("DELETE FROM media WHERE public_id=?", (row["media_public_id"],))  # frees quota
            trash_media(con, row["media_public_id"], "story")
        con.commit()
    kick_cloud_cleanup()
    return {"ok": True}


# =====================================================================
# Step 17: blocked-users list, saved-posts list, account deletion (plan sections 9 + 11).
# Same rules as everywhere: every read goes through VISIBLE, so a saved post of someone who later
# went private / blocked me simply disappears from my list.
# =====================================================================
def _follow_list(kind: str, username: str, offset: int, limit: int, uid: str) -> dict:
    """Step 20: who follows `username` ('followers') or whom they follow ('following'). Rules, all enforced HERE:
    - missing user, or any block between us: the same "not found";
    - a private account I am not an accepted follower of (and it is not me): refused, the list stays hidden;
    - people who blocked me / whom I blocked are left out of the list (their existence is not revealed)."""
    limit = max(1, min(limit, 100)); offset = max(0, offset)
    with closing(db()) as con:
        t = user_by_name(con, username)
        if t["uid"] != uid:
            if is_blocked(con, uid, t["uid"]):
                raise HTTPException(404, "User not found")
            if t["is_private"] and not con.execute(
                    "SELECT 1 FROM follows WHERE follower=? AND followee=? AND status='accepted'", (uid, t["uid"])).fetchone():
                raise HTTPException(403, "This account is private. Follow them first to see their followers.")
        col, other = ("follower", "followee") if kind == "followers" else ("followee", "follower")
        rows = con.execute(
            f"""SELECT u.uid, u.username, u.display_name, u.avatar_public_id FROM follows f
                JOIN users u ON u.uid=f.{col}
                WHERE f.{other}=:t AND f.status='accepted'
                  AND NOT EXISTS(SELECT 1 FROM blocks b WHERE (b.blocker=:me AND b.blocked=u.uid) OR (b.blocker=u.uid AND b.blocked=:me))
                ORDER BY f.created_at DESC, u.username LIMIT :lim OFFSET :off""",
            {"t": t["uid"], "me": uid, "lim": limit + 1, "off": offset}).fetchall()
    more = len(rows) > limit
    return {"users": [{"username": "@" + r["username"], "display_name": r["display_name"],
                       "avatar_url": avatar_url(r["avatar_public_id"])} for r in rows[:limit]], "more": more}


@app.get("/users/{username}/followers")
def user_followers(username: str, offset: int = 0, limit: int = 50, uid: str = Depends(current_user)):
    return _follow_list("followers", username, offset, limit, uid)


@app.get("/users/{username}/following")
def user_following(username: str, offset: int = 0, limit: int = 50, uid: str = Depends(current_user)):
    return _follow_list("following", username, offset, limit, uid)


@app.get("/users/me/blocked")
def my_blocked(uid: str = Depends(current_user)):
    with closing(db()) as con:
        rows = con.execute(
            "SELECT u.username, u.display_name, u.avatar_public_id FROM blocks b JOIN users u ON u.uid=b.blocked "
            "WHERE b.blocker=? ORDER BY u.username", (uid,)).fetchall()
    return {"users": [{"username": "@" + r["username"], "display_name": r["display_name"],
                       "avatar_url": avatar_url(r["avatar_public_id"])} for r in rows]}


@app.get("/posts/saved")
def saved_posts(before: int | None = None, limit: int = 30, uid: str = Depends(current_user)):
    limit = max(1, min(limit, 50))
    where = [VISIBLE, "EXISTS(SELECT 1 FROM bookmarks s WHERE s.post_id=p.id AND s.uid=:me)"]
    if before:
        where.append("p.id < :before")
    sql = f"{POST_SELECT} WHERE {' AND '.join(where)} ORDER BY p.id DESC LIMIT :limit"
    with closing(db()) as con:
        rows = con.execute(sql, {"me": uid, "before": before, "limit": limit}).fetchall()
    return {"posts": [post_dict(r, uid) for r in rows]}


def _delete_user_data(con, uid: str):
    """Removes EVERYTHING that belongs to a user from the database; their files are queued for Cloudinary deletion."""
    # my own posts / shorts / stories, with everything that hangs on them
    for t in ("likes", "bookmarks", "comments", "post_reactions"):
        con.execute(f"DELETE FROM {t} WHERE post_id IN (SELECT id FROM posts WHERE uid=?)", (uid,))
    con.execute("DELETE FROM posts WHERE uid=?", (uid,))
    for t in ("short_likes", "short_comments", "short_reactions", "short_bookmarks"):
        con.execute(f"DELETE FROM {t} WHERE short_id IN (SELECT id FROM shorts WHERE uid=?)", (uid,))
    con.execute("DELETE FROM shorts WHERE uid=?", (uid,))
    con.execute("DELETE FROM story_views WHERE story_id IN (SELECT id FROM stories WHERE uid=?)", (uid,))
    con.execute("DELETE FROM stories WHERE uid=?", (uid,))
    # what I did on other people's content
    for t in ("likes", "bookmarks", "comments", "post_reactions", "short_likes", "short_comments",
              "short_reactions", "short_bookmarks", "story_views"):
        con.execute(f"DELETE FROM {t} WHERE uid=?", (uid,))
    con.execute("DELETE FROM follows WHERE follower=? OR followee=?", (uid, uid))
    con.execute("DELETE FROM blocks WHERE blocker=? OR blocked=?", (uid, uid))
    # one-to-one chats disappear completely
    dm_ids = [r[0] for r in con.execute(
        "SELECT c.id FROM conversations c JOIN conv_members m ON m.conv_id=c.id WHERE c.kind='dm' AND m.uid=?", (uid,))]
    for cid in dm_ids:
        con.execute("DELETE FROM messages WHERE conv_id=?", (cid,))
        con.execute("DELETE FROM conv_members WHERE conv_id=?", (cid,))
        con.execute("DELETE FROM conversations WHERE id=?", (cid,))
    # groups I own: hand over to an admin (else the longest member); no one left = group is deleted
    for g in con.execute("SELECT id FROM conversations WHERE kind='group' AND owner=?", (uid,)).fetchall():
        heir = con.execute(
            "SELECT uid FROM conv_members WHERE conv_id=? AND uid!=? "
            "ORDER BY CASE role WHEN 'admin' THEN 0 ELSE 1 END, joined_at LIMIT 1", (g["id"], uid)).fetchone()
        if heir:
            con.execute("UPDATE conversations SET owner=? WHERE id=?", (heir["uid"], g["id"]))
            con.execute("UPDATE conv_members SET role='owner' WHERE conv_id=? AND uid=?", (g["id"], heir["uid"]))
        else:
            con.execute("DELETE FROM messages WHERE conv_id=?", (g["id"],))
            con.execute("DELETE FROM conv_members WHERE conv_id=?", (g["id"],))
            con.execute("DELETE FROM conversations WHERE id=?", (g["id"],))
    con.execute("DELETE FROM messages WHERE uid=?", (uid,))
    con.execute("DELETE FROM conv_members WHERE uid=?", (uid,))
    for m_row in con.execute("SELECT public_id, kind FROM media WHERE uid=?", (uid,)).fetchall():
        trash_media(con, m_row["public_id"], m_row["kind"])
    con.execute("DELETE FROM media WHERE uid=?", (uid,))
    con.execute("DELETE FROM ai_usage WHERE uid=?", (uid,))
    con.execute("DELETE FROM notifications WHERE uid=? OR actor=?", (uid, uid))      # Step 27
    con.execute("DELETE FROM reports WHERE reporter=? AND target_type='problem'", (uid,))   # Step 28: my own free-text problem reports
    con.execute("DELETE FROM users WHERE uid=?", (uid,))


@app.delete("/users/me")
def delete_account(uid: str = Depends(current_user)):
    """Account deletion (plan sections 9 + 11). Irreversible. The username becomes free again."""
    rate_limit(uid, "delete_account", 3, 3600)
    with closing(db()) as con:
        if not con.execute("SELECT 1 FROM users WHERE uid=?", (uid,)).fetchone():
            raise HTTPException(404, "Account not found")
        _delete_user_data(con, uid)
        con.commit()
    kick_cloud_cleanup()
    if not DEV_AUTH:   # best effort: also remove the Firebase login itself (the app signs out either way)
        try:
            _init_firebase()
            from firebase_admin import auth as fb_auth
            fb_auth.delete_user(uid)
        except Exception:
            pass
    return {"ok": True}


# =====================================================================
# Sociax AI Pro ($10) and Sociax App Pro ($3) payments with Chapa (TEST mode). One-time payment = 30 days, NOT a subscription.
# The two products are SEPARATE (plan v3.1): each one has its own price, its own expiry, and buying one never unlocks the other.
# Rules (Chapa plan): the app never says "I paid". Pro is granted ONLY here, after the server
# verifies the transaction directly with Chapa and checks reference, status, amount, currency and user.
# =====================================================================
import secrets  # noqa: E402
from decimal import Decimal, InvalidOperation  # noqa: E402

AI_PRO_PRICE = Decimal("10")          # fixed on the SERVER; the app can never send a price
AI_PRO_CURRENCY = "USD"               # ETB and USD are supported by Chapa; no silent conversion
AI_PRO_SECONDS = 30 * 24 * 3600
APP_PRO_PRICE = Decimal("3")          # plan v3.1: US $3 per month, 600 MB combined media allowance
APP_PRO_CURRENCY = "USD"
APP_PRO_SECONDS = 30 * 24 * 3600

# product id used by the app in POST /payments/initialize?product=...  ->  what the SERVER sells
PRODUCTS = {
    "ai_pro": {"product": "ai_pro_1_month", "price": AI_PRO_PRICE, "currency": AI_PRO_CURRENCY,
               "seconds": AI_PRO_SECONDS, "title": "Sociax AI Pro", "description": "AI Pro, 1 month"},
    "app_pro": {"product": "app_pro_1_month", "price": APP_PRO_PRICE, "currency": APP_PRO_CURRENCY,
                "seconds": APP_PRO_SECONDS, "title": "Sociax App Pro", "description": "App Pro, 1 month"},
}
PRODUCT_BY_DB_NAME = {v["product"]: v for v in PRODUCTS.values()}
CHAPA_RETURN_URL = os.getenv("CHAPA_RETURN_URL")  # optional page Chapa sends the user back to (never grants Pro)

with closing(db()) as _con:
    _con.execute(
        """CREATE TABLE IF NOT EXISTS payments(
            tx_ref TEXT PRIMARY KEY, uid TEXT NOT NULL, provider TEXT NOT NULL DEFAULT 'Chapa',
            product TEXT NOT NULL, amount TEXT NOT NULL, currency TEXT NOT NULL,
            status TEXT NOT NULL,              -- initiated | pending | success | failed | rejected
            created_at INTEGER NOT NULL, paid_at INTEGER,
            pro_started_at INTEGER, pro_expires_at INTEGER,
            provider_ref TEXT, granted INTEGER NOT NULL DEFAULT 0)""")
    _con.execute("CREATE INDEX IF NOT EXISTS payments_uid ON payments(uid)")
    _con.commit()


def _pro_expires_at(uid: str, product: str) -> int:
    with closing(db()) as con:
        return _pro_expires_at_con(con, uid, product)


def _pro_expires_at_con(con, uid: str, product: str) -> int:
    r = con.execute("SELECT MAX(pro_expires_at) FROM payments WHERE uid=? AND product=? AND granted=1",
                    (uid, product)).fetchone()
    return int(r[0] or 0)


def ai_pro_expires_at(uid: str) -> int:
    return _pro_expires_at(uid, PRODUCTS["ai_pro"]["product"])


def app_pro_expires_at(uid: str) -> int:
    return _pro_expires_at(uid, PRODUCTS["app_pro"]["product"])


def is_ai_pro(uid: str) -> bool:
    return ai_pro_expires_at(uid) > int(time.time())


def is_app_pro(uid: str) -> bool:
    return app_pro_expires_at(uid) > int(time.time())


def _payment_dict(row) -> dict:
    kind = next((k for k, v in PRODUCTS.items() if v["product"] == row["product"]), None)
    return {"tx_ref": row["tx_ref"], "status": row["status"], "granted": bool(row["granted"]),
            "pro_expires_at": row["pro_expires_at"], "product": kind}


def _own_payment(con, uid: str, tx_ref: str):
    row = con.execute("SELECT * FROM payments WHERE tx_ref=? AND uid=?", (tx_ref, uid)).fetchone()
    if not row:  # unknown reference OR someone else's: the same answer, nothing leaks
        raise HTTPException(404, "Payment not found")
    return row


@app.post("/payments/initialize")
def payments_initialize(uid: str = Depends(current_user), product: str = "ai_pro"):
    """product = ai_pro ($10, AI allowance x5) or app_pro ($3, 600 MB media). The price is chosen HERE, never by the app."""
    rate_limit(uid, "pay_init", 5, 60)
    item = PRODUCTS.get(product)
    if item is None:
        raise HTTPException(400, "Unknown product")
    if not chapa_client.configured():
        raise HTTPException(503, "Payments are not connected yet")
    tx_ref = f"sx-{hashlib.sha256(uid.encode()).hexdigest()[:8]}-{secrets.token_hex(8)}"
    with closing(db()) as con:
        user = con.execute("SELECT display_name FROM users WHERE uid=?", (uid,)).fetchone()
        if not user:
            raise HTTPException(400, "Finish onboarding first")
        con.execute("INSERT INTO payments(tx_ref, uid, product, amount, currency, status, created_at) "
                    "VALUES (?,?,?,?,?,'initiated',?)",
                    (tx_ref, uid, item["product"], str(item["price"]), item["currency"], int(time.time())))
        con.commit()
        payload = {"amount": str(item["price"]), "currency": item["currency"], "tx_ref": tx_ref,
                   "email": f"user-{tx_ref[3:11]}@example.com",   # we do not store emails; Chapa wants one
                   "first_name": (user["display_name"] or "Sociax").split(" ")[0][:30] or "Sociax",
                   "last_name": "User",
                   "customization": {"title": item["title"], "description": item["description"]}}
        if CHAPA_RETURN_URL:
            payload["return_url"] = CHAPA_RETURN_URL
        try:
            res = chapa_client.initialize(payload)
            url = (res.get("data") or {}).get("checkout_url") if res.get("status") == "success" else None
        except chapa_client.ChapaError:
            url = None
        if not url:
            con.execute("UPDATE payments SET status='failed' WHERE tx_ref=?", (tx_ref,))
            con.commit()
            raise HTTPException(502, "Could not start the payment. Please try again.")
    return {"tx_ref": tx_ref, "checkout_url": url, "amount": str(item["price"]), "currency": item["currency"],
            "product": product}


@app.get("/payments/verify/{tx_ref}")
def payments_verify(tx_ref: str, uid: str = Depends(current_user)):
    """Ask Chapa directly. Grants 30 days of the product that was bought (AI Pro or App Pro) exactly once
    per successful, matching transaction."""
    rate_limit(uid, "pay_verify", 20, 60)
    with closing(db()) as con:
        row = _own_payment(con, uid, tx_ref)
        item = PRODUCT_BY_DB_NAME.get(row["product"])
        if item is None:  # a row we do not sell any more: never grant anything for it
            raise HTTPException(404, "Payment not found")
        if row["granted"]:  # already paid + granted: never add time twice
            return _payment_dict(row)
        try:
            res = chapa_client.verify(tx_ref)
        except chapa_client.ChapaError:
            raise HTTPException(502, "Could not check the payment right now. Please try again.")
        data = res.get("data") if isinstance(res.get("data"), dict) else {}
        state = str(data.get("status", "")).lower() if res.get("status") == "success" else "failed"
        if state == "success":
            try:
                amount_ok = Decimal(str(data.get("amount"))) == item["price"]
            except (InvalidOperation, ValueError):
                amount_ok = False
            ok = (amount_ok and str(data.get("currency", "")).upper() == item["currency"]
                  and data.get("tx_ref") == tx_ref and Decimal(row["amount"]) == item["price"]
                  and row["currency"] == item["currency"])
            if not ok:  # paid something else (wrong amount/currency/reference): no Pro
                con.execute("UPDATE payments SET status='rejected' WHERE tx_ref=? AND granted=0", (tx_ref,))
            else:
                now = int(time.time())
                con.execute("BEGIN IMMEDIATE")
                cur = _pro_expires_at_con(con, uid, row["product"])
                start = max(now, cur)  # still active -> extend from the current end; else from now
                got = con.execute(
                    "UPDATE payments SET status='success', granted=1, paid_at=?, pro_started_at=?, pro_expires_at=?, "
                    "provider_ref=? WHERE tx_ref=? AND uid=? AND granted=0",
                    (now, start, start + item["seconds"], str(data.get("reference") or "")[:80], tx_ref, uid)).rowcount
                if not got:
                    pass  # a parallel request already granted it: nothing more to add
        elif state in ("pending", "processing", "initiated"):
            con.execute("UPDATE payments SET status='pending' WHERE tx_ref=? AND granted=0", (tx_ref,))
        else:  # failed, cancelled, unknown ...
            con.execute("UPDATE payments SET status='failed' WHERE tx_ref=? AND granted=0", (tx_ref,))
        con.commit()
        return _payment_dict(_own_payment(con, uid, tx_ref))


def ai_pro_expires_at_con(con, uid: str) -> int:
    return _pro_expires_at_con(con, uid, PRODUCTS["ai_pro"]["product"])


@app.get("/payments/status/{tx_ref}")
def payments_status(tx_ref: str, uid: str = Depends(current_user)):
    """What the SERVER already knows (no call to Chapa)."""
    with closing(db()) as con:
        return _payment_dict(_own_payment(con, uid, tx_ref))
