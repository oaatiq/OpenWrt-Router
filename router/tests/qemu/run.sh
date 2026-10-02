#!/bin/sh
# Boots the official OpenWrt x86-64 image in QEMU, installs the WrtPilot
# package with opkg or apk (dependencies from the OpenWrt feeds) and runs
# the API smoke test against it from the host.
#
# Usage: router/tests/qemu/run.sh <wrtpilot .ipk or .apk> [openwrt-version]
#           (install.sh over SSH, then again through the router API like the
#           app's button, with install.sh and the package served from this host)
#        router/tests/qemu/run.sh online [openwrt-version]   (published installer, over SSH)
#        router/tests/qemu/run.sh app [openwrt-version]      (published installer, through the
#                                                             router API like the app's button)
# Needs: qemu-system-x86_64, curl, ssh, python3 (KVM is used when available)
set -eu

PKG="$1"
case "$PKG" in online|app) ;; *) PKG="$(realpath "$PKG")" ;; esac
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
	[ -f "$WORK/http.pid" ] && kill "$(cat "$WORK/http.pid")" 2>/dev/null || true
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

REMOVE='if command -v opkg >/dev/null; then opkg remove wrtpilot; else apk del wrtpilot; fi'
if [ "$PKG" = online ]; then
	echo "== install with the published one-line installer"
	ssh_r 'wget -qO- https://github.com/oaatiq/OpenWrt-Router/releases/download/router-latest/install.sh | sh'
	ssh_r 'grep -h wrtpilot /etc/opkg/customfeeds.conf /etc/apk/repositories.d/customfeeds.list 2>/dev/null || echo "(no feed configured)"'
elif [ "$PKG" = app ]; then
	echo "== install through the router API with the root login, like the app"
	python3 "$HERE/app_install.py" "http://127.0.0.1:$HTTP_PORT/ubus" ""   # fresh OpenWrt: empty root password
else
	echo "== install $(basename "$PKG") with install.sh"
	case "$PKG" in
	*.apk)	REMOTE=/tmp/wrtpilot.apk ;;	# OpenWrt 25.12+
	*)	REMOTE=/tmp/wrtpilot.ipk ;;
	esac
	ssh_r "cat > $REMOTE" < "$PKG"
	ssh_r 'cat > /tmp/install.sh' < "$HERE/../../install.sh"
	ssh_r "sh /tmp/install.sh $REMOTE"
fi
ssh_r "wrtpilot passwd '$PASSWORD' && wrtpilot credentials | head -1"
ssh_r 'pgrep -f wrtpilotd >/dev/null && echo "wrtpilotd running"'
ssh_r 'wrtpilot fingerprint || true'   # needs HTTPS (default on 24.10+ images)

echo "== API smoke test"
python3 "$HERE/smoke.py" "http://127.0.0.1:$HTTP_PORT/ubus" "$PASSWORD"

echo "== firewall integration"
ssh_r 'nft list table inet wrtpilot >/dev/null && nft list table inet wrtpilot_acct >/dev/null && echo "tables present"'
ssh_r 'fw4 reload >/dev/null 2>&1; sleep 1; nft list table inet wrtpilot >/dev/null && echo "tables survive fw4 reload"'

echo "== reset and remove"
ssh_r 'wrtpilot reset && ! nft list table inet wrtpilot >/dev/null 2>&1 && echo "reset removed the rules"'
ssh_r "$REMOVE >/dev/null && ! ubus list wrtpilot >/dev/null 2>&1 && echo 'package removed cleanly'"

case "$PKG" in online|app) ;; *)
	echo "== install again through the router API, like the app (root login, LuCI's permissions)"
	# the guest reaches this host as 192.168.1.2 (QEMU user networking)
	SRV="$WORK/srv"
	mkdir -p "$SRV"
	cp "$PKG" "$SRV/"
	sed -e 's#^BASE=.*#BASE=http://192.168.1.2:8000#' -e 's/^FEED=.*/FEED=0/' \
		-e "s/^IPK_FILE=.*/IPK_FILE=$(basename "$PKG")/" -e "s/^APK_FILE=.*/APK_FILE=$(basename "$PKG")/" \
		"$HERE/../../install.sh" > "$SRV/install.sh"
	python3 -m http.server --bind 127.0.0.1 --directory "$SRV" 8000 > "$WORK/http.log" 2>&1 &
	echo $! > "$WORK/http.pid"
	sleep 1
	python3 "$HERE/app_install.py" "http://127.0.0.1:$HTTP_PORT/ubus" "" "http://192.168.1.2:8000/install.sh"
	ssh_r "$REMOVE >/dev/null && echo 'removed again'"
	;;
esac

echo "== passed"
