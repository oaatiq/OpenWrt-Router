#!/bin/sh
# Builds the OpenWrt userland the tests need on a regular Linux host:
# libubox, ubus, uci, ucode (with fs/uci/ubus/uloop), rpcd (ucode plugin
# support) and uhttpd (ubus plugin). Installs into /usr/local (needs root).
#
# Usage: router/tests/ci/build-deps.sh [ucode-commit]
# Debian/Ubuntu build dependencies: cmake gcc make pkg-config libjson-c-dev libssl-dev
set -eu

PREFIX=/usr/local
WORK="${WORK:-$(mktemp -d)}"

# ucode as shipped by OpenWrt 23.05 (2024-07-11); override to test others
UCODE_COMMIT="${1:-1a8a0bcf725520820802ad433db22d8f64fbed6c}"
LIBUBOX_COMMIT=e7608b69283d919d031d13cc8e21692503f5dbea
UBUS_COMMIT=414c60a2e29e72c2c4f573bc50ad54aef076d169
UCI_COMMIT=74f6277aabffc943d026f406df57c22595134c42
RPCD_COMMIT=a6c6b63b47bd8f4f762ef2e6787210cd3e0fb8a0
UHTTPD_COMMIT=373145f72c884c36a2b16f7f47e74ffae06bd754
USTREAM_COMMIT=cea28c5bc43ae80c3531c98f4e4dc67b7dcf0ebb

fetch() { # <github repo> <commit> <dir>
	echo "== $1 @ $2"
	git init -q "$WORK/$3"
	git -C "$WORK/$3" fetch -q --depth 1 "https://github.com/$1.git" "$2"
	git -C "$WORK/$3" checkout -q FETCH_HEAD
}

build() { # <dir> [cmake options...]
	dir="$1"
	shift
	cmake -S "$WORK/$dir" -B "$WORK/$dir/build" -DCMAKE_INSTALL_PREFIX="$PREFIX" \
		-DCMAKE_BUILD_TYPE=Release "$@" >/dev/null
	cmake --build "$WORK/$dir/build" -j "$(nproc)" >/dev/null
	cmake --install "$WORK/$dir/build" >/dev/null
	ldconfig
}

fetch openwrt/libubox "$LIBUBOX_COMMIT" libubox
build libubox -DBUILD_LUA=OFF -DBUILD_EXAMPLES=OFF -DABIVERSION=1

fetch openwrt/ubus "$UBUS_COMMIT" ubus
build ubus -DBUILD_LUA=OFF -DBUILD_EXAMPLES=OFF

fetch openwrt/uci "$UCI_COMMIT" uci
build uci -DBUILD_LUA=OFF

fetch jow-/ucode "$UCODE_COMMIT" ucode
build ucode -DUBUS_SUPPORT=ON -DUCI_SUPPORT=ON -DULOOP_SUPPORT=ON \
	-DRTNL_SUPPORT=OFF -DNL80211_SUPPORT=OFF

fetch openwrt/rpcd "$RPCD_COMMIT" rpcd
build rpcd -DFILE_SUPPORT=ON -DIWINFO_SUPPORT=OFF -DRPCSYS_SUPPORT=ON -DUCODE_SUPPORT=ON

# uhttpd does not build without TLS support at this commit
fetch openwrt/ustream-ssl "$USTREAM_COMMIT" ustream-ssl
build ustream-ssl

fetch openwrt/uhttpd "$UHTTPD_COMMIT" uhttpd
build uhttpd -DTLS_SUPPORT=ON -DLUA_SUPPORT=OFF -DUCODE_SUPPORT=OFF -DUBUS_SUPPORT=ON

ucode -e 'print("ucode ok\n")'
rm -rf "$WORK"
