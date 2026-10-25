"""Sociax backend (FastAPI).
Step 1: Firebase token verification, unique @username, onboarding stored server-side.
All AI provider keys live HERE (env vars), never in the Android app."""
import hashlib
import hmac
import logging
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
import ranker  # Step 39: For You scoring (pure code)
import rank_engine  # Step 48: optional Go ranking engine (backend-go/); Python ranks by itself when it is off or fails
import metrics  # Step 47: how long requests take / how often they fail (route patterns only)

API_VERSION = "0.58.0"
app = FastAPI(title="Sociax API", version=API_VERSION)

# Cloudinary credentials: server-side only (env vars). The Android app never sees the secret.
CLOUD_NAME = os.getenv("CLOUDINARY_CLOUD_NAME")
CLOUD_KEY = os.getenv("CLOUDINARY_API_KEY")
CLOUD_SECRET = os.getenv("CLOUDINARY_API_SECRET")
AVATAR_MAX_BYTES = 5 * 1024 * 1024
POST_MAX_BYTES = 10 * 1024 * 1024
# Technical safety cap for ONE short clip (the plan sets no per-file value; the DAILY media allowance still applies).
SHORT_MAX_BYTES = 100 * 1024 * 1024
DURATION_RETRIES = 2                   # Step 36: Cloudinary may need a moment before it reports a new video's length
DURATION_RETRY_WAIT = 1.0
# Step 53 (plan v3.9.1): there is NO duration limit for a short video, and an unreadable length never blocks publishing.
# When Cloudinary has not reported the length yet, the clip is saved with duration = NULL ("unknown", never 0) and a small
# background job asks Cloudinary again after these delays (seconds). Tests set it to () so no thread is started.
DURATION_BACKFILL_DELAYS = (15, 60, 180)

_rate: dict = {}

# Plan v3.5 section 25 / v3.9 section 37: measure API latency. Only the ROUTE PATTERN (never the real address, a username or a token)
# and the time are logged, and only for slow requests; every answer carries X-Response-Time-ms.
SLOW_REQUEST_MS = int(os.getenv("SLOW_REQUEST_MS", "1500"))
_perf_log = __import__("logging").getLogger("sociax.perf")


_metrics = metrics.Metrics()                       # Step 47
_NOT_MEASURED = ("/health", "/health/config", "/admin/metrics")      # health checks and the metrics page itself would only add noise


@app.middleware("http")
async def _timing(request, call_next):
    t0 = time.perf_counter()
    try:
        response = await call_next(request)
    except Exception:
        route = getattr(request.scope.get("route"), "path", "unmatched")
        if route not in _NOT_MEASURED:
            _metrics.record(request.method, route, 500, int((time.perf_counter() - t0) * 1000))
        raise
    ms = int((time.perf_counter() - t0) * 1000)
    response.headers["X-Response-Time-ms"] = str(ms)
    route = getattr(request.scope.get("route"), "path", "unmatched")
    if route not in _NOT_MEASURED:
        _metrics.record(request.method, route, response.status_code, ms)       # pattern only, never the real address
    if ms >= SLOW_REQUEST_MS:
        _perf_log.warning("slow request: %s %s took %d ms (status %s)", request.method, route, ms, response.status_code)
    return response


def rate_limit(uid: str, key: str, limit: int, window_s: int):
    """Tiny in-memory limiter (per process). Use Redis when you run several servers."""
    now = time.time()
    hits = [t for t in _rate.get((uid, key), []) if now - t < window_s]
    if len(hits) >= limit:
        raise HTTPException(429, "Too many requests. Please slow down.")
    hits.append(now)
    _rate[(uid, key)] = hits

# ---- Limit errors (plan section 8): the app shows "upgrade or wait" from the machine-readable `code` ----
def media_full_error(reset_in_seconds: int = 0) -> HTTPException:
    """DAILY media allowance (500 MB free / 1.5 GB Sociax Pro, plan v3.2) reached. The app offers Sociax Pro or waiting for the reset."""
    return HTTPException(413, {"code": "media_full", "reset_in_seconds": int(reset_in_seconds),
                               "message": f"Daily media limit reached. It resets in {int(reset_in_seconds) // 3600 + 1} h, "
                                          "or upgrade to Sociax Pro."})


def ai_limit_error(reset_in_seconds: int) -> HTTPException:
    """Free AI allowance used up. The app offers Sociax Pro now, or waiting for the reset."""
    return HTTPException(429, {"code": "ai_limit", "reset_in_seconds": int(reset_in_seconds),
                               "message": f"Free AI limit reached. Resets in {reset_in_seconds // 3600 + 1} h."})


# ---- Plan constants (v3.3: ONE paid plan, Sociax Pro, US $15 / month) ----
# Step 53 (plan v3.9.1): the fixed Short Video duration cap (25 s, then 60 s) is gone. Size, file type, login, the daily
# media quota and Cloudinary's own limits are checked on their own (see SHORT_MAX_BYTES above).
MIN_AGE = 15
MB = 1024 * 1024                       # ONE conversion everywhere: 1 MB = 1024 * 1024 bytes, 1 GB = 1024 MB
FREE_MEDIA_MB = 500                    # Free: 500 MB per DAY (posts, photos, videos, files combined)
PRO_MEDIA_MB = 1536                    # Sociax Pro: 1.5 GB per DAY = 1536 MB
MEDIA_RESET_SECONDS = 24 * 3600        # a user's media day starts at their first upload and lasts 24 h
AI_RESET_SECONDS = 48 * 3600           # Free AI allowance window (plan v3.2: keep only if the owner confirms; asked in the README)
AI_PRO_MULTIPLIER = 7                  # Sociax Pro = 7x the Free AI allowance
FREE_AI_REQUESTS = int(os.getenv("FREE_AI_REQUESTS", "10"))  # TEMPORARY testing value: the plan has NOT decided the free amount yet

DB_PATH = os.getenv("SOCIAX_DB", "sociax.db")
# DEV_AUTH=1 lets you test without Firebase using the token "dev-<anything>". NEVER enable in production.
DEV_AUTH = os.getenv("DEV_AUTH") == "1"

LANGUAGES = ("en", "fr", "zh", "ko", "ru")   # plan v3.5: exactly these five, English is the default
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


