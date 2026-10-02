# WrtPilot API reference

The router agent exposes one ubus object, `wrtpilot`, served by rpcd. Clients
reach it through uhttpd's JSON-RPC endpoint at `/ubus`, exactly like LuCI does.
The Android app is one such client; `tools/mock-router` implements the same
API for development.

## Transport and session

Every request is an HTTP `POST` of a JSON-RPC 2.0 `call`:

```json
{ "jsonrpc": "2.0", "id": 1, "method": "call",
  "params": [ "<session>", "<object>", "<method>", { "arg": "value" } ] }
```

The reply's `result` is `[status]` or `[status, data]`; status `0` means
success (ubus status codes: 2 invalid argument, 3 method not found, 4 object
not found, 6 permission denied, 7 timeout).

**Login** with the anonymous session `00000000000000000000000000000000`:

```json
["00000000000000000000000000000000", "session", "login",
 { "username": "wrtpilot", "password": "…" }]
```

→ `[0, { "ubus_rpc_session": "c0ffee…", "timeout": 300, … }]`, or `[6]` for a
wrong password. Use the returned session for every following call. A session
expires after 300 s without calls; the endpoint then answers with the JSON-RPC
error `-32002` ("Access denied") and the client logs in again.

The `wrtpilot` user's ACL only allows the `wrtpilot` object (read methods:
`status clients live history events groups qos_get version`; everything else
is write). Any other object returns `-32002`.

The package also ships the ACL group `wrtpilot-setup`. Only logins with
`read '*'` / `write '*'` get it, which by default means root. It lets the
app, after installing WrtPilot with the root login, read
`/etc/wrtpilot/initial_password` and the installer's result
(`/tmp/wrtpilot-install.rc`, `.log`), and run `wrtpilot passwd <new>` (rpcd
`file` object). The `wrtpilot` user does not get it.

rpcd checks argument types against each method's signature (strings, 32-bit
integers, booleans, arrays) and rejects unknown arguments with status 2.

## Replies and errors

Every method returns an object with `ok`:

```json
{ "ok": true, … }
{ "ok": false, "error": "self_block", "message": "Refusing to block the device that is making this request" }
```

`error` is a stable code (the app translates it); `message` is English text
for logs.

| Code | Meaning |
| --- | --- |
| `invalid_argument` | a value is out of range or malformed |
| `invalid_mac`, `invalid_group_id`, `invalid_domain`, `invalid_schedule` | the named value is malformed |
| `unknown_group` | no group with that id |
| `self_block` | the change would cut off the device making the request |
| `collector_unavailable` | `wrtpilotd` is not running (statistics) |
| `sqm_missing` | sqm-scripts is not installed |
| `no_wan` | the WAN interface could not be determined |
| `apply_failed`, `nft_failed` | the router could not apply the change (nothing was changed) |

Conventions: MAC addresses are lower-case `aa:bb:cc:dd:ee:ff` (input is
normalised). Times are Unix seconds. Rates are bits per second, limits kbit/s,
byte counts bytes, quotas MB (1 MB = 1 000 000 bytes). An "until" value of `0`
means "not set" and `-1` means "indefinitely".

## Status and devices

### `status` {}

```json
{ "ok": true, "agent_version": "0.1.0", "openwrt_version": "23.05.6",
  "openwrt_description": "OpenWrt 23.05.6 r24106-…", "target": "ramips/mt7621",
  "model": "Xiaomi Mi Router 4A Gigabit", "hostname": "OpenWrt", "timezone": "CET-1CEST,…",
  "uptime": 86400, "time": 1790000000, "enabled": true,
  "wan": { "up": true, "interface": "wan", "proto": "pppoe", "device": "pppoe-wan",
           "uptime": 3600, "ipv4": "203.0.113.7", "ipv6": "2001:db8::1", "dns": ["…"] },
  "offload": { "software": false, "hardware": false }, "offload_warning": false,
  "capabilities": { "tc": true, "ifb": true, "sqm": true, "cake": true,
                    "nftset": true, "hostapd": true, "ip": true },
  "coarse_limiting": false,
  "collector": { "running": true, "interval": 2 },
  "last_apply": { "ts": 1790000000, "ok": true, "warnings": [] },
  "self_macs": ["3c:28:6d:11:22:01"] }
```

