"""Signed OTA (protocol v0.5): manifest checks, signatures, approval gate, download tokens."""

import hashlib
import importlib.util
import json
from pathlib import Path

import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi.testclient import TestClient

from pyra_hub.api import create_app
from pyra_hub.config import HubConfig, NodeConfig, load_config
from pyra_hub.ota import (
    FirmwareStore,
    OtaConfig,
    OtaError,
    check_manifest,
    signed_message,
    signed_ota_cmd,
    version_code,
)
from pyra_hub.registry import Registry
from pyra_hub.signing import verify

TOKEN = "t"
H = {"Authorization": f"Bearer {TOKEN}"}
NODE_KEY = "node-secret"
TOOL = Path(__file__).resolve().parents[2] / "tools" / "ota_sign.py"


class FakeBridge:
    def __init__(self):
        self.registry = Registry()
        self.client = object()
        self.sent = []

    def publish_cmd(self, node, payload):
        self.sent.append((node, payload))
        return True


def _keypair(tmp: Path, name="k"):
    key = ec.generate_private_key(ec.SECP256R1())
    priv = tmp / f"{name}_priv.pem"
    pub = tmp / f"{name}_pub.pem"
    priv.write_bytes(
        key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
    )
    pub.write_bytes(
        key.public_key().public_bytes(
            serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo
        )
    )
    return key, priv, pub


def _image(fw_dir: Path, key, version="0.5.0", payload=b"\xe9fake-esp32-app" * 64):
    fw_dir.mkdir(parents=True, exist_ok=True)
    name = f"pyra-claw-esp32-wake-{version}"
    sha = hashlib.sha256(payload).hexdigest()
    code = version_code(version)
    msg = signed_message("pyra-claw-esp32-wake", version, code, len(payload), sha)
    sig = key.sign(msg, ec.ECDSA(hashes.SHA256())).hex()
    (fw_dir / f"{name}.bin").write_bytes(payload)
    manifest = {
        "format": "pyra-ota/1",
        "project": "pyra-claw-esp32-wake",
        "version": version,
        "version_code": code,
        "size": len(payload),
        "sha256": sha,
        "sig": sig,
    }
    (fw_dir / f"{name}.json").write_text(json.dumps(manifest))
    return name, manifest


def _client(tmp: Path, pub: Path | None = None, base_url="http://hub.local:8787", bridge=None):
    cfg = HubConfig(
        auth_token=TOKEN,
        audit_log="",
        node=NodeConfig(hub_token=NODE_KEY),
        ota=OtaConfig(
            firmware_dir=str(tmp / "fw"), base_url=base_url, public_key=str(pub) if pub else ""
        ),
    )
    bridge = bridge or FakeBridge()
    return TestClient(create_app(cfg, bridge, start_scheduler=False)), bridge


def _start(c, image, node="esp32-wake-01", approve=True):
    body = {
        "type": "remote.command",
        "command": "node.ota",
        "target": node,
        "args": {"image": image},
    }
    if approve:
        body["approval_token"] = c.post("/api/approve/node.ota", headers=H).json()["approval_token"]
    return c.post("/api/command", json=body, headers=H)


def test_version_code():
    assert version_code("0.5.0") == 500
    assert version_code("1.2.3") == 10203
    for bad in ("0.5", "1.2.3.4", "a.b.c", "100.0.0", ""):
        with pytest.raises(OtaError):
            version_code(bad)


def test_manifest_shape_checks(tmp_path):
    key, _, _ = _keypair(tmp_path)
    _, m = _image(tmp_path / "fw", key)
    assert check_manifest(m)["version_code"] == 500
    for field, bad in [
        ("format", "x"),
        ("project", "other"),
        ("version_code", 501),
        ("size", 0),
        ("size", 0x1E0001),
        ("sha256", "ABC"),
        ("sig", "zz"),
    ]:
        with pytest.raises(OtaError):
            check_manifest({**m, field: bad})


def test_store_lists_valid_and_flags_tampered(tmp_path):
    key, _, pub = _keypair(tmp_path)
    good, _ = _image(tmp_path / "fw", key, "0.5.0")
    bad, _ = _image(tmp_path / "fw", key, "0.6.0")
    (tmp_path / "fw" / f"{bad}.bin").write_bytes(b"\xe9tampered")
    store = FirmwareStore(OtaConfig(firmware_dir=str(tmp_path / "fw"), public_key=str(pub)))
    imgs = {i["image"]: i for i in store.list()}
    assert imgs[good]["valid"] and imgs[good]["hub_verified"]
    assert not imgs[bad]["valid"] and "mismatch" in imgs[bad]["error"]
    assert [i["version_code"] for i in store.list()] == [600, 500]  # newest first


