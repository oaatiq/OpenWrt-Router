# Installing WrtPilot on an OpenWrt router

This takes about five minutes. You need SSH access to the router as `root`
(the same password as the LuCI web interface).

## Requirements

- OpenWrt **23.05**, **24.10** or **25.12** (any target: the package contains
  no compiled code).
- About 150 KB of free flash for WrtPilot itself, plus its dependencies
  (rpcd, uhttpd and ucode are already present on images with LuCI).

## Easiest: from the app

In the app tap **Add my router** and log in with the router's **root** user
and password (the same as for LuCI). If WrtPilot is not installed yet, the app
offers **Install WrtPilot**: it runs the same installer as below on the
router, shows its progress, then switches to the restricted `wrtpilot` login.
The root password is used for this step only and is not saved. The router
needs internet access, and LuCI (installed on all official images).

How it works: the router's API gives no shell, not even to root, so the app
uses what LuCI's own pages let the root login do. It adds a one-time job to
root's crontab (*System > Scheduled Tasks*) that downloads and runs the
installer. The job deletes itself when it starts, so it runs once, within a
minute. The app follows the installer in the system log (*Status > System
Log*). If the router does not allow this, the app shows the commands for a
computer instead.

## Quick install from a computer

On a computer connected to the router, open a terminal (PowerShell on
Windows, Terminal on macOS/Linux) and run:

```sh
ssh root@192.168.1.1          # your router's address; enter the root password
wget -qO- https://github.com/oaatiq/OpenWrt-Router/releases/download/router-latest/install.sh | sh
```

