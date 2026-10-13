# Mines — continued development of OpenMinis

Mines continues the development of [OpenMinis](https://github.com/OpenMinis/OpenMinis)
(GPL-3.0). It is built from upstream commit `b4c0661` plus the patches in `patches/`.
The original OpenMinis `LICENSE` and copyright notices are kept unchanged; Mines
is distributed under the same GPL-3.0 terms.

- `minis-app-*.patch`: the Mines app changes (package `com.openminis.mines.plus`,
  label "Mines"), split into parts (≤ 90 KB each, binary-safe) because of an upload size
  limit; applied in order. `split_patches.py` regenerates them from the staged om-src tree,
  `push_patches.py <first> <last> <total> "<msg>"` uploads them (all but the last with [skip ci]).
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

Brand icons on the Connections page: see `app/src/main/assets/licenses/mines-brand-icons.txt`
(Simple Icons CC0, Lobe Icons MIT, Remix Icon / IconPark Apache-2.0, Arcticons CC BY-SA 4.0).

## Staged tree (since build-24)
`tree/` is a git checkout of upstream b4c0661 with local commits: `d8cba85` = patches 01–44,
`34af0e2` = + patch 45 (build-24), `8efb3d1` = + patch 46 (build-25), `3c57c53` = + patch 47 (build-26), `1c1be6a` = + patch 48 (build-27), `644230d` = + patch 49 (build-28), `a5f7e86` = + patch 50 (build-29), `8b626e8` = + patch 51 (build-30). A new build's patch is
`git diff --cached --binary <last commit> -- src/android` → `remote/patches/minis-app-NN.patch`,
pushed with `gitpush.py` (one commit, Git Data API). `minis-dsh selftest` (run locally or in CI)
must pass: it uses the same commands the app runs (`minis-dsh add/list/web`) and loads the
widget page in headless Chrome.

## Sandbox network self-test (since build-26)
`hark-build/sandbox-net-selftest.sh` boots the APK's own Alpine minirootfs (aarch64 under qemu) with the app's default_mount overlay, `etc/minis/net.env` and CA bundle, and must pass `apk add gcc`, `pip download requests`, a stalled-mirror recovery, a dead-proxy run and `minis-doctor`. A fresh Mozilla CA bundle (curl.se) is fetched at build time into `default_mount/etc/ssl/minis-mozilla.pem`. An overlay audit step fails the build if a floating window is added without `plus/Overlays.kt`.

## Emulator smoke test (since build-28)
`.github/workflows/vps-smoke.yml` runs on pushes touching `hark-build/smoke/`. `hark-build/smoke/target` names what to test
(`build-27` = a release tag, or `run:<actions run id>` = that run's `mines-apk` artifact). It boots API 34 + 35 x86_64
emulators, starts a local sshd and a fake OpenAI endpoint on the runner, drives the real UI with adb/uiautomator
(onboarding → chat → home Today card → photo wallpaper on home + desk pet → cold start → 工作位置 → servers) and fails
on any FATAL EXCEPTION of the app in logcat.
