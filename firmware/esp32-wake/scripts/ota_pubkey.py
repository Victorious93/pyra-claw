# PlatformIO pre-script: compiles the OTA *public* key into the firmware.
#
# Key source (first found): env PYRA_OTA_PUBKEY=<path to PEM>, else keys/ota_pub.pem.
# Only the PUBLIC key is read. The private key never belongs in this repo
# (see docs/MANUAL.md "Signed OTA updates"). No key = OTA disabled (node rejects every
# update with ota:rejected_no_pubkey) but the build still succeeds.
import base64
import os
import re

Import("env")  # noqa: F821  (PlatformIO SCons global)

# DER prefix of a P-256 SubjectPublicKeyInfo with an uncompressed point (91 bytes total).
P256_SPKI_PREFIX = bytes.fromhex("3059301306072a8648ce3d020106082a8648ce3d030107034200")

project_dir = env.subst("$PROJECT_DIR")  # noqa: F821
path = os.environ.get("PYRA_OTA_PUBKEY") or os.path.join(project_dir, "keys", "ota_pub.pem")

if os.path.isfile(path):
    pem = open(path, encoding="ascii").read()
    if "PRIVATE" in pem:
        raise SystemExit("PYRA_OTA_PUBKEY points at a PRIVATE key. Use ota_pub.pem only.")
    m = re.search(r"-----BEGIN PUBLIC KEY-----(.+?)-----END PUBLIC KEY-----", pem, re.S)
    if not m:
        raise SystemExit(f"{path}: not a PEM public key")
    der = base64.b64decode("".join(m.group(1).split()))
    if len(der) != 91 or not der.startswith(P256_SPKI_PREFIX):
        raise SystemExit(f"{path}: OTA key must be ECDSA P-256 (prime256v1), uncompressed")
    env.Append(CPPDEFINES=[("OTA_PUBKEY_DER_HEX", env.StringifyMacro(der.hex()))])  # noqa: F821
    print(f"OTA: public key compiled in from {path}")
else:
    print("OTA: no public key (set PYRA_OTA_PUBKEY or add keys/ota_pub.pem); OTA disabled")
