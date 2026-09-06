package main

import (
	"flag"
	"fmt"
	"os"
	"os/signal"
	"syscall"

	lanternsdk "github.com/getlantern/lanternsdk-android/lantern"
)

func main() {
	configDir := flag.String("config-dir", "", "Lantern config directory")
	listen := flag.String("listen", ":8080", "HTTP proxy listen address")
	proxyAll := flag.Bool("proxy-all", true, "Proxy all traffic")
	flag.Parse()

	if *configDir == "" {
		fmt.Fprintln(os.Stderr, "missing --config-dir")
		os.Exit(2)
	}

	client := lanternsdk.NewLanternClient()
	client.Setup("GostX", *configDir)

	result, err := client.Start(*listen, *proxyAll)
	if err != nil {
		fmt.Fprintf(os.Stderr, "lantern start failed: %v\n", err)
		os.Exit(4)
	}

	fmt.Printf("LANTERN_READY %s\n", result.Addr)

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
	<-stop

	if err := client.Stop(); err != nil {
		fmt.Fprintf(os.Stderr, "lantern stop failed: %v\n", err)
	}
}