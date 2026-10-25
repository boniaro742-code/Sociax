"""Sociax For You ranker v1 (plan v3.9 section 31.2). PURE code: no database, no network, so it can be tested alone.

The caller (main.py) has ALREADY removed everything the viewer may not see (private account, followers-only, blocked,
moderation, hidden, reported by the viewer). This module only puts the remaining items in order: a high score can never
bring back something that was filtered out, because filtered items never reach this module.

score = W_interest*Interest + W_watch*WatchQuality + W_fresh*Freshness + W_engage*Engagement + W_follow*FollowRelevance
        + W_diversity*Diversity - RepeatPenalty - QualityPenalty          (every positive signal is on a 0..100 scale)

The starting weights (30 / 20 / 15 / 15 / 10 / 10) are product hypotheses from the plan, NOT proven values. They can be
changed without code through the RANK_WEIGHTS setting and must only be tuned from measured results.
"""
import logging
import math
from dataclasses import dataclass

VERSION = "v1"
_log = logging.getLogger("sociax.ranker")

DEFAULT_WEIGHTS = {"interest": 0.30, "watch": 0.20, "fresh": 0.15, "engage": 0.15, "follow": 0.10, "diversity": 0.10}

NEUTRAL = 50.0                 # a signal we know nothing about is neither good nor bad (new content gets a fair chance)
FRESH_HALF_LIFE_H = 48.0       # freshness halves every 48 hours: a gradual decline, never a cliff
ENGAGE_FULL_POINTS = 50        # this many weighted engagement points = 100 on the engagement scale (log scale)
INTEREST_K = 6.0               # interest points at which the interest signal is 50
WATCH_MIN_VIEWERS = 3          # fewer different viewers than this = not enough evidence, so neutral
ACCIDENTAL_SKIP_MS = 1000      # a skip before 1 s is an accident and says nothing

# points a viewer's OWN earlier actions on a creator's content give to "interest in that creator" (no topic tags exist yet)
INTEREST_POINTS = {"like": 3.0, "save": 4.0, "comment": 4.0, "share": 4.0, "complete": 2.0}

REPEAT_SEEN_48H = 40.0         # the viewer already saw this item in the last 48 h
REPEAT_SEEN_14D = 15.0         # ... in the last 14 days
REPEAT_SKIPPED = 25.0          # ... and skipped it
QUALITY_PER_REPORTER = 10.0    # each different person who reported it (reports are SIGNALS, not proof)
QUALITY_MAX = 30.0

EXPLORE_EVERY = 5              # every 5th slot goes to a new / small creator when one is eligible
EXPLORE_MAX_ENGAGE_POINTS = 3  # "new / small": hardly any engagement yet
MAX_SAME_CREATOR_RUN = 2       # never more than 2 in a row from one creator (when anything else exists)


def parse_weights(text: str | None) -> dict:
    """RANK_WEIGHTS="interest=0.30,watch=0.20,fresh=0.15,engage=0.15,follow=0.10,diversity=0.10".
    Anything wrong (missing key, negative, not a number, total not 1.0) -> the plan's default weights, and a warning (no values)."""
    if not text or not text.strip():
        return dict(DEFAULT_WEIGHTS)
    try:
        w = {}
        for part in text.split(","):
            k, v = part.split("=")
            w[k.strip()] = float(v)
        if set(w) != set(DEFAULT_WEIGHTS) or any(x < 0 or math.isnan(x) for x in w.values()) or abs(sum(w.values()) - 1.0) > 0.011:
            raise ValueError("bad weights")
        return w
    except (ValueError, TypeError):
        _log.warning("RANK_WEIGHTS is invalid: using the default weights")
        return dict(DEFAULT_WEIGHTS)


# ---- the six positive signals, each normalised to 0..100 ----
def freshness(age_seconds: float) -> float:
    age_h = max(age_seconds, 0.0) / 3600.0
    return 100.0 * 0.5 ** (age_h / FRESH_HALF_LIFE_H)


def engagement(points: float) -> float:
    """points = likes + 2*saves + 2*different commenters + 2*different sharers, each counted ONCE per person and never from the creator."""
    return 100.0 * min(1.0, math.log1p(max(points, 0.0)) / math.log1p(ENGAGE_FULL_POINTS))


def interest(points: float) -> float:
    """The viewer's own positive actions on this creator, saturating (a lot of history is not 'infinitely' interested)."""
    p = max(points, 0.0)
    return 100.0 * p / (p + INTEREST_K)


