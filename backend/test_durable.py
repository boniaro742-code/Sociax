"""Step 54 (plan v3.9.2): the saved database survives a restart / deploy, and an unreachable or damaged saved copy never
turns into an empty account. Uses only the standard library (no FastAPI needed), so it also runs on a bare Python.
Run:  cd backend && python test_durable.py"""
import json
import os
import sqlite3
import tempfile
import time

import durable
from durable import Durable, DirStore, StoreRejected, StoreUnavailable, make_snapshot, parse_snapshot, store_from_env


def tmp():
    return tempfile.mkdtemp()


def make_db(path, users=("u1",)):
    con = sqlite3.connect(path)
    con.execute("CREATE TABLE IF NOT EXISTS users(uid TEXT PRIMARY KEY, username TEXT)")
    for u in users:
        con.execute("INSERT OR REPLACE INTO users VALUES (?,?)", (u, "@" + u))
    con.commit()
    con.close()


def names(path):
    con = sqlite3.connect(path)
    try:
        return sorted(r[0] for r in con.execute("SELECT uid FROM users"))
    finally:
        con.close()


class FakeStore:
    """A store that can be switched to 'network down' and counts writes."""

    def __init__(self):
        self.slots, self.down, self.puts, self.reject = {}, False, 0, False

    def get(self, slot):
        if self.down:
            raise StoreUnavailable("down")
        return self.slots.get(slot)

    def put(self, slot, data):
        if self.down:
            raise StoreUnavailable("down")
        if self.reject:
            raise StoreRejected("too large")
        self.puts += 1
        self.slots[slot] = data

    def describe(self):
        return "fake"


# ---- 1. first ever start: confirmed "nothing saved" -> new database is allowed ----
st = FakeStore()
dbp = os.path.join(tmp(), "a.db")
d = Durable(dbp, st)
assert d.state == "restoring" and not d.serving
assert d.startup(attempts=1) == "ready" and d.restored_from == "nothing saved yet" and d.serving

# ---- 2. save, lose the disk (new empty folder), start again: the account is back ----
make_db(dbp, ("u1", "u2"))
assert d.backup_now() is True and d.newest_slot == "a" and d.seq == 1
assert d.backup_now() is False                      # nothing changed: no second upload
assert st.puts == 1
dbp2 = os.path.join(tmp(), "fresh-disk.db")         # a new machine: no file at all
d2 = Durable(dbp2, st)
assert d2.startup(attempts=1) == "ready" and d2.restored_from == "snapshot"
assert names(dbp2) == ["u1", "u2"], names(dbp2)

# ---- 3. slots alternate and the NEWEST wins even when the clock did not move ----
make_db(dbp, ("u1", "u2", "u3"))
assert d.backup_now() and d.newest_slot == "b" and d.seq == 2
make_db(dbp, ("u1", "u2", "u3", "u4"))
assert d.backup_now() and d.newest_slot == "a" and d.seq == 3
dbp3 = os.path.join(tmp(), "x.db")
d3 = Durable(dbp3, st)
d3.startup(attempts=1)
assert names(dbp3) == ["u1", "u2", "u3", "u4"] and d3.newest_slot == "a" and d3.seq == 3
make_db(dbp3, ("u1", "u2", "u3", "u4", "u5"))
assert d3.backup_now() and d3.newest_slot == "b" and d3.seq == 4       # it never overwrites the newest slot

# ---- 4. storage unreachable at start-up: NEVER an empty account, NEVER a backup over the good copy ----
before = dict(st.slots)
st.down = True
dbp4 = os.path.join(tmp(), "y.db")
d4 = Durable(dbp4, st)
assert d4.startup(attempts=2, pauses=(0.0,)) == "restoring" and not d4.serving
assert "unreachable" in d4.last_error
make_db(dbp4, ())                                   # the app creates an empty database meanwhile (init_db)
assert d4.backup_now(force=True) is False           # backups are closed in this state
st.down = False
assert st.slots == before and st.puts == 4          # the saved copies are byte-for-byte unchanged
restored = []
d4.on_restored = lambda: restored.append(1)
assert d4.restore_once() == "ready" and d4.serving and names(dbp4) == ["u1", "u2", "u3", "u4", "u5"]
assert d4.backup_now() is False                     # restored file == saved copy: nothing to upload

