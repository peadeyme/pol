# Bluetooth protocol

The Android phone runs a BLE GATT server (peripheral). The iPhone connects as central.

| What | UUID |
|---|---|
| Service | `6F70C0DE-0000-4E1A-9B2C-000000000001` |
| TX — Android → iPhone (notify) | `6F70C0DE-0000-4E1A-9B2C-000000000002` |
| RX — iPhone → Android (write with response) | `6F70C0DE-0000-4E1A-9B2C-000000000003` |

## Framing
Each message is one UTF-8 JSON object with a `t` field. It is split into packets
that fit the link; every packet starts with one flag byte:
`0x01` = first packet, `0x02` = last packet (a one-packet message has `0x03`).

## Pairing
1. Android shows a 6-digit code.
2. iPhone sends `{"t":"hello","id":"<install id>","name":"iPhone","code":"123456"}`.
3. Android answers `welcome` (and remembers the id) or `denied`.
4. Later connections send `hello` without `code`; the remembered id is enough.
Android ignores every other message until `hello` succeeds on that connection.

## Android → iPhone
| `t` | fields |
|---|---|
| `welcome` | `device`, `sims[{id,slot,name}]`, `battery`, `trialDays`, `license` |
| `status` | `battery`, `sims`, `license` (every 60 s) |
| `contacts` | `items[{id,name,fav,phones[]}]` |
| `sms_sync` | `items[sms]` — latest 300 |
| `sms` | `item` — one new incoming SMS |
| `sent` | `ref`, `ok` |
| `calls` | `items[{addr,name,ts,type:in/out/missed,dur,sim}]` |
| `call` | `id`, `state: ringing/active/ended`, `addr`, `name` |
| `denied` / `license` | pairing refused / subscription inactive |

`license` = `{active, plan: trial/m/s/y, daysLeft}`. While inactive, Android sends no SMS/call data.

`sms` = `{id, addr, name, body, ts (ms), dir: in/out, sim}`

## iPhone → Android
| `t` | fields |
|---|---|
| `hello` | see pairing |
| `send_sms` | `ref`, `to`, `body`, `sim` |
| `dial` | `to`, `sim` |
| `call_answer`, `call_end` | — |
| `sync` | asks for contacts, SMS and calls again |
| `ping` | asks for `status` |

## Not done yet
- Call audio over Bluetooth / Wi-Fi (phase 3). Today, answering on the iPhone answers on the Android phone.
- Message encryption on top of BLE.