`capabilities`: `tc`/`ifb` precise shaping, `sqm` sqm-scripts installed,
`nftset` dnsmasq can fill nftables sets (complete website blocking), `hostapd`
Wi‑Fi control. `self_macs`: devices currently talking to the API (they cannot
be blocked or paused).

### `clients` {}

All known devices (seen in DHCP leases, the neighbour table, Wi‑Fi station
lists, the configuration or the collector), online ones first.

```json
{ "ok": true, "time": 1790000000, "clients": [ {
  "mac": "c4:5d:83:88:99:04", "name": "Kid tablet", "custom_name": "Kid tablet",
  "hostname": "Galaxy-Tab-A8", "ip": "192.168.1.150", "ipv6": ["fd00::5d83"],
  "conn": "2.4G", "ssid": "Home", "signal": -61, "online": true,
  "first_seen": 1780000000, "last_seen": 1790000000,
  "rx_bps": 2400000, "tx_bps": 180000, "today_rx": 812000000, "today_tx": 41000000,
  "group": "kids", "blocked": "", "blocked_until": 0,
  "paused": false, "paused_until": 0, "paused_by": "", "schedule_blocked": false,
  "dl_limit_kbps": 0, "ul_limit_kbps": 0,
  "daily_quota_mb": 2000, "quota_action": "block", "quota_exceeded": false,
  "is_self": false, "random_mac": false } ] }
```

`conn`: `2.4G`, `5G`, `6G`, `wifi`, `lan` or `unknown`. `rx` is what the
device downloads, `tx` what it uploads. `blocked`: `""`, `internet` or `wifi`.
`paused_by`: `device` or `group`. `name` is the custom name, else the static
lease name, else the host name.

### `set_device` { mac, name?, group?, daily_quota_mb?, quota_action? }

Changes only the given fields. `name: ""` returns to the automatic name,
`group: ""` removes the device from its group, `daily_quota_mb: 0` removes the
quota, `quota_action`: `notify`, `block` or `""` (router default).
→ `{ ok, device: { mac, custom_name, group, blocked, blocked_until, paused_until,
dl_limit_kbps, ul_limit_kbps, daily_quota_mb, quota_action } }`

### `forget_device` { mac }

Removes the device's settings and statistics. → `{ ok }`

### `block` { mac, mode, duration_s? }

`mode`: `internet` (the device stays on the network but cannot reach the WAN)
or `wifi` (disconnected and banned from every access point). `duration_s`
(up to 30 days) makes the block expire by itself; omitted or `0` = until
unblocked. → `{ ok, mac, mode, blocked_until }`

### `unblock` { mac } → `{ ok, mac }`

### `set_limit` { mac | group, dl_kbps, ul_kbps }

`0` removes a direction's limit. → `{ ok, coarse }`; `coarse: true` means the
router can only police (approximate), see `capabilities`.

## Statistics

### `live` { macs?, samples?, devices? }

The collector's ring buffer (one sample every `interval` seconds, the last
ten minutes). `samples` (default 150) most recent samples; `macs` limits the
devices returned; `devices: false` returns only the totals.

```json
{ "ok": true, "interval": 2, "ts": [1790000000, 1790000002],
  "total": { "rx": [5100000, 4800000], "tx": [300000, 280000] },
  "devices": { "c4:5d:83:88:99:04": { "rx": [2400000, 2300000], "tx": [180000, 170000] } } }
```

### `history` { mac, resolution, since? }

`resolution`: `minute` or `hour` (last 24 hours) or `day` (`history_days`,
default 35). `mac`: one device, `""` for all devices summed, `"*"` for every
device separately. Each point is `[bucket start, rx bytes, tx bytes]`.

```json
{ "ok": true, "resolution": "hour", "mac": "", "series": [[1789996400, 912000000, 51000000]] }
{ "ok": true, "resolution": "day", "mac": "*", "devices": { "c4:5d:83:88:99:04": [[1789945200, 2100000000, 98000000]] } }
```

### `events` { since_id? }

New-device and quota events after `since_id` (the last 200 are kept).

```json
{ "ok": true, "last_id": 42, "reset": false, "events": [
  { "id": 41, "ts": 1790000000, "type": "new_device", "mac": "12:9b:6e:00:01:0b",
    "data": { "hostname": "Galaxy-S23", "ip": "192.168.1.210", "conn": "5G" } },
  { "id": 42, "ts": 1790000500, "type": "quota_exceeded", "mac": "c4:5d:83:88:99:04",
    "data": { "used_mb": 2004, "quota_mb": 2000, "action": "block" } } ] }
```

