# Configuration: `/etc/config/wrtpilot`

Everything WrtPilot enforces comes from this UCI file. The app edits it through
the API; you can also edit it by hand and run `wrtpilot apply`.

## `config settings 'main'`

| Option | Default | Meaning |
| --- | --- | --- |
| `enabled` | `1` | `0` removes every rule and stops enforcing (statistics keep running) |
| `sample_interval` | `2` | seconds between traffic counter samples (live view resolution) |
| `persist_path` | `/etc/wrtpilot` | where daily totals and events are saved, at most once per hour; use a USB disk path (for example `/mnt/usb/wrtpilot`) to spare the flash |
| `lan_network` (list) | `lan` | networks whose devices are managed |
| `history_days` | `35` | days of daily totals kept on the router (the app keeps its own copy) |
| `quota_action` | `notify` | what happens when a device exceeds its daily quota without its own setting: `notify` or `block` (until midnight) |
| `sched_horizon_days` | `14` | how far ahead schedule windows are written into the firewall (2–28) |
| `adguard_port` | `5353` | port of AdGuard Home on the router, for the `adguard_local` filter |
| `tc_enabled` | `1` | `0` never uses tc/ifb (speed limits then use nftables policing) |

Minute-level history (last 24 hours) lives in RAM (`/var/run/wrtpilot`), so
the flash is written at most once per hour.

## `config device`

One section per device with settings; section names are generated
(`d_aabbccddeeff`).

| Option | Meaning |
| --- | --- |
| `mac` | the device (required) |
| `name` | custom name shown in the app |
| `group` | id of its family group |
| `blocked` | `internet` or `wifi` |
| `blocked_until` | Unix time when the block ends (absent = until unblocked) |
| `paused_until` | Unix time, or `indefinite` |
| `dl_limit_kbps`, `ul_limit_kbps` | speed limits (0 or absent = none) |
| `daily_quota_mb` | daily download + upload limit in MB (1 MB = 1 000 000 bytes) |
| `quota_action` | `notify` or `block`, overrides the default |

## `config group '<id>'`

The section name is the group id (`a-z`, `0-9`, `_`, max 32 characters).

| Option | Meaning |
| --- | --- |
| `name` | name shown in the app |
| `schedule` (list) | allowed hours, for example `mon-fri 07:00-08:00`; see below |
| `schedule_enabled` | `0` keeps the rules but does not enforce them |
| `paused_until` | Unix time, or `indefinite` |
| `dns_filter` | `off`, `cleanbrowsing_family`, `cloudflare_family`, `adguard_family`, `opendns_family`, `adguard_local` or `custom` |
| `dns_custom` (list) | resolver addresses for `custom` (`1.2.3.4` or `1.2.3.4#5353`, max 4) |
| `safesearch` | `1` forces SafeSearch (Google, Bing, DuckDuckGo) and YouTube restricted mode |
| `blocklist` (list) | blocked domains, subdomains included |
| `dl_limit_kbps`, `ul_limit_kbps` | speed shared by all members |

### Schedules

Each rule is `<days> <start>-<end>` and allows internet in that window; a
group with at least one rule (and `schedule_enabled` not `0`) has internet
**only** inside its windows.

- Days: `mon` … `sun`, ranges (`mon-fri`; `fri-mon` wraps around the week),
  comma lists (`sat,sun`), or `daily`, `weekdays`, `weekend`.
- Times: `HH:MM`, `24:00` allowed as an end. If the end is not after the
  start, the window runs past midnight: `fri 20:00-01:00` is Friday 20:00 to
  Saturday 01:00.

Windows are written into the firewall as absolute times for the next
`sched_horizon_days` days, so they follow the router's time zone and daylight
saving changes exactly, and keep working if the agent stops. The collector
refreshes them every hour.

## Example

```
config settings 'main'
	option enabled '1'
	option quota_action 'notify'
	list lan_network 'lan'
	list lan_network 'guest'

config group 'kids'
	option name 'Kids'
	option dns_filter 'cloudflare_family'
	option safesearch '1'
	list blocklist 'tiktok.com'
	list schedule 'mon-thu 07:00-20:30'
	list schedule 'sun 07:00-20:30'
	list schedule 'fri,sat 08:00-22:00'

config device 'd_c45d83889904'
	option mac 'c4:5d:83:88:99:04'
	option name 'Kid tablet'
	option group 'kids'
	option daily_quota_mb '2000'
	option quota_action 'block'
```

After editing by hand:

```sh
wrtpilot apply
```

## Files

| Path | Content |
| --- | --- |
| `/etc/config/wrtpilot` | this configuration |
| `/etc/wrtpilot/` | daily totals, events, initial password (kept across sysupgrade) |
| `/var/run/wrtpilot/` | generated rules (`fw4.nft`), minute history, state (RAM) |
| `/usr/share/nftables.d/ruleset-post/30-wrtpilot.nft` | fw4 include (link to the generated rules) |
| `/etc/config/rpcd` (`wrtpilot` section) | the app login |