def _make_short_duration_nullable(con) -> bool:
    """Step 53 (plan v3.9.1): older databases created `shorts.duration` as NOT NULL, so a clip whose length is unknown could
    not be saved. SQLite cannot drop a NOT NULL, so the table is rebuilt ONCE inside one transaction (all rows, ids and the
    id counter are kept, the index is made again). A database that is already fine is not touched. Returns True if it rebuilt."""
    con.commit()
    con.execute("BEGIN IMMEDIATE")                      # a second worker waits here; it re-checks below and finds nothing to do
    try:
        row = con.execute("SELECT sql FROM sqlite_master WHERE type='table' AND name='shorts'").fetchone()
        pattern = re.compile(r"duration\s+REAL\s+NOT\s+NULL", re.I)
        if not row or not pattern.search(row["sql"]):
            con.rollback()
            return False
        ddl = pattern.sub("duration REAL", row["sql"], count=1)
        seq = con.execute("SELECT seq FROM sqlite_sequence WHERE name='shorts'").fetchone()
        con.execute("ALTER TABLE shorts RENAME TO shorts_old")
        con.execute(ddl)
        con.execute("INSERT INTO shorts SELECT * FROM shorts_old")      # same column order: the new table is the old one minus NOT NULL
        con.execute("DROP TABLE shorts_old")                            # also drops the old index
        con.execute("CREATE INDEX IF NOT EXISTS shorts_uid ON shorts(uid, id)")
        if seq:                                                         # never hand out an id that was used before
            con.execute("UPDATE sqlite_sequence SET seq=MAX(seq, ?) WHERE name='shorts'", (seq["seq"],))
        con.commit()
        return True
    except Exception:
        con.rollback()
        raise


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
        if "language" not in cols:            # Step 37 (plan v3.5): the app language saved on the account ('' = never saved)
            con.execute("ALTER TABLE users ADD COLUMN language TEXT NOT NULL DEFAULT ''")
        if "read_receipts" not in cols:       # Step 43 (plan section 9: Chat > read receipts): 1 = I show "Seen" AND may see other people's
            con.execute("ALTER TABLE users ADD COLUMN read_receipts INTEGER NOT NULL DEFAULT 1")
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
                media_public_id TEXT NOT NULL, duration REAL,                -- NULL = length unknown (plan v3.9.1)
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
                receipt_read_id INTEGER NOT NULL DEFAULT 0,   -- Step 43: newest message SHOWN to the other person as read (0 = none)
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
        ccols = {r["name"] for r in con.execute("PRAGMA table_info(conv_members)")}
        if ccols and "receipt_read_id" not in ccols:   # Step 43: the newest message this person has SHOWN as read (0 = nothing shown)
            con.execute("ALTER TABLE conv_members ADD COLUMN receipt_read_id INTEGER NOT NULL DEFAULT 0")
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
        # Step 30 (plan v3.2): DAILY media allowance. One row per user: the 24 h window and the bytes uploaded in it.
        # Deleting a post never gives bytes back (it is an upload allowance, not storage), and old content is never
        # deleted when the counter resets.
        con.execute(
            """CREATE TABLE IF NOT EXISTS media_usage(
                uid TEXT PRIMARY KEY, window_start INTEGER NOT NULL, used_bytes INTEGER NOT NULL DEFAULT 0)""")
        # Step 32 (plan v3.3 section 3): one-time tokens that prove a FRESH Google sign-in, used to reset a forgotten app lock.
        # Only a hash of the token is stored; it lives 5 minutes and works once.
        con.execute(
            """CREATE TABLE IF NOT EXISTS lock_recovery(
                token_hash TEXT PRIMARY KEY, uid TEXT NOT NULL, expires_at INTEGER NOT NULL)""")
        # Step 24: files waiting to be deleted on Cloudinary (see "Cloudinary clean-up queue" below)
        con.execute(
            """CREATE TABLE IF NOT EXISTS cloud_trash(
                public_id TEXT PRIMARY KEY,
                resource_type TEXT NOT NULL,          -- 'image' | 'video'
                queued_at INTEGER NOT NULL,
                tries INTEGER NOT NULL DEFAULT 0)"""
        )
        _make_short_duration_nullable(con)     # Step 53 (plan v3.9.1): an existing database must also accept "length unknown"
        # Step 35 (plan v3.6/v3.9): every post / short has a moderation state. 'ok' = normal; anything else ('restricted',
        # 'removed') hides it from everybody except its owner. A moderator tool can set it later; the feed already obeys it.
        for table in ("posts", "shorts"):
            if "moderation_state" not in {r["name"] for r in con.execute(f"PRAGMA table_info({table})")}:
                con.execute(f"ALTER TABLE {table} ADD COLUMN moderation_state TEXT NOT NULL DEFAULT 'ok'")
        # Step 38 (plan v3.9 sections 31, 32, 35): a SMALL, documented set of interaction events (no message text, no private chat),
        # and the viewer's own "do not show me this" choices. Events carry an idempotency key (a retry never counts twice) and a
        # SERVER time (a time sent by the phone is never trusted). Old events are deleted after EVENT_RETENTION_DAYS.
        con.executescript(
            """
            CREATE TABLE IF NOT EXISTS interaction_events(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uid TEXT NOT NULL,
                content_type TEXT NOT NULL,            -- 'post' | 'short'
                content_id INTEGER NOT NULL,
                event TEXT NOT NULL,
                value_ms INTEGER,                      -- watch time (ms) for watch / skip, visible time (ms) for impression
                source TEXT NOT NULL DEFAULT 'app',    -- 'app' (sent by the phone) | 'server' (recorded by the server itself)
                idem_key TEXT NOT NULL,
                version INTEGER NOT NULL DEFAULT 1,
                created_at INTEGER NOT NULL);
            CREATE UNIQUE INDEX IF NOT EXISTS interaction_idem ON interaction_events(uid, idem_key);
            CREATE INDEX IF NOT EXISTS interaction_recent ON interaction_events(uid, content_type, content_id, id);
            CREATE INDEX IF NOT EXISTS interaction_age ON interaction_events(created_at);
            -- kind 'item' = "Not interested" (target 'post:12' / 'short:5'); kind 'creator' = "Hide creator" (target = creator uid)
            CREATE TABLE IF NOT EXISTS rec_hidden(
                uid TEXT NOT NULL, kind TEXT NOT NULL, target TEXT NOT NULL, created_at INTEGER NOT NULL,
                PRIMARY KEY(uid, kind, target));
            -- Step 39: a ranked For You list is made ONCE and then paged from this snapshot, so refreshing / scrolling never
            -- reorders or repeats items. It holds only item numbers; privacy is checked again every time a page is served.
            CREATE TABLE IF NOT EXISTS feed_snapshots(
                token TEXT PRIMARY KEY, uid TEXT NOT NULL, kind TEXT NOT NULL,   -- kind 'post' | 'short'
                ids TEXT NOT NULL, created_at INTEGER NOT NULL);
            CREATE INDEX IF NOT EXISTS feed_snapshots_uid ON feed_snapshots(uid, created_at);
            -- Step 41 (plan v3.8 section 28): COUNTED AdMob impressions of Free accounts. One row per impression; the key makes a
            -- retry count once. `day` = the app-defined day number (see ad_day()). Nothing about the ad itself is stored.
            CREATE TABLE IF NOT EXISTS ad_impressions(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uid TEXT NOT NULL, day INTEGER NOT NULL, idem_key TEXT NOT NULL, created_at INTEGER NOT NULL);
            CREATE UNIQUE INDEX IF NOT EXISTS ad_impressions_idem ON ad_impressions(uid, idem_key);
            CREATE INDEX IF NOT EXISTS ad_impressions_day ON ad_impressions(uid, day);
            -- Step 46 (plan v3.9 section 20: Notifications -> FCM push): the phones that agreed to push, and a small outbox of
            -- pushes still to send. An outbox row holds ONLY: who, what kind, @actor, item number, count. Never message text.
            CREATE TABLE IF NOT EXISTS push_tokens(
                token TEXT PRIMARY KEY, uid TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
            CREATE INDEX IF NOT EXISTS push_tokens_uid ON push_tokens(uid);
            CREATE TABLE IF NOT EXISTS push_outbox(
                id INTEGER PRIMARY KEY AUTOINCREMENT, uid TEXT NOT NULL, type TEXT NOT NULL, actor TEXT NOT NULL DEFAULT '',
                ref INTEGER NOT NULL DEFAULT 0, count INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0, locked_until INTEGER NOT NULL DEFAULT 0);
            CREATE INDEX IF NOT EXISTS push_outbox_due ON push_outbox(locked_until, id);
            """
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
    # Step 51: tell "this server cannot check sign-ins" (503, the app waits / retries and shows the real cause) apart from "this
    # token is bad" (401, the person signs in again). Before, a missing or wrong Firebase service-account file ALSO answered 401, so
    # the phone looped between "sign-in expired" and the login screen with nothing useful in the log.
    try:
        _init_firebase()
    except Exception as e:
        logging.getLogger("sociax").error("firebase sign-in check unavailable: %s (is the secret file /etc/secrets/firebase-service-account.json set?)",
                                          e.__class__.__name__)
        raise HTTPException(503, "The server cannot check sign-ins right now")
    try:
        from firebase_admin import auth
        return auth.verify_id_token(token)["uid"]
    except Exception as e:
        if e.__class__.__name__ in ("CertificateFetchError", "TransportError", "ConnectionError", "Timeout"):
            logging.getLogger("sociax").warning("firebase could not fetch its public keys: %s", e.__class__.__name__)
            raise HTTPException(503, "The server cannot check sign-ins right now")
        raise HTTPException(401, "Invalid or expired token")


@app.get("/health")
def health():
    return {"ok": True}


@app.get("/admin/metrics")
def admin_metrics(x_metrics_token: str | None = Header(default=None)):
    """Step 47 (plan v3.9 section 37): the owner's baseline numbers. NOT for app users: it answers only to the secret in
    METRICS_TOKEN (16+ characters, sent as header X-Metrics-Token); with no secret set, or a wrong one, it answers exactly like
    a page that does not exist. Contains no user, id, query or content: route patterns, counts, milliseconds, queue sizes."""
    rate_limit("-", "metrics", 60, 60)
    expected = os.getenv("METRICS_TOKEN", "")
    if len(expected) < 16 or not x_metrics_token or not hmac.compare_digest(x_metrics_token.encode(), expected.encode()):
        raise HTTPException(404, "Not found")
    out = _metrics.snapshot()
    with closing(db()) as con:
        backlog = con.execute("SELECT COUNT(*) AS n, MIN(created_at) AS oldest FROM push_outbox").fetchone()
    out["push_backlog"] = {"waiting": backlog["n"], "oldest_s": (int(time.time()) - backlog["oldest"]) if backlog["oldest"] else 0}
    out["version"] = API_VERSION
    out["rank_engine"] = rank_engine.client.report()
    out["note"] = ("Starts again at 0 when the server restarts. Percentiles use the newest %d timings of each route. "
                   "This is a baseline, not proof of speed (plan section 37)." % metrics.WINDOW)
    return out


def config_report() -> dict:
    """Plan v3.6 sections 3 + 4: confirms that the required settings EXIST without ever showing a value."""
    creds = os.getenv("GOOGLE_APPLICATION_CREDENTIALS", "")
    firebase = bool(creds) and os.path.isfile(creds)
    cloud = bool(CLOUD_NAME and CLOUD_KEY and CLOUD_SECRET)
    ai = ai_providers.configured()
    payments = bool(os.getenv("CHAPA_SECRET_KEY"))
    missing = []
    if not firebase and not DEV_AUTH:
        missing.append("GOOGLE_APPLICATION_CREDENTIALS (Firebase service account file)")
    if not cloud:
        missing += [n for n, v in (("CLOUDINARY_CLOUD_NAME", CLOUD_NAME), ("CLOUDINARY_API_KEY", CLOUD_KEY),
                                   ("CLOUDINARY_API_SECRET", CLOUD_SECRET)) if not v]
    if not any(ai.values()):
        missing.append("XAI_API_KEY or GEMINI_API_KEY or ANTHROPIC_API_KEY (at least one AI provider)")
    return {"ok": not missing, "version": API_VERSION, "database": True, "firebase": firebase, "cloudinary": cloud,
            "ai": ai, "payments": payments, "missing": missing,
            "rank_engine": rank_engine.client.report(),      # Step 48: booleans and counters only (never the address or the secret)
            "dev_auth_enabled": DEV_AUTH}      # must be false in production


@app.get("/health/config")
def health_config():
    """Deployment check (no login needed, so you can open it in a browser after every deploy). Booleans and the NAMES of
    missing settings only: no value, key or path is ever returned."""
    return config_report()


@app.get("/auth/callback")
def auth_callback(error: str | None = None):
    """Plan v3.6 section 4: Google sign-in in the app uses Android Credential Manager + Firebase, so no backend page is needed.
    This route exists only so that a redirect address pointed at the backend never ends in an unintended 404:
    it shows a plain "return to the app" page for success and a plain page for a failure (the error text is never echoed)."""
    from starlette.responses import HTMLResponse
    if error:
        body = "<h2>Sign-in did not finish</h2><p>Please close this page, return to the Sociax app and try again.</p>"
        return HTMLResponse(f"<!doctype html><meta name=viewport content='width=device-width'><title>Sociax</title>{body}", status_code=400)
    body = "<h2>Sociax</h2><p>You can close this page and return to the Sociax app.</p>"
    return HTMLResponse(f"<!doctype html><meta name=viewport content='width=device-width'><title>Sociax</title>{body}")


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
        "read_receipts": bool(row["read_receipts"]),      # Step 43
        "notifications": notif_prefs(row),
        "language": row["language"] or None,       # Step 37: None = never saved on the account
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
    read_receipts: bool | None = None                  # Step 43: show "Seen" in one-to-one chats (and see other people's)
    notifications: dict[str, bool] | None = None      # Step 27: {"follows": false, ...} (only the groups given change)
    language: Literal["en", "fr", "zh", "ko", "ru"] | None = None   # Step 37: the app language is kept on the account too


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
        if body.language is not None:
            con.execute("UPDATE users SET language=? WHERE uid=?", (body.language, uid))
        if body.read_receipts is not None:       # Step 43: switched off -> nothing stays recorded for the other side
            con.execute("UPDATE users SET read_receipts=? WHERE uid=?", (int(body.read_receipts), uid))
            if not body.read_receipts:
                con.execute("UPDATE conv_members SET receipt_read_id=0 WHERE uid=?", (uid,))
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
    # Step 46: the SAME decision as above (category on, not blocked, not myself) also decides the push: nothing is pushed that
    # would not be written in the bell list. Only when this person has a phone registered for push.
    if con.execute("SELECT 1 FROM push_tokens WHERE uid=? LIMIT 1", (recipient,)).fetchone():
        a = con.execute("SELECT username FROM users WHERE uid=?", (actor,)).fetchone() if actor else None
        con.execute("INSERT INTO push_outbox(uid, type, actor, ref, count, created_at) VALUES (?,?,?,?,?,?)",
                    (recipient, ntype, ("@" + a["username"]) if a else "", ref, count, int(time.time())))


# ---------------- Push (Step 46): Firebase Cloud Messaging ----------------
# The phone says "send me push" (POST /push/token) and says "stop" (POST /push/token/remove). notify() above queues a row in
# push_outbox; a small background worker (push_deliver_once) sends it through FCM as a DATA message: type, @actor, item number,
# count. The phone builds the visible text itself, in the person's language; the server never sends message text or captions.
PUSH_MAX_TOKENS = 5          # phones per account (newest kept)
PUSH_MAX_ATTEMPTS = 3
PUSH_MAX_AGE = 600           # a push older than 10 minutes is dropped (a late "new message" is worse than none)


class PushToken(BaseModel):
    token: str = Field(min_length=20, max_length=4096)


def _push_token_ok(token: str) -> str:
    token = (token or "").strip()
    if not 20 <= len(token) <= 4096 or any(ord(c) < 33 or ord(c) == 127 for c in token):
        raise HTTPException(422, "Invalid push token")
    return token


@app.post("/push/token")
def push_register(body: PushToken, uid: str = Depends(current_user)):
    """This phone wants push for this account. The token moves to this account if it was registered to another one (shared phone)."""
    rate_limit(uid, "push_token", 30, 3600)
    token = _push_token_ok(body.token)
    now = int(time.time())
    with closing(db()) as con:
        if not user_row(con, uid):
            raise HTTPException(404, "Account not found")
        con.execute("INSERT INTO push_tokens(token, uid, created_at, updated_at) VALUES (?,?,?,?) "
                    "ON CONFLICT(token) DO UPDATE SET uid=excluded.uid, updated_at=excluded.updated_at", (token, uid, now, now))
        con.execute("DELETE FROM push_tokens WHERE uid=? AND token NOT IN "
                    "(SELECT token FROM push_tokens WHERE uid=? ORDER BY updated_at DESC, rowid DESC LIMIT ?)",
                    (uid, uid, PUSH_MAX_TOKENS))
        con.commit()
    return {"ok": True}


@app.post("/push/token/remove")
def push_remove(body: PushToken, uid: str = Depends(current_user)):
    """Stop push on this phone (switch off, sign out). Only the account's OWN token can be removed."""
    rate_limit(uid, "push_token", 30, 3600)
    token = _push_token_ok(body.token)
    with closing(db()) as con:
        con.execute("DELETE FROM push_tokens WHERE token=? AND uid=?", (token, uid))
        if not con.execute("SELECT 1 FROM push_tokens WHERE uid=? LIMIT 1", (uid,)).fetchone():
            con.execute("DELETE FROM push_outbox WHERE uid=?", (uid,))           # no phone left: nothing to send
        con.commit()
    return {"ok": True}


def _fcm_send(token: str, data: dict) -> str:
    """The real sender. Returns 'ok' | 'invalid' (this token is dead: remove it) | 'retry'. Needs firebase-admin credentials
    (the same GOOGLE_APPLICATION_CREDENTIALS as login). A data message with high priority, valid for one hour."""
    try:
        _init_firebase()
        from firebase_admin import messaging
        messaging.send(messaging.Message(token=token, data=data, android=messaging.AndroidConfig(priority="high", ttl=3600)))
        return "ok"
    except Exception as e:  # noqa: BLE001
        return "invalid" if type(e).__name__ in ("UnregisteredError", "SenderIdMismatchError") else "retry"


def push_deliver_once(sender, limit: int = 50, now: int | None = None) -> int:
    """Sends the queued pushes once and returns how many phones accepted one. `sender(token, data)` -> 'ok' | 'invalid' | 'retry'.
    A row is claimed first (locked for 60 s), so two workers never send the same push twice. A failed row is tried again
    later (30 s, 60 s) up to PUSH_MAX_ATTEMPTS, and dropped after PUSH_MAX_AGE. Dead tokens are deleted."""
    now = int(now if now is not None else time.time())
    with closing(db()) as con:
        con.execute("DELETE FROM push_outbox WHERE created_at < ?", (now - PUSH_MAX_AGE,))
        rows = con.execute("SELECT * FROM push_outbox WHERE locked_until<=? ORDER BY id LIMIT ?", (now, limit)).fetchall()
        claimed = []
        for r in rows:
            if con.execute("UPDATE push_outbox SET locked_until=?, attempts=attempts+1 WHERE id=? AND locked_until<=?",
                           (now + 60, r["id"], now)).rowcount:
                claimed.append(r)
        con.commit()
    sent = 0
    for r in claimed:
        with closing(db()) as con:
            tokens = [t["token"] for t in con.execute("SELECT token FROM push_tokens WHERE uid=?", (r["uid"],))]
        data = {"type": r["type"], "actor": r["actor"], "ref": str(r["ref"]), "count": str(r["count"])}
        retry, dead = False, []
        for t in tokens:
            try:
                res = sender(t, data)
            except Exception:  # noqa: BLE001
                res = "retry"
            if res == "ok":
                sent += 1
            elif res == "invalid":
                dead.append(t)
            else:
                retry = True
        with closing(db()) as con:
            for t in dead:
                con.execute("DELETE FROM push_tokens WHERE token=?", (t,))
            attempts = r["attempts"] + 1
            if retry and attempts < PUSH_MAX_ATTEMPTS:
                con.execute("UPDATE push_outbox SET locked_until=? WHERE id=?", (now + 30 * attempts, r["id"]))
            else:
                con.execute("DELETE FROM push_outbox WHERE id=?", (r["id"],))
            con.commit()
    return sent


def _push_worker():
    while True:
        try:
            push_deliver_once(_fcm_send)
        except Exception:  # noqa: BLE001
            logging.getLogger("sociax.push").exception("push delivery failed")
        time.sleep(2)


def start_push_worker():
    threading.Thread(target=_push_worker, daemon=True, name="sociax-push").start()


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
    """DAILY media allowance (plan v3.2): 500 MB free, 1.5 GB (1536 MB) while a VERIFIED Sociax Pro payment is active."""
    return PRO_MEDIA_MB if is_pro(uid) else FREE_MEDIA_MB


def used_bytes(con, uid: str) -> int:
    """Everything the user still has stored (information only: the quota is the DAILY counter below)."""
    return int(con.execute("SELECT COALESCE(SUM(bytes),0) FROM media WHERE uid=?", (uid,)).fetchone()[0])


def media_state(con, uid: str) -> dict:
    """Today's media counter, computed on the SERVER (never from numbers sent by the app).
    The day is a 24 h window that starts with the first upload; when it ends the counter starts again at 0."""
    now = int(time.time())
    limit = user_limit_mb(uid) * MB
    r = con.execute("SELECT window_start, used_bytes FROM media_usage WHERE uid=?", (uid,)).fetchone()
    if not r or now - r["window_start"] >= MEDIA_RESET_SECONDS:
        return {"used": 0, "limit": limit, "reset_at": None, "reset_in_seconds": 0, "fresh": True}
    reset_at = r["window_start"] + MEDIA_RESET_SECONDS
    return {"used": r["used_bytes"], "limit": limit, "reset_at": reset_at, "reset_in_seconds": reset_at - now, "fresh": False}


def media_check(con, uid: str, add_bytes: int) -> dict:
    """Raises the 413 "media_full" answer when today's allowance cannot take [add_bytes] more."""
    st = media_state(con, uid)
    if st["used"] + add_bytes > st["limit"]:
        raise media_full_error(st["reset_in_seconds"])
    return st


def media_take(con, uid: str, add_bytes: int):
    """Count an accepted upload against today's allowance. Call it in the SAME transaction as the INSERT INTO media
    (the caller commits). Call media_check first."""
    now = int(time.time())
    r = con.execute("SELECT window_start FROM media_usage WHERE uid=?", (uid,)).fetchone()
    if not r or now - r["window_start"] >= MEDIA_RESET_SECONDS:
        con.execute("INSERT INTO media_usage(uid, window_start, used_bytes) VALUES (?,?,?) "
                    "ON CONFLICT(uid) DO UPDATE SET window_start=excluded.window_start, used_bytes=excluded.used_bytes",
                    (uid, now, add_bytes))
    else:
        con.execute("UPDATE media_usage SET used_bytes=used_bytes+? WHERE uid=?", (add_bytes, uid))


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
        media_check(con, uid, body.size_bytes)     # today's allowance (plan v3.2); the real size is checked again after the upload
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
        media_check(con, uid, size)
        media_take(con, uid, size)
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
    language: str | None = None        # Step 37: the language chosen on the opening screen (anything else is ignored)


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
                "INSERT INTO users(uid, username, display_name, age, source, created_at, language) VALUES (?,?,?,?,?,?,?)",
                (uid, username, body.display_name.strip(), body.age, source, int(time.time()),
                 body.language if body.language in LANGUAGES else ""),
            )
            con.commit()
        except sqlite3.IntegrityError:
            raise HTTPException(409, "That @username is already taken")
    return {"username": "@" + username}


