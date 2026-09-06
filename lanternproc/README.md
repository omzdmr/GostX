# Lantern isolated runtime

This directory intentionally has its own Go module.

Why:
- GostX/libgost keeps its existing dependency graph.
- Lantern keeps its own quic-go / Flashlight dependency graph.
- We will build this as a separate Android-native executable, not gomobile AAR.
- The Android app will start/stop the process and use its HTTP proxy on port 8080.

Do not add Lantern dependencies to libgost/go.mod.