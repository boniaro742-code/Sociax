// Package ranker is the Go port of backend/ranker.py (Sociax For You ranker v1, plan v3.9 section 31.2).
//
// It is PURE code: no database, no network. The Python backend has ALREADY removed everything the viewer may not see
// (private account, followers-only, blocked, moderation, hidden, reported by the viewer) before it sends candidates here,
// and this package can only put those same candidates in a different ORDER: it can never add an item, so a high score can
// never bring back something that was filtered out.
//
//	score = W_interest*Interest + W_watch*Watch + W_fresh*Fresh + W_engage*Engage + W_follow*Follow
//	        + W_diversity*Diversity - RepeatPenalty - QualityPenalty        (every positive signal is on a 0..100 scale)
//
// Every formula and constant below is copied from ranker.py on purpose. If you change one, change the other, then run
// `python tools/gen_rank_parity.py` and `go test ./...` (the parity test compares both languages on the same inputs).
package ranker

import (
	"math"
	"strconv"
	"strings"
)

// Version is the ranker VERSION string of ranker.py ("v1"): the two languages implement the same ranking.
const Version = "v1"

const (
	Neutral             = 50.0 // a signal we know nothing about is neither good nor bad
	FreshHalfLifeH      = 48.0 // freshness halves every 48 hours
	EngageFullPoints    = 50.0 // this many weighted engagement points = 100 (log scale)
	InterestK           = 6.0  // interest points at which the interest signal is 50
	WatchMinViewers     = 3    // fewer different viewers than this = not enough evidence
	RepeatSeen48h       = 40.0 // the viewer already saw this item in the last 48 h
	RepeatSeen14d       = 15.0 // ... in the last 14 days
	RepeatSkipped       = 25.0 // ... and skipped it
	QualityPerReporter  = 10.0 // each different person who reported it (a SIGNAL, not proof)
	QualityMax          = 30.0
	ExploreEvery        = 5   // every 5th slot goes to a new / small creator when one is eligible
	ExploreMaxEngagePts = 3.0 // "new / small": hardly any engagement yet
	MaxSameCreatorRun   = 2   // never more than 2 in a row from one creator (when anything else exists)
)

// Weights are the six starting weights of the plan (30 / 20 / 15 / 15 / 10 / 10). They are product hypotheses, not proven values.
type Weights struct {
	Interest  float64 `json:"interest"`
	Watch     float64 `json:"watch"`
	Fresh     float64 `json:"fresh"`
	Engage    float64 `json:"engage"`
	Follow    float64 `json:"follow"`
	Diversity float64 `json:"diversity"`
}

// DefaultWeights returns the plan's starting weights.
func DefaultWeights() Weights {
	return Weights{Interest: 0.30, Watch: 0.20, Fresh: 0.15, Engage: 0.15, Follow: 0.10, Diversity: 0.10}
}

// Valid is the same rule as ranker.parse_weights: no negative or non-finite value and a total of 1.0 (+- 0.011).
func (w Weights) Valid() bool {
	all := [6]float64{w.Interest, w.Watch, w.Fresh, w.Engage, w.Follow, w.Diversity}
	sum := 0.0
	for _, x := range all {
		if math.IsNaN(x) || math.IsInf(x, 0) || x < 0 {
			return false
		}
		sum += x
	}
	return math.Abs(sum-1.0) <= 0.011
}

