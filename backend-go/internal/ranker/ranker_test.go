package ranker

import (
	"encoding/json"
	"math"
	"os"
	"reflect"
	"testing"
)

func near(a, b, tol float64) bool { return math.Abs(a-b) <= tol }
func fp(x float64) *float64       { return &x }

// The Python backend and this package must order EXACTLY the same. testdata/parity.json is written by
// `python tools/gen_rank_parity.py` from backend/ranker.py; here every case must come out identical.
func TestParityWithPython(t *testing.T) {
	raw, err := os.ReadFile("testdata/parity.json")
	if err != nil {
		t.Fatal(err)
	}
	var file struct {
		Ranker string `json:"ranker"`
		Cases  []struct {
			Kind     string  `json:"kind"`
			Weights  Weights `json:"weights"`
			Items    []Item  `json:"items"`
			Expected []int64 `json:"expected"`
		} `json:"cases"`
	}
	if err := json.Unmarshal(raw, &file); err != nil {
		t.Fatal(err)
	}
	if file.Ranker != Version {
		t.Fatalf("parity file is for ranker %q but Go implements %q: regenerate it", file.Ranker, Version)
	}
	if len(file.Cases) < 100 {
		t.Fatalf("only %d parity cases", len(file.Cases))
	}
	for i, c := range file.Cases {
		got := OrderDefault(c.Items, c.Weights)
		if len(c.Expected) == 0 && len(got) == 0 {
			continue
		}
		if !reflect.DeepEqual(got, c.Expected) {
			t.Fatalf("case %d (%s, %d items): Go order differs from Python\n go: %v\n py: %v", i, c.Kind, len(c.Items), got, c.Expected)
		}
	}
	t.Logf("%d cases identical to the Python ranker", len(file.Cases))
}

func TestDefaultWeightsAreThePlans(t *testing.T) {
	w := DefaultWeights()
	if w != (Weights{0.30, 0.20, 0.15, 0.15, 0.10, 0.10}) || !w.Valid() {
		t.Fatalf("weights %+v", w)
	}
}

func TestParseWeights(t *testing.T) {
	w, ok := ParseWeights("interest=0.4,watch=0.2,fresh=0.1,engage=0.1,follow=0.1,diversity=0.1")
	if !ok || w.Interest != 0.4 {
		t.Fatalf("valid setting refused: %+v %v", w, ok)
	}
	for _, bad := range []string{
		"interest=0.5", "interest=0.3,watch=0.2,fresh=0.15,engage=0.15,follow=0.1,diversity=0.2",
		"interest=-0.1,watch=0.4,fresh=0.2,engage=0.2,follow=0.2,diversity=0.1", "garbage", "a=b",
		"interest=0.3,watch=0.2,fresh=0.15,engage=0.15,follow=0.1,other=0.1", "interest=nan,watch=0.2,fresh=0.2,engage=0.2,follow=0.2,diversity=0.2",
		"interest=0.3,watch=0.2,fresh=0.15,engage=0.15,follow=0.1,diversity=0.1,x=0",
	} {
		if got, ok := ParseWeights(bad); ok || got != DefaultWeights() {
			t.Errorf("%q: want the defaults and ok=false, got %+v %v", bad, got, ok)
		}
	}
	if got, ok := ParseWeights("   "); !ok || got != DefaultWeights() {
		t.Error("empty text = defaults, nothing wrong")
	}
}

func TestSignals(t *testing.T) {
	if !near(Freshness(0), 100, 0.01) || !near(Freshness(48*3600), 50, 0.01) || !near(Freshness(96*3600), 25, 0.01) || !near(Freshness(-500), 100, 0.01) {
		t.Error("freshness must halve every 48 h")
	}
	if !(Freshness(1) > Freshness(3600) && Freshness(3600) > Freshness(86400) && Freshness(86400) > Freshness(86400*30)) {
		t.Error("freshness must fall gradually")
	}
	if Engagement(0) != 0 || !near(Engagement(50), 100, 0.01) || Engagement(1e6) != 100 || !(Engagement(1) < Engagement(10)) {
		t.Error("engagement")
	}
	if Interest(0) != 0 || !near(Interest(6), 50, 0.01) || Interest(1000) >= 100 {
		t.Error("interest")
	}
	if WatchQuality(fp(1.0), 2) != Neutral || WatchQuality(nil, 50) != Neutral || WatchQuality(fp(0.8), 3) != 80 {
		t.Error("watch quality: fewer than 3 viewers or no data = neutral")
	}
	if RepeatPenalty(nil, false) != 0 || RepeatPenalty(fp(3600), false) != 40 || RepeatPenalty(fp(5*86400), false) != 15 ||
		RepeatPenalty(fp(30*86400), false) != 0 || RepeatPenalty(fp(3600), true) != 65 || RepeatPenalty(nil, true) != 25 {
		t.Error("repeat penalty")
	}
	if QualityPenalty(0) != 0 || QualityPenalty(2) != 20 || QualityPenalty(99) != 30 || QualityPenalty(-4) != 0 {
		t.Error("quality penalty")
	}
	if Diversity(0) != 100 || Diversity(1) != 50 || !near(Diversity(2), 33.33, 0.01) {
		t.Error("diversity")
	}
}

