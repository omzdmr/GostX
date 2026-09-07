package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/getlantern/radiance/backend"
	"github.com/getlantern/radiance/config"
	"github.com/getlantern/radiance/events"
	"github.com/getlantern/radiance/vpn"
)

func main() {
	var dataDir string
	var logDir string
	var listen string
	var deviceID string
	var locale string
	var country string
	var envName string

	flag.StringVar(&dataDir, "data-dir", "", "Radiance data directory")
	flag.StringVar(&logDir, "log-dir", "", "Radiance log directory")
	flag.StringVar(&listen, "listen", "0.0.0.0:8080", "mixed HTTP/SOCKS listen address")
	flag.StringVar(&deviceID, "device-id", "", "Lantern client device ID")
	flag.StringVar(&locale, "locale", "en", "locale")
	flag.StringVar(&country, "country", "cn", "client country hint")
	flag.StringVar(&envName, "env", "prod", "Radiance environment")
	flag.Parse()

	if strings.TrimSpace(dataDir) == "" || strings.TrimSpace(deviceID) == "" {
		log.Fatal("--data-dir and --device-id are required")
	}
	if strings.TrimSpace(logDir) == "" {
		logDir = dataDir
	}
	if err := os.MkdirAll(dataDir, 0o755); err != nil {
		log.Fatalf("create data dir: %v", err)
	}
	if err := os.MkdirAll(logDir, 0o755); err != nil {
		log.Fatalf("create log dir: %v", err)
	}

	_ = os.Setenv("RADIANCE_USE_SOCKS_PROXY", "true")
	_ = os.Setenv("RADIANCE_SOCKS_ADDRESS", listen)
	_ = os.Setenv("RADIANCE_COUNTRY", country)
	_ = os.Setenv("RADIANCE_ENV", envName)

	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()

	be, err := backend.NewLocalBackend(ctx, backend.Options{
		DataDir:          dataDir,
		LogDir:           logDir,
		Locale:           locale,
		LogLevel:         "debug",
		DeviceID:         deviceID,
		TelemetryConsent: false,
	})
	if err != nil {
		log.Fatalf("create Radiance backend: %v", err)
	}
	defer be.Close()

	configReady := make(chan struct{}, 1)
	events.SubscribeContext(ctx, func(config.NewConfigEvent) {
		select {
		case configReady <- struct{}{}:
		default:
		}
	})
	events.SubscribeContext(ctx, func(evt vpn.AutoSelectedEvent) {
		if strings.TrimSpace(evt.Selected) != "" {
			fmt.Printf("RADIANCE_SELECTED %s\n", evt.Selected)
		}
	})

	be.Start()

	deadline := time.Now().Add(3 * time.Minute)
	var lastErr error
	for ctx.Err() == nil && time.Now().Before(deadline) {
		connectCtx, connectCancel := context.WithTimeout(ctx, 60*time.Second)
		err = be.ConnectVPN(connectCtx, vpn.AutoSelectTag)
		connectCancel()
		if err == nil {
			break
		}
		lastErr = err
		select {
		case <-ctx.Done():
			return
		case <-configReady:
		case <-time.After(3 * time.Second):
		}
	}
	if err != nil {
		log.Fatalf("Radiance auto connect failed: %v", lastErr)
	}

	fmt.Printf("RADIANCE_READY %s\n", listen)
	<-ctx.Done()
	_ = be.DisconnectVPN()
}
