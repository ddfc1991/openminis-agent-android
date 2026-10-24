# hark-build — build helpers for Mines

On `mines-main` the app source is in `src/android/` as normal commits; this folder only holds what CI needs around it.
See the repository [README](../README.md).

- `mines-debug.keystore` — the fixed signing key (alias `androiddebugkey`). Every build is signed with it and the
  workflow fails if the APK certificate SHA-256 is not
  `001814fb258110b003c851aeeac930fd3d803224eb9acdcab0a383edcb740e2b`.
- `RELEASE` — build label (`38.9.12.1`); its integer part is `MINES_BUILD` for the in-app update check.
- `patches/proot-real-ifaces.patch` — PRoot `fake_netlink` returns the device's real interfaces (applied to the
  `deps/proot` submodule by CI). The old `minis-app-*.patch` stack is gone from this branch; it is still on
  `build-3912` / `hark-minis-build` for history.
- `sandbox-net-selftest.sh` — boots the APK's own Alpine minirootfs (aarch64 under qemu) with the app's overlay, env
  and CA bundle; must pass `apk add gcc`, `pip download requests`, stalled-mirror recovery, dead-proxy run, `minis-doctor`.
- `smoke/` — emulator smoke test (`vps-smoke.yml`); `smoke/target` line 1 = `run:<build run id>` or a release tag.
- `vps-worker/` — the server side of the mines-link channel. `probe/` — web probe helper. `notes/` — release notes.
- `free-offers.json`, `selftest/`, `release/` — kept from the patch-era build.
