// Command sociax-engine is the optional Go service next to the Python backend (plan v3.4 section 17, v3.9 section 30).
// Today it ranks the For You candidates. The Python backend keeps working without it: if this service is off, slow or
// answers anything unexpected, Python ranks the same candidates itself.
package main

import (
	"context"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"sociax/engine/internal/config"
	"sociax/engine/internal/server"
)

func level(name string) slog.Level {
	switch name {
	case "debug":
		return slog.LevelDebug
	case "warn":
		return slog.LevelWarn
	case "error":
		return slog.LevelError
	}
	return slog.LevelInfo
}

func main() {
	cfg, err := config.Load(os.Getenv)
	if err != nil {
		// the message names the setting, never a value
		slog.New(slog.NewJSONHandler(os.Stderr, nil)).Error("engine not started", "reason", err.Error())
		os.Exit(2)
	}
	log := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: level(cfg.LogLevel)}))

	srv := &http.Server{
		Addr:              net.JoinHostPort("", cfg.Port),
		Handler:           server.New(cfg, log).Handler(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      10 * time.Second,
		IdleTimeout:       120 * time.Second,
		MaxHeaderBytes:    16 << 10,
	}

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)
	done := make(chan struct{})
	go func() {
		<-stop
		log.Info("shutting down")
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := srv.Shutdown(ctx); err != nil {
			log.Warn("shutdown was not clean", "reason", err.Error())
		}
		close(done)
	}()

	log.Info("engine listening", "port", cfg.Port)
	if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Error("server stopped", "reason", err.Error())
		os.Exit(1)
	}
	<-done
}
