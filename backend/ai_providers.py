"""Sociax AI providers (plan section 2 + 7): Grok leads, Gemini assists, Claude (Anthropic) is the third provider.

- Keys come ONLY from environment variables (XAI_API_KEY, GEMINI_API_KEY, ANTHROPIC_API_KEY). They never go to the app,
  never to the logs, never to the repository.
- Default order: Grok, then Gemini, then Claude. The first configured provider that answers wins; if it is missing
  or fails, the next one answers (assist / fallback). Change the order with AI_PROVIDER_ORDER, e.g. "claude,grok,gemini".
- Claude was added at the product owner's request (plan v3.0 section 2 said Grok + Gemini only for the first version).
- Prompts and answers are never logged.
"""
import logging
import os
import time

import httpx

log = logging.getLogger("sociax.ai")     # logs only the provider name + a failure CATEGORY: never the prompt, the answer or a key

XAI_KEY = os.getenv("XAI_API_KEY")
GEMINI_KEY = os.getenv("GEMINI_API_KEY")
ANTHROPIC_KEY = os.getenv("ANTHROPIC_API_KEY")
XAI_MODEL = os.getenv("XAI_MODEL", "grok-4.6")
GEMINI_MODEL = os.getenv("GEMINI_MODEL", "gemini-2.5-flash")
ANTHROPIC_MODEL = os.getenv("ANTHROPIC_MODEL", "claude-sonnet-5-5")
DEFAULT_ORDER = ("grok", "gemini", "claude")
TIMEOUT_S = 30.0
TOTAL_DEADLINE_S = float(os.getenv("AI_TOTAL_DEADLINE_S", "50"))   # no new provider is started after this many seconds
RETRY_WAIT_S = 0.6                                                  # one quick retry for a connection / 5xx failure
MAX_ANSWER_CHARS = 4000

SYSTEM_PROMPT = (
    "You are Sociax AI, a helpful assistant inside a social app. Answer clearly and briefly "
    "(under 250 words unless asked for more). Reply in the language of the question. "
    "Do not ask for passwords or personal data."
)


class AiUnavailable(Exception):
    """No provider is configured, or every configured provider failed. `reasons` = {provider: failure category}."""

    def __init__(self, reasons: dict | None = None):
        super().__init__("AI unavailable")
        self.reasons = reasons or {}


def reason_of(e: Exception) -> str:
    """A short, safe failure category for the server log (plan v3.6 section 3): http_401 (bad key), http_429 (provider rate
    limit / quota), http_5xx, timeout, bad_format (answer not understood) or network. Never contains text from the request."""
    code = getattr(getattr(e, "response", None), "status_code", None)
    if isinstance(code, int):
        return f"http_{code}"
    if "Timeout" in type(e).__name__:
        return "timeout"
    if isinstance(e, (ValueError, KeyError, TypeError)):
        return "bad_format"
    return "network"


def configured() -> dict:
    return {"grok": bool(XAI_KEY), "gemini": bool(GEMINI_KEY), "claude": bool(ANTHROPIC_KEY)}


def order() -> list:
    """Provider order from AI_PROVIDER_ORDER (unknown names are ignored, missing ones are added at the end)."""
    wanted = [x.strip().lower() for x in os.getenv("AI_PROVIDER_ORDER", "").split(",") if x.strip()]
    result = [x for x in wanted if x in DEFAULT_ORDER]
    result = list(dict.fromkeys(result))
    return result + [x for x in DEFAULT_ORDER if x not in result]


def _ask_grok(prompt: str) -> str:
    r = httpx.post(
        "https://api.x.ai/v1/responses",
        headers={"Authorization": f"Bearer {XAI_KEY}", "Content-Type": "application/json"},
        json={"model": XAI_MODEL,
              "input": [{"role": "system", "content": SYSTEM_PROMPT},
                        {"role": "user", "content": prompt}]},
        timeout=TIMEOUT_S,
    )
    r.raise_for_status()
    data = r.json()
    if isinstance(data.get("output_text"), str) and data["output_text"].strip():
        return data["output_text"].strip()
    parts = []
    for item in data.get("output", []):
        if item.get("type") == "message":
            for c in item.get("content", []):
                if c.get("type") in ("output_text", "text") and c.get("text"):
                    parts.append(c["text"])
    text = "".join(parts).strip()
    if not text:
        raise ValueError("empty Grok answer")
    return text


def _ask_gemini(prompt: str) -> str:
    r = httpx.post(
        f"https://generativelanguage.googleapis.com/v1beta/models/{GEMINI_MODEL}:generateContent",
        headers={"x-goog-api-key": GEMINI_KEY, "Content-Type": "application/json"},
        json={"systemInstruction": {"parts": [{"text": SYSTEM_PROMPT}]},
              "contents": [{"role": "user", "parts": [{"text": prompt}]}]},
        timeout=TIMEOUT_S,
    )
    r.raise_for_status()
    cands = r.json().get("candidates") or []
    parts = (cands[0].get("content", {}).get("parts", []) if cands else [])
    text = "".join(p.get("text", "") for p in parts).strip()
    if not text:
        raise ValueError("empty Gemini answer")
    return text


def _ask_claude(prompt: str) -> str:
    r = httpx.post(
        "https://api.anthropic.com/v1/messages",
        headers={"x-api-key": ANTHROPIC_KEY, "anthropic-version": "2023-06-01", "content-type": "application/json"},
        json={"model": ANTHROPIC_MODEL, "max_tokens": 1024, "system": SYSTEM_PROMPT,
              "messages": [{"role": "user", "content": prompt}]},
        timeout=TIMEOUT_S,
    )
    r.raise_for_status()
    blocks = r.json().get("content") or []
    text = "".join(b.get("text", "") for b in blocks if b.get("type") == "text").strip()
    if not text:
        raise ValueError("empty Claude answer")
    return text


def _call(name: str, prompt: str) -> str:
    if name == "grok":
        return _ask_grok(prompt)
    if name == "gemini":
        return _ask_gemini(prompt)
    return _ask_claude(prompt)


def _is_configured(name: str) -> bool:
    return bool({"grok": XAI_KEY, "gemini": GEMINI_KEY, "claude": ANTHROPIC_KEY}[name])


def ask(prompt: str) -> tuple[str, str]:
    """Returns (answer, provider). Raises AiUnavailable (with the failure categories) if nothing could answer.
    Bounded: one quick retry only for connection / server (5xx) failures, never for a bad key or a rate limit,
    and no new provider is started after TOTAL_DEADLINE_S."""
    prompt = prompt.strip()[:2000]
    started = time.monotonic()
    reasons: dict = {}
    for name in order():
        if not _is_configured(name):
            continue
        if time.monotonic() - started > TOTAL_DEADLINE_S:
            reasons[name] = "skipped_deadline"
            continue
        for attempt in (1, 2):
            try:
                return _call(name, prompt)[:MAX_ANSWER_CHARS], name
            except Exception as e:  # try the next provider; never log the prompt, the answer or the key
                why = reason_of(e)
                reasons[name] = why
                log.warning("AI provider %s failed (%s)%s", name, why, " - retrying" if attempt == 1 and why in ("network", "http_500", "http_502", "http_503", "http_504") else "")
                if attempt == 1 and why in ("network", "http_500", "http_502", "http_503", "http_504"):
                    time.sleep(RETRY_WAIT_S)
                    continue
                break
    raise AiUnavailable(reasons)
