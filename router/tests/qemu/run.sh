#!/bin/sh
# Boots the official OpenWrt x86-64 image in QEMU, installs the WrtPilot
# package with opkg (dependencies from the OpenWrt feeds) and runs the API
# smoke test against it from the host.
#
# Usage: router/tests/qemu/run.sh <wrtpilot_*.ipk> [openwrt-version]
# Needs: qemu-system-x86_64, curl, ssh, python3 (KVM is used when available)
set -eu

IPK="$(realpath "$1")"
VERSION="${2:-23.05.6}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${WORK:-$(mktemp -d)}"
IMG="$WORK/openwrt.img"
PASSWORD='qemu-smoke-pw-1'
SSH_PORT=8022
HTTP_PORT=8080

ssh_r() {
	ssh -p "$SSH_PORT" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
		-o LogLevel=ERROR -o ConnectTimeout=5 -o BatchMode=no root@127.0.0.1 "$@"
}

cleanup() {
	[ -f "$WORK/qemu.pid" ] && kill "$(cat "$WORK/qemu.pid")" 2>/dev/null || true
}
trap cleanup EXIT

echo "== download OpenWrt $VERSION"
curl -fsSL -o "$IMG.gz" \
	"https://downloads.openwrt.org/releases/$VERSION/targets/x86/64/openwrt-$VERSION-x86-64-generic-ext4-combined.img.gz"
# the image has trailing padding: gunzip exits 2 ("trailing garbage ignored")
gunzip -f "$IMG.gz" || [ $? -eq 2 ]

ACCEL=tcg
[ -w /dev/kvm ] && ACCEL=kvm

echo "== boot ($ACCEL)"
# eth0 = LAN (192.168.1.1, reached through host port forwards)
# eth1 = WAN (DHCP from QEMU, internet access for opkg)
qemu-system-x86_64 -machine accel=$ACCEL -m 256 -smp 2 \
	-drive file="$IMG",format=raw,if=virtio \
	-netdev "user,id=lan,net=192.168.1.0/24,host=192.168.1.2,dhcpstart=192.168.1.100,hostfwd=tcp:127.0.0.1:$SSH_PORT-192.168.1.1:22,hostfwd=tcp:127.0.0.1:$HTTP_PORT-192.168.1.1:80" \
	-device virtio-net-pci,netdev=lan \
	-netdev user,id=wan -device virtio-net-pci,netdev=wan \
	-display none -serial file:"$WORK/console.log" \
	-daemonize -pidfile "$WORK/qemu.pid"

# a fresh OpenWrt accepts root over SSH without a password
for i in $(seq 1 90); do
	ssh_r true 2>/dev/null && break
	sleep 2
done
ssh_r 'cat /etc/openwrt_release | grep DISTRIB_DESCRIPTION'

echo "== wait for WAN"
for i in $(seq 1 60); do
	ssh_r 'ping -c1 -W2 downloads.openwrt.org >/dev/null 2>&1' && break
	sleep 2
done

echo "== install $(basename "$IPK")"
ssh_r 'cat > /tmp/wrtpilot.ipk' < "$IPK"
ssh_r 'opkg update >/dev/null && opkg install /tmp/wrtpilot.ipk'
ssh_r "wrtpilot passwd '$PASSWORD' && wrtpilot credentials | head -1"
ssh_r 'pgrep -f wrtpilotd >/dev/null && echo "wrtpilotd running"'

echo "== API smoke test"
python3 "$HERE/smoke.py" "http://127.0.0.1:$HTTP_PORT/ubus" "$PASSWORD"

echo "== firewall integration"
ssh_r 'nft list table inet wrtpilot >/dev/null && nft list table inet wrtpilot_acct >/dev/null && echo "tables present"'
ssh_r 'fw4 reload >/dev/null 2>&1; sleep 1; nft list table inet wrtpilot >/dev/null && echo "tables survive fw4 reload"'

echo "== reset and remove"
ssh_r 'wrtpilot reset && ! nft list table inet wrtpilot >/dev/null 2>&1 && echo "reset removed the rules"'
ssh_r 'opkg remove wrtpilot >/dev/null && ! ubus list wrtpilot >/dev/null 2>&1 && echo "package removed cleanly"'

echo "== passed"
