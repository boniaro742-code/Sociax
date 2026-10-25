#!/usr/bin/env python3
"""Step 48: writes the PARITY vectors that prove the Go ranker (backend-go) orders exactly like backend/ranker.py.
Seeded, so the file is the same every time. Run it again (and commit the result) after ANY change to backend/ranker.py:
    python tools/gen_rank_parity.py
Then `cd backend-go && go test ./...` compares both languages on the same inputs.
    python tools/gen_rank_parity.py --check     (CI) re-ranks the SAVED inputs with ranker.py and says whether the saved answers are still right"""
import json, pathlib, random, sys

root = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(root / "backend"))
import ranker  # noqa: E402

OUT = root / "backend-go/internal/ranker/testdata/parity.json"


def item(rng, i, creators, tie=False):
    if tie:                                    # identical signals: only the id can break the tie
        return dict(id=i, creator=rng.choice(creators), age_seconds=3600.0, interest_points=0.0, watch_avg=None,
                    watch_viewers=0, engage_points=0.0, followed=False, seen_age_seconds=None, skipped=False, reporters=0)
    return dict(
        id=i, creator=rng.choice(creators),
        age_seconds=rng.choice([0.0, rng.uniform(0, 3600), rng.uniform(0, 86400 * 3), rng.uniform(0, 86400 * 60)]),
        interest_points=rng.choice([0.0, 0.0, rng.uniform(0, 30)]),
        watch_avg=rng.choice([None, None, rng.random()]), watch_viewers=rng.choice([0, 1, 2, 3, 5, 40]),
        engage_points=rng.choice([0.0, 1.0, 2.0, rng.uniform(0, 80), float(rng.randint(0, 120))]),
        followed=rng.random() < 0.25,
        seen_age_seconds=rng.choice([None, None, rng.uniform(0, 48 * 3600), rng.uniform(48 * 3600, 14 * 86400), rng.uniform(14 * 86400, 90 * 86400)]),
        skipped=rng.random() < 0.15, reporters=rng.choice([0, 0, 0, 1, 2, 5]))


def check():
    data = json.loads(OUT.read_text(encoding="utf-8"))
    if data["ranker"] != ranker.VERSION:
        print(f"parity file is for ranker {data['ranker']!r}, ranker.py is {ranker.VERSION!r}: run tools/gen_rank_parity.py"); return 1
    bad = [k for k, c in enumerate(data["cases"])
           if ranker.order([ranker.Item(**it) for it in c["items"]], c["weights"]) != c["expected"]]
    if bad:
        print(f"ranker.py no longer gives the saved answers (cases {bad[:10]}): run `python tools/gen_rank_parity.py`, run the Go tests, commit parity.json")
        return 1
    print(f"parity file is current ({len(data['cases'])} cases)")
    return 0


def main():
    if "--check" in sys.argv:
        sys.exit(check())
    rng = random.Random(4809)
    cases = []
    weight_sets = [None, "interest=0.4,watch=0.2,fresh=0.1,engage=0.1,follow=0.1,diversity=0.1",
                   "interest=0.0,watch=0.0,fresh=1.0,engage=0.0,follow=0.0,diversity=0.0",
                   "interest=0.1,watch=0.1,fresh=0.1,engage=0.1,follow=0.1,diversity=0.5"]
    for n in range(0, 12):                                        # tiny lists, including empty and single
        creators = [f"c{k}" for k in range(rng.randint(1, 4))]
        cases.append(("tiny", n, creators, False))
    for n in (5, 6, 10, 11, 15, 20, 30):                           # the exploration slots (every 5th) and the run limit
        cases.append(("explore", n, [f"c{k}" for k in range(rng.randint(2, 6))], False))
    for n in (8, 25, 40):                                          # everything tied: the id decides
        cases.append(("ties", n, [f"c{k}" for k in range(3)], True))
    for n in (30, 30, 30, 30):                                     # ONE creator only: the run limit cannot be applied
        cases.append(("one-creator", n, ["solo"], False))
    for _ in range(150):                                           # the general case
        cases.append(("random", rng.randint(2, 60), [f"c{k}" for k in range(rng.randint(1, 25))], False))
    for _ in range(6):                                             # the real size of one request (RANK_POOL = 300)
        cases.append(("pool", 300, [f"c{k}" for k in range(rng.randint(5, 120))], False))

    out = []
    for k, (kind, n, creators, tie) in enumerate(cases):
        ids = rng.sample(range(1, 100000), n)
        items = [item(rng, i, creators, tie) for i in ids]
        wtxt = weight_sets[k % len(weight_sets)]
        weights = ranker.parse_weights(wtxt)
        its = [ranker.Item(**it) for it in items]
        out.append({"kind": kind, "weights": weights, "items": items, "expected": ranker.order(its, weights)})
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps({"ranker": ranker.VERSION, "cases": out}, separators=(",", ":")), encoding="utf-8")
    print(f"wrote {len(out)} cases to {OUT.relative_to(root)} ({OUT.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    main()