# ---- 5. a saved copy that exists but is damaged: one good slot is used; none good = blocked (503), not empty ----
st5 = FakeStore()
dbp5 = os.path.join(tmp(), "z.db")
make_db(dbp5, ("u1",))
src = Durable(dbp5, st5)
src.startup(attempts=1)
src.backup_now()                                    # slot a (seq 1)
make_db(dbp5, ("u1", "u2"))
src.backup_now()                                    # slot b (seq 2, newest)
st5.slots["b"] = st5.slots["b"][:-20] + b"\x00" * 20            # damage the newest
dbp6 = os.path.join(tmp(), "w.db")
d6 = Durable(dbp6, st5)
assert d6.startup(attempts=1) == "ready" and names(dbp6) == ["u1"] and d6.newest_slot == "a"   # older good copy
st5.slots["a"] = b"garbage"
dbp7 = os.path.join(tmp(), "v.db")
d7 = Durable(dbp7, st5)
assert d7.startup(attempts=1) == "blocked" and not d7.serving and not os.path.exists(dbp7)
before5 = dict(st5.slots)
make_db(dbp7, ())
assert d7.backup_now(force=True) is False and st5.slots == before5     # an empty database can not replace damaged data either

# ---- 6. header / hash tampering is detected ----
blob = make_snapshot(dbp)
head, raw = parse_snapshot(blob)
assert head["bytes"] == len(raw) and head["seq"] == 1
for bad in (b"hello", blob.replace(b'"bytes":', b'"bytes":9', 1), blob[:-30]):
    try:
        parse_snapshot(bad)
        raise AssertionError("tampered snapshot was accepted")
    except AssertionError:
        raise
    except Exception:
        pass

# ---- 7. an existing local file is used as it is (development / a disk that really persists) ----
dbp8 = os.path.join(tmp(), "local.db")
make_db(dbp8, ("only-here",))
d8 = Durable(dbp8, st)
assert d8.startup(attempts=1) == "ready" and d8.restored_from == "local file" and names(dbp8) == ["only-here"]

# ---- 8. a refused upload (file too large) is reported, never crashes, never changes the state ----
st9 = FakeStore()
dbp9 = os.path.join(tmp(), "big.db")
make_db(dbp9, ("u1",))
d9 = Durable(dbp9, st9)
d9.startup(attempts=1)
st9.reject = True
assert d9.backup_now() is False and "refused" in d9.last_error and d9.state == "ready"
st9.reject = False
assert d9.backup_now() is True and d9.last_error == ""

# ---- 9. a normal stop saves the very last change ----
make_db(dbp9, ("u1", "last-second"))
d9.stop(flush=True, timeout=10)
assert st9.puts == 2
dbp10 = os.path.join(tmp(), "after-stop.db")
Durable(dbp10, st9).startup(attempts=1)
assert names(dbp10) == ["last-second", "u1"]

# ---- 10. a late restore (storage came back after start-up) calls the restart hook from the background loop ----
st11 = FakeStore()
dbp11 = os.path.join(tmp(), "late.db")
good = os.path.join(tmp(), "good.db")
make_db(good, ("kept",))
seed = Durable(good, st11)
seed.startup(attempts=1)
seed.backup_now()
st11.down = True
d11 = Durable(dbp11, st11, retry_wait=0.05)
d11.startup(attempts=1)
assert d11.state == "restoring"
hit = []
d11.on_restored = lambda: hit.append(1)
d11.start()
st11.down = False
for _ in range(100):
    if hit:
        break
    time.sleep(0.05)
d11.stop(flush=False)
assert hit and names(dbp11) == ["kept"]

# ---- 11. folder store, report (no secrets) and the settings that switch it on ----
ds = DirStore(os.path.join(tmp(), "folder"))
assert ds.get("a") is None
ds.put("a", b"x")
assert ds.get("a") == b"x"
rep = d9.report()
assert set(rep) == {"enabled", "store", "state", "restored_from", "last_backup_age_s", "last_backup_bytes", "last_error"}
assert store_from_env({}) is None and store_from_env({"SOCIAX_BACKUP": "off"}) is None
assert store_from_env({"RENDER": "true"}) is None                                          # no keys: stays off
cl = store_from_env({"RENDER": "true", "CLOUDINARY_CLOUD_NAME": "c", "CLOUDINARY_API_KEY": "k", "CLOUDINARY_API_SECRET": "s"})
assert cl is not None and cl.describe() == "cloudinary"
assert "secret" not in json.dumps(Durable("x", cl).report()).lower()
assert store_from_env({"CLOUDINARY_CLOUD_NAME": "c", "CLOUDINARY_API_KEY": "k", "CLOUDINARY_API_SECRET": "s"}) is None   # not on Render: off
assert store_from_env({"SOCIAX_BACKUP": "cloudinary"}) is None                                # asked for, but keys missing: off + logged
assert isinstance(store_from_env({"SOCIAX_BACKUP": "dir:" + tmp()}), DirStore)
off = Durable("x.db", None)
assert off.state == "off" and off.serving and off.startup() == "off" and off.backup_now() is False

print("test_durable: all checks passed")
