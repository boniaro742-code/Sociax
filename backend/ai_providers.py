"""Sociax AI providers (plan section 2 + 7): Grok leads, Gemini assists, Claude (Anthropic) is the third provider.

- Keys come ONLY from environment variables (XAI_API_KEY, GEMINI_API_KEY, ANTHROPIC_API_KEY). They never go to the app,
  never to the logs, never to the repository.
- Default order: Grok, then Gemini, then Claude. The first configured provider that answers wins; if it is missing
  or fails, the next one answers (assist / fallback). Change the order with AI_PROVIDER_ORDER, e.g. "claude,grok,gemini".
- Claude was added at the product owner's request (plan v3.0 section 2 said Grok + Gemini only for the first version).
- Prompts and answers are never logged.
"""
import os

import httpx

XAI_KEY = os.getenv("XAI_API_KEY")
GEMINI_KEY = os.getenv("GEMINI_API_KEY")
ANTHROPIC_KEY = os.getenv("ANTHROPIC_API_KEY")
XAI_MODEL = os.getenv("XAI_MODEL", "grok-4.6")
GEMINI_MODEL = os.getenv("GEMINI_MODEL", "gemini-2.5-flash")
ANTHROPIC_MODEL = os.getenv("ANTHROPIC_MODEL", "claude-sonnet-5-5")
DEFAULT_ORDER = ("grok", "gemini", "claude")
TIMEOUT_S = 30.0
MAX_ANSWER_CHARS = 4000

SYSTEM_PROMPT = (
    "You are Sociax AI, a helpful assistant inside a social app. Answer clearly and briefly "
    "(under 250 words unless asked for more). Reply in the language of the question. "
    "Do not ask for passwords or personal data."
)


class AiUnavailable(Exception):
    """No provider is configured, or every configured provider failed."""


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


def ask(prompt: str) -> tuple[str, str]:
    """Returns (answer, provider). Raises AiUnavailable if nothing could answer."""
    prompt = prompt.strip()[:2000]
    for name in order():
        try:
            if name == "grok" and XAI_KEY:
                return _ask_grok(prompt)[:MAX_ANSWER_CHARS], "grok"
            if name == "gemini" and GEMINI_KEY:
                return _ask_gemini(prompt)[:MAX_ANSWER_CHARS], "gemini"
            if name == "claude" and ANTHROPIC_KEY:
                return _ask_claude(prompt)[:MAX_ANSWER_CHARS], "claude"
        except Exception:
            pass  # try the next provider; never log the prompt or the key
    raise AiUnavailable()
