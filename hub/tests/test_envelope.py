import pytest

from pyra_hub.envelope import MacroStep, RemoteCommand, parse_envelope, unwrap


def test_parse_remote_command():
    env = parse_envelope({"type": "remote.command", "command": "pc.wake", "source": "android"})
    assert isinstance(env, RemoteCommand)
    assert env.command == "pc.wake" and env.source == "android"


def test_parse_macro_step_unwraps():
    env = parse_envelope(
        {
            "type": "macro.step",
            "macro_id": "morning",
            "step": 1,
            "command": {"type": "remote.command", "command": "hub.status"},
        }
    )
    assert isinstance(env, MacroStep)
    assert unwrap(env).command == "hub.status"


def test_unknown_type_rejected():
    with pytest.raises(ValueError):
        parse_envelope({"type": "nope", "command": "x"})
