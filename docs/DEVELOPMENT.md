# Development

## Android app

Requirements: JDK 17 and the Android SDK (Android Studio Ladybug or newer
works out of the box).

```sh
cd android
./gradlew test               # unit tests (domain, network incl. the agent contract test, app)
./gradlew :app:lintDebug     # lint
./gradlew :app:assembleDebug # app/build/outputs/apk/debug/app-debug.apk
```

Release builds (`assembleRelease`, minified) are signed with the debug key
unless `WRTPILOT_KEYSTORE`, `WRTPILOT_KEYSTORE_PASSWORD`, `WRTPILOT_KEY_ALIAS`
and `WRTPILOT_KEY_PASSWORD` point to a release keystore.

Modules:

| Module | Content |
| --- | --- |
| `app` | Compose UI (screens per feature under `ui/`), ViewModels, navigation, WorkManager sync and notifications, translations in `res/values{,-fr,-ar}` |
| `core:data` | Room database (routers, cached replies, usage history), DataStore settings, Keystore-encrypted passwords, repositories (offline-first, optimistic updates with rollback) |
| `core:network` | JSON-RPC client for uhttpd/rpcd (session handling, certificate pinning), typed `WrtPilotApi`, models |
| `core:domain` | pure Kotlin: schedule grid ⇄ rules, units, device classification, OUI lookup, CSV export |

### Without a router: the mock router

```sh
python3 tools/mock-router/mock_router.py            # http://0.0.0.0:8080, user wrtpilot / password wrtpilot
python3 tools/mock-router/mock_router.py --help     # --no-sqm, --coarse, --offload, --delay, --tls CERT KEY …
```

It implements the whole API ([API.md](API.md)) with ten simulated devices,
live traffic, 30 days of history, a "Kids" group with a schedule, and a new
device joining after a minute (to test notifications). From the Android
emulator, add the router `10.0.2.2` port `8080` without HTTPS.

To test certificate pinning:

```sh
openssl req -x509 -newkey rsa:2048 -nodes -keyout key.pem -out cert.pem -days 30 -subj /CN=mock
python3 tools/mock-router/mock_router.py --port 8443 --tls cert.pem key.pem
```

### Translations

All text is in `app/src/main/res/values/strings.xml` (English, default) with
French (`values-fr`) and Arabic (`values-ar`) translations. Rules:

- Every key exists in all three files, with the same `%1$s`-style arguments.
- Plurals: French needs `one`/`many`/`other`, Arabic
  `zero`/`one`/`two`/`few`/`many`/`other`.
- Numbers, dates and units are formatted with the app locale in code (Arabic
  digits in Arabic); wire formats (schedule rules, CSV) always use `Locale.ROOT`.
- IP/MAC addresses, host names and commands are wrapped with `ltr()` (or
  U+2066…U+2069 in the XML) so they stay left-to-right in Arabic.
- Layouts use start/end, and directional icons use the `AutoMirrored` variants.

## Router agent

The agent is plain ucode (no compilation). Development needs `ucode` with the
`fs`, `uci`, `ubus` and `uloop` modules; for the end-to-end test also `ubusd`,
`rpcd`, `uhttpd`, `nft`, `tc`, `ip`, `dnsmasq`, `iperf3` and root.
`router/tests/ci/build-deps.sh` builds the OpenWrt userland on Debian/Ubuntu
(installs into `/usr/local`).

```sh
python3 router/tests/lint_ucode.py      # ucode pitfalls (forward references, export syntax, …)
router/tests/unit/run.sh                # unit tests: schedules, rule generation, parsers, statistics
sudo router/tests/integration/run.sh    # end-to-end in network namespaces
```

The integration test builds a router, a WAN and two clients out of network
namespaces, starts ubusd, rpcd with the plugin, uhttpd and `wrtpilotd`, and
drives the API over HTTP like the app: blocking, Wi‑Fi deny (fake hostapd),
pauses that expire without the daemon, schedules across a simulated reboot,
HTB shaping measured with iperf3, live rates checked against iperf3, DNS
filtering, SafeSearch, blocklists, DoT blocking, quotas, events, fw4 reloads
and `wrtpilot reset`.

### App ⇄ agent contract

`android/core/network/src/test/resources/fixtures/` holds real replies
recorded from the agent; `AgentContractTest` checks that the app's models read
only fields the agent actually sends. After changing a reply, refresh them:

```sh
sudo WRTPILOT_FIXTURES=$PWD/android/core/network/src/test/resources/fixtures \
  router/tests/integration/run.sh
```

### Building the package

With the OpenWrt SDK:

```sh
ln -s /path/to/OpenWrt-Router/router package/wrtpilot
make package/wrtpilot/compile V=s
```

CI does the same with the official SDK containers for 23.05, 24.10 and 25.12,
then boots each release's x86-64 image in QEMU, installs the package and runs
`router/tests/qemu/smoke.py` against it (`router/tests/qemu/run.sh` runs the
same locally if QEMU is installed).

## Layout of the agent

| Path (in `router/files`) | Role |
| --- | --- |
| `usr/share/rpcd/ucode/wrtpilot.uc` | rpcd plugin: method table and argument signatures |
| `usr/share/ucode/wrtpilot/api.uc` | API implementation (shared with the CLI) |
| `usr/share/ucode/wrtpilot/apply.uc` | UCI → plan → nftables, tc, dnsmasq, hostapd; reset |
| `usr/share/ucode/wrtpilot/{nft,tc,dns,wifi,sqm}.uc` | generators for each subsystem |
| `usr/share/ucode/wrtpilot/schedule.uc` | schedule rules → absolute windows |
| `usr/share/ucode/wrtpilot/stats.uc` | counters → rates, minute/day history, events |
| `usr/share/ucode/wrtpilot/sysinfo.uc` | devices, leases, neighbours, Wi‑Fi, capabilities |
| `usr/sbin/wrtpilotd` | collector daemon (`wrtpilotd` ubus object), quotas, expiries, watchdog |
| `usr/sbin/wrtpilot` | command line tool |
| `etc/uci-defaults/90-wrtpilot` | first install: rpcd login, `/ubus` prefix |
| `usr/share/rpcd/acl.d/wrtpilot.json` | least-privilege ACL |

## Continuous integration

| Workflow | Jobs |
| --- | --- |
| `router.yml` | lint, unit and integration tests (ucode as in OpenWrt and ucode master); packages with the OpenWrt SDK (23.05, 24.10, 25.12); QEMU smoke test on each release |
| `android.yml` | unit tests, lint, debug and release APKs (artifact `wrtpilot-apk`) |
| `apk.yml` | on every push to `main`: builds the APKs and publishes them to the [`latest`](https://github.com/oaatiq/OpenWrt-Router/releases/tag/latest) pre-release |

APKs are signed with the shared debug key committed in
`android/app/debug.keystore` (standard `android` passwords), so any build
installs over any other, and CI sets an increasing version code. To sign with
your own key, add the repository secrets `WRTPILOT_KEYSTORE_BASE64`
(`base64 -w0 release.keystore`), `WRTPILOT_KEYSTORE_PASSWORD`,
`WRTPILOT_KEY_ALIAS` and `WRTPILOT_KEY_PASSWORD`; note that phones with a
debug-signed build must uninstall it once before installing the first build
signed with the new key.
