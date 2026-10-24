# Changelog

## 38.9.12.1 — repository reorganisation (mines-main)

Version unchanged: versionCode 54 / versionName 1.23.12.1. **No functional or UI change** (weather skin unchanged).

- The Mines source is now ordinary git history on branch `mines-main`: upstream OpenMinis `b4c0661` + one commit with
  all Mines changes (the former `hark-build/patches/minis-app-01…64.patch`, verified byte-identical: same git tree as
  the 38.9.12.1 build). CI no longer applies patches one by one.
- New workflow `mines-main.yml`: build → sign with the fixed Mines key → verify certificate SHA-256 → unit tests →
  artifact. Never publishes.
- `vps-smoke.yml` now runs on `mines-main`.
- `MODULES.md`: planned per-feature package map for `com.openminis.app.plus` (no files moved yet).
- `README.md` rewritten for the new flow; upstream README kept as `docs/OpenMinis-README.md`.

## 38.9.12.1 (built 2026-10-23 on build-3912, CI run 39886347792)

Screenshot OOM fix and safe start after an OOM, `:acra` process WebView-lock crash loop fixed, home back to the
38.9.11.3.1 layout, in-call screen keeps 接听, knowledge base as the HUD, 自建域名 inbox client.
