package libgost

import (
	"fmt"
	"sync"

	lanternsdk "github.com/getlantern/lanternsdk-android/lantern"
)

var (
	lanternMu     sync.Mutex
	lanternClient *lanternsdk.LanternClient
)

// StartLantern starts Lantern's embedded HTTP proxy using the supplied
// persistent configuration directory. The returned address is the actual
// address reported by Lantern.
func StartLantern(configDir string, addr string, proxyAll bool) (string, error) {
	lanternMu.Lock()
	defer lanternMu.Unlock()

	if lanternClient != nil {
		if lanternClient.IsRunning() {
			if port, err := lanternClient.HTTPProxyPort(); err == nil && port > 0 {
				return fmt.Sprintf("0.0.0.0:%d", port), nil
			}
			_ = lanternClient.Stop()
		}
		lanternClient = nil
	}

	client := lanternsdk.NewLanternClient()
	client.Setup("GostX", configDir)

	result, err := client.Start(addr, proxyAll)
	if err != nil {
		return "", err
	}
	if result == nil {
		_ = client.Stop()
		return "", fmt.Errorf("Lantern returned no proxy address")
	}

	lanternClient = client
	return result.Addr, nil
}

// StopLantern stops the currently active embedded Lantern proxy.
func StopLantern() error {
	lanternMu.Lock()
	defer lanternMu.Unlock()

	if lanternClient == nil {
		return nil
	}

	client := lanternClient
	lanternClient = nil
	return client.Stop()
}

// IsLanternRunning reports whether the embedded Lantern proxy is active.
func IsLanternRunning() bool {
	lanternMu.Lock()
	defer lanternMu.Unlock()

	return lanternClient != nil && lanternClient.IsRunning()
}