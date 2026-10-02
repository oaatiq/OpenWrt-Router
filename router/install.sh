#!/bin/sh
# WrtPilot router agent installer. Run on the router (as root, over SSH):
#
#   wget -qO- https://github.com/oaatiq/OpenWrt-Router/releases/download/router-latest/install.sh | sh
#
# Downloads the package matching the router's package manager (opkg on
# OpenWrt 23.05/24.10, apk on 25.12+), installs it with its dependencies and
# prints the login for the app. Re-running it updates WrtPilot.
# `sh install.sh <file>` installs a package file that is already on the router.

BASE="${WRTPILOT_BASE:-https://github.com/oaatiq/OpenWrt-Router/releases/download/router-latest}"

say() { printf '%s\n' "$*"; }
die() { printf 'Error: %s\n' "$*" >&2; exit 1; }

[ -w /etc/config ] || die "run this as root on the router"
[ -f /etc/openwrt_release ] || die "this does not look like an OpenWrt router"
. /etc/openwrt_release

case "$DISTRIB_RELEASE" in
	1[0-9].*|2[0-2].*) die "OpenWrt $DISTRIB_RELEASE is too old, WrtPilot needs 23.05 or newer" ;;
esac

if command -v opkg >/dev/null 2>&1; then
	PM=opkg
	FILE=wrtpilot-router-openwrt23-24.ipk
elif command -v apk >/dev/null 2>&1; then
	PM=apk
	FILE=wrtpilot-router-openwrt25.apk
else
	die "neither opkg nor apk found"
fi

say "WrtPilot installer: ${DISTRIB_DESCRIPTION:-OpenWrt $DISTRIB_RELEASE} ($PM)"

if [ -n "$1" ]; then
	PKG="$1"
	[ -f "$PKG" ] || die "$PKG not found"
else
	PKG="/tmp/$FILE"
	say "Downloading $FILE ..."
	rm -f "$PKG"
	wget -q -O "$PKG" "$BASE/$FILE" || die "download failed: $BASE/$FILE
Check the router's internet connection, or download the file on a computer and
copy it to the router (see docs/INSTALL.md)."
	[ -s "$PKG" ] || die "downloaded file is empty"
fi

say "Updating package lists ..."
if [ "$PM" = opkg ]; then
	opkg update >/dev/null 2>&1 || say "(opkg update reported errors, trying anyway)"
	say "Installing ..."
	opkg install "$PKG" || die "installation failed (see the messages above)"
else
	apk update >/dev/null 2>&1 || say "(apk update reported errors, trying anyway)"
	say "Installing ..."
	apk add --allow-untrusted "$PKG" || die "installation failed (see the messages above)"
fi

[ -n "$1" ] || rm -f "$PKG"

ubus list wrtpilot >/dev/null 2>&1 || {
	/etc/init.d/rpcd restart >/dev/null 2>&1
	sleep 2
}
ubus list wrtpilot >/dev/null 2>&1 || die "WrtPilot is installed but rpcd did not load it; check 'logread -e rpcd'"

say ""
say "WrtPilot is installed. Use this login in the app:"
say ""
wrtpilot credentials
say "Router address: $(uci -q get network.lan.ipaddr | cut -d/ -f1)"
if [ -s "$(uci -q get uhttpd.main.cert || echo /etc/uhttpd.crt)" ]; then
	say "HTTPS is available. Certificate fingerprint (the app will show the same):"
	wrtpilot fingerprint
else
	if [ "$PM" = opkg ]; then
		say "Tip: for an encrypted connection install HTTPS support: opkg install luci-ssl"
	else
		say "Tip: for an encrypted connection install HTTPS support: apk add luci-ssl"
	fi
fi