The script adds WrtPilot's **package feed** to OpenWrt's package manager
(with its signing key, like any third-party OpenWrt repository), installs the
`wrtpilot` package with its dependencies and prints the login to enter in the
app (user `wrtpilot`). Then continue with
[HTTPS](#4-turn-on-https-recommended) and
[adding the router in the app](#5-add-the-router-in-the-app).

From then on WrtPilot is managed like any other OpenWrt package:

| | OpenWrt 23.05 / 24.10 | OpenWrt 25.12+ |
| --- | --- | --- |
| Update | `opkg update && opkg upgrade wrtpilot`, or LuCI › System › Software › Updates | `apk update && apk add --upgrade wrtpilot` |
| After a firmware upgrade | run the installer again, or tap **Install WrtPilot** in the app | same |

A firmware upgrade keeps the feed, the key, your WrtPilot settings and the app
login. But like every package you installed yourself, the package itself (and
the speed-limit packages) must be installed again. Run the installer again
for that; the app then works exactly as before.

### Adding the feed by hand

Instead of running the script you can add the feed yourself:

```sh
# OpenWrt 23.05 / 24.10
wget -qO /etc/opkg/keys/42af730eb294d6fe https://raw.githubusercontent.com/oaatiq/OpenWrt-Router/main/router/keys/42af730eb294d6fe
echo 'src/gz wrtpilot https://github.com/oaatiq/OpenWrt-Router/releases/download/router-latest' >> /etc/opkg/customfeeds.conf
opkg update && opkg install wrtpilot

# OpenWrt 25.12+
wget -qO /etc/apk/keys/wrtpilot-feed.pem https://raw.githubusercontent.com/oaatiq/OpenWrt-Router/main/router/keys/wrtpilot-feed.pem
echo 'https://github.com/oaatiq/OpenWrt-Router/releases/download/router-latest/packages.adb' >> /etc/apk/repositories.d/customfeeds.list
apk update && apk add wrtpilot
```

The steps below install a downloaded package file instead (no feed, so no
updates through the package manager).

## 1. Get the package

Download the package for your OpenWrt version from the
[router-latest](https://github.com/oaatiq/OpenWrt-Router/releases/tag/router-latest)
release:

| OpenWrt | File |
| --- | --- |
| 23.05, 24.10 | `wrtpilot_<version>_all.ipk` |
| 25.12 and later | `wrtpilot-<version>.apk` (an OpenWrt package, not the phone app) |

Copy it to the router (`-O` makes recent OpenSSH clients use the protocol the
router understands):

```sh
scp -O wrtpilot_*.ipk root@192.168.1.1:/tmp/
```

## 2. Install

```sh
ssh root@192.168.1.1

# OpenWrt 23.05 / 24.10
opkg update && opkg install /tmp/wrtpilot_*.ipk

# OpenWrt 25.12 and later
apk update && apk add --allow-untrusted /tmp/wrtpilot-*.apk
```

The package installs its dependencies from the OpenWrt feeds and then:

- creates a dedicated login **`wrtpilot`** with a random password; it can only
  use WrtPilot's API, never the rest of the router (no root access);
- makes the web server answer API calls on `/ubus`;
- starts `wrtpilotd`, the traffic statistics collector;
- adds WrtPilot's firewall rules through fw4 (a separate `inet wrtpilot`
  table; your own firewall configuration is not modified).

## 3. Get the login for the app

```sh
wrtpilot credentials
```

```
User:     wrtpilot
Password: q8ZkF2xP0aLm4TnB
```

Change it whenever you like with `wrtpilot passwd`.

## 4. Turn on HTTPS (recommended)

With HTTPS the password and everything the app sends are encrypted. If
`https://192.168.1.1` already opens LuCI (after a certificate warning), it is
ready. Otherwise:

```sh
opkg install luci-ssl                         # images with LuCI
# opkg install px5g-mbedtls libustream-mbedtls   # images without LuCI
/etc/init.d/uhttpd restart
```

The router uses its own (self-signed) certificate. When you add the router,
the app shows the certificate's fingerprint; compare it with:

```sh
wrtpilot fingerprint
```

If they match, tap *Trust*. From then on the app accepts only this exact
certificate, and warns you if it ever changes (for example after a reset).

## 5. Add the router in the app

Open WrtPilot, tap **Add my router** and enter the router's address
(`192.168.1.1` or `openwrt.lan`), the user `wrtpilot` and the password. The
app then shows what this router supports and what to install to get the most
out of it.

## Optional packages

| Feature | Install | Without it |
| --- | --- | --- |
| Precise per-device speed limits | `tc-tiny kmod-sched-core kmod-ifb`: **added by the installer** when missing and there is 1 MB of free flash (skip with `WRTPILOT_NO_EXTRAS=1`) | limits are approximate: traffic over the limit is dropped instead of queued, so speed is jumpy |
| Smart Queue (less lag when busy) | `sqm-scripts` (and `luci-app-sqm` if you like) | the Smart Queue screen explains how to install it |
| Complete website blocking | `dnsmasq-full` (replaces `dnsmasq`) | blocking works through DNS only |
| Kick devices off the Wi‑Fi | `hostapd` with ubus support (default on images with Wi‑Fi) | "Kick off the Wi‑Fi" is unavailable; internet blocking still works |

```sh
opkg update
opkg install sqm-scripts
opkg remove dnsmasq && opkg install dnsmasq-full
```

## Flow offloading

Software and hardware flow offloading make traffic skip the firewall, so
per-device statistics, limits and blocking become inaccurate. The app warns
you and can turn offloading off with one tap (Network › Firewall ›
*Routing/NAT Offloading* in LuCI does the same). On very fast connections
(above about 500 Mbit/s) some small routers may become slower without it.

## Locked out? Recovery

WrtPilot refuses to block the phone that sends the command, but if something
goes wrong:

```sh
wrtpilot reset             # remove every WrtPilot rule until the next change
wrtpilot reset --disable   # ... and keep them off after a reboot
wrtpilot unblock-all       # clear all blocks and pauses, keep groups and schedules
```

Re-enable with `uci set wrtpilot.main.enabled=1; uci commit wrtpilot; wrtpilot apply`.

## Updating and removing

With the feed, update like any package (see the table above). Without it,
install a newer package file the same way. Settings in `/etc/config/wrtpilot`
and the usage history in `/etc/wrtpilot` are always kept, also across
firmware upgrades.

```sh
opkg remove wrtpilot        # apk del wrtpilot on 25.12
```

Removing the package deletes all its firewall rules. The `wrtpilot` login is
kept in `/etc/config/rpcd`; delete that section if you no longer need it.

## Using the router from outside

Use a VPN such as Tailscale or WireGuard: see [TAILSCALE.md](TAILSCALE.md).
**Never** open the router's web interface to the internet with a port forward.

## Troubleshooting

| The app says | Check on the router |
| --- | --- |
| Cannot reach the router | `ping` it from the phone; same Wi‑Fi or VPN? |
| The router's API is not reachable | `uci get uhttpd.main.ubus_prefix` should print `/ubus`; `opkg install uhttpd-mod-ubus` |
| WrtPilot is not installed | `ubus list wrtpilot`; `/etc/init.d/rpcd restart` |
| The installation failed (from the app) | `logread -e wrtpilot-install` shows the installer's messages; `cat /etc/crontabs/root` should not list `wrtpilot-app-install` any more |
| Wrong username or password | `wrtpilot credentials`, or set a new one with `wrtpilot passwd` |
| Traffic statistics stopped | `/etc/init.d/wrtpilotd restart`; `logread -e wrtpilot` |

`wrtpilot status` prints what the app sees (versions, capabilities, last
apply result); `logread -e wrtpilot` shows the agent's log.
