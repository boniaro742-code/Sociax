// Package config reads the engine's settings from environment variables. Error messages NEVER contain a value:
// a wrong secret must not end up in a log.
package config

import (
	"errors"
	"fmt"
	"strconv"
	"strings"
)

// MinTokenLen: a shared secret shorter than this is refused at start-up (the engine does not run unprotected).
const MinTokenLen = 24

// Config is everything the engine needs to run.
type Config struct {
	Port        string // PORT (Render sets it), default 8080
	Token       string // ENGINE_TOKEN: shared secret, sent by the Python backend in X-Engine-Token
	MaxItems    int    // ENGINE_MAX_ITEMS: candidates per request (the Python backend ranks at most 300)
	MaxInflight int    // ENGINE_MAX_INFLIGHT: requests handled at the same time; more get 503 and Python falls back
	LogLevel    string // LOG_LEVEL: debug | info | warn | error
}

func intEnv(get func(string) string, name string, def, lo, hi int) (int, error) {
	raw := strings.TrimSpace(get(name))
	if raw == "" {
		return def, nil
	}
	n, err := strconv.Atoi(raw)
	if err != nil || n < lo || n > hi {
		return 0, fmt.Errorf("%s must be a whole number from %d to %d", name, lo, hi)
	}
	return n, nil
}

// Load validates the settings. `get` is os.Getenv in production and a map lookup in tests.
func Load(get func(string) string) (Config, error) {
	var c Config
	c.Port = strings.TrimSpace(get("PORT"))
	if c.Port == "" {
		c.Port = "8080"
	}
	if p, err := strconv.Atoi(c.Port); err != nil || p < 1 || p > 65535 {
		return Config{}, errors.New("PORT must be a number from 1 to 65535")
	}
	c.Token = get("ENGINE_TOKEN")
	if len(c.Token) < MinTokenLen {
		return Config{}, fmt.Errorf("ENGINE_TOKEN is missing or shorter than %d characters", MinTokenLen)
	}
	var err error
	if c.MaxItems, err = intEnv(get, "ENGINE_MAX_ITEMS", 2000, 1, 20000); err != nil {
		return Config{}, err
	}
	if c.MaxInflight, err = intEnv(get, "ENGINE_MAX_INFLIGHT", 32, 1, 1024); err != nil {
		return Config{}, err
	}
	c.LogLevel = strings.ToLower(strings.TrimSpace(get("LOG_LEVEL")))
	switch c.LogLevel {
	case "":
		c.LogLevel = "info"
	case "debug", "info", "warn", "error":
	default:
		return Config{}, errors.New("LOG_LEVEL must be debug, info, warn or error")
	}
	return c, nil
}
