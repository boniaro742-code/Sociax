"""Step 54 (plan v3.9.2 section 3): the running server never shows an empty account while its saved database is not loaded.
Checks the wiring in main.py without starting a web server: the gate answers 503 `data_unavailable` for data routes (health stays
open), /health/config reports the state without any secret, and with backup OFF (the default outside Render) nothing changes.
Run:  cd backend && DEV_AUTH=1 python test_durable_gate.py"""
import asyncio
import json
import os
import re
import tempfile
import types

os.environ["DEV_AUTH"] = "1"
os.environ["SOCIAX_DB"] = os.path.join(tempfile.mkdtemp(), "t.db")
for k in ("RENDER", "SOCIAX_BACKUP"):
    os.environ.pop(k, None)
import main as m  # noqa: E402
import durable  # noqa: E402

# A fake secret VALUE: the report may name a setting (CLOUDINARY_API_SECRET) but must never contain this value.
SENTINEL = "SENTINEL-secret-value-8f3a91c2"
m.CLOUD_SECRET = SENTINEL
ALLOWED_SETTING_NAMES = {"CLOUDINARY_API_SECRET", "CHAPA_SECRET_KEY"}

# The version is NOT pinned to one number any more (a pin like "0.59.0" turned red on every release): it must only look like x.y.z.
assert re.fullmatch(r"\d+\.\d+\.\d+", m.API_VERSION), m.API_VERSION


def call(path):
    request = types.SimpleNamespace(url=types.SimpleNamespace(path=path))

    async def next_(_request):
        return "PASSED"

    return asyncio.run(m._saved_data_gate(request, next_))


# default (not on Render, no SOCIAX_BACKUP): backup is off, everything is served as before
assert m._durable.state == "off" and m._durable.serving
assert call("/users/me") == "PASSED"
assert m.health_ready().status_code == 200 and json.loads(m.health_ready().body)["ready"] is True      # R9: readiness is OK when backup is off
rep = m.config_report()
assert rep["durable_storage"]["enabled"] is False and rep["durable_storage"]["state"] == "off"
assert "SOCIAX_BACKUP" not in " ".join(rep["missing"])          # only a Render server is told to switch it on

# a server that has a store but has not loaded the saved database yet: 503 with a machine-readable code, health stays open
real_store, real_state = m._durable.store, m._durable.state
m._durable.store = durable.DirStore(tempfile.mkdtemp())
try:
    for state in ("restoring", "blocked"):
        m._durable.state = state
        r = call("/users/me")
        assert r.status_code == 503 and r.headers["retry-after"] == "30", state
        body = json.loads(r.body)
        assert body["detail"]["code"] == "data_unavailable" and "saved data" in body["detail"]["message"].lower()
        assert call("/health") == "PASSED" and call("/health/config") == "PASSED" and call("/health/ready") == "PASSED"
        rr = m.health_ready()                                                                          # R9: not ready while the saved data is not loaded
        assert rr.status_code == 503 and rr.headers["retry-after"] == "30" and json.loads(rr.body)["ready"] is False, state
        rep = m.config_report()
        assert rep["ok"] is False and any("saved data not loaded" in x for x in rep["missing"]), rep["missing"]
        assert rep["durable_storage"]["state"] == state
        text = json.dumps(rep)
        # R1 (v0.67.0 report): a setting NAME such as CLOUDINARY_API_SECRET may appear in "missing"; a secret VALUE never may.
        assert SENTINEL not in text, "a secret value leaked into /health/config"
        for word in re.findall(r"[A-Za-z0-9_]*secret[A-Za-z0-9_]*", text, flags=re.I):
            assert word in ALLOWED_SETTING_NAMES, "unexpected secret-looking text in /health/config: " + word
    m._durable.state = "ready"
    assert call("/users/me") == "PASSED" and m.health_ready().status_code == 200
    assert m.config_report()["durable_storage"]["state"] == "ready"
finally:
    m._durable.store, m._durable.state = real_store, real_state

# on Render without durable storage the report says so (the free disk is erased on every restart)
os.environ["RENDER"] = "true"
try:
    assert any("SOCIAX_BACKUP" in x for x in m.config_report()["missing"])
finally:
    os.environ.pop("RENDER", None)

print("test_durable_gate: all checks passed")
