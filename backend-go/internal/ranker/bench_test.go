package ranker

import (
	"math/rand"
	"testing"
)

func randomItems(n, creators int, seed int64) []Item {
	rng := rand.New(rand.NewSource(seed))
	items := make([]Item, n)
	for i := range items {
		seen := rng.Float64() * 86400 * 20
		avg := rng.Float64()
		items[i] = Item{ID: int64(i + 1), Creator: string(rune('A'+rng.Intn(creators))) + "x", AgeSeconds: rng.Float64() * 86400 * 30,
			InterestPoints: float64(rng.Intn(3)) * 4, WatchAvg: &avg, WatchViewers: rng.Intn(6), EngagePoints: float64(rng.Intn(60)),
			Followed: rng.Intn(4) == 0, SeenAgeSeconds: &seen, Skipped: rng.Intn(8) == 0, Reporters: rng.Intn(3)}
	}
	return items
}

// One request of the real size (the Python backend ranks 300 candidates). Compare with `python tools/bench_rank.py`.
func BenchmarkOrder300(b *testing.B) {
	items := randomItems(300, 20, 1)
	w := DefaultWeights()
	b.ReportAllocs()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		OrderDefault(items, w)
	}
}

func BenchmarkOrder2000(b *testing.B) {
	items := randomItems(2000, 40, 2)
	w := DefaultWeights()
	b.ReportAllocs()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		OrderDefault(items, w)
	}
}
