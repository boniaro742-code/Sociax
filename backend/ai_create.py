"""Step 55 (plan v3.2 sections 1 and 4-6, v3.9 section 34): AI Create = make an image or a short video with AI, PREVIEW it, and only
after the person says Yes use it. Nothing is posted, set or sent without that Yes.

This module is plain Python + sqlite3 (no FastAPI) so every rule can be tested here. main.py only wires it to HTTP.

RULES IT ENFORCES ON THE SERVER (never trusts numbers from the app):
  * Sociax Pro only (plan v3.2 section 1 lists the AI allowances as a Pro benefit).
  * At most 4 images and 3 videos per person in a rolling 24 h window that starts at the first generation; the app is told how many
    are left and when the window ends. A generation that FAILS gives its slot back.
  * The result is a DRAFT. A draft is used at most once (claim -> used) and can be discarded ("No"); an unused draft expires after 24 h
    and the file is queued for deletion. Drafts live under the person's own folder; a draft of someone else can never be used.
  * The prompt is NEVER stored or logged (only the draft's kind, size and time).

WHAT IS NOT HERE (honest): no AI provider is chosen yet. `provider_from_env` returns None until the owner picks one, and then the
server answers 503 `generation_unavailable` ("not available yet") instead of pretending. A real content-safety check of prompts and
results is the provider's job plus a later step; this module does not claim to moderate.
"""
import os
import sqlite3
import time

LIMITS = {"image": 4, "video": 3}
WINDOW_SECONDS = 24 * 3600
DRAFT_TTL_SECONDS = 24 * 3600
PROMPT_MAX = 1000
MAX_BYTES = {"image": 8 * 1024 * 1024, "video": 100 * 1024 * 1024}
MIMES = {"image": ("image/png", "image/jpeg", "image/webp"), "video": ("video/mp4",)}


class AiCreateError(Exception):
    """Base class. `code` is the machine-readable reason sent to the app."""
    code = "error"
    status = 400


class BadRequest(AiCreateError):
    code, status = "bad_request", 422


class Unavailable(AiCreateError):
    code, status = "generation_unavailable", 503


class LimitReached(AiCreateError):
    code, status = "gen_limit", 429

    def __init__(self, kind: str, reset_in: int):
        super().__init__(kind)
        self.kind, self.reset_in = kind, reset_in


class ProviderFailed(AiCreateError):
    code, status = "generation_failed", 502


class NotFound(AiCreateError):
    code, status = "draft_not_found", 404


class Conflict(AiCreateError):
    code, status = "draft_not_usable", 409


# ---------------------------------------------------------------------------------------------------------------- providers
class GenResult:
    def __init__(self, data: bytes, mime: str):
        self.data, self.mime = data, mime


class Provider:
    """A generation service. Implement `kinds` and `generate`; raise ProviderFailed on any error (the slot is given back)."""

    def kinds(self) -> set:
        return set()

    def generate(self, kind: str, prompt: str) -> GenResult:
        raise ProviderFailed("not implemented")


class FakeProvider(Provider):
    """For tests and local development ONLY (main.py refuses it unless DEV_AUTH is on). Makes a tiny valid PNG / a stub mp4."""
    PNG = bytes.fromhex("89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000d49444154789c6360000002000001e221bc330000000049454e44ae426082")

    def __init__(self, fail: bool = False):
        self.fail, self.calls = fail, 0

    def kinds(self) -> set:
        return {"image", "video"}

    def generate(self, kind: str, prompt: str) -> GenResult:
        self.calls += 1
        if self.fail:
            raise ProviderFailed("fake failure")
        return GenResult(self.PNG if kind == "image" else b"\x00\x00\x00\x18ftypmp42" + b"\x00" * 32,
                         "image/png" if kind == "image" else "video/mp4")


def provider_from_env(env=None):
    """GEN_PROVIDER = fake (dev only). Unset / unknown = None: AI Create answers "not available yet". Real providers are added here
    once the owner has chosen one (name, key, price); nothing is guessed."""
    env = os.environ if env is None else env
    name = (env.get("GEN_PROVIDER") or "").strip().lower()
    if name == "fake":
        return FakeProvider()
    return None