def test_store_rejects_wrong_signing_key(tmp_path):
    key, _, _ = _keypair(tmp_path, "a")
    _, _, other_pub = _keypair(tmp_path, "b")
    name, _ = _image(tmp_path / "fw", key)
    store = FirmwareStore(OtaConfig(firmware_dir=str(tmp_path / "fw"), public_key=str(other_pub)))
    with pytest.raises(OtaError) as e:
        store.get(name)
    assert "signature" in str(e.value)


def test_signed_message_signature_covers_version(tmp_path):
    key, _, pub = _keypair(tmp_path)
    name, m = _image(tmp_path / "fw", key)
    # Re-label the same signed image as a newer version: signature must fail.
    m2 = {**m, "version": "0.9.0", "version_code": 900}
    (tmp_path / "fw" / f"{name}.json").write_text(json.dumps(m2))
    store = FirmwareStore(OtaConfig(firmware_dir=str(tmp_path / "fw"), public_key=str(pub)))
    assert store.list()[0]["valid"] is False


def test_bad_public_key_disables_all(tmp_path):
    key, _, _ = _keypair(tmp_path)
    _image(tmp_path / "fw", key)
    junk = tmp_path / "junk.pem"
    junk.write_text("not a key")
    store = FirmwareStore(OtaConfig(firmware_dir=str(tmp_path / "fw"), public_key=str(junk)))
    assert store.key_error and store.list()[0]["valid"] is False


def test_ota_needs_approval(tmp_path):
    key, _, pub = _keypair(tmp_path)
    name, _ = _image(tmp_path / "fw", key)
    c, bridge = _client(tmp_path, pub)
    r = _start(c, name, approve=False)
    assert r.status_code == 403 and bridge.sent == []
    body = {"type": "remote.command", "command": "node.ota", "args": {"image": name}}
    wrong = c.post("/api/approve/pc.power_pulse", headers=H).json()["approval_token"]
    assert (
        c.post("/api/command", json={**body, "approval_token": wrong}, headers=H).status_code == 403
    )


def test_ota_start_publishes_signed_command(tmp_path):
    key, _, pub = _keypair(tmp_path)
    name, m = _image(tmp_path / "fw", key)
    c, bridge = _client(tmp_path, pub)
    r = _start(c, name)
    assert r.status_code == 200, r.text
    assert r.json()["result"] == {
        "node": "esp32-wake-01",
        "image": name,
        "version": "0.5.0",
        "relayed": True,
    }
    node, p = bridge.sent[0]
    assert node == "esp32-wake-01" and p["action"] == "ota"
    assert p["img_sig"] == m["sig"] and p["sha256"] == m["sha256"] and p["size"] == m["size"]
    assert verify(NODE_KEY, p["id"], "ota", p["version_code"], p["ts"], p["sig"])
    assert not verify("other", p["id"], "ota", p["version_code"], p["ts"], p["sig"])
    assert p["url"].startswith(f"http://hub.local:8787/ota/files/{name}.bin?t=")
    events = [e["event"] for e in c.get("/api/audit", headers=H).json()["events"]]
    assert "ota_started" in events and "approval_issued" in events


def test_download_requires_token_and_returns_image(tmp_path):
    key, _, pub = _keypair(tmp_path)
    name, m = _image(tmp_path / "fw", key)
    c, bridge = _client(tmp_path, pub)
    assert c.get(f"/ota/files/{name}.bin").status_code == 403
    assert c.get(f"/ota/files/{name}.bin?t=nope").status_code == 403
    _start(c, name)
    url = bridge.sent[0][1]["url"].replace("http://hub.local:8787", "")
    r = c.get(url)
    assert r.status_code == 200 and hashlib.sha256(r.content).hexdigest() == m["sha256"]
    assert c.get(url).status_code == 200  # reusable for retries within the TTL
    assert c.get(f"/ota/files/{name}.json?t=x").status_code == 404
    assert c.get("/ota/files/..%2Fsecret.bin?t=x").status_code in (400, 404)


def test_download_token_expires(tmp_path):
    key, _, _ = _keypair(tmp_path)
    name, _ = _image(tmp_path / "fw", key)
    store = FirmwareStore(OtaConfig(firmware_dir=str(tmp_path / "fw")))
    tok = store.issue_download(name, now=1000)
    assert store.resolve_download(name, tok, now=1500).name == f"{name}.bin"
    with pytest.raises(OtaError):
        store.resolve_download(name, tok, now=1000 + 601)
    with pytest.raises(OtaError):
        store.resolve_download("other", tok, now=1001)


def test_ota_refuses_downgrade_when_node_reports_newer(tmp_path):
    key, _, pub = _keypair(tmp_path)
    name, _ = _image(tmp_path / "fw", key, "0.5.0")
    bridge = FakeBridge()
    bridge.registry.touch("esp32-wake-01", {"fw": "0.5.0", "fw_code": 500})
    c, _ = _client(tmp_path, pub, bridge=bridge)
    r = _start(c, name)
    assert r.status_code == 409 and "anti-rollback" in r.json()["detail"]
    assert bridge.sent == []


