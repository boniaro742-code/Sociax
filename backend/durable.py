"""Step 54 (plan v3.9.2 sections 2, 3 and 4): the database must SURVIVE restarts and deploys.

WHY THIS EXISTS. Sociax keeps every account, post, message and setting in ONE SQLite file (`sociax.db`). A free Render web
service has an EPHEMERAL disk: the file is erased on every deploy, restart and wake-up after a sleep. Then the same Google
login finds no account, the app asks for a new name / @username, and all saved data seems to be gone. Plan v3.9.2 forbids that.

WHAT IT DOES (no change to the SQL of the app):
  * At start-up, BEFORE the tables are created: the newest saved copy ("snapshot") of the database is downloaded from durable
    storage and put in place. Only a CONFIRMED "no snapshot has ever been saved" lets the server start with an empty database.
  * A snapshot that exists but cannot be fetched or read (network down, damaged) NEVER means "empty account": the server answers
    503 "saved data not available yet" to everything except /health and /health/config, keeps trying, and never writes a backup
    while it is in that state (so an empty database can never overwrite good saved data).
  * While running, a background thread saves a new snapshot whenever the database changed, at most once every
    BACKUP_MIN_INTERVAL seconds (default 45), and once more when the server is told to stop.
  * Two slots (a / b) are written in turn, so the newest good copy is never overwritten before the new one has arrived.
  * Every snapshot carries a header (time, size, SHA-256) and is checked (gzip, hash, SQLite integrity_check, `users` table)
    before it is used. A damaged newest slot falls back to the older slot.

HONEST LIMITS (also printed by GET /health/config):
  * Changes made in the last seconds before a sudden crash are not in the last snapshot (a normal stop / deploy saves first).
  * During a deploy the old and the new server run side by side for a short time; what is written on the old one in that window
    may be missing afterwards. Deploy at a quiet moment.
  * Cloudinary's free plan limits the size of a raw file; when the compressed database grows near that limit the backup reports
    an error in /health/config and the real fix is a managed database (PostgreSQL) or a Render disk. This module is the bridge.
  * The stores below are tested with a folder / a fake; the Cloudinary store was NOT run against the real Cloudinary here.
"""
import gzip
import hashlib
import io
import json
import logging
import os
import shutil
import sqlite3
import tempfile
import threading
import time
from contextlib import closing

log = logging.getLogger("sociax.durable")

MAGIC = b"SOCIAXDB1 "
SLOTS = ("a", "b")


class StoreUnavailable(Exception):
    """The durable storage could not be reached / answered with an error. Says NOTHING about whether a snapshot exists."""


class StoreRejected(Exception):
    """The durable storage refused this upload (for example the file is too large). Permanent until something changes."""


# ----------------------------------------------------------------------------------------------------------------------
# Stores: get(slot) -> bytes | None (None = CONFIRMED not there), put(slot, bytes)
# ----------------------------------------------------------------------------------------------------------------------
class DirStore:
    """A folder on the same machine. Used by the tests and for trying things locally; NOT durable on Render's free plan."""

    def __init__(self, path: str):
        self.path = path
        os.makedirs(path, exist_ok=True)

    def get(self, slot: str):
        p = os.path.join(self.path, f"sociax-db-{slot}.bin")
        try:
            with open(p, "rb") as f:
                return f.read()
        except FileNotFoundError:
            return None
        except OSError as e:
            raise StoreUnavailable(e.__class__.__name__)

    def put(self, slot: str, data: bytes) -> None:
        p = os.path.join(self.path, f"sociax-db-{slot}.bin")
        try:
            tmp = p + ".tmp"
            with open(tmp, "wb") as f:
                f.write(data)
            os.replace(tmp, p)
        except OSError as e:
            raise StoreUnavailable(e.__class__.__name__)

    def describe(self) -> str:
        return "folder"