# ---------------------------------------------------------------------------------------------------------------- tables
def ensure_tables(con: sqlite3.Connection) -> None:
    con.execute("CREATE TABLE IF NOT EXISTS ai_gen_usage(uid TEXT NOT NULL, kind TEXT NOT NULL, window_start INTEGER NOT NULL, "
                "used INTEGER NOT NULL, PRIMARY KEY(uid, kind))")
    con.execute("CREATE TABLE IF NOT EXISTS ai_gen_drafts(id INTEGER PRIMARY KEY AUTOINCREMENT, uid TEXT NOT NULL, kind TEXT NOT NULL, "
                "public_id TEXT NOT NULL, bytes INTEGER NOT NULL, status TEXT NOT NULL, created_at INTEGER NOT NULL, decided_at INTEGER)")
    con.execute("CREATE INDEX IF NOT EXISTS idx_ai_gen_drafts_uid ON ai_gen_drafts(uid, status)")
    con.commit()


# ---------------------------------------------------------------------------------------------------------------- quota
def quota_state(con, uid: str, now: int | None = None) -> dict:
    now = int(now if now is not None else time.time())
    out = {}
    for kind, limit in LIMITS.items():
        r = con.execute("SELECT window_start, used FROM ai_gen_usage WHERE uid=? AND kind=?", (uid, kind)).fetchone()
        if not r or now - r[0] >= WINDOW_SECONDS:
            out[kind] = {"limit": limit, "used": 0, "remaining": limit, "reset_in_seconds": 0}
        else:
            out[kind] = {"limit": limit, "used": r[1], "remaining": max(limit - r[1], 0),
                         "reset_in_seconds": r[0] + WINDOW_SECONDS - now}
    return out


def reserve(con, uid: str, kind: str, now: int | None = None) -> int:
    """Takes one slot (atomically) or raises LimitReached. Returns the window the slot belongs to (needed to give it back)."""
    now = int(now if now is not None else time.time())
    limit = LIMITS[kind]
    con.execute("BEGIN IMMEDIATE")
    try:
        r = con.execute("SELECT window_start, used FROM ai_gen_usage WHERE uid=? AND kind=?", (uid, kind)).fetchone()
        if not r or now - r[0] >= WINDOW_SECONDS:
            con.execute("INSERT INTO ai_gen_usage(uid, kind, window_start, used) VALUES (?,?,?,1) "
                        "ON CONFLICT(uid, kind) DO UPDATE SET window_start=excluded.window_start, used=1", (uid, kind, now))
            start = now
        elif r[1] >= limit:
            con.rollback()
            raise LimitReached(kind, r[0] + WINDOW_SECONDS - now)
        else:
            con.execute("UPDATE ai_gen_usage SET used=used+1 WHERE uid=? AND kind=?", (uid, kind))
            start = r[0]
        con.commit()
        return start
    except LimitReached:
        raise
    except Exception:
        con.rollback()
        raise


def refund(con, uid: str, kind: str, window_start: int) -> None:
    """A generation that failed never costs the person a slot (only if the window is still the same one)."""
    con.execute("UPDATE ai_gen_usage SET used=MAX(used-1,0) WHERE uid=? AND kind=? AND window_start=?", (uid, kind, window_start))
    con.commit()


# ---------------------------------------------------------------------------------------------------------------- drafts
def _draft(r) -> dict:
    return {"id": r[0], "uid": r[1], "kind": r[2], "public_id": r[3], "bytes": r[4], "status": r[5], "created_at": r[6]}


def get_draft(con, uid: str, draft_id: int) -> dict:
    r = con.execute("SELECT id, uid, kind, public_id, bytes, status, created_at FROM ai_gen_drafts WHERE id=? AND uid=?",
                    (draft_id, uid)).fetchone()
    if not r:
        raise NotFound(str(draft_id))      # someone else's draft answers exactly like a missing one
    return _draft(r)