def test_ota_disabled_without_base_url_or_hub_token(tmp_path):
    key, _, pub = _keypair(tmp_path)
    name, _ = _image(tmp_path / "fw", key)
    c, _ = _client(tmp_path, pub, base_url="")
    assert _start(c, name).status_code == 503
    cfg = HubConfig(
        auth_token=TOKEN,
        audit_log="",
        node=NodeConfig(hub_token="CHANGE_ME_NODE"),
        ota=OtaConfig(firmware_dir=str(tmp_path / "fw"), base_url="http://h"),
    )
    c2 = TestClient(create_app(cfg, FakeBridge(), start_scheduler=False))
    assert _start(c2, name).status_code == 503


def test_ota_unknown_or_invalid_image(tmp_path):
    key, _, pub = _keypair(tmp_path)
    c, _ = _client(tmp_path, pub)
    assert _start(c, "pyra-claw-esp32-wake-9.9.9").status_code == 404
    assert _start(c, "../etc/passwd").status_code == 400
    body = {"type": "remote.command", "command": "node.ota", "args": {}}
    tok = c.post("/api/approve/node.ota", headers=H).json()["approval_token"]
    assert (
        c.post("/api/command", json={**body, "approval_token": tok}, headers=H).status_code == 422
    )


def test_api_ota_lists_images_and_nodes(tmp_path):
    key, _, pub = _keypair(tmp_path)
    name, _ = _image(tmp_path / "fw", key)
    bridge = FakeBridge()
    bridge.registry.touch("esp32-wake-01", {"fw": "0.4.0", "fw_code": 400, "ota": "valid"})
    c, _ = _client(tmp_path, pub, bridge=bridge)
    d = c.get("/api/ota", headers=H).json()
    assert d["enabled"] and d["hub_verifies"]
    assert d["images"][0]["image"] == name and d["images"][0]["valid"]
    assert d["nodes"][0]["fw_code"] == 400
    assert c.get("/api/ota").status_code == 401


def test_timers_cannot_contain_ota(tmp_path):
    c, _ = _client(tmp_path)
    t = {
        "name": "nightly ota",
        "schedule": {"kind": "manual"},
        "steps": [{"command": "node.ota", "target": "esp32-wake-01", "args": {"image": "x"}}],
    }
    r = c.post("/api/timers", json=t, headers=H)
    assert r.status_code == 422 and "timer" in r.json()["detail"]


def test_signed_ota_cmd_without_hub_side_key(tmp_path):
    key, _, _ = _keypair(tmp_path)
    name, _ = _image(tmp_path / "fw", key)
    store = FirmwareStore(OtaConfig(firmware_dir=str(tmp_path / "fw"), base_url="http://h/"))
    info = store.get(name)
    assert info["hub_verified"] is False
    p = signed_ota_cmd(NODE_KEY, "abc", info, store.download_url(name, "tok"), ts=1700000000)
    assert p["url"] == f"http://h/ota/files/{name}.bin?t=tok" and p["ts"] == 1700000000


def test_config_loads_ota_section(tmp_path):
    f = tmp_path / "c.toml"
    f.write_text('[ota]\nfirmware_dir = "/x"\nbase_url = "http://hub:8787"\n')
    cfg = load_config(f)
    assert cfg.ota.firmware_dir == "/x" and cfg.ota.base_url == "http://hub:8787"


def test_signing_tool_roundtrip_matches_hub(tmp_path):
    """tools/ota_sign.py (what Don runs) must produce manifests the hub accepts."""
    spec = importlib.util.spec_from_file_location("ota_sign", TOOL)
    tool = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(tool)
    keys = tmp_path / "keys"
    assert tool.main(["keygen", "--dir", str(keys)]) == 0
    assert (keys / "ota_priv.pem").stat().st_mode & 0o077 == 0
    fw = tmp_path / "firmware.bin"
    fw.write_bytes(b"\xe9" + b"\x00" * 4096)
    out = tmp_path / "fw"
    args = ["sign", "--key", str(keys / "ota_priv.pem"), "--bin", str(fw)]
    assert tool.main([*args, "--version", "0.5.0", "--out", str(out)]) == 0
    manifest = str(out / "pyra-claw-esp32-wake-0.5.0.json")
    assert tool.main(["verify", "--pub", str(keys / "ota_pub.pem"), "--manifest", manifest]) == 0
    store = FirmwareStore(OtaConfig(firmware_dir=str(out), public_key=str(keys / "ota_pub.pem")))
    assert store.get("pyra-claw-esp32-wake-0.5.0")["hub_verified"] is True
    bad = tmp_path / "bad.bin"
    bad.write_bytes(b"\x00" * 10)
    with pytest.raises(SystemExit):
        tool.main(
            [
                "sign",
                "--key",
                str(keys / "ota_priv.pem"),
                "--bin",
                str(bad),
                "--version",
                "0.5.0",
                "--out",
                str(out),
            ]
        )
