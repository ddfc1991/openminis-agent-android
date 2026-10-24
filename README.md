# Mines — continued development of OpenMinis

Mines continues the development of [OpenMinis](https://github.com/OpenMinis/OpenMinis) (GPL-3.0).
The original OpenMinis `LICENSE` and copyright notices are kept unchanged; Mines is distributed under the
same GPL-3.0 terms. The upstream README is kept as [`docs/OpenMinis-README.md`](docs/OpenMinis-README.md).

## Branch `mines-main` = the Mines source tree

Since 38.9.12.1 (versionCode 54 / 1.23.12.1) the full Mines source lives in this branch as ordinary git history:

| commit | what |
|---|---|
| `b4c0661` and its parents | upstream OpenMinis, unchanged (real upstream history) |
| "Mines 38.9.12.1 source" | every Mines change up to 38.9.12.1 (= the old `hark-build/patches/minis-app-01…64.patch` applied to b4c0661, byte-identical tree) |
| "Repository layout" and later | build infrastructure, docs, then normal development commits |

There is no patch stack any more: edit files under `src/android/`, commit, push. The old patch-based branches
(`hark-minis-build`, `build-39xx`) are left as they were for history.

Layout:

- `src/android/` — the Android app (package `com.openminis.mines.plus`, label "Mines"). Mines' own code is mostly in
  `app/src/main/java/com/openminis/app/plus/` and `…/ui/plus/`; see [`MODULES.md`](MODULES.md) for the planned
  per-feature package map (not applied yet).
- `deps/`, `scripts/` — upstream native dependency and sandbox scripts. `deps/proot` stays an upstream submodule;
  Mines' one PRoot change is `hark-build/patches/proot-real-ifaces.patch`, applied by CI.
- `hark-build/` — build helpers: `mines-debug.keystore` (fixed signing key), `RELEASE` (build label),
  `sandbox-net-selftest.sh`, `smoke/` (emulator smoke test), `vps-worker/`, `probe/`, `notes/`.
- [`CHANGELOG.md`](CHANGELOG.md) — what changed in each version.

## Build (GitHub Actions)

`.github/workflows/mines-main.yml` ("Build Mines APK (mines-main, source tree)") runs on every push to `mines-main`
(not for `*.md`-only or smoke-only changes). It checks the branch out into `om/`, inits `deps/proot` and applies its
patch, then runs the same steps as the old build: DSH / market / free-entry / overlay / readability / sandbox-network
checks, native deps (proot, rootfs, rclone, sherpa-onnx, CAM++ model, mihomo — all pinned by SHA-256), `assembleRelease`,
re-sign v1+v2+v3 with `hark-build/mines-debug.keystore`, **fail unless the certificate SHA-256 is
`001814fb258110b003c851aeeac930fd3d803224eb9acdcab0a383edcb740e2b`** and the package is `com.openminis.mines.plus`,
then the unit tests. Output: run artifacts `mines-apk` (`mines-<RELEASE>.apk`) and `mines-mapping` (R8 mapping).
**It never publishes**: no release, no tag (`permissions: contents: read`).

Bump a version: change `versionCode` / `versionName` in `src/android/app/build.gradle.kts`, `hark-build/RELEASE`,
and add a `CHANGELOG.md` entry.

## Emulator smoke test

`.github/workflows/vps-smoke.yml` runs on pushes to `mines-main` touching `hark-build/smoke/`.
`hark-build/smoke/target` line 1 = `run:<build run id>` (that run's `mines-apk` artifact) or a release tag; line 2 = extra
smoke sections. It boots an API 34 x86_64 emulator (1080×2412, 480 dpi like the user's phone), starts local sshd /
fake OpenAI / IMAP / proxy / 诊断 relay on the runner, drives the real UI and fails on any FATAL EXCEPTION or ANR.

## Install note

Package `com.openminis.mines.plus`, always signed with the same key, so new builds install over the old one.
Brand icons on the Connections page: see `src/android/app/src/main/assets/licenses/mines-brand-icons.txt`
(Simple Icons CC0, Lobe Icons MIT, Remix Icon / IconPark Apache-2.0, Arcticons CC BY-SA 4.0).