class ShortMeta(BaseModel):
    duration_seconds: float | None = None      # plan v3.9.1: optional; the phone may not be able to read it, and it is never a limit
    size_mb: float
    mime: str


@app.post("/media/validate-short")
def validate_short(meta: ShortMeta, uid: str = Depends(current_user)):
    # plan v3.9.1: NO duration check here. Type, size and the daily quota are still checked, each on its own.
    if not meta.mime.startswith("video/"):
        raise HTTPException(415, "Unsupported file type")
    if meta.size_mb * 1024 * 1024 > SHORT_MAX_BYTES:
        raise HTTPException(413, f"Video must be under {SHORT_MAX_BYTES // (1024 * 1024)} MB")
    with closing(db()) as con:
        media_check(con, uid, int(meta.size_mb * MB))
    return {"ok": True}  # advisory only: the real check happens again in POST /shorts on the uploaded file


@app.get("/quota/media")
def media_quota(uid: str = Depends(current_user)):
    """Today's media allowance (plan v3.2): used / remaining / exact reset time, plus what is stored. All counted here."""
    with closing(db()) as con:
        st = media_state(con, uid)
        stored = used_bytes(con, uid)
    return {"period": "day", "limit_mb": st["limit"] // MB, "free_limit_mb": FREE_MEDIA_MB, "pro_limit_mb": PRO_MEDIA_MB,
            "used_mb": round(st["used"] / MB, 2), "remaining_mb": round(max(st["limit"] - st["used"], 0) / MB, 2),
            "reset_at": st["reset_at"], "reset_in_seconds": st["reset_in_seconds"],
            "stored_mb": round(stored / MB, 2),
            "pro": is_pro(uid), "pro_expires_at": pro_expires_at(uid) or None}


def ai_limit(uid: str) -> int:
    return FREE_AI_REQUESTS * (AI_PRO_MULTIPLIER if is_pro(uid) else 1)  # Pro (7x) only after a VERIFIED Chapa payment


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
            "pro": is_pro(uid), "pro_expires_at": pro_expires_at(uid) or None}


@app.get("/quota")
def quota_all(uid: str = Depends(current_user)):
    """Plan v3.9 section 38: one answer with today's media and AI allowance. Counted on the server; nothing here is sent by the app."""
    return {"media": media_quota(uid), "ai": ai_quota(uid)}


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
        # Plan v3.6 section 3: a SAFE, structured answer. Nothing about keys or settings is revealed to the user.
        raise HTTPException(503, {"code": "ai_not_configured", "retryable": False,
                                  "message": "Sociax AI is not available yet. Please try again later."})
    with closing(db()) as con:
        ai_take(con, uid)
        try:
            answer, provider = ai_providers.ask(body.prompt)
        except ai_providers.AiUnavailable as e:
            ai_refund(con, uid)           # a failed answer never costs the user part of the free allowance
            why = set(e.reasons.values())
            busy = bool(why) and why <= {"http_429", "timeout"}
            raise HTTPException(502, {"code": "ai_unavailable", "retryable": True,
                                      "message": ("Sociax AI is busy right now. Please try again in a minute." if busy
                                                  else "Sociax AI could not answer right now. Please try again.")})
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
# Plan v3.7: Public = every signed-in account (follower or not) can see it, unless the account is private / blocked / the item is
# restricted by moderation. Followers-only = accepted followers only. The owner always sees their own items.
VISIBLE = (f"(p.uid=:me OR (p.moderation_state='ok' AND {NOT_BLOCKED} "
           f"AND ((p.audience='public' AND u.is_private=0) OR {FOLLOW_ACCEPTED})))")

