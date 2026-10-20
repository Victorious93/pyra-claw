import pytest

from pyra_hub.wol import magic_packet


def test_magic_packet_bytes():
    pkt = magic_packet("01:23:45:67:89:ab")
    assert len(pkt) == 102
    assert pkt[:6] == b"\xff" * 6
    assert pkt[6:12] == bytes([0x01, 0x23, 0x45, 0x67, 0x89, 0xAB])
    assert pkt[6:] == bytes.fromhex("0123456789ab") * 16


def test_magic_packet_dash_format():
    assert magic_packet("01-23-45-67-89-AB") == magic_packet("0123456789ab")


def test_bad_mac():
    with pytest.raises(ValueError):
        magic_packet("zz:zz")
