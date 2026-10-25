package server

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"

	"sociax/engine/internal/config"
)

const token = "0123456789abcdef0123456789abcdef"

func newServer(t *testing.T, mutate func(*config.Config)) (*httptest.Server, *bytes.Buffer) {
	t.Helper()
	cfg := config.Config{Port: "8080", Token: token, MaxItems: 50, MaxInflight: 4, LogLevel: "debug"}
	if mutate != nil {
		mutate(&cfg)
	}
	logs := &bytes.Buffer{}
	h := New(cfg, slog.New(slog.NewJSONHandler(logs, &slog.HandlerOptions{Level: slog.LevelDebug}))).Handler()
	ts := httptest.NewServer(h)
	t.Cleanup(ts.Close)
	return ts, logs
}

func post(t *testing.T, url, tok, body string) (int, map[string]any) {
	t.Helper()
	req, _ := http.NewRequest("POST", url+"/v1/rank", strings.NewReader(body))
	if tok != "" {
		req.Header.Set("X-Engine-Token", tok)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	var out map[string]any
	_ = json.Unmarshal(raw, &out)
	return resp.StatusCode, out
}

func items(n int) string {
	parts := make([]string, n)
	for i := range parts {
		parts[i] = fmt.Sprintf(`{"id":%d,"creator":"c%d","age_seconds":%d}`, i+1, i%3, i*100)
	}
	return `{"items":[` + strings.Join(parts, ",") + `]}`
}

func TestHealthNeedsNoSecret(t *testing.T) {
	ts, _ := newServer(t, nil)
	for _, p := range []string{"/healthz", "/readyz"} {
		resp, err := http.Get(ts.URL + p)
		if err != nil || resp.StatusCode != 200 {
			t.Fatalf("%s: %v %v", p, resp, err)
		}
		raw, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		if strings.Contains(string(raw), token) {
			t.Fatal("health page shows the secret")
		}
	}
}

func TestRankRequiresTheToken(t *testing.T) {
	ts, _ := newServer(t, nil)
	for _, tok := range []string{"", "wrong", token + "x", token[:len(token)-1]} {
		if code, body := post(t, ts.URL, tok, items(3)); code != 401 || body["error"] != "unauthorized" {
			t.Errorf("token %q: %d %v", tok, code, body)
		}
	}
	resp, _ := http.Get(ts.URL + "/v1/stats")
	if resp.StatusCode != 401 {
		t.Errorf("stats without a token: %d", resp.StatusCode)
	}
}

func TestRankHappyPath(t *testing.T) {
	ts, _ := newServer(t, nil)
	code, body := post(t, ts.URL, token, items(7))
	if code != 200 || body["engine"] != "go" || body["ranker"] != "v1" {
		t.Fatalf("%d %v", code, body)
	}
	ids := body["ids"].([]any)
	if len(ids) != 7 {
		t.Fatalf("ids %v", ids)
	}
	seen := map[float64]bool{}
	for _, v := range ids {
		seen[v.(float64)] = true
	}
	if len(seen) != 7 {
		t.Fatalf("not a permutation: %v", ids)
	}
	// the engine can only reorder what it received
	for i := 1; i <= 7; i++ {
		if !seen[float64(i)] {
			t.Fatalf("id %d lost", i)
		}
	}
}

func TestEmptyListAndLimit(t *testing.T) {
	ts, _ := newServer(t, nil)
	if code, body := post(t, ts.URL, token, `{"items":[]}`); code != 200 || len(body["ids"].([]any)) != 0 {
		t.Fatalf("%d %v", code, body)
	}
	code, body := post(t, ts.URL, token, strings.Replace(items(9), `{"items"`, `{"limit":4,"items"`, 1))
	if code != 200 || len(body["ids"].([]any)) != 4 {
		t.Fatalf("limit: %d %v", code, body)
	}
}

func TestWeightsAreUsedAndChecked(t *testing.T) {
	ts, _ := newServer(t, nil)
	// freshness only: the youngest item must come first
	body := `{"weights":{"interest":0,"watch":0,"fresh":1,"engage":0,"follow":0,"diversity":0},"items":[` +
		`{"id":1,"creator":"a","age_seconds":999999},{"id":2,"creator":"b","age_seconds":5},{"id":3,"creator":"c","age_seconds":50000}]}`
	code, out := post(t, ts.URL, token, body)
	if code != 200 || out["ids"].([]any)[0].(float64) != 2 {
		t.Fatalf("%d %v", code, out)
	}
	bad := strings.Replace(body, `"fresh":1`, `"fresh":3`, 1)
	if code, out := post(t, ts.URL, token, bad); code != 400 || out["error"] != "bad_weights" {
		t.Fatalf("total 3.0 must be refused: %d %v", code, out)
	}
}

func TestBadRequestsAreRefusedWithAShortCode(t *testing.T) {
	ts, _ := newServer(t, nil)
	cases := map[string]struct {
		body string
		code int
		err  string
	}{
		"not json":          {`nope`, 400, "bad_json"},
		"trailing data":     {`{"items":[]} {"items":[]}`, 400, "bad_json"},
		"unknown field":     {`{"items":[],"admin":true}`, 400, "bad_json"},
		"duplicate id":      {`{"items":[{"id":1,"creator":"a"},{"id":1,"creator":"b"}]}`, 400, "duplicate_id"},
		"no creator":        {`{"items":[{"id":1,"creator":""}]}`, 400, "bad_creator"},
		"long creator":      {`{"items":[{"id":1,"creator":"` + strings.Repeat("x", 129) + `"}]}`, 400, "bad_creator"},
		"negative count":    {`{"items":[{"id":1,"creator":"a","reporters":-1}]}`, 400, "bad_number"},
		"negative viewers":  {`{"items":[{"id":1,"creator":"a","watch_viewers":-2}]}`, 400, "bad_number"},
		"huge number":       {`{"items":[{"id":1,"creator":"a","age_seconds":1e999}]}`, 400, "bad_json"},
		"negative limit":    {`{"limit":-1,"items":[]}`, 400, "bad_limit"},
		"too many items":    {items(51), 413, "too_many_items"},
		"string for number": {`{"items":[{"id":"1","creator":"a"}]}`, 400, "bad_json"},
	}
	for name, c := range cases {
		code, body := post(t, ts.URL, token, c.body)
		if code != c.code || body["error"] != c.err {
			t.Errorf("%s: want %d %s, got %d %v", name, c.code, c.err, code, body)
		}
	}
}

func TestBodyTooLarge(t *testing.T) {
	ts, _ := newServer(t, func(c *config.Config) { c.MaxItems = 20000 })
	big := `{"items":[],"pad":"` + strings.Repeat("a", maxBodyBytes) + `"}`
	if code, body := post(t, ts.URL, token, big); code != 413 || body["error"] != "too_large" {
		t.Fatalf("%d %v", code, body)
	}
}

func TestWrongMethodAndPath(t *testing.T) {
	ts, _ := newServer(t, nil)
	resp, _ := http.Get(ts.URL + "/v1/rank")
	if resp.StatusCode != http.StatusMethodNotAllowed {
		t.Errorf("GET /v1/rank: %d", resp.StatusCode)
	}
	resp, _ = http.Get(ts.URL + "/nothing")
	if resp.StatusCode != http.StatusNotFound {
		t.Errorf("unknown path: %d", resp.StatusCode)
	}
}

func TestBusyWhenTooManyAtOnce(t *testing.T) {
	cfg := config.Config{Port: "8080", Token: token, MaxItems: 50, MaxInflight: 1, LogLevel: "debug"}
	s := New(cfg, slog.New(slog.NewJSONHandler(io.Discard, nil)))
	s.sem <- struct{}{} // pretend one request is being handled
	ts := httptest.NewServer(s.Handler())
	defer ts.Close()
	req, _ := http.NewRequest("POST", ts.URL+"/v1/rank", strings.NewReader(items(2)))
	req.Header.Set("X-Engine-Token", token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != 503 || resp.Header.Get("Retry-After") == "" {
		t.Fatalf("busy: %d", resp.StatusCode)
	}
	<-s.sem
	if code, _ := post(t, ts.URL, token, items(2)); code != 200 {
		t.Fatalf("after the slot is free: %d", code)
	}
}

func TestManyRequestsAtTheSameTime(t *testing.T) {
	ts, _ := newServer(t, func(c *config.Config) { c.MaxInflight = 64 })
	var wg sync.WaitGroup
	var mu sync.Mutex
	bad := 0
	for i := 0; i < 40; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if code, _ := post(t, ts.URL, token, items(30)); code != 200 {
				mu.Lock()
				bad++
				mu.Unlock()
			}
		}()
	}
	wg.Wait()
	if bad != 0 {
		t.Fatalf("%d of 40 parallel requests failed", bad)
	}
}

