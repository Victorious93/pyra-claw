# OTA public key (optional, local only)

Put `ota_pub.pem` here (or set `PYRA_OTA_PUBKEY=/path/ota_pub.pem`) before `pio run` to
compile OTA signature checking in. This folder is git-ignored except this file.
**Never** put `ota_priv.pem` here. Make keys with `python3 tools/ota_sign.py keygen`.