# Step 38 (plan v3.9 section 31.1): content the viewer chose to hide ("Not interested" / "Hide creator") never enters
# THEIR For You / Following feed. It is an extra filter on top of VISIBLE, never a replacement for it.
def _not_hidden(kind: str) -> str:
    return ("NOT EXISTS(SELECT 1 FROM rec_hidden h WHERE h.uid=:me AND "
            f"((h.kind='creator' AND h.target=p.uid) OR (h.kind='item' AND h.target='{kind}:'||p.id)))")


NOT_HIDDEN_POST = _not_hidden("post")
NOT_HIDDEN_SHORT = _not_hidden("short")

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
        "type": "photo", "visibility": r["audience"], "moderation_state": r["moderation_state"],
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
    # Step 35 (plan v3.6 section 1): a retry of the SAME upload never creates a second post and never counts the quota twice.
    # If the first answer got lost on a weak network, the app asks again and simply receives the post that already exists.
    with closing(db()) as con:
        again = con.execute(f"{POST_SELECT} WHERE p.media_public_id=:pid AND p.uid=:me", {"me": uid, "pid": body.public_id}).fetchone()
    if again:
        return post_dict(again, uid)
    size = asset_bytes(body.public_id)
    if size <= 0 or size > POST_MAX_BYTES:
        with closing(db()) as con:      # a refused photo is never kept on Cloudinary
            trash_media(con, body.public_id, "post"); con.commit()
        kick_cloud_cleanup()
        raise HTTPException(413, "Photo must be under 10 MB")
    with closing(db()) as con:
        if not con.execute("SELECT 1 FROM users WHERE uid=?", (uid,)).fetchone():
            raise HTTPException(404, "Finish onboarding first")
        if con.execute("SELECT 1 FROM media WHERE public_id=?", (body.public_id,)).fetchone():
            raise HTTPException(409, "Already posted")
        media_check(con, uid, size)
        media_take(con, uid, size)
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
        forget_content(con, "post", post_id)
        con.execute("DELETE FROM media WHERE public_id=?", (row["media_public_id"],))  # frees quota
        trash_media(con, row["media_public_id"], "post")
        con.commit()
    kick_cloud_cleanup()
    return {"ok": True}


@app.get("/feed")
def feed(tab: Literal["for_you", "following"] = "for_you", before: int | None = None,
         limit: int = 20, uid: str = Depends(current_user)):
    limit = max(1, min(limit, 50))
    where = [VISIBLE, NOT_HIDDEN_POST]
    if tab == "following":
        where.append(f"(p.uid=:me OR {FOLLOW_ACCEPTED})")
    if before:
        where.append("p.id < :before")
    sql = f"{POST_SELECT} WHERE {' AND '.join(where)} ORDER BY p.id DESC LIMIT :limit"
    with closing(db()) as con:
        rows = con.execute(sql, {"me": uid, "before": before, "limit": limit}).fetchall()
    posts = [post_dict(r, uid) for r in rows]
    return {"posts": posts, "next_before": posts[-1]["id"] if len(posts) == limit else None}


# Step 38 (plan v3.9 section 38): the two feeds also have their own named addresses. Same code, same rules.
@app.get("/feed/following")
def feed_following(before: int | None = None, limit: int = 20, uid: str = Depends(current_user)):
    return feed("following", before, limit, uid)


def _toggle(table: str, post_id: int, uid: str, on: bool):
    with closing(db()) as con:
        row = visible_post(con, post_id, uid)
        if on:
            cur = con.execute(f"INSERT OR IGNORE INTO {table}(post_id, uid) VALUES (?,?)", (post_id, uid))
            if cur.rowcount and table in ("likes", "bookmarks"):
                record_server_event(con, uid, "post", post_id, "like" if table == "likes" else "save", row["uid"])
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
        row = visible_post(con, post_id, uid)
        con.execute("INSERT INTO comments(post_id, uid, text, created_at) VALUES (?,?,?,?)",
                    (post_id, uid, body.text.strip(), int(time.time())))
        record_server_event(con, uid, "post", post_id, "comment", row["uid"])
        con.commit()
    _notify_owner("posts", post_id, uid, "post_comment")
    return {"ok": True}


class Report(BaseModel):
    reason: str = Field(min_length=1, max_length=500)


@app.post("/posts/{post_id}/report")
def report_post(post_id: int, body: Report, uid: str = Depends(current_user)):
    rate_limit(uid, "report", 20, 3600)
    with closing(db()) as con:
        row = visible_post(con, post_id, uid)
        con.execute("INSERT INTO reports(reporter, target_type, target_id, reason, created_at) VALUES (?,?,?,?,?)",
                    (uid, "post", str(post_id), body.reason.strip(), int(time.time())))
        record_server_event(con, uid, "post", post_id, "report", row["uid"])
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
    con.execute("UPDATE conv_members SET last_read_id=MIN(last_read_id, ?), receipt_read_id=MIN(receipt_read_id, ?) "
                "WHERE conv_id=? AND uid=?", (first - 1, first - 1, conv_id, recipient))   # Step 43: released = not "Seen" either


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
SEARCH_PAGE = 20          # Step 45 (plan sections 19, 33): results per list per page
SEARCH_MAX_OFFSET = 200   # nobody pages deeper than this: it bounds the cost of one request
SEARCH_MAX_CHARS = 50


def _like_escape(text: str) -> str:
    """Make `text` safe inside a LIKE pattern (used with ESCAPE '\\'): % and _ are literal characters, never wildcards.
    (Before Step 45 they were deleted, so searching the username "a_b" really searched "ab".)"""
    return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")


def _next_offset(rows: list, offset: int):
    """rows were fetched with LIMIT page+1: more exists when there is an extra row (and we are not past the depth limit)."""
    nxt = offset + SEARCH_PAGE
    return nxt if len(rows) > SEARCH_PAGE and nxt < SEARCH_MAX_OFFSET else None


@app.get("/search")
def search(q: str, uid: str = Depends(current_user), type: str | None = None, offset: int = 0):
    """People by @username/display name (unless blocked) + PUBLIC posts + PUBLIC short videos (caption match).
    Private accounts, followers-only content and blocked people never appear. Groups are never searchable (plan section 6).
    Step 45 (plan sections 19, 33, 40): input is validated (50 characters at most, no control characters), every list is paged
    (`type` = users | posts | shorts and `offset` from the `next` value of the previous answer; nothing deeper than 200),
    People are ranked exact @username, then username starts with, then name starts with, then contains; posts and videos
    put captions where a WORD starts with the search text before captions that only contain it inside a word, newest first."""
    rate_limit(uid, "search", 60, 60)
    if type not in (None, "users", "posts", "shorts"):
        raise HTTPException(422, "Unknown search list")
    raw = (q or "").strip()
    if len(raw) > SEARCH_MAX_CHARS:
        raise HTTPException(422, f"Search text is too long ({SEARCH_MAX_CHARS} characters at most)")
    if any(ord(ch) < 32 or ord(ch) == 127 for ch in raw):
        raise HTTPException(422, "Search text has characters that are not allowed")
    offset = max(0, min(int(offset), SEARCH_MAX_OFFSET))
    q = raw.lstrip("@").lower()
    empty = {"users": [], "posts": [], "shorts": [], "next": {"users": None, "posts": None, "shorts": None}}
    if len(q) < 2:
        return empty
    esc = _like_escape(q)
    args = {"q": "%" + esc + "%", "pre": esc + "%", "word": "% " + esc + "%", "exact": q,
            "lim": SEARCH_PAGE + 1, "off": offset}
    out = dict(empty, next=dict(empty["next"]))
    with closing(db()) as con:
        if type in (None, "users"):
            urows = con.execute(
                """SELECT u.uid, u.username, u.display_name, u.avatar_public_id, u.is_private,
                     (SELECT f.status FROM follows f WHERE f.follower=:me AND f.followee=u.uid) AS follow_status
                   FROM users u WHERE (u.username LIKE :q ESCAPE '\\' OR LOWER(u.display_name) LIKE :q ESCAPE '\\') AND u.uid!=:me
                     AND NOT EXISTS(SELECT 1 FROM blocks b WHERE (b.blocker=:me AND b.blocked=u.uid)
                       OR (b.blocker=u.uid AND b.blocked=:me))
                   ORDER BY CASE WHEN u.username=:exact THEN 0
                                 WHEN u.username LIKE :pre ESCAPE '\\' THEN 1
                                 WHEN LOWER(u.display_name) LIKE :pre ESCAPE '\\' OR LOWER(u.display_name) LIKE :word ESCAPE '\\' THEN 2
                                 ELSE 3 END, u.username
                   LIMIT :lim OFFSET :off""", dict(args, me=uid)).fetchall()
            out["next"]["users"] = _next_offset(urows, offset)
            out["users"] = [{"username": "@" + r["username"], "display_name": r["display_name"],
                             "avatar_url": avatar_url(r["avatar_public_id"]), "is_private": bool(r["is_private"]),
                             "follow_status": r["follow_status"] or "none"} for r in urows[:SEARCH_PAGE]]
        caption_rank = ("ORDER BY CASE WHEN LOWER(p.caption) LIKE :pre ESCAPE '\\' OR LOWER(p.caption) LIKE :word ESCAPE '\\' "
                        "THEN 0 ELSE 1 END, p.id DESC LIMIT :lim OFFSET :off")
        if type in (None, "posts"):
            prows = con.execute(
                f"""{POST_SELECT} WHERE p.audience='public' AND u.is_private=0 AND {NOT_BLOCKED}
                    AND LOWER(p.caption) LIKE :q ESCAPE '\\' {caption_rank}""", dict(args, me=uid)).fetchall()
            out["next"]["posts"] = _next_offset(prows, offset)
            out["posts"] = [post_dict(r, uid) for r in prows[:SEARCH_PAGE]]
        if type in (None, "shorts"):
            srows = con.execute(
                f"""{SHORT_SELECT} WHERE p.audience='public' AND u.is_private=0 AND {NOT_BLOCKED}
                    AND LOWER(p.caption) LIKE :q ESCAPE '\\' {caption_rank}""", dict(args, me=uid)).fetchall()
            out["next"]["shorts"] = _next_offset(srows, offset)
            out["shorts"] = [short_dict(r, uid) for r in srows[:SEARCH_PAGE]]
    return out


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
        "type": "video", "visibility": r["audience"], "moderation_state": r["moderation_state"],
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
    chosen_seconds: int | None = None   # OLD apps still send 20 / 25 / 60; since plan v3.9.1 the value is accepted and ignored