class CloudinaryStore:
    """Private ("authenticated") raw files on the Cloudinary account the app already uses. Nobody can open them without the
    API secret that lives only on the server. Needs CLOUDINARY_CLOUD_NAME / _API_KEY / _API_SECRET."""

    FOLDER = "sociax-backup"

    def __init__(self, cloud_name: str, api_key: str, api_secret: str, timeout: float = 30.0):
        self.cloud_name, self.api_key, self.api_secret, self.timeout = cloud_name, api_key, api_secret, timeout

    def _public_id(self, slot: str) -> str:
        return f"{self.FOLDER}/sociax-db-{slot}.bin"          # raw files keep the extension inside the public id

    def get(self, slot: str):
        try:
            import cloudinary.utils
            import httpx
        except ImportError as e:
            raise StoreUnavailable("missing package " + e.name)
        try:
            url, _ = cloudinary.utils.cloudinary_url(
                self._public_id(slot), resource_type="raw", type="authenticated", sign_url=True, secure=True,
                cloud_name=self.cloud_name, api_key=self.api_key, api_secret=self.api_secret)
            r = httpx.get(url, timeout=self.timeout, follow_redirects=True)
        except Exception as e:
            raise StoreUnavailable(e.__class__.__name__)
        if r.status_code == 404:
            return None
        if r.status_code != 200:
            raise StoreUnavailable(f"HTTP {r.status_code} {r.headers.get('x-cld-error', '')[:60]}")
        return r.content

    def put(self, slot: str, data: bytes) -> None:
        try:
            import cloudinary
            import cloudinary.uploader
        except ImportError as e:
            raise StoreUnavailable("missing package " + e.name)
        try:
            cloudinary.config(cloud_name=self.cloud_name, api_key=self.api_key, api_secret=self.api_secret, secure=True)
            cloudinary.uploader.upload(
                io.BytesIO(data), resource_type="raw", type="authenticated", public_id=self._public_id(slot),
                overwrite=True, invalidate=True, unique_filename=False, use_filename=False)
        except Exception as e:
            name = e.__class__.__name__
            text = str(e).lower()
            if "too large" in text or "file size" in text or "maximum" in text:
                raise StoreRejected(name)
            raise StoreUnavailable(name)

    def describe(self) -> str:
        return "cloudinary"


# ----------------------------------------------------------------------------------------------------------------------
# Snapshot = header line + gzip(sqlite file)
# ----------------------------------------------------------------------------------------------------------------------
def make_snapshot(db_path: str, now: float | None = None, seq: int = 1) -> bytes:
    """A consistent copy of the live database (SQLite online-backup API: safe while requests keep writing)."""
    fd, tmp = tempfile.mkstemp(suffix=".db")
    os.close(fd)
    try:
        with closing(sqlite3.connect(db_path)) as src, closing(sqlite3.connect(tmp)) as dst:
            src.backup(dst)
        with open(tmp, "rb") as f:
            raw = f.read()
    finally:
        try:
            os.remove(tmp)
        except OSError:
            pass
    body = gzip.compress(raw, compresslevel=6, mtime=0)
    head = {"v": 1, "seq": int(seq), "saved_at": int(now if now is not None else time.time()), "bytes": len(raw),
            "sha256": hashlib.sha256(raw).hexdigest()}
    return MAGIC + json.dumps(head, separators=(",", ":")).encode() + b"\n" + body


def parse_snapshot(blob: bytes):
    """Returns (header dict, raw sqlite bytes). Raises ValueError for anything damaged."""
    if not blob.startswith(MAGIC) or b"\n" not in blob[:512]:
        raise ValueError("not a snapshot")
    head_line, body = blob[len(MAGIC):].split(b"\n", 1)
    head = json.loads(head_line)
    raw = gzip.decompress(body)
    if len(raw) != head.get("bytes") or hashlib.sha256(raw).hexdigest() != head.get("sha256"):
        raise ValueError("snapshot does not match its header")
    return head, raw


def _check_sqlite(raw: bytes) -> None:
    """integrity_check + the `users` table must exist (a snapshot without it is not a Sociax database)."""
    fd, tmp = tempfile.mkstemp(suffix=".db")
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(raw)
        with closing(sqlite3.connect(tmp)) as con:
            if con.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
                raise ValueError("integrity_check failed")
            if not con.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='users'").fetchone():
                raise ValueError("no users table")
    finally:
        try:
            os.remove(tmp)
        except OSError:
            pass