// ParseWeights reads "interest=0.30,watch=0.20,...". Anything wrong (missing or extra key, negative, not a number, total not 1.0)
// gives the default weights and ok=false. Empty text gives the defaults and ok=true (nothing was configured, nothing is wrong).
func ParseWeights(text string) (w Weights, ok bool) {
	if strings.TrimSpace(text) == "" {
		return DefaultWeights(), true
	}
	got := map[string]float64{}
	for _, part := range strings.Split(text, ",") {
		kv := strings.Split(part, "=")
		if len(kv) != 2 {
			return DefaultWeights(), false
		}
		v, err := strconv.ParseFloat(strings.TrimSpace(kv[1]), 64)
		if err != nil {
			return DefaultWeights(), false
		}
		got[strings.TrimSpace(kv[0])] = v
	}
	if len(got) != 6 {
		return DefaultWeights(), false
	}
	var parsed Weights
	for k, v := range got {
		switch k {
		case "interest":
			parsed.Interest = v
		case "watch":
			parsed.Watch = v
		case "fresh":
			parsed.Fresh = v
		case "engage":
			parsed.Engage = v
		case "follow":
			parsed.Follow = v
		case "diversity":
			parsed.Diversity = v
		default:
			return DefaultWeights(), false
		}
	}
	if !parsed.Valid() {
		return DefaultWeights(), false
	}
	return parsed, true
}

// ---- the six positive signals, each normalised to 0..100 ----

// Freshness halves every 48 hours: a gradual decline, never a cliff.
func Freshness(ageSeconds float64) float64 {
	ageH := math.Max(ageSeconds, 0.0) / 3600.0
	return 100.0 * math.Pow(0.5, ageH/FreshHalfLifeH)
}

// Engagement: points = likes + 2*saves + 2*different commenters + 2*different sharers (counted once per person by the caller).
func Engagement(points float64) float64 {
	return 100.0 * math.Min(1.0, math.Log1p(math.Max(points, 0.0))/math.Log1p(EngageFullPoints))
}

// Interest: the viewer's own positive actions on this creator, saturating.
func Interest(points float64) float64 {
	p := math.Max(points, 0.0)
	return 100.0 * p / (p + InterestK)
}

// WatchQuality is neutral without enough evidence (no average, or fewer than three different viewers).
func WatchQuality(avg *float64, viewers int) float64 {
	if avg == nil || viewers < WatchMinViewers {
		return Neutral
	}
	return 100.0 * math.Max(0.0, math.Min(1.0, *avg))
}

// RepeatPenalty lowers what the viewer already saw (and more if they skipped it).
func RepeatPenalty(seenAgeSeconds *float64, skipped bool) float64 {
	p := 0.0
	if seenAgeSeconds != nil {
		if *seenAgeSeconds < 48*3600 {
			p += RepeatSeen48h
		} else if *seenAgeSeconds < 14*86400 {
			p += RepeatSeen14d
		}
	}
	if skipped {
		p += RepeatSkipped
	}
	return p
}

// QualityPenalty: reports are signals, not proof, and the penalty is capped.
func QualityPenalty(reporters int) float64 {
	n := reporters
	if n < 0 {
		n = 0
	}
	return math.Min(QualityMax, QualityPerReporter*float64(n))
}

// Diversity is 100 for a creator not shown yet in this list, then 50, 33, 25 ...
func Diversity(alreadyFromCreator int) float64 {
	n := alreadyFromCreator
	if n < 0 {
		n = 0
	}
	return 100.0 / float64(1+n)
}

// Item is one candidate. Creator is only compared for equality (the caller may send an anonymous label).
type Item struct {
	ID             int64    `json:"id"`
	Creator        string   `json:"creator"`
	AgeSeconds     float64  `json:"age_seconds"`
	InterestPoints float64  `json:"interest_points"`
	WatchAvg       *float64 `json:"watch_avg"`
	WatchViewers   int      `json:"watch_viewers"`
	EngagePoints   float64  `json:"engage_points"`
	Followed       bool     `json:"followed"`
	SeenAgeSeconds *float64 `json:"seen_age_seconds"`
	Skipped        bool     `json:"skipped"`
	Reporters      int      `json:"reporters"`
}

// Explore marks a new / small creator the viewer has no history with: eligible for a protected exploration slot.
func (it *Item) Explore() bool {
	return it.EngagePoints < ExploreMaxEngagePts && !it.Followed && it.InterestPoints == 0
}