@app.post("/shorts")
def create_short(body: NewShort, uid: str = Depends(current_user)):
    rate_limit(uid, "short", 10, 3600)
    if not body.public_id.startswith(f"users/{uid}/short_") or ".." in body.public_id:
        raise HTTPException(403, "Not your upload")
    with closing(db()) as con:      # Step 35: the same retry rule as photos (never a duplicate clip, never counted twice)
        again = con.execute(f"{SHORT_SELECT} WHERE p.media_public_id=:pid AND p.uid=:me", {"me": uid, "pid": body.public_id}).fetchone()
    if again:
        return short_dict(again, uid)
    info = video_info(body.public_id)
    for _ in range(DURATION_RETRIES):
        if float(info.get("duration") or 0) > 0:
            break
        time.sleep(DURATION_RETRY_WAIT)
        info = video_info(body.public_id)
    size = int(info.get("bytes", 0))
    # plan v3.9.1: the length is OPTIONAL information. Unknown is stored as NULL, never as 0 and never as a guess,
    # and it is never a reason to refuse a clip. Size, type, login and the daily quota below are still checked.
    try:
        seconds = float(info.get("duration") or 0)
    except (TypeError, ValueError):
        seconds = 0.0
    duration = seconds if seconds > 0 and seconds != float("inf") else None
    fmt = str(info.get("format", "")).lower()
    reject = None
    if size <= 0 or size > SHORT_MAX_BYTES:
        reject = (413, f"Video must be under {SHORT_MAX_BYTES // (1024 * 1024)} MB")
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
        try:
            media_check(con, uid, size)
        except HTTPException:
            destroy_video(body.public_id)
            raise
        media_take(con, uid, size)
        con.execute("INSERT INTO media(public_id, uid, kind, bytes, created_at) VALUES (?,?,?,?,?)",
                    (body.public_id, uid, "short", size, int(time.time())))
        cur = con.execute(
            "INSERT INTO shorts(uid, caption, media_public_id, duration, audience, comments_enabled, created_at) "
            "VALUES (?,?,?,?,?,?,?)",
            (uid, body.caption.strip(), body.public_id, duration, body.audience, int(body.comments_enabled), int(time.time())))
        con.commit()
        row = con.execute(f"{SHORT_SELECT} WHERE p.id=:id", {"me": uid, "id": cur.lastrowid}).fetchone()
    if duration is None:
        _start_duration_backfill(cur.lastrowid, body.public_id)
    return short_dict(row, uid)


def _backfill_duration_once(short_id: int, public_id: str) -> bool:
    """Asks Cloudinary for the length of a published clip whose length was unknown. True when it is known now (or the clip
    is gone). Any failure just means 'try later': it never touches the post itself."""
    try:
        seconds = float(video_info(public_id).get("duration") or 0)
    except Exception:
        return False
    if seconds <= 0 or seconds == float("inf"):
        return False
    with closing(db()) as con:
        con.execute("UPDATE shorts SET duration=? WHERE id=? AND duration IS NULL", (seconds, short_id))
        con.commit()
    return True


def _start_duration_backfill(short_id: int, public_id: str):
    """Plan v3.9.1: if the length was not known at publish time, fill it in later from trusted Cloudinary metadata.
    Runs in a small background thread; failure leaves the length unknown and the post fully valid."""
    delays = tuple(DURATION_BACKFILL_DELAYS)
    if not delays:
        return

    def run():
        for wait in delays:
            time.sleep(wait)
            if _backfill_duration_once(short_id, public_id):
                return

    threading.Thread(target=run, daemon=True).start()


@app.get("/shorts/feed")
def shorts_feed(before: int | None = None, limit: int = 10, uid: str = Depends(current_user)):
    limit = max(1, min(limit, 30))
    where = [VISIBLE, NOT_HIDDEN_SHORT]
    if before:
        where.append("p.id < :before")
    sql = f"{SHORT_SELECT} WHERE {' AND '.join(where)} ORDER BY p.id DESC LIMIT :limit"
    with closing(db()) as con:
        rows = con.execute(sql, {"me": uid, "before": before, "limit": limit}).fetchall()
    return {"shorts": [short_dict(r, uid) for r in rows]}


@app.get("/shorts/for-you")
def shorts_for_you(cursor: str | None = None, limit: int = 10, uid: str = Depends(current_user)):
    """Ranked For You (short videos), same rules as /feed/for-you. Must stay ABOVE /shorts/{short_id}."""
    return _ranked_feed("short", cursor, limit, uid)


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


@app.get("/shorts/{short_id}")
def get_short(short_id: int, uid: str = Depends(current_user)):
    """Step 35: open ONE short video. Same VISIBLE rule as the feed; hidden and missing give the same 404."""
    with closing(db()) as con:
        return short_dict(visible_short(con, short_id, uid), uid)


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
        forget_content(con, "short", short_id)
        con.execute("DELETE FROM media WHERE public_id=?", (row["media_public_id"],))   # frees quota
        trash_media(con, row["media_public_id"], "short")
        con.commit()
    kick_cloud_cleanup()
    return {"ok": True}


@app.post("/shorts/{short_id}/like")
def like_short(short_id: int, uid: str = Depends(current_user)):
    with closing(db()) as con:
        row = visible_short(con, short_id, uid)
        cur = con.execute("INSERT OR IGNORE INTO short_likes(short_id, uid) VALUES (?,?)", (short_id, uid))
        if cur.rowcount:
            record_server_event(con, uid, "short", short_id, "like", row["uid"])
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
        row = visible_short(con, short_id, uid)
        cur = con.execute("INSERT OR IGNORE INTO short_bookmarks(short_id, uid) VALUES (?,?)", (short_id, uid))
        if cur.rowcount:
            record_server_event(con, uid, "short", short_id, "save", row["uid"])
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
        record_server_event(con, uid, "short", short_id, "comment", row["uid"])
        con.commit()
    _notify_owner("shorts", short_id, uid, "short_comment")
    return {"ok": True}


@app.post("/shorts/{short_id}/report")
def report_short(short_id: int, body: Report, uid: str = Depends(current_user)):
    rate_limit(uid, "report", 20, 3600)
    with closing(db()) as con:
        row = visible_short(con, short_id, uid)
        con.execute("INSERT INTO reports(reporter, target_type, target_id, reason, created_at) VALUES (?,?,?,?,?)",
                    (uid, "short", str(short_id), body.reason.strip(), int(time.time())))
        record_server_event(con, uid, "short", short_id, "report", row["uid"])
        con.commit()
    return {"ok": True}


# =====================================================================
# Step 38 (plan v3.9 sections 31, 32, 35, 38): discovery engine FOUNDATION, "Phase B".
# - POST /events/interaction : the phone reports what it SAW (impression / watch / complete / skip / share).
#   Like, save, comment and report are NEVER accepted from the phone: the server records them itself when the real
#   action happens (see record_server_event), so a phone cannot fake them.
# - POST /feedback/recommendation : "Not interested", "Hide creator", "Reset recommendation history".
# - Hidden items never enter that person's feeds (NOT_HIDDEN_*), on top of VISIBLE (privacy always comes first).
# Nothing here ranks yet (that is the next step); it only collects clean, minimal, deletable signals.
# =====================================================================
EVENT_SCHEMA_VERSION = 1
EVENT_RETENTION_DAYS = int(os.getenv("EVENT_RETENTION_DAYS", "90"))   # old events are deleted (plan v3.9 section 35: minimise retention)
EVENTS_PER_MINUTE = 240                 # abuse limit per account
CLIENT_EVENTS = ("impression", "watch", "complete", "skip", "share")   # what a phone may report
SERVER_EVENTS = ("like", "save", "comment", "report")                  # only the server records these
VIDEO_ONLY_EVENTS = ("watch", "complete")
IMPRESSION_MIN_VISIBLE_MS = 1000        # an item counts as SEEN only after 1 s really on screen (pre-loaded items do not count)
WATCH_SLACK_MS = 2000                   # clock / buffering slack
WATCH_MAX_LOOPS = 10                    # one event cannot claim more than 10 plays of a short clip
MAX_VALUE_MS = 3_600_000
_IDEM_RE = re.compile(r"^[A-Za-z0-9_.:-]{8,64}$")
_last_event_purge = 0.0


class InteractionEvent(BaseModel):
    content_type: Literal["post", "short"]
    content_id: int = Field(ge=1)
    event: Literal["impression", "watch", "complete", "skip", "share"]
    idem_key: str = Field(min_length=8, max_length=64, pattern=r"^[A-Za-z0-9_.:-]+$")   # the phone makes a new one per event and reuses it on retry
    value_ms: int | None = Field(default=None, ge=0, le=MAX_VALUE_MS)
    version: int = Field(default=EVENT_SCHEMA_VERSION, ge=1, le=100)


def _check_event(body) -> None:
    """The same rules again in code (not only in the model), so every entrance is checked."""
    if body.event not in CLIENT_EVENTS:
        raise HTTPException(422, "Unknown event")
    if body.content_type not in ("post", "short"):
        raise HTTPException(422, "Unknown content type")
    if not isinstance(body.content_id, int) or body.content_id < 1:
        raise HTTPException(422, "Bad content id")
    if not isinstance(body.idem_key, str) or not _IDEM_RE.match(body.idem_key):
        raise HTTPException(422, "Bad idempotency key")
    if body.value_ms is not None and (not isinstance(body.value_ms, int) or body.value_ms < 0 or body.value_ms > MAX_VALUE_MS):
        raise HTTPException(422, "Bad value")
    if body.event in VIDEO_ONLY_EVENTS and body.content_type != "short":
        raise HTTPException(422, "This event is only for short videos")


def record_server_event(con, uid: str, ctype: str, cid: int, event: str, owner: str | None = None) -> None:
    """Called inside the real like / save / comment / report action. The first time per person and item only (a repeat has the
    same key and is ignored). Never raises: a problem here must never break the action itself."""
    if owner is not None and owner == uid:
        return    # my own content is not a signal about my taste
    try:
        con.execute(
            "INSERT OR IGNORE INTO interaction_events(uid, content_type, content_id, event, value_ms, source, idem_key, version, created_at) "
            "VALUES (?,?,?,?,NULL,'server',?,?,?)",
            (uid, ctype, cid, event, f"srv:{event}:{ctype}:{cid}", EVENT_SCHEMA_VERSION, int(time.time())))
    except sqlite3.Error:
        pass


def purge_old_events(con, force: bool = False) -> None:
    """At most once an hour: delete events older than EVENT_RETENTION_DAYS."""
    global _last_event_purge
    now = time.time()
    if not force and now - _last_event_purge < 3600:
        return
    _last_event_purge = now
    con.execute("DELETE FROM interaction_events WHERE created_at < ?", (int(now) - EVENT_RETENTION_DAYS * 86400,))


def forget_content(con, ctype: str, cid: int) -> None:
    """A deleted post / short: its events and every "not interested" mark on it go too."""
    con.execute("DELETE FROM interaction_events WHERE content_type=? AND content_id=?", (ctype, cid))
    con.execute("DELETE FROM rec_hidden WHERE kind='item' AND target=?", (f"{ctype}:{cid}",))


