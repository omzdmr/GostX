package main

import (
	"flag"
	"fmt"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"

	"github.com/getlantern/flashlight/v7"
	"github.com/getlantern/flashlight/v7/client"
	"github.com/getlantern/flashlight/v7/stats"
	"github.com/getlantern/lantern-client/internalsdk/common"
)

const startTimeout = 20 * time.Second

func main() {
	configDir := flag.String("config-dir", "", "Lantern config directory")
	listen := flag.String("listen", ":8080", "HTTP proxy listen address")
	proxyAll := flag.Bool("proxy-all", true, "Proxy all traffic")
	deviceID := flag.String("device-id", "", "Stable Lantern device UUID")
	flag.Parse()

	if *configDir == "" {
		fmt.Fprintln(os.Stderr, "missing --config-dir")
		os.Exit(2)
	}
	if *deviceID == "" {
		fmt.Fprintln(os.Stderr, "missing --device-id")
		os.Exit(3)
	}

	tracker := stats.NewTracker()
	userConfig := common.NewUserConfig(
		"",
		*deviceID,
		381696446,
		"K1qttSsZruN",
		map[string]string{},
		"",
	)

	runner, err := flashlight.New(
		"GostX",
		common.ApplicationVersion,
		common.RevisionDate,
		*configDir,
		false,
		func() bool { return false },
		func() bool { return *proxyAll },
		func() bool { return false },
		func() bool { return true },
		map[string]interface{}{},
		userConfig,
		tracker,
		func() bool { return false },
		func() string { return "" },
		nil,
		func(category, action, label string) {},
	)
	if err != nil {
		fmt.Fprintf(os.Stderr, "lantern init failed: %v\n", err)
		os.Exit(4)
	}

	var mu sync.Mutex
	var runningClient *client.Client

	go func() {
		runner.Run(
			*listen,
			"127.0.0.1:0",
			func(c *client.Client) {
				mu.Lock()
				runningClient = c
				mu.Unlock()
			},
			nil,
		)
	}()

	addr, ok := client.Addr(startTimeout)
	if !ok {
		fmt.Fprintf(os.Stderr, "HTTP proxy did not start within %v\n", startTimeout)
		os.Exit(5)
	}

	fmt.Printf("LANTERN_DEVICE %s\n", *deviceID)
	fmt.Printf("LANTERN_READY %s\n", addr.(string))

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
	<-stop

	mu.Lock()
	c := runningClient
	mu.Unlock()
	if c != nil {
		if err := c.Stop(); err != nil {
			fmt.Fprintf(os.Stderr, "lantern stop failed: %v\n", err)
		}
	}
}