def watch_ratio(event: str, value_ms: int | None, duration_s: float | None) -> float | None:
    """One event -> how much of the clip was enjoyed (0..1), normalised for the clip's length. None = says nothing.
    Plan v3.9.1: a clip's length can be unknown (None). A 'complete' event still counts, but a watched / skipped
    fraction cannot be computed without a length, so those events say nothing (no length is ever invented)."""
    if event == "complete":
        return 1.0
    if duration_s is None or duration_s <= 0:
        return None
    dur_ms = duration_s * 1000.0
    if event == "watch":
        return min(1.0, (value_ms or 0) / dur_ms) if value_ms else None
    if event == "skip":
        if value_ms is None or value_ms < ACCIDENTAL_SKIP_MS:
            return None            # an accidental skip must not punish the video
        return 0.5 * min(1.0, value_ms / dur_ms)    # left early: at most half credit
    return None


def watch_quality(avg_ratio: float | None, viewers: int) -> float:
    if avg_ratio is None or viewers < WATCH_MIN_VIEWERS:
        return NEUTRAL
    return 100.0 * max(0.0, min(1.0, avg_ratio))


def repeat_penalty(seen_age_seconds: float | None, skipped: bool) -> float:
    p = 0.0
    if seen_age_seconds is not None:
        if seen_age_seconds < 48 * 3600:
            p += REPEAT_SEEN_48H
        elif seen_age_seconds < 14 * 86400:
            p += REPEAT_SEEN_14D
    if skipped:
        p += REPEAT_SKIPPED
    return p


def quality_penalty(reporters: int) -> float:
    return min(QUALITY_MAX, QUALITY_PER_REPORTER * max(reporters, 0))


def diversity(already_from_creator: int) -> float:
    """100 for a creator not shown yet in this list, then 50, 33, 25 ... (computed while the list is being built)."""
    return 100.0 / (1 + max(already_from_creator, 0))


@dataclass
class Item:
    id: int
    creator: str
    age_seconds: float
    interest_points: float = 0.0
    watch_avg: float | None = None
    watch_viewers: int = 0
    engage_points: float = 0.0
    followed: bool = False
    seen_age_seconds: float | None = None
    skipped: bool = False
    reporters: int = 0

    @property
    def explore(self) -> bool:
        """A new / small creator the viewer has no history with: eligible for a protected exploration slot."""
        return (self.engage_points < EXPLORE_MAX_ENGAGE_POINTS and not self.followed and self.interest_points == 0)


def signals(it: Item) -> dict:
    return {"interest": interest(it.interest_points), "watch": watch_quality(it.watch_avg, it.watch_viewers),
            "fresh": freshness(it.age_seconds), "engage": engagement(it.engage_points),
            "follow": 100.0 if it.followed else 0.0}


def base_score(it: Item, w: dict) -> float:
    """Everything except diversity (that one depends on what is already in the list)."""
    s = signals(it)
    pos = w["interest"] * s["interest"] + w["watch"] * s["watch"] + w["fresh"] * s["fresh"] \
        + w["engage"] * s["engage"] + w["follow"] * s["follow"]
    return pos - repeat_penalty(it.seen_age_seconds, it.skipped) - quality_penalty(it.reporters)


def order(items: list, w: dict, explore_every: int = EXPLORE_EVERY, max_run: int = MAX_SAME_CREATOR_RUN) -> list:
    """Returns item ids, best first. Greedy: at every position take the best remaining item, where the diversity part
    depends on how many items of that creator are already in the list. Ties go to the newer item (higher id), so the
    result is the same every time for the same input."""
    base = {it.id: base_score(it, w) for it in items}
    remaining = list(items)
    chosen, counts = [], {}
    while remaining:
        pos = len(chosen) + 1
        pool = remaining
        if max_run and len(chosen) >= max_run:
            last = {c.creator for c in chosen[-max_run:]}
            if len(last) == 1:
                other = [it for it in remaining if it.creator not in last]
                pool = other or remaining
        if explore_every and pos % explore_every == 0:
            fresh_creators = [it for it in pool if it.explore and counts.get(it.creator, 0) == 0]
            pool = fresh_creators or pool
        best = max(pool, key=lambda it: (base[it.id] + w["diversity"] * diversity(counts.get(it.creator, 0)), it.id))
        chosen.append(best)
        remaining.remove(best)
        counts[best.creator] = counts.get(best.creator, 0) + 1
    return [it.id for it in chosen]