@app.post("/events/interaction")
def record_interaction(body: InteractionEvent, uid: str = Depends(current_user)):
    rate_limit(uid, "events", EVENTS_PER_MINUTE, 60)
    _check_event(body)
    with closing(db()) as con:
        # Only items this person is ALLOWED to see can produce an event; hidden and missing answer the same 404.
        row = visible_short(con, body.content_id, uid) if body.content_type == "short" else visible_post(con, body.content_id, uid)
        if row["uid"] == uid:
            return {"ok": True, "recorded": False, "reason": "own_content"}
        v = body.value_ms
        if body.event == "impression" and (v is None or v < IMPRESSION_MIN_VISIBLE_MS):
            return {"ok": True, "recorded": False, "reason": "below_threshold"}
        if body.event == "watch" and not v:
            return {"ok": True, "recorded": False, "reason": "empty_watch"}
        if body.content_type == "short" and v is not None and body.event in ("watch", "complete", "skip"):
            if row["duration"] and v > row["duration"] * 1000 * WATCH_MAX_LOOPS + WATCH_SLACK_MS:   # unknown length (NULL): nothing to compare with
                return {"ok": True, "recorded": False, "reason": "impossible"}    # ignored, not an error: the phone must not retry it
        purge_old_events(con)
        cur = con.execute(
            "INSERT OR IGNORE INTO interaction_events(uid, content_type, content_id, event, value_ms, source, idem_key, version, created_at) "
            "VALUES (?,?,?,?,?,'app',?,?,?)",
            (uid, body.content_type, body.content_id, body.event, v, body.idem_key, EVENT_SCHEMA_VERSION, int(time.time())))
        con.commit()
    if not cur.rowcount:
        return {"ok": True, "recorded": False, "duplicate": True}      # a retry of the same event: counted once
    return {"ok": True, "recorded": True}


class RecFeedback(BaseModel):
    action: Literal["not_interested", "hide_creator", "reset"]
    content_type: Literal["post", "short"] | None = None
    content_id: int | None = Field(default=None, ge=1)


@app.post("/feedback/recommendation")
def recommendation_feedback(body: RecFeedback, uid: str = Depends(current_user)):
    """Plan v3.9 section 31.2 controls. 'reset' wipes what the engine learned about ME (events), my "Not interested" marks and
    my hidden creators, so it is also the way to undo a hide. 'Refresh feed' needs no endpoint: it is simply a new GET."""
    if body.action not in ("not_interested", "hide_creator", "reset"):
        raise HTTPException(422, "Unknown action")
    rate_limit(uid, "rec_feedback", 60, 3600)
    now = int(time.time())
    with closing(db()) as con:
        if body.action == "reset":
            con.execute("DELETE FROM interaction_events WHERE uid=?", (uid,))
            con.execute("DELETE FROM rec_hidden WHERE uid=?", (uid,))
            con.execute("DELETE FROM feed_snapshots WHERE uid=?", (uid,))
            con.commit()
            return {"ok": True, "action": "reset"}
        if body.content_type not in ("post", "short") or not body.content_id:
            raise HTTPException(422, "content_type and content_id are required")
        row = visible_short(con, body.content_id, uid) if body.content_type == "short" else visible_post(con, body.content_id, uid)
        if row["uid"] == uid:
            raise HTTPException(422, "Your own content cannot be hidden from your feed")
        if body.action == "not_interested":
            con.execute("INSERT OR IGNORE INTO rec_hidden(uid, kind, target, created_at) VALUES (?, 'item', ?, ?)",
                        (uid, f"{body.content_type}:{body.content_id}", now))
        else:
            con.execute("INSERT OR IGNORE INTO rec_hidden(uid, kind, target, created_at) VALUES (?, 'creator', ?, ?)",
                        (uid, row["uid"], now))
        con.commit()
    return {"ok": True, "action": body.action}


@app.get("/feedback/recommendation")
def recommendation_state(uid: str = Depends(current_user)):
    """For Settings: how much personalization the engine holds about me (counts only), so I can see it and reset it."""
    with closing(db()) as con:
        events = con.execute("SELECT COUNT(*) FROM interaction_events WHERE uid=?", (uid,)).fetchone()[0]
        items = con.execute("SELECT COUNT(*) FROM rec_hidden WHERE uid=? AND kind='item'", (uid,)).fetchone()[0]
        creators = con.execute("SELECT COUNT(*) FROM rec_hidden WHERE uid=? AND kind='creator'", (uid,)).fetchone()[0]
    return {"events": events, "not_interested": items, "hidden_creators": creators, "retention_days": EVENT_RETENTION_DAYS,
            "schema_version": EVENT_SCHEMA_VERSION}


# =====================================================================
# Step 39 (plan v3.9 sections 31, 38, 41; roadmap Phase C): the first For You ranker.
# Order of work for every request (privacy ALWAYS first):
#   1. SQL picks the newest RANK_POOL items this person may see (VISIBLE + not hidden + not reported by me).
#   2. ranker.py scores and orders them (weights from RANK_WEIGHTS, default = the plan's 30/20/15/15/10/10).
#   3. The order is stored as a snapshot; pages are read from it (no repeats, no reshuffling when you scroll or when new posts arrive).
#   4. Every page re-checks VISIBLE + hidden when it is served: something blocked / hidden / deleted after the snapshot is dropped.
#   5. If ranking fails for any reason the answer is the plain newest-first feed (`ranked: false`), never an error and never extra content.
# GET /feed (newest first) and GET /feed/following are unchanged. Nothing here reads message text or private chats.
# =====================================================================
RANK_WEIGHTS = ranker.parse_weights(os.getenv("RANK_WEIGHTS"))
RANK_POOL = 300                       # only the newest 300 eligible items are ranked (a measured limit can raise it later)
SNAPSHOT_TTL_S = 30 * 60              # a ranked list is paged for 30 minutes; "Refresh" simply asks again without a cursor
SNAPSHOT_MAX_ITEMS = 150
SNAPSHOTS_PER_USER = 5
INTEREST_WINDOW_S = 30 * 86400        # the viewer's own recent actions (the last 30 days) shape "interest"
_rank_log = __import__("logging").getLogger("sociax.rank")

_RANK_KINDS = {
    "post": dict(table="posts", select=POST_SELECT, hidden=NOT_HIDDEN_POST, likes=("likes", "post_id"),
                 saves=("bookmarks", "post_id"), comments=("comments", "post_id"), as_dict=post_dict,
                 key="posts", default_limit=20, max_limit=50),
    "short": dict(table="shorts", select=SHORT_SELECT, hidden=NOT_HIDDEN_SHORT, likes=("short_likes", "short_id"),
                  saves=("short_bookmarks", "short_id"), comments=("short_comments", "short_id"), as_dict=short_dict,
                  key="shorts", default_limit=10, max_limit=30),
}


def _in_list(ids):
    return ",".join(f":i{k}" for k in range(len(ids))), {f"i{k}": v for k, v in enumerate(ids)}


def rank_candidates(con, uid: str, ctype: str) -> list:
    """Returns item ids, best first (at most SNAPSHOT_MAX_ITEMS). Only items the viewer is allowed to see are ever scored."""
    cfg = _RANK_KINDS[ctype]
    table, now = cfg["table"], int(time.time())
    dur = ", p.duration AS duration" if ctype == "short" else ""
    reported = "NOT EXISTS(SELECT 1 FROM reports r WHERE r.reporter=:me AND r.target_type=:ct AND r.target_id=CAST(p.id AS TEXT))"
    rows = con.execute(
        f"SELECT p.id, p.uid, p.created_at{dur} FROM {table} p JOIN users u ON u.uid=p.uid "
        f"WHERE {VISIBLE} AND {cfg['hidden']} AND {reported} ORDER BY p.id DESC LIMIT :n",
        {"me": uid, "ct": ctype, "n": RANK_POOL}).fetchall()
    if not rows:
        return []
    ids = [r["id"] for r in rows]
    ph, idp = _in_list(ids)
    base = {"ct": ctype, **idp}

    def counts(sql, extra=None):
        return {r["cid"]: r["n"] for r in con.execute(sql, {**base, **(extra or {})}).fetchall()}

    # engagement: each person counted ONCE per item, never the creator themselves (anti-spam: repeated taps change nothing)
    like_t, like_c = cfg["likes"]; save_t, save_c = cfg["saves"]; com_t, com_c = cfg["comments"]
    likes = counts(f"SELECT x.{like_c} AS cid, COUNT(DISTINCT x.uid) AS n FROM {like_t} x JOIN {table} p ON p.id=x.{like_c} "
                   f"WHERE x.{like_c} IN ({ph}) AND x.uid != p.uid GROUP BY x.{like_c}")
    saves = counts(f"SELECT x.{save_c} AS cid, COUNT(DISTINCT x.uid) AS n FROM {save_t} x JOIN {table} p ON p.id=x.{save_c} "
                   f"WHERE x.{save_c} IN ({ph}) AND x.uid != p.uid GROUP BY x.{save_c}")
    comments = counts(f"SELECT x.{com_c} AS cid, COUNT(DISTINCT x.uid) AS n FROM {com_t} x JOIN {table} p ON p.id=x.{com_c} "
                      f"WHERE x.{com_c} IN ({ph}) AND x.uid != p.uid GROUP BY x.{com_c}")
    shares = counts(f"SELECT e.content_id AS cid, COUNT(DISTINCT e.uid) AS n FROM interaction_events e JOIN {table} p ON p.id=e.content_id "
                    f"WHERE e.content_type=:ct AND e.event='share' AND e.content_id IN ({ph}) AND e.uid != p.uid GROUP BY e.content_id")

    # watch quality (short videos only): per viewer the BEST share of the clip watched, averaged over different viewers
    watch_avg, watch_n = {}, {}
    if ctype == "short":
        best = {}
        for r in con.execute(
                f"SELECT e.uid AS u, e.content_id AS cid, e.event AS ev, e.value_ms AS v, p.duration AS d FROM interaction_events e "
                f"JOIN shorts p ON p.id=e.content_id WHERE e.content_type='short' AND e.event IN ('watch','complete','skip') "
                f"AND e.content_id IN ({ph}) AND e.uid != p.uid", base).fetchall():
            ratio = ranker.watch_ratio(r["ev"], r["v"], r["d"])
            if ratio is not None:
                key = (r["cid"], r["u"])
                best[key] = max(best.get(key, 0.0), ratio)
        per = {}
        for (cid, _u), ratio in best.items():
            per.setdefault(cid, []).append(ratio)
        for cid, lst in per.items():
            watch_avg[cid], watch_n[cid] = sum(lst) / len(lst), len(lst)

    # the viewer's own interest in each creator (by CREATOR: posts have no topic tags yet, and none are invented)
    interest_pts = {}
    for ct, tb in (("post", "posts"), ("short", "shorts")):
        for r in con.execute(
                f"SELECT p.uid AS creator, e.event AS ev, COUNT(*) AS n FROM interaction_events e JOIN {tb} p ON p.id=e.content_id "
                f"WHERE e.uid=:me AND e.content_type=:ct AND e.created_at>=:since AND e.event IN ('like','save','comment','share','complete') "
                f"GROUP BY p.uid, e.event", {"me": uid, "ct": ct, "since": now - INTEREST_WINDOW_S}).fetchall():
            interest_pts[r["creator"]] = interest_pts.get(r["creator"], 0.0) + ranker.INTEREST_POINTS[r["ev"]] * r["n"]

    # repeats: what the viewer already saw / skipped
    seen = {r["cid"]: r["t"] for r in con.execute(
        f"SELECT content_id AS cid, MAX(created_at) AS t FROM interaction_events WHERE uid=:me AND content_type=:ct "
        f"AND event='impression' AND content_id IN ({ph}) GROUP BY content_id", {**base, "me": uid}).fetchall()}
    skipped = {r["cid"] for r in con.execute(
        f"SELECT DISTINCT content_id AS cid FROM interaction_events WHERE uid=:me AND content_type=:ct "
        f"AND event='skip' AND content_id IN ({ph})", {**base, "me": uid}).fetchall()}

    # quality: different people who reported it (a signal, not proof; the creator's own report does not count)
    reporters = {int(r["cid"]): r["n"] for r in con.execute(
        f"SELECT r.target_id AS cid, COUNT(DISTINCT r.reporter) AS n FROM reports r JOIN {table} p ON CAST(p.id AS TEXT)=r.target_id "
        f"WHERE r.target_type=:ct AND r.reporter != p.uid AND p.id IN ({ph}) GROUP BY r.target_id", base).fetchall()}

    followed = {r["followee"] for r in con.execute(
        "SELECT followee FROM follows WHERE follower=? AND status='accepted'", (uid,)).fetchall()}

    items = []
    for r in rows:
        cid = r["id"]
        items.append(ranker.Item(
            id=cid, creator=r["uid"], age_seconds=now - r["created_at"],
            interest_points=interest_pts.get(r["uid"], 0.0), watch_avg=watch_avg.get(cid), watch_viewers=watch_n.get(cid, 0),
            engage_points=likes.get(cid, 0) + 2 * saves.get(cid, 0) + 2 * comments.get(cid, 0) + 2 * shares.get(cid, 0),
            followed=r["uid"] in followed, seen_age_seconds=(now - seen[cid]) if cid in seen else None,
            skipped=cid in skipped, reporters=reporters.get(cid, 0)))
    # Step 48: the Go engine (when RANK_ENGINE_URL is set) puts the SAME already-filtered candidates in order; it can only reorder them,
    # and any problem (off, slow, wrong answer) means Python ranks them here, exactly as before.
    return rank_engine.client.order(items, RANK_WEIGHTS, limit=SNAPSHOT_MAX_ITEMS)


