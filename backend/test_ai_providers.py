"""Backend checks for the AI providers incl. Claude (Step 29). Run:  cd backend && python test_ai_providers.py
No internet needed: the HTTP calls are replaced by fakes."""
import os

for k in ("AI_PROVIDER_ORDER",):
    os.environ.pop(k, None)
import ai_providers as ap  # noqa: E402


def ok(msg): print("PASS", msg)


class FakeResp:
    def __init__(self, data, status=200): self._d, self.status = data, status
    def raise_for_status(self):
        if self.status >= 400: raise RuntimeError("http %d" % self.status)
    def json(self): return self._d


calls = []
class FakeHttpx:
    """Pretend servers: answers depend on the URL, failures are switched per provider."""
    fail = set()
    @staticmethod
    def post(url, headers=None, json=None, timeout=None):
        calls.append((url, headers, json))
        name = "grok" if "x.ai" in url else "gemini" if "googleapis" in url else "claude"
        if name in FakeHttpx.fail: return FakeResp({}, 500)
        if name == "grok": return FakeResp({"output_text": "hello from grok"})
        if name == "gemini": return FakeResp({"candidates": [{"content": {"parts": [{"text": "hello from gemini"}]}}]})
        return FakeResp({"content": [{"type": "text", "text": "hello "}, {"type": "text", "text": "from claude"}]})

ap.httpx = FakeHttpx
ap.RETRY_WAIT_S = 0

def setup(grok=False, gemini=False, claude=False):
    ap.XAI_KEY = "x" if grok else None
    ap.GEMINI_KEY = "g" if gemini else None
    ap.ANTHROPIC_KEY = "a" if claude else None
    FakeHttpx.fail = set(); calls.clear()

setup(grok=True, gemini=True, claude=True)
assert ap.configured() == {"grok": True, "gemini": True, "claude": True}
setup()
assert ap.configured() == {"grok": False, "gemini": False, "claude": False}
ok("configured() reports all three providers")

setup(grok=True, gemini=True, claude=True)
assert ap.ask("hi") == ("hello from grok", "grok") and len(calls) == 1
ok("default order: Grok answers first")

FakeHttpx.fail = {"grok"}
assert ap.ask("hi") == ("hello from gemini", "gemini")
FakeHttpx.fail = {"grok", "gemini"}
assert ap.ask("hi") == ("hello from claude", "claude")
ok("if Grok fails Gemini answers; if both fail Claude answers")

FakeHttpx.fail = {"grok", "gemini", "claude"}
try:
    ap.ask("hi"); raise AssertionError("should raise")
except ap.AiUnavailable:
    ok("all providers failing raises AiUnavailable")

setup(claude=True)
assert ap.ask("  question  ") == ("hello from claude", "claude")
url, headers, body = calls[0]
assert url == "https://api.anthropic.com/v1/messages"
assert headers["x-api-key"] == "a" and headers["anthropic-version"] == "2023-06-01"
assert body["model"] == ap.ANTHROPIC_MODEL and body["messages"] == [{"role": "user", "content": "question"}]
assert body["system"] == ap.SYSTEM_PROMPT and body["max_tokens"] > 0
ok("Claude request: right URL, key header, version, model, system prompt, trimmed question; text blocks joined")

setup(grok=True, gemini=True, claude=True)
os.environ["AI_PROVIDER_ORDER"] = "claude,grok"
assert ap.order() == ["claude", "grok", "gemini"]
assert ap.ask("hi")[1] == "claude"
os.environ["AI_PROVIDER_ORDER"] = " Gemini , nonsense, gemini "
assert ap.order() == ["gemini", "grok", "claude"]
os.environ.pop("AI_PROVIDER_ORDER")
assert ap.order() == ["grok", "gemini", "claude"]
ok("AI_PROVIDER_ORDER lets Claude lead; unknown / repeated names are ignored; missing ones are appended")

setup(grok=True, claude=True)
os.environ["AI_PROVIDER_ORDER"] = "gemini,claude,grok"
assert ap.ask("hi")[1] == "claude"      # Gemini has no key: skipped without a call
assert all("googleapis" not in c[0] for c in calls)
os.environ.pop("AI_PROVIDER_ORDER")
ok("a provider without a key is skipped")

big = "x" * 9000
class Big(FakeHttpx):
    @staticmethod
    def post(url, headers=None, json=None, timeout=None): return FakeResp({"content": [{"type": "text", "text": big}]})
ap.httpx = Big; setup(claude=True)
assert len(ap.ask("hi")[0]) == ap.MAX_ANSWER_CHARS
ok("answers are cut at the maximum length")
print("ALL PASS")
