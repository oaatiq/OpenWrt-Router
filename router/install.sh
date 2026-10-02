#!/bin/sh
# WrtPilot router agent installer. Run on the router (as root, over SSH):
#
#   wget -qO- https://github.com/oaatiq/OpenWrt-Router/releases/download/router-latest/install.sh | sh
#
# It adds the WrtPilot package feed to the package manager (opkg on OpenWrt
# 23.05/24.10, apk on 25.12+) with its signing key, then installs the
# "wrtpilot" package like any other OpenWrt package, and prints the login for
# the app. Afterwards:
#   update:                        opkg update && opkg upgrade wrtpilot   (or LuCI > Software)
#   after a firmware upgrade:      opkg update && opkg install wrtpilot   (the feed is kept)
# On OpenWrt 25.12+ use: apk update && apk add --upgrade wrtpilot
#
# `sh install.sh <file>` installs a package file that is already on the router.
#
# `sh install.sh --app <id>` is how the app's "Install WrtPilot" button runs
# it: the app has no shell on the router, so it adds a one-time job to root's
# crontab (with the permissions LuCI gives the root login for System >
# Scheduled Tasks). The job removes itself, and the output goes to the system
# log (tag "wrtpilot-install") where the app follows it.

BASE="${WRTPILOT_BASE:-https://github.com/oaatiq/OpenWrt-Router/releases/download/router-latest}"

# set when the release is published (FEED=1: signed package feed available)
FEED=0
IPK_FILE=wrtpilot-router-openwrt23-24.ipk
APK_FILE=wrtpilot-router-openwrt25.apk

# public keys of the WrtPilot feed (usign for opkg, ECDSA for apk)
USIGN_FINGERPRINT=42af730eb294d6fe
USIGN_KEY='untrusted comment: WrtPilot package feed
RWRCr3MOspTW/nUw07Y+A+uWxKkmf9NoKVp14CpIsRxx0Z7dKGa3bRNX'
APK_KEY='-----BEGIN PUBLIC KEY-----
MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE/DPzYa1yfg2MpH8z1r1EKcdEaiOy
JOl1DGWG3cnkUkQ9oiZXV7M2H1QOGroZe8AUoPFdf/+fmKGNXfZUp3M/cw==
-----END PUBLIC KEY-----'

say() { printf '%s\n' "$*"; }
die() { printf 'Error: %s\n' "$*" >&2; exit 1; }

if [ "$1" = --app ]; then
	export PATH=/usr/sbin:/usr/bin:/sbin:/bin
	# the cron line already removed itself; make busybox crond reload root's crontab
	sed -i '/wrtpilot-app-install/d' /etc/crontabs/root 2>/dev/null
	{ echo root >> /etc/crontabs/cron.update; } 2>/dev/null
	mkdir "/tmp/wrtpilot-app-$2" 2>/dev/null || exit 0	# this request already ran
	rm -f /tmp/wrtpilot-install.rc
	logger -t wrtpilot-install "started $2"
	{ ( WRTPILOT_APP=1 sh "$0" ); echo "$? $2" > /tmp/wrtpilot-install.rc; } 2>&1 |
		tee /tmp/wrtpilot-install.log | logger -t wrtpilot-install
	read -r rc _ < /tmp/wrtpilot-install.rc
	logger -t wrtpilot-install "finished $2 rc=$rc"
	exit 0
fi

[ -w /etc/config ] || die "run this as root on the router"
[ -f /etc/openwrt_release ] || die "this does not look like an OpenWrt router"
. /etc/openwrt_release

case "$DISTRIB_RELEASE" in
	1[0-9].*|2[0-2].*) die "OpenWrt $DISTRIB_RELEASE is too old, WrtPilot needs 23.05 or newer" ;;
esac

if command -v opkg >/dev/null 2>&1; then
	PM=opkg
elif command -v apk >/dev/null 2>&1; then
	PM=apk
else
	die "neither opkg nor apk found"
fi

say "WrtPilot installer: ${DISTRIB_DESCRIPTION:-OpenWrt $DISTRIB_RELEASE} ($PM)"

