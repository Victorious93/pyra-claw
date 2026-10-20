# Pyra Claw Protocol (v0.1)

## Transport
- **Phone → Hub:** HTTP REST on `:8787`, header `Authorization: Bearer <token>`. Live status on WebSocket `/ws?token=<token>` (JSON pushed every 2 s).
- **Hub → ESP32:** MQTT (Mosquitto on the Pi). Topics:
  - `pyra/node/<id>/cmd`: hub → node, QoS 1
  - `pyra/node/<id>/status`: node → hub, retained. The LWT payload is `{"state":"offline"}`
  - `pyra/node/<id>/heartbeat`: node → hub every 10 s

## Envelopes (droidcommand-AI fleet)
```json
{"type":"remote.command","id":"<uuid>","command":"pc.wake","target":null,
 "args":{},"approval_token":null,"source":"pyra-claw-android","ts":0}
```
```json
{"type":"macro.step","macro_id":"morning","step":0,"command":{ "...remote.command..." }}
```
POST either envelope to `/api/command`. A `macro.step` is unwrapped and its inner command is run.

## Command surface

| Command | Approval | Effect | Result |
|---|---|---|---|
| `hub.status` | no | Hub version, uptime, MQTT state, nodes | status object |
| `node.list` | no | Known nodes from heartbeats/status | `{nodes:[...]}` |
| `pc.wake` | no | Hub sends WOL broadcast **and** relays `wake_pc` to the wake node | `{wol_bytes, relayed}` |
| `pc.power_pulse` | **yes** | Relays `power_pulse` (ms ≤ 300) to the wake node → PC817 → PWR_SW | `{ms, relayed}` |

## Approval flow
1. `POST /api/approve/pc.power_pulse` → `{approval_token, ttl_s}`. The token is single-use and expires after `approval_ttl_s` (default 30 s).
2. `POST /api/command` with `approval_token`. If the token is missing, invalid, expired or reused, the hub returns **403** and writes an audit entry.
3. The node also refuses a pulse with no token. It clamps the pulse to 300 ms and enforces a 5 s cooldown.

## Other endpoints
`GET /health` (no auth), `GET /api/status`, `GET /api/nodes`, `GET /api/audit`.
