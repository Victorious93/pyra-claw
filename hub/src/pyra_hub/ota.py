"""Signed OTA firmware updates for ESP32 nodes (protocol v0.5, UNTESTED ON HARDWARE).

Layout of ``[ota] firmware_dir`` (default /var/lib/pyra-claw/firmware)::

    pyra-claw-esp32-wake-0.5.0.bin    firmware image (pio run output)
    pyra-claw-esp32-wake-0.5.0.json   manifest written by tools/ota_sign.py

Manifest (format ``pyra-ota/1``)::

    {"format": "pyra-ota/1", "project": "pyra-claw-esp32-wake", "version": "0.5.0",
     "version_code": 500, "size": 1234567, "sha256": "<64 hex>", "sig": "<hex DER>"}

``sig`` is ECDSA P-256 / SHA-256 over :func:`signed_message`. The private key never touches
the hub or the repo; the hub only *serves* images. If ``[ota] public_key`` is set the hub
also verifies each manifest and refuses to offer an image that fails (defence in depth;
the ESP32 always verifies with the key compiled into its firmware).

Starting an update is the ``node.ota`` remote.command, which is approval-gated like
``pc.power_pulse``: POST /api/approve/node.ota, then POST /api/command with the token.
The MQTT command to the node is HMAC-signed with ``hub_token`` (action ``ota``, the
version_code in the ``ms`` slot) so a rogue MQTT client can't start an update either.
"""

from __future__ import annotations

import hashlib
import json
import re
import secrets
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .signing import sign

FORMAT = "pyra-ota/1"
PROJECT = "pyra-claw-esp32-wake"
NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,95}$")
SHA_RE = re.compile(r"^[0-9a-f]{64}$")
SIG_RE = re.compile(r"^[0-9a-f]{16,160}$")
VERSION_RE = re.compile(r"^(\d{1,2})\.(\d{1,2})\.(\d{1,2})$")
# App slot of firmware/esp32-wake/partitions_ota.csv (two OTA slots of 0x1E0000).
MAX_IMAGE_SIZE = 0x1E0000
DOWNLOAD_TTL_S = 600


class OtaError(ValueError):
    """Bad image/manifest. ``status`` is the HTTP status the API should answer with."""

    def __init__(self, msg: str, status: int = 422) -> None:
        super().__init__(msg)
        self.status = status


def version_code(version: str) -> int:
    """ "0.5.0" -> 500 (major*10000 + minor*100 + patch). Must match FW_VERSION_CODE."""
    m = VERSION_RE.match(version or "")
    if not m:
        raise OtaError(f"bad version {version!r} (want X.Y.Z, each 0-99)")
    a, b, c = (int(x) for x in m.groups())
    return a * 10000 + b * 100 + c


def signed_message(project: str, version: str, code: int, size: int, sha256: str) -> bytes:
    """Exact bytes the ECDSA signature covers. The ESP32 builds the same string."""
    for part in (project, version, sha256):
        if "|" in part:
            raise OtaError("'|' not allowed in manifest fields")
    return f"{FORMAT}|{project}|{version}|{int(code)}|{int(size)}|{sha256}".encode()


def load_public_key(pem: bytes):
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.hazmat.primitives.serialization import load_pem_public_key

    key = load_pem_public_key(pem)
    if not isinstance(key, ec.EllipticCurvePublicKey) or key.curve.name != "secp256r1":
        raise OtaError("OTA public key must be ECDSA P-256 (prime256v1)")
    return key


def verify_signature(public_key, message: bytes, sig_hex: str) -> bool:
    from cryptography.exceptions import InvalidSignature
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import ec

    try:
        public_key.verify(bytes.fromhex(sig_hex), message, ec.ECDSA(hashes.SHA256()))
        return True
    except (InvalidSignature, ValueError):
        return False


def check_manifest(m: dict[str, Any]) -> dict[str, Any]:
    """Shape checks only (no file / signature). Returns the normalised manifest."""
    if m.get("format") != FORMAT:
        raise OtaError(f"manifest format must be {FORMAT!r}")
    if m.get("project") != PROJECT:
        raise OtaError(f"manifest project must be {PROJECT!r}")
    code = version_code(str(m.get("version", "")))
    if m.get("version_code") != code:
        raise OtaError(f"version_code {m.get('version_code')!r} != {code} for {m['version']}")
    size = m.get("size")
    if not isinstance(size, int) or not 0 < size <= MAX_IMAGE_SIZE:
        raise OtaError(f"size must be 1..{MAX_IMAGE_SIZE} bytes")
    if not isinstance(m.get("sha256"), str) or not SHA_RE.match(m["sha256"]):
        raise OtaError("sha256 must be 64 lowercase hex chars")
    if not isinstance(m.get("sig"), str) or not SIG_RE.match(m["sig"]):
        raise OtaError("sig must be lowercase hex (DER ECDSA signature)")
    return {
        k: m[k] for k in ("format", "project", "version", "version_code", "size", "sha256", "sig")
    }


