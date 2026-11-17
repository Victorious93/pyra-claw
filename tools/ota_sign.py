#!/usr/bin/env python3
"""Pyra Claw OTA signing tool (ECDSA P-256 / SHA-256). Runs on YOUR machine, not the hub.

  keygen  --dir ~/.pyra-claw-ota          make ota_priv.pem (0600) + ota_pub.pem
  sign    --key ~/.pyra-claw-ota/ota_priv.pem --bin firmware.bin --version 0.5.0 --out DIR
          writes DIR/pyra-claw-esp32-wake-0.5.0.bin and .json (manifest)
  verify  --pub ota_pub.pem --manifest DIR/pyra-claw-esp32-wake-0.5.0.json

The private key must never be committed or copied to the hub. In CI it comes from the
secret PYRA_OTA_SIGNING_KEY (see .github/workflows/firmware-sign.yml). Nothing here
prints key material.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
from pathlib import Path

FORMAT = "pyra-ota/1"
PROJECT = "pyra-claw-esp32-wake"
MAX_IMAGE_SIZE = 0x1E0000


def version_code(version: str) -> int:
    parts = version.split(".")
    if len(parts) != 3 or not all(p.isdigit() and len(p) <= 2 for p in parts):
        raise SystemExit(f"bad version {version!r} (want X.Y.Z, each 0-99)")
    a, b, c = (int(p) for p in parts)
    return a * 10000 + b * 100 + c


def message(project: str, version: str, code: int, size: int, sha: str) -> bytes:
    return f"{FORMAT}|{project}|{version}|{code}|{size}|{sha}".encode()


def keygen(args) -> int:
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import ec

    d = Path(args.dir).expanduser()
    d.mkdir(parents=True, exist_ok=True)
    priv_path, pub_path = d / "ota_priv.pem", d / "ota_pub.pem"
    if priv_path.exists() and not args.force:
        raise SystemExit(f"{priv_path} exists (use --force to replace; old devices keep the old key)")
    key = ec.generate_private_key(ec.SECP256R1())
    pem = key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    )
    fd = os.open(priv_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(pem)
    pub_path.write_bytes(
        key.public_key().public_bytes(
            serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo
        )
    )
    print(f"private key: {priv_path} (mode 0600, keep it OFF the repo and OFF the hub)")
    print(f"public key:  {pub_path} (build firmware with PYRA_OTA_PUBKEY={pub_path})")
    return 0


def sign(args) -> int:
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.hazmat.primitives.serialization import load_pem_private_key

    data = Path(args.bin).read_bytes()
    if not 0 < len(data) <= MAX_IMAGE_SIZE:
        raise SystemExit(f"image is {len(data)} bytes; OTA slot is {MAX_IMAGE_SIZE}")
    if data[0] != 0xE9:
        raise SystemExit("not an ESP32 app image (first byte != 0xE9); use .pio/.../firmware.bin")
    key = load_pem_private_key(Path(args.key).expanduser().read_bytes(), password=None)
    if not isinstance(key, ec.EllipticCurvePrivateKey) or key.curve.name != "secp256r1":
        raise SystemExit("signing key must be ECDSA P-256")
    code = version_code(args.version)
    sha = hashlib.sha256(data).hexdigest()
    sig = key.sign(message(PROJECT, args.version, code, len(data), sha), ec.ECDSA(hashes.SHA256()))
    name = f"{PROJECT}-{args.version}"
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / f"{name}.bin").write_bytes(data)
    manifest = {
        "format": FORMAT,
        "project": PROJECT,
        "version": args.version,
        "version_code": code,
        "size": len(data),
        "sha256": sha,
        "sig": sig.hex(),
    }
    (out / f"{name}.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"signed {name}: {len(data)} bytes, version_code {code}, sha256 {sha[:16]}…")
    return 0


def verify(args) -> int:
    from cryptography.exceptions import InvalidSignature
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.hazmat.primitives.serialization import load_pem_public_key

    mpath = Path(args.manifest)
    m = json.loads(mpath.read_text())
    data = mpath.with_suffix(".bin").read_bytes()
    ok = len(data) == m["size"] and hashlib.sha256(data).hexdigest() == m["sha256"]
    ok = ok and m["version_code"] == version_code(m["version"]) and m["format"] == FORMAT
    pub = load_pem_public_key(Path(args.pub).read_bytes())
    try:
        msg = message(m["project"], m["version"], m["version_code"], m["size"], m["sha256"])
        pub.verify(bytes.fromhex(m["sig"]), msg, ec.ECDSA(hashes.SHA256()))
    except (InvalidSignature, ValueError):
        ok = False
    print("OK" if ok else "FAILED")
    return 0 if ok else 1


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    k = sub.add_parser("keygen")
    k.add_argument("--dir", default="~/.pyra-claw-ota")
    k.add_argument("--force", action="store_true")
    s = sub.add_parser("sign")
    s.add_argument("--key", required=True)
    s.add_argument("--bin", required=True)
    s.add_argument("--version", required=True)
    s.add_argument("--out", required=True)
    v = sub.add_parser("verify")
    v.add_argument("--pub", required=True)
    v.add_argument("--manifest", required=True)
    args = p.parse_args(argv)
    return {"keygen": keygen, "sign": sign, "verify": verify}[args.cmd](args)


if __name__ == "__main__":
    sys.exit(main())
