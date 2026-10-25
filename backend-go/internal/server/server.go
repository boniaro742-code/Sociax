// Package server is the HTTP face of the Sociax engine.
//
//	GET  /healthz   process is alive (no secret, no data)
//	GET  /readyz    ready to rank
//	POST /v1/rank   order candidates (needs X-Engine-Token)
//	GET  /v1/stats  counts and timings, no content (needs X-Engine-Token)
//
// /v1/rank is a SERVER-TO-SERVER call from the Python backend, not an address for phones: the phone never talks to this
// service, so no user token and no user identity is needed here. The Python backend sends only what ranking needs
// (numbers and anonymous creator labels) and the engine can only reorder the candidates it was given.
package server

import (
	"crypto/sha256"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"math"
	"net/http"
	"sort"
	"sync"
	"time"

	"sociax/engine/internal/config"
	"sociax/engine/internal/ranker"
)

const (
	maxBodyBytes = 2 << 20 // 2 MiB: 2000 candidates are about 300 KB
	maxCreatorID = 128
	statsWindow  = 500 // newest rank timings kept in memory
)

// Server holds the settings and the in-memory counters (nothing is written to disk; they start at 0 after a restart).
type Server struct {
	cfg       config.Config
	log       *slog.Logger
	tokenHash [32]byte
	sem       chan struct{}
	started   time.Time

	mu      sync.Mutex
	total   int64
	c4xx    int64
	c5xx    int64
	busy    int64
	timings []int64 // microseconds, ring buffer
	next    int
}

// New builds the server. The token is stored only as a SHA-256 hash.
func New(cfg config.Config, log *slog.Logger) *Server {
	return &Server{
		cfg: cfg, log: log, tokenHash: sha256.Sum256([]byte(cfg.Token)),
		sem: make(chan struct{}, cfg.MaxInflight), started: time.Now(),
		timings: make([]int64, 0, statsWindow),
	}
}

// Handler returns the routes.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", s.healthz)
	mux.HandleFunc("GET /readyz", s.readyz)
	mux.HandleFunc("POST /v1/rank", s.rank)
	mux.HandleFunc("GET /v1/stats", s.stats)
	return mux
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func fail(w http.ResponseWriter, status int, code string) {
	writeJSON(w, status, map[string]string{"error": code})
}

func (s *Server) authorized(r *http.Request) bool {
	got := sha256.Sum256([]byte(r.Header.Get("X-Engine-Token")))
	return subtle.ConstantTimeCompare(got[:], s.tokenHash[:]) == 1
}

func (s *Server) healthz(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "service": "sociax-engine", "ranker": ranker.Version})
}

func (s *Server) readyz(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"ready": true, "ranker": ranker.Version})
}

// rankRequest is the body of POST /v1/rank.
type rankRequest struct {
	Weights *ranker.Weights `json:"weights"` // optional: the Python backend sends its RANK_WEIGHTS, so there is ONE place to configure them
	Items   []ranker.Item   `json:"items"`
	Limit   int             `json:"limit"` // optional: return only the best N (0 = all)
}

func (s *Server) record(status int, micros int64, rank bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.total++
	switch {
	case status >= 500:
		s.c5xx++
	case status >= 400:
		s.c4xx++
	}
	if rank && status == http.StatusOK {
		if len(s.timings) < statsWindow {
			s.timings = append(s.timings, micros)
		} else {
			s.timings[s.next] = micros
			s.next = (s.next + 1) % statsWindow
		}
	}
}