func TestOrderBasics(t *testing.T) {
	w := DefaultWeights()
	if got := OrderDefault(nil, w); len(got) != 0 {
		t.Errorf("empty input gave %v", got)
	}
	if got := OrderDefault([]Item{{ID: 7, Creator: "a"}}, w); !reflect.DeepEqual(got, []int64{7}) {
		t.Errorf("single item gave %v", got)
	}
	// an order is always a permutation of the input: nothing added, nothing lost
	items := make([]Item, 0, 40)
	for i := 1; i <= 40; i++ {
		items = append(items, Item{ID: int64(i), Creator: string(rune('a' + i%5)), AgeSeconds: float64(i * 1000)})
	}
	got := OrderDefault(items, w)
	seen := map[int64]bool{}
	for _, id := range got {
		seen[id] = true
	}
	if len(got) != 40 || len(seen) != 40 {
		t.Fatalf("not a permutation: %v", got)
	}
}

func TestTiesGoToTheNewerItem(t *testing.T) {
	items := []Item{{ID: 1, Creator: "a", AgeSeconds: 100}, {ID: 3, Creator: "b", AgeSeconds: 100}, {ID: 2, Creator: "c", AgeSeconds: 100}}
	if got := OrderDefault(items, DefaultWeights()); !reflect.DeepEqual(got, []int64{3, 2, 1}) {
		t.Errorf("got %v", got)
	}
}

func TestNeverMoreThanTwoInARowFromOneCreator(t *testing.T) {
	var items []Item
	for i := 1; i <= 6; i++ { // creator "big" is clearly the best, but must not take the first 3 places
		items = append(items, Item{ID: int64(100 + i), Creator: "big", AgeSeconds: 0, EngagePoints: 50, Followed: true})
	}
	for i := 1; i <= 4; i++ {
		items = append(items, Item{ID: int64(i), Creator: string(rune('p' + i)), AgeSeconds: 86400 * 20})
	}
	byID := map[int64]string{}
	for _, it := range items {
		byID[it.ID] = it.Creator
	}
	got := OrderDefault(items, DefaultWeights())
	run := 0
	for k, id := range got {
		if k > 0 && byID[id] == byID[got[k-1]] {
			run++
		} else {
			run = 0
		}
		if run >= 2 && k < 8 {
			t.Fatalf("three in a row from %s at position %d: %v", byID[id], k, got)
		}
	}
}

func TestExplorationSlotGoesToNewSmallCreator(t *testing.T) {
	var items []Item
	for i := 1; i <= 8; i++ { // strong, followed creators, every one different
		items = append(items, Item{ID: int64(10 + i), Creator: string(rune('a' + i)), AgeSeconds: 10, EngagePoints: 40, Followed: true, InterestPoints: 10})
	}
	items = append(items, Item{ID: 1, Creator: "newbie", AgeSeconds: 86400 * 40}) // old, no engagement, not followed
	got := OrderDefault(items, DefaultWeights())
	if got[4] != 1 { // the 5th slot is protected for a new / small creator
		t.Errorf("slot 5 should be the new creator (id 1): %v", got)
	}
}

func TestOrderCannotAddOrDropItems(t *testing.T) {
	items := []Item{{ID: 5, Creator: "x"}, {ID: 9, Creator: "y", Reporters: 9, Skipped: true}}
	got := OrderDefault(items, DefaultWeights())
	if len(got) != 2 {
		t.Fatalf("an item with many reports is ranked lower, never dropped or invented here: %v", got)
	}
}
