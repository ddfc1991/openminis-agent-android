# Mines — continued development of OpenMinis

Mines continues the development of [OpenMinis](https://github.com/OpenMinis/OpenMinis)
(GPL-3.0). It is built from upstream commit `b4c0661` plus the patches in `patches/`.
The original OpenMinis `LICENSE` and copyright notices are kept unchanged; Mines
is distributed under the same GPL-3.0 terms.

- `minis-app-*.patch`: the Mines app changes (package `com.openminis.mines.plus`,
  label "Mines"), split into parts because of an upload size limit; applied in order.
- `proot-real-ifaces.patch`: PRoot `fake_netlink` returns the device's real interfaces.
- `mines-debug.keystore`: the fixed signing key. Every build is signed with it and the
  workflow fails if the APK's certificate SHA-256 is not
  `001814fb258110b003c851aeeac930fd3d803224eb9acdcab0a383edcb740e2b`.

Actions → "Build Mines APK (continued development of OpenMinis)" runs on every push to
`hark-minis-build`. The APK is attached to the run as the `mines-apk` artifact and
published as a pre-release `build-<run number>`.

Install note: builds up to build-12 were signed with throwaway keys. From the next build
the package is `com.openminis.mines.plus`, so it installs beside the older Mines; use
Settings → Mines → 从旧版迁移 to bring over providers and skills (via the old app's
backup export). Later builds update in place.

## Sandbox network self-test (since build-26)
`hark-build/sandbox-net-selftest.sh` boots the APK's own Alpine minirootfs (aarch64 under qemu) with the app's default_mount overlay, `etc/minis/net.env` and CA bundle, and must pass `apk add gcc`, `pip download requests`, a stalled-mirror recovery, a dead-proxy run and `minis-doctor`. A fresh Mozilla CA bundle (curl.se) is fetched at build time into `default_mount/etc/ssl/minis-mozilla.pem`. An overlay audit step fails the build if a floating window is added without `plus/Overlays.kt`.