@dataclass
class OtaConfig:
    firmware_dir: str = "/var/lib/pyra-claw/firmware"
    # How nodes reach the hub, e.g. "http://192.168.1.20:8787". Empty = OTA disabled (503).
    base_url: str = ""
    # Optional PEM (public key only) so the hub refuses images that wouldn't verify anyway.
    public_key: str = ""


class FirmwareStore:
    def __init__(self, cfg: OtaConfig) -> None:
        self.cfg = cfg
        self.dir = Path(cfg.firmware_dir)
        self._downloads: dict[str, tuple[str, float]] = {}
        self._lock = threading.Lock()
        self._pub = None
        self.key_error: str | None = None
        if cfg.public_key:
            try:
                self._pub = load_public_key(Path(cfg.public_key).read_bytes())
            except (OSError, ValueError) as e:  # OtaError is a ValueError
                self.key_error = f"can't load ota.public_key: {e}"

    # ---- images -------------------------------------------------------------------
    def _inspect(self, name: str) -> dict[str, Any]:
        info: dict[str, Any] = {"image": name, "valid": False}
        try:
            raw = json.loads((self.dir / f"{name}.json").read_text())
            m = check_manifest(raw)
            info.update({k: m[k] for k in ("version", "version_code", "size", "sha256")})
            bin_path = self.dir / f"{name}.bin"
            data = bin_path.read_bytes()
            if len(data) != m["size"]:
                raise OtaError(f"size mismatch: file {len(data)} != manifest {m['size']}")
            if hashlib.sha256(data).hexdigest() != m["sha256"]:
                raise OtaError("sha256 mismatch between .bin and manifest")
            msg = signed_message(
                m["project"], m["version"], m["version_code"], m["size"], m["sha256"]
            )
            if self.key_error:
                raise OtaError(self.key_error)
            if self._pub is not None:
                if not verify_signature(self._pub, msg, m["sig"]):
                    raise OtaError("signature does not verify with ota.public_key")
                info["hub_verified"] = True
            else:
                info["hub_verified"] = False
            info["manifest"] = m
            info["valid"] = True
        except FileNotFoundError as e:
            info["error"] = f"missing file: {Path(e.filename).name if e.filename else e}"
        except (OtaError, ValueError, json.JSONDecodeError) as e:
            info["error"] = str(e)
        return info

    def list(self) -> list[dict[str, Any]]:
        if not self.dir.is_dir():
            return []
        names = sorted({p.stem for p in self.dir.glob("*.json") if NAME_RE.match(p.stem)})
        out = []
        for n in names:
            i = self._inspect(n)
            i.pop("manifest", None)
            out.append(i)
        return sorted(out, key=lambda i: i.get("version_code", -1), reverse=True)

    def get(self, name: str) -> dict[str, Any]:
        if not NAME_RE.match(name or ""):
            raise OtaError("bad image name", 400)
        if not (self.dir / f"{name}.json").exists():
            raise OtaError(f"no such image {name!r}", 404)
        info = self._inspect(name)
        if not info["valid"]:
            raise OtaError(f"image {name!r} invalid: {info.get('error')}", 422)
        return info

    # ---- one-time-ish download tokens (valid DOWNLOAD_TTL_S, reusable for retries) --
    def issue_download(self, name: str, now: float | None = None) -> str:
        now = time.time() if now is None else now
        tok = secrets.token_urlsafe(18)
        with self._lock:
            self._downloads = {k: v for k, v in self._downloads.items() if v[1] > now}
            self._downloads[tok] = (name, now + DOWNLOAD_TTL_S)
        return tok

    def resolve_download(self, name: str, token: str | None, now: float | None = None) -> Path:
        now = time.time() if now is None else now
        with self._lock:
            entry = self._downloads.get(token or "")
        if not entry or entry[0] != name or entry[1] < now:
            raise OtaError("download token missing, expired or for another image", 403)
        return self.dir / f"{name}.bin"

    def download_url(self, name: str, token: str) -> str:
        base = self.cfg.base_url.rstrip("/")
        return f"{base}/ota/files/{name}.bin?t={token}"


def signed_ota_cmd(
    hub_token: str, cmd_id: str, info: dict[str, Any], url: str, ts: int | None = None
) -> dict[str, Any]:
    """MQTT body for ``{"action":"ota"}``. ``sig`` = HMAC (hub_token); ``img_sig`` = ECDSA."""
    ts = int(time.time()) if ts is None else int(ts)
    m = info["manifest"]
    return {
        "action": "ota",
        "id": cmd_id,
        "url": url,
        "project": m["project"],
        "version": m["version"],
        "version_code": m["version_code"],
        "size": m["size"],
        "sha256": m["sha256"],
        "img_sig": m["sig"],
        "ts": ts,
        "sig": sign(hub_token, cmd_id, "ota", m["version_code"], ts),
    }