# ----------------------------------------------------------------------------------------------------------------------
# R8 (report v0.67.0): proof tools. They only READ the store and never touch the live database.
# ----------------------------------------------------------------------------------------------------------------------
def inspect_store(store) -> list:
    """What the two slots hold, without restoring anything: [{slot, exists, valid, seq, saved_at, bytes, problem}].
    `problem` is an error NAME only (never an address or key). StoreUnavailable propagates: the answer would be unknown."""
    out = []
    for slot in SLOTS:
        blob = store.get(slot)
        if blob is None:
            out.append({"slot": slot, "exists": False, "valid": False, "seq": None, "saved_at": None, "bytes": 0, "problem": ""})
            continue
        row = {"slot": slot, "exists": True, "valid": False, "seq": None, "saved_at": None, "bytes": len(blob), "problem": ""}
        try:
            head, raw = parse_snapshot(blob)
            row["seq"], row["saved_at"] = int(head.get("seq", 0)), int(head["saved_at"])
            _check_sqlite(raw)
            row["valid"] = True
        except Exception as e:
            row["problem"] = e.__class__.__name__
        out.append(row)
    return out


def table_counts(db_path: str) -> dict:
    """{table name: number of rows} of a SQLite file (numbers only, no content)."""
    with closing(sqlite3.connect(db_path)) as con:
        names = [r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")]
        return {n: con.execute('SELECT COUNT(*) FROM "%s"' % n.replace('"', '""')).fetchone()[0] for n in names}


def restore_drill(store, out_path: str) -> dict:
    """RESTORE TEST: take the newest GOOD snapshot from the store and write it to `out_path`, a file that is NOT the live
    database (it refuses a path that already exists). Returns {slot, seq, saved_at, age_s, bytes, tables{name: rows}}.
    Raises ValueError when nothing usable exists, StoreUnavailable when the store cannot be reached."""
    if os.path.exists(out_path):
        raise ValueError("refusing to overwrite an existing file")
    found = []
    for slot in SLOTS:
        blob = store.get(slot)
        if blob is None:
            continue
        try:
            head, raw = parse_snapshot(blob)
            _check_sqlite(raw)
            found.append((slot, head, raw))
        except Exception:
            continue
    if not found:
        raise ValueError("no usable snapshot in the store")
    found.sort(key=lambda t: (t[1].get("seq", 0), t[1]["saved_at"]), reverse=True)
    slot, head, raw = found[0]
    with open(out_path, "wb") as f:
        f.write(raw)
    return {"slot": slot, "seq": int(head.get("seq", 0)), "saved_at": int(head["saved_at"]),
            "age_s": max(0, int(time.time()) - int(head["saved_at"])), "bytes": len(raw), "tables": table_counts(out_path)}


# ----------------------------------------------------------------------------------------------------------------------
# The manager: state machine + restore + background backup
# ----------------------------------------------------------------------------------------------------------------------
class Durable:
    """States: off (no durable storage configured: behaves like before) | restoring (server answers 503 for data) |
    ready (restored, or confirmed that no snapshot exists) | blocked (snapshot exists but cannot be used: 503, no backups)."""

    def __init__(self, db_path: str, store=None, min_interval: float = 45.0, retry_wait: float = 15.0):
        self.db_path, self.store = db_path, store
        self.min_interval, self.retry_wait = min_interval, retry_wait
        self.state = "off" if store is None else "restoring"
        self.newest_slot: str | None = None        # slot that holds the newest snapshot we know of
        self.seq = 0                               # number of the newest snapshot we know of (counts up with every backup)
        self.last_backup_at = 0
        self.last_backup_bytes = 0
        self.last_error = ""
        self.last_error_at = 0                     # R8: when the last error happened (time only, no text)
        self.failures_in_row = 0                   # R8: failed backups since the last good one (0 = healthy)
        self.restored_from = ""                    # "", "snapshot", "local file", "nothing saved yet"
        self.restored_saved_at = 0
        self._sig = None                           # (mtime, size) of the database at the last successful backup
        self._lock = threading.Lock()
        self._wake = threading.Event()
        self._stop = threading.Event()
        self._thread = None
        self.on_restored = None                    # called (no arguments) after a late restore, to create missing tables

    # ---- information (booleans / numbers only; never an address, key or file content) ----
    @property
    def enabled(self) -> bool:
        return self.store is not None

    @property
    def serving(self) -> bool:
        """True when the server may answer data requests."""
        return self.state in ("off", "ready")

    def report(self) -> dict:
        now = int(time.time())
        return {"enabled": self.enabled, "store": self.store.describe() if self.store else None, "state": self.state,
                "restored_from": self.restored_from, "last_backup_age_s": (now - self.last_backup_at) if self.last_backup_at else None,
                "last_backup_bytes": self.last_backup_bytes, "last_error": self.last_error,
                # R8 (report v0.67.0): the version number of the newest copy, how many backups failed in a row, and when the last error was
                "last_backup_seq": self.seq, "failures_in_row": self.failures_in_row,
                "last_error_age_s": (now - self.last_error_at) if (self.last_error and self.last_error_at) else None}

    # ---- restore ----
    def _read_slots(self):
        """Fetch both slots. Returns the list of (slot, header, raw) that are valid, newest first, and how many slots EXIST
        (a damaged slot still counts as existing). Raises StoreUnavailable if either slot could not be asked."""
        found, existing = [], 0
        for slot in SLOTS:
            blob = self.store.get(slot)                  # StoreUnavailable propagates: we cannot be sure about the other slot
            if blob is None:
                continue
            existing += 1
            try:
                head, raw = parse_snapshot(blob)
                _check_sqlite(raw)
                found.append((slot, head, raw))
            except Exception as e:                       # damaged: never used, never deleted, reported loudly
                log.error("durable: slot %s exists but is damaged (%s)", slot, e.__class__.__name__)
                self.last_error = f"slot {slot} damaged"
        found.sort(key=lambda t: (t[1].get("seq", 0), t[1]["saved_at"]), reverse=True)     # newest first (seq counts up; time breaks old ties)
        return found, existing

    def restore_once(self) -> str:
        """One attempt. Returns the new state. Never creates an empty database by itself."""
        if not self.enabled:
            return self.state
        try:
            found, existing = self._read_slots()
        except StoreUnavailable as e:
            self.last_error = "storage unreachable: " + str(e)[:80]
            log.warning("durable: cannot reach the storage (%s): the server will NOT start with an empty database", e)
            self.state = "restoring"
            return self.state
        if found:
            slot, head, raw = found[0]
            fd, tmp = tempfile.mkstemp(suffix=".db", dir=os.path.dirname(os.path.abspath(self.db_path)) or ".")
            with os.fdopen(fd, "wb") as f:
                f.write(raw)
            for ext in ("-wal", "-shm", "-journal"):
                try:
                    os.remove(self.db_path + ext)
                except OSError:
                    pass
            os.replace(tmp, self.db_path)
            self.newest_slot, self.restored_from, self.restored_saved_at = slot, "snapshot", head["saved_at"]
            self.seq = int(head.get("seq", 0))
            self.last_error = ""
            self._sig = self._signature()
            self.last_backup_at = head["saved_at"]
            self.state = "ready"
            log.info("durable: database restored from slot %s (saved %d s ago)", slot, int(time.time()) - head["saved_at"])
            return self.state
        if existing:
            # snapshots exist but none can be used: NEVER start empty (that would erase saved data on the next backup)
            self.state = "blocked"
            self.last_error = "saved data exists but cannot be read"
            log.error("durable: %d snapshot(s) exist but none is readable: server stays closed, no backup is written", existing)
            return self.state
        self.restored_from = "nothing saved yet"
        self.last_error = ""
        self.state = "ready"                              # CONFIRMED: both slots answered "not found"
        log.info("durable: no snapshot has ever been saved: starting with a new database")
        return self.state

    def startup(self, attempts: int = 3, pauses=(2.0, 6.0)) -> str:
        """Called once at import, before the tables are created. A few quick tries, then the background thread keeps trying."""
        if not self.enabled:
            return self.state
        if os.path.isfile(self.db_path) and os.path.getsize(self.db_path) > 0:
            # a database file is already here (local development, or a disk that really persists): use it, do not overwrite it
            self.restored_from, self.state = "local file", "ready"
            self._sig = None
            return self.state
        for i in range(attempts):
            if self.restore_once() in ("ready", "blocked"):
                break
            if i < attempts - 1:
                time.sleep(pauses[min(i, len(pauses) - 1)])
        return self.state

    # ---- backup ----
    def _signature(self):
        sig = []
        for ext in ("", "-wal"):
            try:
                st = os.stat(self.db_path + ext)
                sig.append((st.st_mtime_ns, st.st_size))
            except OSError:
                sig.append(None)
        return tuple(sig)

    def backup_now(self, force: bool = False) -> bool:
        """One snapshot if the database changed (or force). Never runs unless the state is 'ready'."""
        with self._lock:
            if self.state != "ready" or not self.enabled:
                return False
            sig = self._signature()
            if not force and sig == self._sig:
                return False
            if not os.path.isfile(self.db_path):
                return False
            started = time.time()
            try:
                blob = make_snapshot(self.db_path, seq=self.seq + 1)
                slot = SLOTS[1] if self.newest_slot == SLOTS[0] else SLOTS[0]     # write the slot that is NOT the newest
                self.store.put(slot, blob)
            except StoreRejected as e:
                self._failed("storage refused the backup (file too large?): " + str(e)[:60])
                log.error("durable: BACKUP FAILED (#%d in a row): %s", self.failures_in_row, self.last_error)
                return False
            except (StoreUnavailable, sqlite3.Error, OSError) as e:
                self._failed("backup failed: " + e.__class__.__name__)
                log.warning("durable: BACKUP FAILED (#%d in a row): %s (will retry)", self.failures_in_row, self.last_error)
                return False
            self.newest_slot, self._sig, self.seq = slot, sig, self.seq + 1
            self.last_backup_at, self.last_backup_bytes, self.last_error = int(time.time()), len(blob), ""
            if self.failures_in_row:
                log.info("durable: backups work again after %d failed attempt(s)", self.failures_in_row)
            self.failures_in_row, self.last_error_at = 0, 0
            # R8: one line per good backup: version (seq), slot, size, time taken. Numbers only: never an address, key or file content.
            log.info("durable: BACKUP OK seq=%d slot=%s bytes=%d took=%.1fs", self.seq, slot, len(blob), time.time() - started)
            return True

    def _failed(self, text: str) -> None:
        self.last_error, self.last_error_at = text, int(time.time())
        self.failures_in_row += 1

    def _loop(self):
        while not self._stop.is_set():
            if self.state in ("restoring",):
                if self.restore_once() == "ready" and self.on_restored:
                    try:
                        self.on_restored()
                    except Exception:
                        log.exception("durable: could not prepare the restored database")
                self._stop.wait(self.retry_wait)
                continue
            if self.state == "blocked":
                self._stop.wait(self.retry_wait * 4)
                if self.restore_once() == "ready" and self.on_restored:
                    self.on_restored()
                continue
            self._wake.wait(self.min_interval)
            self._wake.clear()
            if self._stop.is_set():
                break
            self.backup_now()

    def start(self):
        if self.enabled and not self._thread:
            self._thread = threading.Thread(target=self._loop, name="sociax-durable", daemon=True)
            self._thread.start()

    def stop(self, flush: bool = True, timeout: float = 20.0):
        """Server is stopping (deploy / sleep): save the very last changes."""
        self._stop.set()
        self._wake.set()
        if flush and self.enabled and self.state == "ready":
            t = threading.Thread(target=lambda: self.backup_now(), daemon=True)
            t.start()
            t.join(timeout)
        if self._thread:
            self._thread.join(1.0)


def store_from_env(env=None):
    """SOCIAX_BACKUP = off | cloudinary | dir:<folder>. Unset: on Render with Cloudinary keys -> cloudinary, otherwise off."""
    env = os.environ if env is None else env
    mode = (env.get("SOCIAX_BACKUP") or "").strip()
    name, key, secret = env.get("CLOUDINARY_CLOUD_NAME"), env.get("CLOUDINARY_API_KEY"), env.get("CLOUDINARY_API_SECRET")
    if not mode:
        mode = "cloudinary" if (env.get("RENDER") and name and key and secret) else "off"
    if mode == "off":
        return None
    if mode.startswith("dir:"):
        return DirStore(mode[4:])
    if mode == "cloudinary":
        if not (name and key and secret):
            log.error("durable: SOCIAX_BACKUP=cloudinary but the Cloudinary settings are missing: backup is OFF")
            return None
        return CloudinaryStore(name, key, secret)
    log.error("durable: unknown SOCIAX_BACKUP value: backup is OFF")
    return None
