# WrtPilot

[![Router agent](https://github.com/oaatiq/OpenWrt-Router/actions/workflows/router.yml/badge.svg)](https://github.com/oaatiq/OpenWrt-Router/actions/workflows/router.yml)
[![Android app](https://github.com/oaatiq/OpenWrt-Router/actions/workflows/android.yml/badge.svg)](https://github.com/oaatiq/OpenWrt-Router/actions/workflows/android.yml)

**Your home network, in your pocket.** WrtPilot is an Android app plus a small
agent for [OpenWrt](https://openwrt.org) routers that lets anyone in the family
see who is connected and control the internet, without opening LuCI:

- **Devices**: every device on the network with its live speed, usage today and
  over 30 days, connection type, signal and IP/MAC addresses. Unknown devices
  are recognised by manufacturer and type.
- **Control**: block a device (internet only, or kick it off the Wi‑Fi), pause
  it for 15 minutes / 1 hour / the rest of the day, limit its speed, or give it
  a daily data limit.
- **Parental control**: put the kids' devices in a group, then set allowed
  hours with a weekly schedule grid, pause the whole group in one tap, filter
  adult content (CleanBrowsing, Cloudflare for Families, AdGuard, OpenDNS or
  your own DNS), force SafeSearch and block websites.
- **Smart Queue (SQM)**: keep calls and games smooth when someone downloads.
- **Notifications** when a new device joins or reaches its data limit.
- **Several routers** (home, holiday home, parents…), offline-first (the last
  known state is shown instantly), every change applied optimistically and
  rolled back if the router refuses.
- **English, French and Arabic** (right-to-left), following the phone's
  language or chosen in the app.

Pauses, blocks with a duration and schedules are enforced by the router's
firewall with absolute expiry times: they end on time even if the phone is
off or the agent crashes, and they survive reboots and `fw4 reload`.

## How it works

```
 Android app ──HTTPS──▶ uhttpd /ubus ──▶ rpcd ──▶ wrtpilot (ucode plugin)
 (Compose, Room,         JSON-RPC,        session +        │ UCI /etc/config/wrtpilot
  WorkManager)           pinned cert      least-privilege  ▼
                                          ACL           apply: nftables (fw4 include),
                                                        tc HTB + ifb, dnsmasq per group,
                                          wrtpilotd ◀── hostapd Wi‑Fi deny, SQM
                                          (stats, history, events)
```

- **Router agent** (`router/`): an OpenWrt package written in ucode, with no
  compiled code (`PKGARCH:=all`, about 125 KB installed). It adds a `wrtpilot` ubus
  object to rpcd and a dedicated rpcd login, `wrtpilot`, that can only call
  that object. The app never uses the root password.
- **Android app** (`android/`): Kotlin, Jetpack Compose Material 3, MVVM with
  Hilt, OkHttp + kotlinx.serialization, Room, WorkManager, Vico charts.
  Minimum Android 8.0.

## Getting started

1. **Install the agent on the router** (OpenWrt 23.05, 24.10 or 25.12): see
   [docs/INSTALL.md](docs/INSTALL.md). In short:

   ```sh
   opkg update && opkg install /tmp/wrtpilot_*.ipk    # apk add --allow-untrusted on 25.12
   wrtpilot credentials                               # login for the app
   ```

2. **Install the app**: download `wrtpilot-<version>.apk` from the
   [latest build](https://github.com/oaatiq/OpenWrt-Router/releases/tag/latest)
   (rebuilt on every push to `main`) and open it on the phone. Tap
   *Add my router*, enter the router's address and the login. The app checks
   what the router supports and tells you what to install for the best results.

3. **Away from home?** Use a VPN: [docs/TAILSCALE.md](docs/TAILSCALE.md).
   Never forward the router's web interface to the internet.

## Repository layout

| Path | What |
| --- | --- |
| `router/` | OpenWrt package: rpcd plugin, ACL, `wrtpilotd` collector, `wrtpilot` CLI, ucode modules, tests |
| `android/` | Gradle project: `app`, `core:network` (JSON-RPC client), `core:data` (Room, DataStore, repositories), `core:domain` |
| `tools/mock-router/` | API simulator to develop the app without a router |
| `tools/oui/` | Builds the manufacturer table bundled in the app |
| `docs/` | [Install](docs/INSTALL.md), [remote access](docs/TAILSCALE.md), [API](docs/API.md), [configuration](docs/CONFIG.md), [development](docs/DEVELOPMENT.md) |
| `.github/workflows/` | CI: agent tests, router packages, QEMU test on real OpenWrt, Android build; every push to `main` publishes the APK |

## Development

```sh
# app against a simulated router (emulator: use 10.0.2.2:8080, user wrtpilot, password wrtpilot)
python3 tools/mock-router/mock_router.py

# Android: unit tests, lint, APK
cd android && ./gradlew test :app:lintDebug :app:assembleDebug

# agent: lint + unit tests (needs ucode), end-to-end test in network namespaces (root)
python3 router/tests/lint_ucode.py && router/tests/unit/run.sh
sudo router/tests/integration/run.sh
```

See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) for details.

## License

MIT, see [LICENSE](LICENSE). Manufacturer names come from the IEEE
Registration Authority's public OUI list.
