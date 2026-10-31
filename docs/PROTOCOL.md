# Pyra Claw Protocol (v0.3)

> ⚠️ v0.2 firmware changes and all v0.3 device-power backends are **UNTESTED ON HARDWARE**.

## Changes in v0.3 (Timer tab, backward compatible)
- `protocol` is now `"0.3"`. Nothing that existed in v0.2 changed: `pc.wake` with `"target": null` (VictorSuite) and the `pc.power_pulse` approval flow work exactly as before.
- New commands for `/api/command` and timer steps. `target` is a device id from the hub config's `[devices.<id>]`:

| Command | Approval | Effect |
|---|---|---|
| `charge.on` / `charge.off` | no | enable/disable charging through the device's `charge` backend |
| `device.power_on` | no | best-effort power-on through the `power_on` backend (see caveats) |
| `device.power_off` | **yes** | power the device off through the `power_off` backend |

  On `/api/command`, an unknown device, a device without that backend, or a backend failure all return **502** with the reason. In a timer they are rejected at create/update time with **422**.
- New endpoints, all with Bearer auth:
  - `GET /api/devices`: `{devices:[{id,label,charge_backend,power_on_backend,power_off_backend}]}`
  - `GET /api/timers`: `{timers:[Timer], events:[last 20]}`. `POST /api/timers` creates one (201). `GET|PUT|DELETE /api/timers/{id}`.
  - `POST /api/timers/{id}/enable` and `/disable`. `POST /api/timers/{id}/run` returns 202, or **409** if it is already running.
- `/ws` frames gain a `timers` key with the same object as `GET /api/timers`. Clients that ignore unknown keys are unaffected.
- `macro.step` envelopes may now carry the device commands.

### Timer object
```json
{"name":"Night charge","enabled":true,
 "schedule":{"kind":"daily","time":"23:00"},
 "steps":[{"command":"charge.off","target":"phone1","delay_s":0},
          {"command":"charge.off","target":"phone2","delay_s":10},
          {"command":"device.power_off","target":"phone3","delay_s":30}],
 "approval_token":"<only when gated steps are added or changed>"}
```
- `schedule.kind`: `once` (`at`, ISO 8601, naive means hub local time), `daily` (`time` "HH:MM"), `weekly` (`time` plus `days`, 0 = Mon … 6 = Sun), or `manual` (only `run`).
- Steps run in order. `delay_s` (0–21600) is waited **before** the step. The first failing step stops the sequence.
- Server fields: `id, preapproved, preapproved_at, next_run, last_run, last_result, running, current_step` (Unix-seconds timestamps).
- Missed fire times (hub down) are **skipped, not replayed**. A `once` timer disables itself after firing.
- Timers persist to `[hub] timers_file` (atomic JSON write).
- Events: `timer_started`, `step_ok`, `step_failed`, `timer_finished`. Each run is also written to the audit log.

### Approval in timers (design choice: pre-approval)
Approval tokens last 30 s, so an unattended timer cannot get one at fire time. Instead:
1. When a timer contains gated steps (`device.power_off`, `pc.power_pulse`), create/update needs `approval_token` from `POST /api/approve/timer.arm`. Without it the hub returns **403**.
2. The hub records `timer_preapproved` in the audit log, with the gated commands and the full step list. It then marks the timer `preapproved`.
3. A `PUT` with identical steps keeps the pre-approval. Changing the steps of a gated timer needs a new token.
4. At run time, a gated step on a timer that is not pre-approved is **blocked** (`step_failed`, audit `rejected` with `reason: no_approval`).

## Changes in v0.2 (notes for VictorSuite and other clients)
- **Hub REST is backward compatible.** `pc.wake` with `"target": null` over `:8787` works exactly as in v0.1 (no approval, same `{wol_bytes, relayed}` result). `pc.power_pulse` still uses the same approval flow.
- `hub.status` / `GET /api/status` now include `"protocol": "0.2"`; the `pc.power_pulse` result gains `"signed": true`.
- `pc.power_pulse` returns **503** if the hub has no `[node] hub_token` configured (it can no longer relay an unsigned pulse).
- **Hub → node `power_pulse` is now HMAC-signed.** The old `approval` field is no longer sent; v0.2 firmware ignores it. A v0.1 node will reject v0.2 pulses (no `approval`), and a v0.2 node rejects unsigned v0.1 pulses: update hub and firmware together.
- MQTT now requires a username/password (Mosquitto listens on the network with `allow_anonymous false`).
- New optional **standalone HTTP API on the ESP32** (no Pi/broker needed): `GET /health`, `POST /wake`, `POST /pulse`.