func TestLogsNeverContainTheTokenOrCreators(t *testing.T) {
	ts, logs := newServer(t, nil)
	post(t, ts.URL, "wrong-"+token, items(3))
	post(t, ts.URL, token, strings.Replace(items(3), `"c0"`, `"very-private-creator-label"`, 1))
	post(t, ts.URL, token, `{"items":[{"id":1,"creator":"very-private-creator-label"},{"id":1,"creator":"x"}]}`)
	out := logs.String()
	for _, secret := range []string{token, "wrong-", "very-private-creator-label"} {
		if strings.Contains(out, secret) {
			t.Fatalf("log contains %q:\n%s", secret, out)
		}
	}
	if !strings.Contains(out, `"status":401`) || !strings.Contains(out, `"status":400`) {
		t.Fatalf("failures should be logged with their status:\n%s", out)
	}
}

func TestStats(t *testing.T) {
	ts, _ := newServer(t, nil)
	for i := 0; i < 5; i++ {
		post(t, ts.URL, token, items(20))
	}
	post(t, ts.URL, token, `bad`)
	req, _ := http.NewRequest("GET", ts.URL+"/v1/stats", nil)
	req.Header.Set("X-Engine-Token", token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil || resp.StatusCode != 200 {
		t.Fatal(err, resp)
	}
	var st map[string]any
	_ = json.NewDecoder(resp.Body).Decode(&st)
	if st["requests"].(float64) != 6 || st["errors_4xx"].(float64) != 1 || st["rank_samples"].(float64) != 5 {
		t.Fatalf("%v", st)
	}
	if _, ok := st["rank_us"].(map[string]any)["p95"]; !ok {
		t.Fatalf("no p95: %v", st)
	}
}

func TestStatsWindowStaysBounded(t *testing.T) {
	cfg := config.Config{Port: "8080", Token: token, MaxItems: 5, MaxInflight: 2, LogLevel: "error"}
	s := New(cfg, slog.New(slog.NewJSONHandler(io.Discard, nil)))
	for i := 0; i < statsWindow*3; i++ {
		s.record(200, int64(i), true)
	}
	if len(s.timings) != statsWindow {
		t.Fatalf("%d timings kept", len(s.timings))
	}
}

func TestPercentile(t *testing.T) {
	if percentile(nil, 50) != 0 {
		t.Error("empty")
	}
	s := []int64{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}
	if percentile(s, 50) != 5 || percentile(s, 95) != 10 || percentile(s, 99) != 10 || percentile(s, 0) != 1 {
		t.Error("percentile")
	}
}