// BaseScore is everything except diversity (that one depends on what is already in the list).
func BaseScore(it *Item, w Weights) float64 {
	// The float64(...) conversions forbid fused multiply-add on CPUs that have it (arm64): the arithmetic must round exactly
	// like the Python version, step by step, so the two languages order ties the same way.
	pos := float64(w.Interest*Interest(it.InterestPoints)) + float64(w.Watch*WatchQuality(it.WatchAvg, it.WatchViewers))
	pos += float64(w.Fresh * Freshness(it.AgeSeconds))
	pos += float64(w.Engage * Engagement(it.EngagePoints))
	follow := 0.0
	if it.Followed {
		follow = 100.0
	}
	pos += float64(w.Follow * follow)
	return pos - RepeatPenalty(it.SeenAgeSeconds, it.Skipped) - QualityPenalty(it.Reporters)
}

// Order returns the item ids, best first. Greedy, exactly like ranker.order: at every position take the best remaining item,
// where the diversity part depends on how many items of that creator are already in the list. Ties go to the higher id
// (newer item), so the same input always gives the same output. The items must have DIFFERENT ids (the server checks that).
//
// Speed: creators are turned into small numbers once, the diversity part of every creator is kept in a slice, and the
// "who may take this slot" rules are two plain values (no maps and no closures inside the loop that runs n*n times).
func Order(items []Item, w Weights, exploreEvery, maxRun int) []int64 {
	n := len(items)
	cidx := make([]int, n)     // creator number of each item
	base := make([]float64, n) // score without the diversity part
	small := make([]bool, n)   // new / small creator (eligible for the protected slot)
	names := make(map[string]int, n)
	for i := range items {
		c, ok := names[items[i].Creator]
		if !ok {
			c = len(names)
			names[items[i].Creator] = c
		}
		cidx[i] = c
		base[i] = BaseScore(&items[i], w)
		small[i] = items[i].Explore()
	}
	counts := make([]int, len(names))      // items of each creator already in the list
	divPart := make([]float64, len(names)) // w.Diversity * Diversity(counts), the same expression as the Python version
	for c := range divPart {
		divPart[c] = float64(w.Diversity * Diversity(0))
	}
	taken := make([]bool, n)
	chosen := make([]int, 0, n)

	for len(chosen) < n {
		pos := len(chosen) + 1
		exclude := -1 // a creator who already has maxRun in a row: skipped when anybody else is left
		if maxRun > 0 && len(chosen) >= maxRun {
			last := cidx[chosen[len(chosen)-1]]
			same := true
			for _, c := range chosen[len(chosen)-maxRun:] {
				if cidx[c] != last {
					same = false
					break
				}
			}
			if same {
				for i := 0; i < n; i++ {
					if !taken[i] && cidx[i] != last {
						exclude = last
						break
					}
				}
			}
		}
		exploreOnly := false // every exploreEvery-th slot: only new / small creators not shown yet, when one exists
		if exploreEvery > 0 && pos%exploreEvery == 0 {
			for i := 0; i < n; i++ {
				if !taken[i] && cidx[i] != exclude && small[i] && counts[cidx[i]] == 0 {
					exploreOnly = true
					break
				}
			}
		}
		best := -1
		bestScore := 0.0
		for i := 0; i < n; i++ {
			if taken[i] || cidx[i] == exclude {
				continue
			}
			c := cidx[i]
			if exploreOnly && !(small[i] && counts[c] == 0) {
				continue
			}
			sc := base[i] + divPart[c]
			if best < 0 || sc > bestScore || (sc == bestScore && items[i].ID > items[best].ID) {
				best, bestScore = i, sc
			}
		}
		taken[best] = true
		chosen = append(chosen, best)
		c := cidx[best]
		counts[c]++
		divPart[c] = float64(w.Diversity * Diversity(counts[c]))
	}
	ids := make([]int64, n)
	for k, i := range chosen {
		ids[k] = items[i].ID
	}
	return ids
}

// OrderDefault is Order with the plan's exploration rhythm and run limit.
func OrderDefault(items []Item, w Weights) []int64 {
	return Order(items, w, ExploreEvery, MaxSameCreatorRun)
}
