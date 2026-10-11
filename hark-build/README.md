# minis-build

Builds the OpenMinis Android app from upstream commit b4c0661 plus the patches in `patches/`:

- `minis-app-fixes.patch`: real network addresses in the sandbox, dynamic phantom-process budget, ssh in the long-timeout class, `minis-vps` (VPS back end), Minis as a device on the nanoMuse hub (`minis-muse`).
- `proot-real-ifaces.patch`: PRoot `fake_netlink` returns the device's real interfaces.

Actions → "Build Minis APK" → Run workflow. The APK is attached to the run as `minis-apk`.

Note: this APK is signed with this build's own debug key, so Android will not install it over an existing Minis. Back up Minis first, uninstall, install this, restore.
