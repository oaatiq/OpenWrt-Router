# Using WrtPilot away from home

The app talks to the router's web server. That server must **never** be
reachable from the internet: do not create a port forward for ports 80/443
and do not enable the web interface on the WAN. Instead, connect the phone to
the home network through a VPN. Two good options:

| | Tailscale | WireGuard |
| --- | --- | --- |
| Setup | a few minutes, no port forward, works behind CGNAT | needs a public IP (or DDNS) and one UDP port forward |
| Accounts | free Tailscale account | none |
| Packages | `tailscale` | `wireguard-tools luci-proto-wireguard` |

In both cases the app uses the router's **VPN address** instead of
`192.168.1.1`. You can save the router twice in the app (for example
"Home" and "Home (remote)") and switch between them from the title bar.

## Tailscale

On the router (needs about 10 MB of flash; check with `df -h /overlay`):

```sh
opkg update && opkg install tailscale     # apk add tailscale on 25.12
/etc/init.d/tailscale enable && /etc/init.d/tailscale start
tailscale up --accept-dns=false
```

Open the link it prints, log in, and approve the router. Then allow traffic
from the Tailscale interface to the router:

```sh
uci add firewall zone
uci set firewall.@zone[-1].name='tailscale'
uci set firewall.@zone[-1].input='ACCEPT'
uci set firewall.@zone[-1].output='ACCEPT'
uci set firewall.@zone[-1].forward='REJECT'
uci add_list firewall.@zone[-1].device='tailscale0'
uci commit firewall && /etc/init.d/firewall reload
```

Install Tailscale on the phone and log in with the same account. Find the
router's address with `tailscale ip -4` (a `100.x.y.z` address) and add it in
WrtPilot. If you turned on HTTPS, the app asks you to trust the same
certificate fingerprint as at home (`wrtpilot fingerprint`).

Only devices in your tailnet can reach the router. Remove the phone from the
Tailscale admin console if it is lost.

## WireGuard

Follow the OpenWrt guide
[WireGuard server](https://openwrt.org/docs/guide-user/services/vpn/wireguard/server):
create a `wg0` interface (for example `10.10.10.1/24`), a peer for the phone,
open the UDP port on the WAN, and put `wg0` in the `lan` zone (or a zone that
accepts input). In the WireGuard app on the phone, import the peer's
configuration (LuCI can show it as a QR code).

In WrtPilot, use the router's WireGuard address (`10.10.10.1`), or
`192.168.1.1` if the peer's `AllowedIPs` include the LAN.

## Notes

- Live statistics and every action work the same over the VPN.
- WrtPilot never blocks the device that sends a command, but a VPN client's
  traffic reaches the router from the VPN interface, so blocking or pausing
  your phone's Wi‑Fi address from outside does not cut your VPN connection.
- Background notifications check the router every 15 minutes or more; they
  work whenever the VPN is connected (Tailscale can stay on permanently).