def claim(con, uid: str, draft_id: int) -> dict:
    """draft -> using (atomic): two taps / two phones can never use the same draft twice."""
    cur = con.execute("UPDATE ai_gen_drafts SET status='using', decided_at=? WHERE id=? AND uid=? AND status='draft'",
                      (int(time.time()), draft_id, uid))
    con.commit()
    if cur.rowcount != 1:
        d = get_draft(con, uid, draft_id)
        raise Conflict(d["status"])
    return get_draft(con, uid, draft_id)


def release(con, uid: str, draft_id: int) -> None:
    """The action failed (network, quota ...): the draft goes back to 'draft' so the person can try again."""
    con.execute("UPDATE ai_gen_drafts SET status='draft' WHERE id=? AND uid=? AND status='using'", (draft_id, uid))
    con.commit()


def complete(con, uid: str, draft_id: int) -> None:
    con.execute("UPDATE ai_gen_drafts SET status='used' WHERE id=? AND uid=? AND status='using'", (draft_id, uid))
    con.commit()


def discard(con, uid: str, draft_id: int) -> dict:
    """"No": the draft is dropped; the caller queues its file for deletion. Only an unused draft can be discarded."""
    cur = con.execute("UPDATE ai_gen_drafts SET status='discarded', decided_at=? WHERE id=? AND uid=? AND status='draft'",
                      (int(time.time()), draft_id, uid))
    con.commit()
    d = get_draft(con, uid, draft_id)
    if cur.rowcount != 1:
        raise Conflict(d["status"])
    return d


def is_pending_draft(con, uid: str, public_id: str, kind: str) -> bool:
    """True when `public_id` is this person's own unused draft of that kind (lets the existing avatar endpoint accept it)."""
    r = con.execute("SELECT 1 FROM ai_gen_drafts WHERE uid=? AND public_id=? AND kind=? AND status IN ('draft','using')",
                    (uid, public_id, kind)).fetchone()
    return bool(r)


def expire_old(con, now: int | None = None) -> list:
    """Unused drafts older than 24 h: marked expired; returns [(public_id, kind)] so the caller can queue the files for deletion."""
    now = int(now if now is not None else time.time())
    rows = con.execute("SELECT id, public_id, kind FROM ai_gen_drafts WHERE status='draft' AND created_at<=?",
                       (now - DRAFT_TTL_SECONDS,)).fetchall()
    for r in rows:
        con.execute("UPDATE ai_gen_drafts SET status='expired', decided_at=? WHERE id=?", (now, r[0]))
    con.commit()
    return [(r[1], r[2]) for r in rows]


# ---------------------------------------------------------------------------------------------------------------- generate
def generate(con, uid: str, kind: str, prompt: str, provider, store, now: int | None = None) -> dict:
    """Make one draft. `store(kind, data, mime) -> public_id` saves the result in the person's own folder (Cloudinary in main.py).
    The slot is reserved first and given back when anything fails, so a failed try is free."""
    if kind not in LIMITS:
        raise BadRequest("kind must be image or video")
    prompt = (prompt or "").strip()
    if not prompt or len(prompt) > PROMPT_MAX:
        raise BadRequest(f"prompt must be 1 to {PROMPT_MAX} characters")
    if provider is None or kind not in provider.kinds():
        raise Unavailable(kind)
    start = reserve(con, uid, kind, now)
    try:
        res = provider.generate(kind, prompt)
        if res.mime not in MIMES[kind] or not (0 < len(res.data) <= MAX_BYTES[kind]):
            raise ProviderFailed("result rejected")
        public_id = store(kind, res.data, res.mime)
    except AiCreateError:
        refund(con, uid, kind, start)
        raise
    except Exception as e:               # network, upload ... : never keep the slot, never leak details
        refund(con, uid, kind, start)
        raise ProviderFailed(e.__class__.__name__)
    ts = int(now if now is not None else time.time())
    cur = con.execute("INSERT INTO ai_gen_drafts(uid, kind, public_id, bytes, status, created_at) VALUES (?,?,?,?, 'draft', ?)",
                      (uid, kind, public_id, len(res.data), ts))
    con.commit()
    return get_draft(con, uid, cur.lastrowid)