`reset: true` means the router's event ids restarted (reinstall); the reply
then contains every stored event.

## Pause

### `pause` { mac | group, duration_s? | until? }

`duration_s` (60 s to 30 days), or `until`: `tomorrow` (next local midnight)
or `indefinite`; nothing = indefinitely. Pauses are enforced by the firewall
with an absolute expiry, so they end on time even if `wrtpilotd` is not
running. → `{ ok, paused_until }`

### `resume` { mac | group } → `{ ok }`

## Groups (parental control)

### `groups` {}

```json
{ "ok": true, "dns_filters": ["off", "adguard_local", "cleanbrowsing_family", "cloudflare_family",
                               "adguard_family", "opendns_family", "custom"],
  "groups": [ { "id": "kids", "name": "Kids", "members": ["c4:5d:83:88:99:04"],
    "dns_filter": "cloudflare_family", "dns_custom": [], "safesearch": true,
    "blocklist": ["tiktok.com"],
    "schedule": ["mon,tue,wed,thu,sun 07:00-20:30", "fri,sat 08:00-22:00"], "schedule_enabled": true,
    "dl_limit_kbps": 0, "ul_limit_kbps": 0, "paused_until": 0,
    "allowed_now": true, "next_change": 1790031000 } ] }
```

`next_change`: when internet access next switches because of the schedule
(`0` if never).

### `set_group` { id?, name?, dns_filter?, dns_custom?, safesearch?, blocklist?, schedule?, schedule_enabled?, members?, dl_kbps?, ul_kbps? }

Without `id` a group is created from `name` (the id is derived from it).
Lists replace the current value; `members` sets the group's devices.

- `dns_filter`: one of `dns_filters`; `custom` uses `dns_custom` (up to 4
  resolver IPs, optionally `ip#port`). `adguard_local` uses AdGuard Home on the
  router (`adguard_port`, default 5353). Members' DNS queries go to a dedicated
  dnsmasq instance whatever server they are configured with, and well-known
  DNS-over-TLS/HTTPS servers are blocked for them.
- `safesearch`: Google, Bing, DuckDuckGo SafeSearch and YouTube restricted mode.
- `blocklist`: domains (`https://www.example.com/page` is normalised to
  `example.com`); subdomains are blocked too. Up to 500.
- `schedule`: allowed hours, rules like `mon-fri 07:00-21:00`. Days: `mon`…`sun`,
  ranges (`fri-mon` wraps), comma lists, `daily`, `weekdays`, `weekend`. A rule
  whose end is not after its start runs past midnight (`fri 20:00-01:00`). With
  `schedule_enabled` and at least one rule, members only have internet inside
  the rules.

→ `{ ok, group: { … as in groups … } }`

### `delete_group` { id }

Members stay on the network without the group's rules. → `{ ok }`

## Smart Queue and maintenance

### `qos_get` {}

```json
{ "ok": true, "available": true, "enabled": true, "dl_kbps": 95000, "ul_kbps": 19000,
  "preset": "gaming", "interface": "pppoe-wan", "qdisc": "cake",
  "presets": ["default", "gaming", "streaming"] }
```

### `qos_set` { enabled?, dl_kbps?, ul_kbps?, preset? }

Configures sqm-scripts on the WAN device. Enabling needs both speeds.
→ same as `qos_get`.

### `set_offload` { software?, hardware? }

Changes firewall flow offloading and reloads the firewall.
→ `{ ok, offload: { software, hardware } }`

### `apply` {}

Regenerates the firewall, shaping, DNS and Wi‑Fi state from the configuration.
→ `{ ok, warnings }` (warnings such as `coarse_limiting`, `tc_failed`).

### `version` {} → `{ ok, agent_version }`

## Command line

The same operations are available on the router:

```
wrtpilot status | clients | groups      JSON, as above
wrtpilot apply [--quiet]                regenerate rules from UCI
wrtpilot reset [--disable]              remove every rule (lockout recovery)
wrtpilot unblock-all                    clear all blocks and pauses
wrtpilot credentials | passwd [pw]      the app login
wrtpilot fingerprint                    HTTPS certificate SHA-256
```