func (s *Server) rank(w http.ResponseWriter, r *http.Request) {
	start := time.Now()
	status, items := http.StatusOK, 0
	defer func() {
		us := time.Since(start).Microseconds()
		s.record(status, us, true)
		// route pattern, status, counts and time only: never a token, an id or a creator
		if status >= 400 {
			s.log.Warn("rank", "route", "/v1/rank", "status", status, "items", items, "us", us)
		} else {
			s.log.Debug("rank", "route", "/v1/rank", "status", status, "items", items, "us", us)
		}
	}()
	reply := func(code int, errCode string) {
		status = code
		fail(w, code, errCode)
	}

	if !s.authorized(r) {
		reply(http.StatusUnauthorized, "unauthorized")
		return
	}
	select {
	case s.sem <- struct{}{}:
		defer func() { <-s.sem }()
	default:
		s.mu.Lock()
		s.busy++
		s.mu.Unlock()
		w.Header().Set("Retry-After", "1")
		reply(http.StatusServiceUnavailable, "busy")
		return
	}

	var req rankRequest
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxBodyBytes))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&req); err != nil {
		var tooBig *http.MaxBytesError
		if errors.As(err, &tooBig) {
			reply(http.StatusRequestEntityTooLarge, "too_large")
		} else {
			reply(http.StatusBadRequest, "bad_json")
		}
		return
	}
	if _, err := dec.Token(); !errors.Is(err, io.EOF) { // nothing may follow the JSON object
		reply(http.StatusBadRequest, "bad_json")
		return
	}
	items = len(req.Items)
	if items > s.cfg.MaxItems {
		reply(http.StatusRequestEntityTooLarge, "too_many_items")
		return
	}
	w8 := ranker.DefaultWeights()
	if req.Weights != nil {
		if !req.Weights.Valid() {
			reply(http.StatusBadRequest, "bad_weights")
			return
		}
		w8 = *req.Weights
	}
	if req.Limit < 0 {
		reply(http.StatusBadRequest, "bad_limit")
		return
	}
	seen := make(map[int64]struct{}, len(req.Items))
	for i := range req.Items {
		it := &req.Items[i]
		if _, dup := seen[it.ID]; dup {
			reply(http.StatusBadRequest, "duplicate_id")
			return
		}
		seen[it.ID] = struct{}{}
		if it.Creator == "" || len(it.Creator) > maxCreatorID {
			reply(http.StatusBadRequest, "bad_creator")
			return
		}
		if !finite(it.AgeSeconds, it.InterestPoints, it.EngagePoints) || (it.WatchAvg != nil && !finite(*it.WatchAvg)) ||
			(it.SeenAgeSeconds != nil && !finite(*it.SeenAgeSeconds)) || it.WatchViewers < 0 || it.Reporters < 0 {
			reply(http.StatusBadRequest, "bad_number")
			return
		}
	}

	t0 := time.Now()
	ids := ranker.OrderDefault(req.Items, w8)
	took := time.Since(t0).Microseconds()
	if req.Limit > 0 && req.Limit < len(ids) {
		ids = ids[:req.Limit]
	}
	if ids == nil {
		ids = []int64{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"ids": ids, "engine": "go", "ranker": ranker.Version, "took_us": took})
}

func finite(xs ...float64) bool {
	for _, x := range xs {
		if math.IsNaN(x) || math.IsInf(x, 0) {
			return false
		}
	}
	return true
}

func percentile(sorted []int64, p float64) int64 {
	if len(sorted) == 0 {
		return 0
	}
	i := int(math.Ceil(p/100*float64(len(sorted)))) - 1
	if i < 0 {
		i = 0
	}
	return sorted[i]
}

func (s *Server) stats(w http.ResponseWriter, r *http.Request) {
	if !s.authorized(r) {
		fail(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	s.mu.Lock()
	t := append([]int64(nil), s.timings...)
	out := map[string]any{
		"uptime_s": int64(time.Since(s.started).Seconds()), "requests": s.total, "errors_4xx": s.c4xx, "errors_5xx": s.c5xx,
		"busy_rejections": s.busy, "rank_samples": len(t),
		"note": "Starts again at 0 when the service restarts. Times are the whole /v1/rank request in microseconds (newest 500). A baseline, not proof of speed.",
	}
	s.mu.Unlock()
	sort.Slice(t, func(i, j int) bool { return t[i] < t[j] })
	out["rank_us"] = map[string]int64{"p50": percentile(t, 50), "p95": percentile(t, 95), "p99": percentile(t, 99)}
	if len(t) > 0 {
		out["rank_us_max"] = t[len(t)-1]
	}
	writeJSON(w, http.StatusOK, out)
}