def _save_snapshot(con, uid: str, ctype: str, ids: list) -> str:
    import secrets as _sec
    now = int(time.time())
    con.execute("DELETE FROM feed_snapshots WHERE created_at < ?", (now - SNAPSHOT_TTL_S,))
    token = _sec.token_urlsafe(9)
    con.execute("INSERT INTO feed_snapshots(token, uid, kind, ids, created_at) VALUES (?,?,?,?,?)",
                (token, uid, ctype, ",".join(str(i) for i in ids), now))
    con.execute("DELETE FROM feed_snapshots WHERE uid=? AND token NOT IN "
                "(SELECT token FROM feed_snapshots WHERE uid=? ORDER BY created_at DESC, rowid DESC LIMIT ?)",
                (uid, uid, SNAPSHOTS_PER_USER))
    return token


def _cursor_expired() -> HTTPException:
    return HTTPException(410, {"code": "cursor_expired", "message": "This list is out of date. Pull to refresh."})


def _parse_cursor(cursor: str):
    token, _, off = cursor.rpartition(".")
    if not token or not off.isdigit() or len(token) > 40 or int(off) > 100000:
        raise _cursor_expired()
    return token, int(off)


def _snapshot_page(con, uid: str, ctype: str, token: str, offset: int, limit: int):
    """One page of a stored ranking. Visibility and hiding are checked AGAIN here, so nothing that became forbidden after the
    snapshot was made (blocked, went private, hidden, removed, deleted) is ever served. Another person's token = expired."""
    cfg = _RANK_KINDS[ctype]
    snap = con.execute("SELECT ids, kind, created_at FROM feed_snapshots WHERE token=? AND uid=?", (token, uid)).fetchone()
    if not snap or snap["kind"] != ctype or int(time.time()) - snap["created_at"] > SNAPSHOT_TTL_S:
        raise _cursor_expired()
    ids = [int(x) for x in snap["ids"].split(",") if x]
    chunk = ids[offset:offset + limit]
    out = []
    if chunk:
        ph, idp = _in_list(chunk)
        by_id = {r["id"]: r for r in con.execute(
            f"{cfg['select']} WHERE p.id IN ({ph}) AND {VISIBLE} AND {cfg['hidden']}", {"me": uid, **idp}).fetchall()}
        out = [cfg["as_dict"](by_id[i], uid) for i in chunk if i in by_id]
    nxt = offset + limit if offset + limit < len(ids) else None
    return out, nxt


def _ranked_feed(ctype: str, cursor: str | None, limit: int, uid: str) -> dict:
    cfg = _RANK_KINDS[ctype]
    limit = max(1, min(limit, cfg["max_limit"]))
    rate_limit(uid, "ranked_feed", 120, 60)
    meta = {"ranked": True, "ranker": ranker.VERSION}
    if cursor:
        token, offset = _parse_cursor(cursor)
        with closing(db()) as con:
            items, nxt = _snapshot_page(con, uid, ctype, token, offset, limit)
        return {cfg["key"]: items, "next_cursor": f"{token}.{nxt}" if nxt is not None else None, **meta}
    try:
        with closing(db()) as con:
            token = _save_snapshot(con, uid, ctype, rank_candidates(con, uid, ctype))
            con.commit()
            items, nxt = _snapshot_page(con, uid, ctype, token, 0, limit)
        return {cfg["key"]: items, "next_cursor": f"{token}.{nxt}" if nxt is not None else None, **meta}
    except Exception as e:   # noqa: BLE001  plan v3.9 section 41: if ranking fails, fall back to the eligible newest-first feed
        _rank_log.warning("ranking failed (%s): serving the newest-first feed", type(e).__name__)
    base = feed("for_you", None, limit, uid) if ctype == "post" else shorts_feed(None, limit, uid)
    return {**base, "next_cursor": None, "ranked": False, "ranker": None, "fallback": "chronological"}


@app.get("/feed/for-you")
def feed_for_you(cursor: str | None = None, limit: int = 20, uid: str = Depends(current_user)):
    """Ranked For You (photos). First call: no cursor. Next pages: pass `next_cursor` back. Refresh = call again without a cursor."""
    return _ranked_feed("post", cursor, limit, uid)


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


def peer_read_id(con, c, me: str):
    """Step 43 (plan section 9: Chat > read receipts). The id of the newest message of MINE that the other person has SEEN, for a
    one-to-one chat only. None = receipts are not visible: a group chat, or either of us switched read receipts off (reciprocal,
    exactly like Online / last seen: if I hide mine I see nobody's). What is "shown" is kept in `receipt_read_id`, which is only
    written while the reader's switch is ON, so switching it on later never reveals what was read while it was off."""
    if c["kind"] != "dm":
        return None
    peer_uid = dm_peer_uid(con, c["id"], me)
    if not peer_uid or is_blocked(con, me, peer_uid):
        return None
    mine, theirs = user_row(con, me), user_row(con, peer_uid)
    if not mine or not theirs or not mine["read_receipts"] or not theirs["read_receipts"]:
        return None
    row = con.execute("SELECT receipt_read_id FROM conv_members WHERE conv_id=? AND uid=?", (c["id"], peer_uid)).fetchone()
    return int(row["receipt_read_id"]) if row else None


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
        return {"messages": msgs, "conversation": conv_summary(con, c, uid),
                "peer_read_id": peer_read_id(con, c, uid)}          # Step 43: null = receipts not visible


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
        c, _ = open_conv(con, conv_id, uid)
        last = con.execute(f"SELECT COALESCE(MAX(id),0) FROM messages WHERE conv_id=? AND {SEEN}", (conv_id, uid)).fetchone()[0]
        con.execute("UPDATE conv_members SET last_read_id=? WHERE conv_id=? AND uid=?", (last, conv_id, uid))
        # Step 43: what the OTHER person may see as "Seen". Only in a one-to-one chat, only while MY read-receipts switch is on,
        # and never backwards. (last_read_id above still moves, it only drives my own unread badge.)
        mine = user_row(con, uid)
        if c["kind"] == "dm" and mine and mine["read_receipts"]:
            con.execute("UPDATE conv_members SET receipt_read_id=MAX(receipt_read_id, ?) WHERE conv_id=? AND uid=?", (last, conv_id, uid))
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
        media_check(con, uid, size)
        media_take(con, uid, size)
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


@app.get("/posts/{post_id}")
def get_post(post_id: int, uid: str = Depends(current_user)):
    """Step 35: open ONE photo post (placed after /posts/saved so "saved" is never read as an id).
    Same VISIBLE rule as the feed; hidden and missing give the same 404."""
    with closing(db()) as con:
        return post_dict(visible_post(con, post_id, uid), uid)


def _delete_user_data(con, uid: str):
    """Removes EVERYTHING that belongs to a user from the database; their files are queued for Cloudinary deletion."""
    # Step 38: what the engine kept about my content, and what it kept about me
    for ctype, table in (("post", "posts"), ("short", "shorts")):
        con.execute(f"DELETE FROM interaction_events WHERE content_type='{ctype}' AND content_id IN "
                    f"(SELECT id FROM {table} WHERE uid=?)", (uid,))
        con.execute(f"DELETE FROM rec_hidden WHERE kind='item' AND target IN (SELECT '{ctype}:'||id FROM {table} WHERE uid=?)", (uid,))
    con.execute("DELETE FROM interaction_events WHERE uid=?", (uid,))
    con.execute("DELETE FROM rec_hidden WHERE uid=? OR (kind='creator' AND target=?)", (uid, uid))
    con.execute("DELETE FROM ad_impressions WHERE uid=?", (uid,))
    con.execute("DELETE FROM feed_snapshots WHERE uid=?", (uid,))
    con.execute("DELETE FROM push_tokens WHERE uid=?", (uid,))      # Step 46
    con.execute("DELETE FROM push_outbox WHERE uid=?", (uid,))
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
    con.execute("DELETE FROM media_usage WHERE uid=?", (uid,))
    con.execute("DELETE FROM lock_recovery WHERE uid=?", (uid,))
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
# Sociax Pro ($15 / month) payments with Chapa (TEST mode). One-time payment = 30 days, NOT a subscription.
# Plan v3.2: there is ONE paid plan. The old $10 AI Pro and $3 App Pro are no longer sold; time already paid for them
# (verified before this version) is honoured until it ends, as Sociax Pro.
# Rules (Chapa plan): the app never says "I paid". Pro is granted ONLY here, after the server
# verifies the transaction directly with Chapa and checks reference, status, amount, currency and user.
# =====================================================================
import secrets  # noqa: E402
from decimal import Decimal, InvalidOperation  # noqa: E402

