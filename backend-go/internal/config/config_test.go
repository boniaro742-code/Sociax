package config

import (
	"strings"
	"testing"
)

func env(m map[string]string) func(string) string { return func(k string) string { return m[k] } }

const goodToken = "0123456789abcdef0123456789abcdef"

func TestDefaults(t *testing.T) {
	c, err := Load(env(map[string]string{"ENGINE_TOKEN": goodToken}))
	if err != nil || c.Port != "8080" || c.MaxItems != 2000 || c.MaxInflight != 32 || c.LogLevel != "info" {
		t.Fatalf("%+v %v", c, err)
	}
}

func TestTokenRequired(t *testing.T) {
	for _, tok := range []string{"", "short", strings.Repeat("x", MinTokenLen-1)} {
		if _, err := Load(env(map[string]string{"ENGINE_TOKEN": tok})); err == nil {
			t.Errorf("token %q must be refused", tok)
		}
	}
}

func TestErrorNeverContainsAValue(t *testing.T) {
	_, err := Load(env(map[string]string{"ENGINE_TOKEN": goodToken, "ENGINE_MAX_ITEMS": "secret-looking-value"}))
	if err == nil || strings.Contains(err.Error(), "secret-looking-value") {
		t.Fatalf("error must name the setting only: %v", err)
	}
	_, err = Load(env(map[string]string{"ENGINE_TOKEN": "tooshort"}))
	if err == nil || strings.Contains(err.Error(), "tooshort") {
		t.Fatalf("error must not echo the token: %v", err)
	}
}

func TestBadSettingsRefused(t *testing.T) {
	for k, v := range map[string]string{"PORT": "99999", "ENGINE_MAX_ITEMS": "0", "ENGINE_MAX_INFLIGHT": "-1", "LOG_LEVEL": "loud"} {
		if _, err := Load(env(map[string]string{"ENGINE_TOKEN": goodToken, k: v})); err == nil {
			t.Errorf("%s=%s must be refused", k, v)
		}
	}
}