## Transport
- **Phone → Hub:** HTTP REST on `:8787`, header `Authorization: Bearer <token>`. Live status on WebSocket `/ws?token=<token>` (JSON pushed every 2 s).
- **Hub → ESP32:** MQTT (Mosquitto on the Pi, `:1883`, username/password required). Topics:
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
| `charge.on` / `charge.off` | no | v0.3, see above (`target` = device id) | backend result |
| `device.power_on` | no | v0.3 | backend result |
| `device.power_off` | **yes** | v0.3 | backend result |

## Approval flow
1. `POST /api/approve/pc.power_pulse` → `{approval_token, ttl_s}`. The token is single-use and expires after `approval_ttl_s` (default 30 s).
2. `POST /api/command` with `approval_token`. If the token is missing, invalid, expired or reused, the hub returns **403** and writes an audit entry.
3. The hub signs the relayed command (below). The node verifies the signature, clamps the pulse to 300 ms and enforces a 5 s cooldown.

## Signed node commands (v0.2)
Hub → node `power_pulse` (MQTT `pyra/node/<id>/cmd`, or HTTP `POST /pulse` on the node):
```json
{"action":"power_pulse","id":"<command id>","ms":300,"ts":1730000000,"sig":"<64 hex chars>"}
```
- `sig = hex(HMAC-SHA256(key = hub_token, msg = "<id>|power_pulse|<ms>|<ts>"))`, lower-case hex (the node accepts either case). `ts` is integer Unix seconds (UTC), `ms` is the integer exactly as sent. `id` must not contain `|` and is at most 64 chars.
- `hub_token` is the shared secret: hub config `[node] hub_token`, ESP32 NVS key `hub_token` (see [PROVISIONING.md](PROVISIONING.md)). It is **not** the hub's REST `auth_token`.
- The node rejects (and publishes `status.detail = "power_pulse:<reason>"`):

| Reason | Meaning | HTTP `/pulse` code |
|---|---|---|
| `rejected_no_hub_token` | node has no `hub_token` provisioned | 503 |
| `rejected_no_time_sync` | node has no NTP time yet (fail closed) | 503 |
| `rejected_bad_id` / `rejected_bad_sig` / `rejected_hmac_error` | malformed or wrong signature | 403 |
| `rejected_stale_ts` | `ts` more than 30 s from the node clock | 403 |
| `rejected_replay` | `id` is among the last 16 accepted ids | 409 |
| `rejected_cooldown` | less than 5 s since the last pulse | 429 |

- Reference implementation: `hub/src/pyra_hub/signing.py` (tests in `hub/tests/test_signing.py`).
- The node gets time from NTP (`configTime`, UTC: pool.ntp.org, time.google.com, time.cloudflare.com). Status and `/health` report `time_synced`.
- `wake_pc` over MQTT stays unsigned (it only sends a magic packet).

## ESP32 standalone HTTP API (v0.2)
Port 80 on the node; optional mDNS name `pyra-<node_id>.local`. Works without the Pi or broker; the MQTT side keeps retrying in the background (500 ms TCP probe, backoff 2 s → 30 s).

| Endpoint | Auth | Effect |
|---|---|---|
| `GET /health` | none | `{ok, node_id, fw, protocol, time_synced, mqtt, ip}` |
| `POST /wake` | `Authorization: Bearer <hub_token>` | sends WOL to `pc_mac`/`pc_broadcast`. 1 per 10 s, else **429**. 401 bad token, 503 no token provisioned |
| `POST /pulse` | signed JSON body (above) | power pulse; never accepted unsigned. Codes per table above, 200 on success |

```
curl http://pyra-esp32-wake-01.local/health
curl -X POST -H "Authorization: Bearer $HUB_TOKEN" http://pyra-esp32-wake-01.local/wake
```
Plain HTTP: the bearer token crosses the LAN in clear text. Use only on a trusted network.

## Other endpoints
`GET /health` (no auth), `GET /api/status`, `GET /api/nodes`, `GET /api/audit`.
