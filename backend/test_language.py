"""Step 37 (plan v3.5 section 24): the language is also kept on the account, only the five plan languages are accepted,
and a missing value stays 'never saved' (the phone's own choice then wins).
Run:  cd backend && DEV_AUTH=1 python test_language.py"""
import os, tempfile

os.environ["DEV_AUTH"] = "1"
os.environ["SOCIAX_DB"] = os.path.join(tempfile.mkdtemp(), "t.db")
import main as m  # noqa: E402
from fastapi import HTTPException  # noqa: E402
from pydantic import ValidationError  # noqa: E402

A, B, C = "dev-a", "dev-b", "dev-c"
assert m.LANGUAGES == ("en", "fr", "zh", "ko", "ru")

m.onboard(m.Onboarding(display_name="alice", username="alice", age=20), A)                       # old app: no language sent
assert m.me(A)["language"] is None
m.onboard(m.Onboarding(display_name="ben", username="bobby", age=20, language="ko"), B)         # chosen on the opening screen
assert m.me(B)["language"] == "ko"
m.onboard(m.Onboarding(display_name="cara", username="cara", age=20, language="xx"), C)         # unknown value is ignored, not an error
assert m.me(C)["language"] is None
print("PASS onboarding keeps the opening-screen language; unknown / missing stays 'never saved'")

assert m.update_me(m.ProfileUpdate(language="fr"), A)["language"] == "fr"
assert m.me(A)["language"] == "fr"
assert m.update_me(m.ProfileUpdate(language="ru"), A)["language"] == "ru"
for bad in ("de", "EN", "english", ""):
    try:
        m.ProfileUpdate(language=bad); raise AssertionError(f"{bad!r} must be refused")
    except ValidationError:
        pass
# changing the language touches nothing else
before = m.me(A)
m.update_me(m.ProfileUpdate(language="zh"), A)
after = m.me(A)
assert {k: v for k, v in before.items() if k != "language"} == {k: v for k, v in after.items() if k != "language"}
print("PASS language can be changed, only the five codes are accepted, nothing else changes")

# other people never see my language
assert "language" not in m.user_profile("alice", B)
print("PASS language is part of my own profile only")

# response time header helper exists and slow-request settings are sane
assert m.SLOW_REQUEST_MS > 0
print("test_language: all checks passed")
