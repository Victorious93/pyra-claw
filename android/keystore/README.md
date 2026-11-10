# Keystores

`pyraclaw-debug.jks` is a **debug-only** PKCS12 keystore. It is committed on purpose (same pattern as
other fleet repos, e.g. VictorGPS) so every machine and every CI run sign debug APKs with the same
certificate. That lets a new debug build install over the previous one without uninstalling, and keeps
the `EXTERNAL_NODE` signature permission stable.

- alias / store password / key password: `pyraclaw-debug` (debug-only, not a secret)
- SHA-256: `F7:5C:47:63:99:06:D1:C5:24:9A:04:D1:C2:E9:4A:57:76:D2:59:CA:49:7D:8F:14:19:69:96:C6:1B:23:72:35`

**No release key is committed.** `.gitignore` blocks other `*.jks` / `*.keystore` / `*.p12` files.
Release signing is not configured yet; when it is, the key will come from CI secrets.

Note: APKs built before v0.4.0 were signed with each CI runner's random debug key, so v0.4.0 can't
install over them. Uninstall the old Pyra Claw once, then install v0.4.0.