PRO_PRICE = Decimal("15")             # fixed on the SERVER; the app can never send a price
PRO_CURRENCY = "USD"                  # ETB and USD are supported by Chapa; no silent conversion
PRO_SECONDS = 30 * 24 * 3600
LEGACY_PRO_PRODUCTS = ("ai_pro_1_month", "app_pro_1_month")   # sold under plan v3.1; never sold again, only honoured

# product id used by the app in POST /payments/initialize?product=...  ->  what the SERVER sells
PRODUCTS = {
    "pro": {"product": "pro_1_month", "price": PRO_PRICE, "currency": PRO_CURRENCY,
            "seconds": PRO_SECONDS, "title": "Sociax Pro", "description": "Sociax Pro, 1 month"},
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


def _pro_expires_at(uid: str) -> int:
    with closing(db()) as con:
        return _pro_expires_at_con(con, uid)


def _pro_expires_at_con(con, uid: str, product: str | None = None) -> int:
    """End of the paid time. product=None: Sociax Pro plus any time still paid under the two old plans."""
    names = (product,) if product else (PRODUCTS["pro"]["product"],) + LEGACY_PRO_PRODUCTS
    marks = ",".join("?" * len(names))
    r = con.execute(f"SELECT MAX(pro_expires_at) FROM payments WHERE uid=? AND product IN ({marks}) AND granted=1",
                    (uid, *names)).fetchone()
    return int(r[0] or 0)


def pro_expires_at(uid: str) -> int:
    return _pro_expires_at(uid)


def is_pro(uid: str) -> bool:
    """True only after a VERIFIED payment whose time has not ended. Benefits unlock immediately after verification."""
    return pro_expires_at(uid) > int(time.time())


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
def payments_initialize(uid: str = Depends(current_user), product: str = "pro"):
    """product = pro (Sociax Pro, $15 / month: 1.5 GB media per day, AI x7, AI generation). The price is chosen HERE, never by the app."""
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
    """Ask Chapa directly. Grants 30 days of Sociax Pro exactly once per successful, matching transaction."""
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
                cur = _pro_expires_at_con(con, uid)   # extends from the end of any Pro time still running
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


@app.get("/payments/status/{tx_ref}")
def payments_status(tx_ref: str, uid: str = Depends(current_user)):
    """What the SERVER already knows (no call to Chapa)."""
    with closing(db()) as con:
        return _payment_dict(_own_payment(con, uid, tx_ref))


# =====================================================================
# Step 32: App Lock recovery (plan v3.3 section 3). The PIN / pattern itself NEVER reaches the server: only a salted hash
# lives on the phone. When the user forgot it, they prove account ownership with a FRESH Google sign-in; the server checks
# that the sign-in really happened a moment ago (Firebase `auth_time`), then hands out a short-lived, single-use token.
# The app redeems the token and only then lets the user choose a new PIN / pattern. The old one is never shown or sent.
# =====================================================================
LOCK_RECOVERY_TTL = 300               # seconds a recovery token lives
LOCK_RECOVERY_MAX_AUTH_AGE = 300      # the Google sign-in must be at most 5 minutes old


def auth_age_seconds(authorization: str | None) -> int | None:
    """How many seconds ago the user really signed in (Firebase `auth_time`). None = unknown -> treated as NOT fresh."""
    token = (authorization or "")[7:]
    if DEV_AUTH and token.startswith("dev-"):
        return 10_000 if token.startswith("dev-stale") else 0     # tests: "dev-stale..." simulates an old sign-in
    try:
        _init_firebase()
        from firebase_admin import auth as fb_auth
        at = fb_auth.verify_id_token(token).get("auth_time")
        return max(0, int(time.time()) - int(at)) if at else None
    except Exception:
        return None


def _lock_token_hash(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


@app.post("/lock/recovery")
def lock_recovery_start(uid: str = Depends(current_user), authorization: str = Header(None)):
    """Step 1: needs a Google sign-in from the last 5 minutes. Returns a one-time token (never logged, stored only as a hash)."""
    import secrets as _secrets
    rate_limit(uid, "lock_recovery", 5, 3600)
    age = auth_age_seconds(authorization)
    if age is None or age > LOCK_RECOVERY_MAX_AUTH_AGE:
        raise HTTPException(403, {"code": "reauth_required", "message": "Please verify with Google again."})
    token = _secrets.token_urlsafe(32)
    now = int(time.time())
    with closing(db()) as con:
        con.execute("DELETE FROM lock_recovery WHERE uid=? OR expires_at<?", (uid, now))   # only the newest token is valid
        con.execute("INSERT INTO lock_recovery(token_hash, uid, expires_at) VALUES (?,?,?)",
                    (_lock_token_hash(token), uid, now + LOCK_RECOVERY_TTL))
        con.commit()
    return {"token": token, "expires_in": LOCK_RECOVERY_TTL}


class LockToken(BaseModel):
    token: str = Field(min_length=20, max_length=200)


@app.post("/lock/recovery/confirm")
def lock_recovery_confirm(body: LockToken, uid: str = Depends(current_user)):
    """Step 2: the token works ONCE, only for the user it was made for, and only for 5 minutes. Every failure looks the same."""
    rate_limit(uid, "lock_confirm", 10, 3600)
    now = int(time.time())
    h = _lock_token_hash(body.token)
    with closing(db()) as con:
        row = con.execute("SELECT expires_at FROM lock_recovery WHERE token_hash=? AND uid=?", (h, uid)).fetchone()
        if row:
            con.execute("DELETE FROM lock_recovery WHERE token_hash=?", (h,))     # used up, whatever happens next
            con.commit()
        if not row or row["expires_at"] < now:
            raise HTTPException(403, {"code": "recovery_invalid", "message": "This verification is not valid. Please try again."})
    return {"ok": True}


# =====================================================================
# Step 41 (plan v3.8 section 28): AdMob in the Short Video feed, Free accounts only, at most 20 COUNTED impressions per day.
# - GET  /ads/status      : may this account see ads, how many are counted today, how many are left, when the day resets.
# - POST /ads/impression  : the phone reports an impression that the official AdMob SDK callback (onAdImpression) confirmed.
#                           The SERVER counts it: once per key, never above the cap, never for Pro. The phone's own counter is never trusted.
# Pro is decided HERE from the verified payment (is_pro), never from anything the phone says. The ad itself (what is shown, the
# AdMob unit) is the phone's business; this server only keeps the count and the rules.
# =====================================================================
AD_DAILY_CAP = 20                       # plan v3.8 section 28.1: a CAP, not a target
AD_DAY_OFFSET_MINUTES = int(os.getenv("AD_DAY_UTC_OFFSET_MINUTES", "0"))   # the app-defined day starts at 00:00 UTC + this offset (default 00:00 UTC)
AD_EVERY_MIN, AD_EVERY_MAX = 3, 4       # spacing: an ad after about 3 (sometimes 4) ordinary videos, never back-to-back
AD_RETENTION_DAYS = 35
ADS_PER_MINUTE = 30                     # abuse limit per account
_last_ad_purge = 0.0


def ad_day(now: float | None = None) -> int:
    """The app-defined day number. The reset time is explicit: 00:00 UTC (+ AD_DAY_UTC_OFFSET_MINUTES). Server clock only."""
    t = int(time.time() if now is None else now) + AD_DAY_OFFSET_MINUTES * 60
    return t // 86400


def ad_day_resets_at(now: float | None = None) -> int:
    return (ad_day(now) + 1) * 86400 - AD_DAY_OFFSET_MINUTES * 60


def ads_counted_today(con, uid: str) -> int:
    return int(con.execute("SELECT COUNT(*) FROM ad_impressions WHERE uid=? AND day=?", (uid, ad_day())).fetchone()[0])


def _ad_status_dict(uid: str, used: int, pro: bool) -> dict:
    now = int(time.time())
    remaining = 0 if pro else max(0, AD_DAILY_CAP - used)
    return {"eligible": (not pro) and remaining > 0, "pro": pro, "reason": "pro" if pro else ("cap" if remaining == 0 else None),
            "daily_cap": AD_DAILY_CAP, "used": used, "remaining": remaining,
            "resets_at": ad_day_resets_at(now), "reset_in_seconds": max(0, ad_day_resets_at(now) - now),
            "every_min": AD_EVERY_MIN, "every_max": AD_EVERY_MAX}


@app.get("/ads/status")
def ads_status(uid: str = Depends(current_user)):
    rate_limit(uid, "ads_status", 60, 60)
    pro = is_pro(uid)
    with closing(db()) as con:
        used = ads_counted_today(con, uid)
        age = con.execute("SELECT age FROM users WHERE uid=?", (uid,)).fetchone()
    out = _ad_status_dict(uid, used, pro)
    # Under 18: the phone must ask AdMob for child-safe, non-personalised treatment (age is the one given at sign-up).
    out["minor"] = bool(age and age["age"] < 18)
    return out


class AdImpression(BaseModel):
    idem_key: str = Field(min_length=8, max_length=64, pattern=r"^[A-Za-z0-9_.:-]+$")   # one per loaded ad; the phone re-uses it on retry


@app.post("/ads/impression")
def ads_impression(body: AdImpression, uid: str = Depends(current_user)):
    rate_limit(uid, "ads_impression", ADS_PER_MINUTE, 60)
    if not isinstance(body.idem_key, str) or not _IDEM_RE.match(body.idem_key):
        raise HTTPException(422, "Bad idempotency key")
    pro = is_pro(uid)       # verified payment state, read on the server
    global _last_ad_purge
    with closing(db()) as con:
        con.execute("BEGIN IMMEDIATE")      # one writer at a time: two phones cannot both take the last place under the cap
        try:
            now = time.time()
            if now - _last_ad_purge > 3600:
                _last_ad_purge = now
                con.execute("DELETE FROM ad_impressions WHERE day < ?", (ad_day(now) - AD_RETENTION_DAYS,))
            used = ads_counted_today(con, uid)
            dup = con.execute("SELECT 1 FROM ad_impressions WHERE uid=? AND idem_key=?", (uid, body.idem_key)).fetchone()
            if dup:                         # a retry of the same impression: counted once, whatever the cap is now
                con.commit()
                return {"ok": True, "counted": False, "duplicate": True, **_ad_status_dict(uid, used, pro)}
            if pro:                         # Pro never sees ads: nothing is counted
                con.commit()
                return {"ok": True, "counted": False, "reason": "pro", **_ad_status_dict(uid, used, pro)}
            if used >= AD_DAILY_CAP:        # cap reached: this one is NOT counted
                con.commit()
                return {"ok": True, "counted": False, "reason": "cap", **_ad_status_dict(uid, used, pro)}
            con.execute("INSERT INTO ad_impressions(uid, day, idem_key, created_at) VALUES (?,?,?,?)",
                        (uid, ad_day(now), body.idem_key, int(now)))
            con.commit()
        except Exception:
            con.rollback()
            raise
    return {"ok": True, "counted": True, **_ad_status_dict(uid, used + 1, pro)}


# Step 46: the push worker runs only when the owner switches it on (PUSH_ENABLED=1) AND real Firebase login is used (not DEV_AUTH).
if os.getenv("PUSH_ENABLED") == "1" and not DEV_AUTH:
    start_push_worker()
