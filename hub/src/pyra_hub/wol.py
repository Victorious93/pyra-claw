"""Wake-on-LAN magic packet sender."""

from __future__ import annotations

import re
import socket

_MAC_RE = re.compile(r"^[0-9A-Fa-f]{2}([:-]?[0-9A-Fa-f]{2}){5}$")


def parse_mac(mac: str) -> bytes:
    if not _MAC_RE.match(mac):
        raise ValueError(f"invalid MAC address: {mac!r}")
    return bytes.fromhex(mac.replace(":", "").replace("-", ""))


def magic_packet(mac: str) -> bytes:
    return b"\xff" * 6 + parse_mac(mac) * 16


def send_wol(mac: str, broadcast: str = "255.255.255.255", port: int = 9) -> int:
    pkt = magic_packet(mac)
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        return s.sendto(pkt, (broadcast, port))