add_feed() {
	if [ "$PM" = opkg ]; then
		mkdir -p /etc/opkg/keys
		printf '%s\n' "$USIGN_KEY" > "/etc/opkg/keys/$USIGN_FINGERPRINT"
		touch /etc/opkg/customfeeds.conf
		sed -i '/^src\/gz wrtpilot /d' /etc/opkg/customfeeds.conf
		printf 'src/gz wrtpilot %s\n' "$BASE" >> /etc/opkg/customfeeds.conf
	else
		mkdir -p /etc/apk/keys /etc/apk/repositories.d
		printf '%s\n' "$APK_KEY" > /etc/apk/keys/wrtpilot-feed.pem
		touch /etc/apk/repositories.d/customfeeds.list
		sed -i '/\/router-latest\/packages.adb$/d' /etc/apk/repositories.d/customfeeds.list
		printf '%s/packages.adb\n' "$BASE" >> /etc/apk/repositories.d/customfeeds.list
	fi
	say "Added the WrtPilot package feed."
}

install_from_feed() {
	say "Updating package lists ..."
	if [ "$PM" = opkg ]; then
		opkg update 2>&1 | grep -iE "wrtpilot|signature" || true
		opkg list wrtpilot 2>/dev/null | grep -q '^wrtpilot ' ||
			die "the WrtPilot feed could not be loaded (see the messages above)"
		say "Installing ..."
		opkg install wrtpilot || die "installation failed (see the messages above)"
	else
		apk update 2>&1 | grep -iE "wrtpilot|untrusted|error" || true
		say "Installing ..."
		apk add --upgrade wrtpilot || die "installation failed (see the messages above)"
	fi
}

install_file() { # <package file>
	say "Updating package lists ..."
	if [ "$PM" = opkg ]; then
		opkg update >/dev/null 2>&1 || say "(opkg update reported errors, trying anyway)"
		say "Installing ..."
		opkg install "$1" || die "installation failed (see the messages above)"
	else
		apk update >/dev/null 2>&1 || say "(apk update reported errors, trying anyway)"
		say "Installing ..."
		apk add --allow-untrusted "$1" || die "installation failed (see the messages above)"
	fi
}

if [ -n "$1" ]; then
	[ -f "$1" ] || die "$1 not found"
	install_file "$1"
elif [ "$FEED" = 1 ]; then
	add_feed
	install_from_feed
else
	FILE=$IPK_FILE
	[ "$PM" = apk ] && FILE=$APK_FILE
	PKG="/tmp/$FILE"
	say "Downloading $FILE ..."
	rm -f "$PKG"
	wget -q -O "$PKG" "$BASE/$FILE" || die "download failed: $BASE/$FILE
Check the router's internet connection, or download the file on a computer and
copy it to the router (see docs/INSTALL.md)."
	[ -s "$PKG" ] || die "downloaded file is empty"
	install_file "$PKG"
	rm -f "$PKG"
fi

ubus list wrtpilot >/dev/null 2>&1 || {
	/etc/init.d/rpcd restart >/dev/null 2>&1
	sleep 2
}
ubus list wrtpilot >/dev/null 2>&1 || die "WrtPilot is installed but rpcd did not load it; check 'logread -e rpcd'"

if [ -n "$WRTPILOT_APP" ]; then
	# the app reads the login itself: keep the password out of the system log
	say "WrtPilot is installed."
	exit 0
fi

say ""
say "WrtPilot is installed. Use this login in the app:"
say ""
wrtpilot credentials
say "Router address: $(uci -q get network.lan.ipaddr | cut -d/ -f1)"
if [ -s "$(uci -q get uhttpd.main.cert || echo /etc/uhttpd.crt)" ]; then
	say "HTTPS is available. Certificate fingerprint (the app will show the same):"
	wrtpilot fingerprint
elif [ "$PM" = opkg ]; then
	say "Tip: for an encrypted connection install HTTPS support: opkg install luci-ssl"
else
	say "Tip: for an encrypted connection install HTTPS support: apk add luci-ssl"
fi
if [ "$FEED" = 1 ] && [ -z "$1" ]; then
	say ""
	if [ "$PM" = opkg ]; then
		say "Updates: opkg update && opkg upgrade wrtpilot (or LuCI > System > Software)."
		say "After a firmware upgrade: opkg update && opkg install wrtpilot"
	else
		say "Updates: apk update && apk add --upgrade wrtpilot"
		say "After a firmware upgrade: apk update && apk add wrtpilot"
	fi
fi
