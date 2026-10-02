// WrtPilot - runtime information about the router and its clients.
//
// parse_* functions are pure (unit tested); the rest read live state.

'use strict';

import { sys, ubus_call, ubus_list, normalize_mac, is_ipv4, is_ipv6, read_json, write_json, RUN_DIR, VERSION } from 'wrtpilot.util';
import { cursor } from 'wrtpilot.config';

// ---------------------------------------------------------------------------
// Parsers
// ---------------------------------------------------------------------------

// dnsmasq lease file: "<expiry> <mac> <ip> <hostname|*> <clientid|*>"
export function parse_leases(text) {
	let out = [];

	for (let line in split(text ?? '', '\n')) {
		let f = split(trim(line), /\s+/);

		if (length(f) < 4)
			continue;

		let mac = normalize_mac(f[1]);

		if (!mac || !(is_ipv4(f[2]) || is_ipv6(f[2])))
			continue;

		push(out, {
			expires: +f[0],
			mac: mac,
			ip: f[2],
			hostname: (f[3] == '*') ? '' : f[3]
		});
	}

	return out;
};

// /proc/net/arp
export function parse_proc_arp(text) {
	let out = [];

	for (let line in split(text ?? '', '\n')) {
		let f = split(trim(line), /\s+/);

		if (length(f) < 6 || !is_ipv4(f[0]))
			continue;

		let mac = normalize_mac(f[3]);

		if (!mac)
			continue;

		push(out, {
			ip: f[0],
			mac: mac,
			dev: f[5],
			state: (hex(f[2]) & 2) ? 'STALE' : 'INCOMPLETE'
		});
	}

	return out;
};

// `ip neigh show` (iproute2 and busybox)
export function parse_ip_neigh(text) {
	let out = [];

	for (let line in split(text ?? '', '\n')) {
		let f = split(trim(line), /\s+/);

		if (length(f) < 3 || !(is_ipv4(f[0]) || is_ipv6(f[0])))
			continue;

		let e = { ip: f[0], mac: null, dev: null, state: f[-1] };

		for (let i = 1; i < length(f) - 1; i++) {
			if (f[i] == 'dev')
				e.dev = f[i + 1];
			else if (f[i] == 'lladdr')
				e.mac = normalize_mac(f[i + 1]);
		}

		if (e.mac)
			push(out, e);
	}

	return out;
};

// /proc/net/tcp prints addresses as 32-bit words in host byte order.
function word_bytes(h, le) {
	let b = [];

	for (let i = 0; i < 4; i++)
		push(b, hex(substr(h, (le ? 3 - i : i) * 2, 2)));

	return b;
}

function hex_ipv4(h, le) {
	return join('.', word_bytes(h, le));
}

function hex_ipv6(h, le) {
	let bytes = [];

	for (let w = 0; w < 4; w++)
		push(bytes, ...word_bytes(substr(h, w * 8, 8), le));

	// IPv4-mapped address (::ffff:a.b.c.d)
	let mapped = true;

	for (let i = 0; i < 10; i++)
		if (bytes[i] != 0)
			mapped = false;

	if (mapped && bytes[10] == 255 && bytes[11] == 255)
		return sprintf('%d.%d.%d.%d', bytes[12], bytes[13], bytes[14], bytes[15]);

	return arrtoip(bytes);
}

// /proc/net/tcp{,6}: returns established connections.
// `be` = host is big endian (e.g. ath79 MIPS), default little endian.
export function parse_proc_tcp(text, v6, be) {
	let le = !be;

	let out = [];

	for (let line in split(text ?? '', '\n')) {
		let f = split(trim(line), /\s+/);

		if (length(f) < 4 || f[3] != '01')
			continue;

		let l = split(f[1], ':'), r = split(f[2], ':');

		if (length(l) != 2 || length(r) != 2)
			continue;

		push(out, {
			local_ip: v6 ? hex_ipv6(l[0], le) : hex_ipv4(l[0], le),
			local_port: hex(l[1]),
			remote_ip: v6 ? hex_ipv6(r[0], le) : hex_ipv4(r[0], le),
			remote_port: hex(r[1])
		});
	}

	return out;
};

// MAC address embedded in a DHCPv6 DUID (DUID-LLT type 1, DUID-LL type 3,
// both with hardware type 1 = ethernet), or null.
export function duid_to_mac(duid) {
	if (type(duid) != 'string')
		return null;

	duid = lc(duid);

	let hw = null;

	if (substr(duid, 0, 8) == '00010001' && length(duid) == 28)
		hw = substr(duid, 16, 12);
	else if (substr(duid, 0, 8) == '00030001' && length(duid) == 20)
		hw = substr(duid, 8, 12);

	if (!hw)
		return null;

	let parts = [];

	for (let i = 0; i < 12; i += 2)
		push(parts, substr(hw, i, 2));

	return normalize_mac(join(':', parts));
};

export function band_from_freq(freq) {
	if (type(freq) != 'int' || freq <= 0)
		return 'wifi';

	if (freq < 3000)
		return '2.4G';

	if (freq >= 5925)
		return '6G';

	return '5G';
};

// uhttpd "listen_http"/"listen_https" entries -> ports
export function parse_listen_ports(entries) {
	let ports = {};

	for (let e in entries ?? []) {
		let m = match(e, /:([0-9]+)$/) ?? match(e, /^([0-9]+)$/);

		if (m)
			ports[+m[1]] = true;
	}

	return ports;
};

// ---------------------------------------------------------------------------
// Live state
// ---------------------------------------------------------------------------

function ip_binary() {
	for (let p in [ '/sbin/ip', '/usr/sbin/ip', '/bin/ip', '/usr/bin/ip' ])
		if (sys.exists(p))
			return p;

	return null;
}

// DHCP leases from dnsmasq and odhcpd: mac -> { ip, ipv6: [], hostname }
export function leases() {
	let res = {};

	let add = function(mac, ip, hostname) {
		mac = normalize_mac(mac);

		if (!mac)
			return;

		let e = res[mac] ??= { ip: null, ipv6: [], hostname: '' };

		if (is_ipv4(ip))
			e.ip = ip;
		else if (is_ipv6(ip) && index(e.ipv6, ip) < 0)
			push(e.ipv6, ip);

		if (hostname && hostname != '*' && e.hostname == '')
			e.hostname = hostname;
	};

	for (let l in parse_leases(sys.readfile('/tmp/dhcp.leases')))
		add(l.mac, l.ip, l.hostname);

	// odhcpd (DHCPv6 always, DHCPv4 on newer releases)
	let v4 = ubus_call('dhcp', 'ipv4leases', {});

	for (let dev, d in v4?.device ?? {})
		for (let l in d.leases ?? [])
			add(l.mac, l.address, l.hostname);

	let v6 = ubus_call('dhcp', 'ipv6leases', {});

	for (let dev, d in v6?.device ?? {})
		for (let l in d.leases ?? []) {
			let mac = duid_to_mac(l.duid);

			if (!mac)
				continue;

			for (let a in l['ipv6-addr'] ?? l['ipv6'] ?? [])
				add(mac, type(a) == 'object' ? a.address : a, l.hostname);
		}

	return res;
};

// Static hostnames from /etc/config/dhcp "host" sections: mac -> name
export function static_hosts() {
	let res = {};
	let c = cursor();

	c.load('dhcp');
	c.foreach('dhcp', 'host', function(s) {
		if (!s.name)
			return;

		for (let m in (type(s.mac) == 'array' ? s.mac : split(s.mac ?? '', /\s+/))) {
			let mac = normalize_mac(m);

			if (mac)
				res[mac] = s.name;
		}
	});

	return res;
};

// Neighbour table: list of { ip, mac, dev, state }
export function neighbors() {
	let ip = ip_binary();

	if (ip) {
		let r = sys.exec(`${ip} neigh show`);

		if (r.code == 0)
			return parse_ip_neigh(r.stdout);
	}

	return parse_proc_arp(sys.readfile('/proc/net/arp'));
};

// Associated Wi-Fi stations: mac -> { band, ssid, iface, signal }
export function wifi_clients() {
	let res = {};

	for (let obj in ubus_list('hostapd.*')) {
		let st = ubus_call(obj, 'get_status', {});
		let cl = ubus_call(obj, 'get_clients', {});
		let band = band_from_freq(st?.freq ?? cl?.freq);

		for (let m, info in cl?.clients ?? {}) {
			let mac = normalize_mac(m);

			if (!mac || info.assoc === false)
				continue;

			res[mac] = {
				band: band,
				ssid: st?.ssid ?? '',
				iface: substr(obj, 8),
				signal: info.signal
			};
		}
	}

	return res;
};

export function hostapd_objects() {
	return ubus_list('hostapd.*');
};

// LAN layer-3 devices (bridges) for the configured LAN networks
export function lan_devices(settings) {
	let devs = [];
	let c = null;

	for (let net in settings.lan_networks) {
		let st = ubus_call(`network.interface.${net}`, 'status', {});
		let dev = st?.l3_device ?? st?.device;

		if (!dev) {
			c ??= cursor();
			c.load('network');

			dev = c.get('network', net, 'device') ?? c.get('network', net, 'ifname');

			if (!dev && c.get('network', net, 'type') == 'bridge')
				dev = `br-${net}`;
		}

		if (dev && match(dev, /^[A-Za-z0-9._@-]{1,15}$/) && index(devs, dev) < 0)
			push(devs, dev);
	}

	return length(devs) ? devs : [ 'br-lan' ];
};

// Primary IPv4/IPv6 addresses of the LAN networks (for DNS redirect targets)
export function lan_addresses(settings) {
	let v4 = [], v6 = [];

	for (let net in settings.lan_networks) {
		let st = ubus_call(`network.interface.${net}`, 'status', {});

		for (let a in st?.['ipv4-address'] ?? [])
			push(v4, a.address);

		for (let a in st?.['ipv6-address'] ?? [])
			push(v6, a.address);

		for (let p in st?.['ipv6-prefix-assignment'] ?? [])
			if (p['local-address']?.address)
				push(v6, p['local-address'].address);
	}

	return { v4: uniq(v4), v6: uniq(v6) };
};

export function wan_status() {
	let dump = ubus_call('network.interface', 'dump', {});
	let wan = null, wan6 = null;

	for (let i in dump?.interface ?? []) {
		let has_default = false;

		for (let r in i.route ?? [])
			if (r.target == '0.0.0.0' && r.mask == 0)
				has_default = true;

		if (i.interface == 'wan' || (has_default && !wan))
			wan = i;

		if (i.interface == 'wan6')
			wan6 = i;
	}

	if (!wan)
		return { up: false, interface: null };

	return {
		up: wan.up == true,
		interface: wan.interface,
		proto: wan.proto,
		device: wan.l3_device ?? wan.device,
		uptime: wan.uptime ?? 0,
		ipv4: wan['ipv4-address']?.[0]?.address,
		ipv6: wan6?.['ipv6-address']?.[0]?.address ?? wan['ipv6-address']?.[0]?.address,
		dns: wan['dns-server'] ?? []
	};
};

function module_available(name) {
	if (sys.exists(`/sys/module/${name}`))
		return true;

	return length(sys.glob(`/lib/modules/*/${name}.ko`)) > 0;
}

function dnsmasq_has_nftset() {
	let cache = read_json(`${RUN_DIR}/caps.json`, {});

	if (cache.nftset != null)
		return cache.nftset;

	let r = sys.exec('dnsmasq --version');
	let has = r.code == 0 && index(r.stdout, ' nftset') >= 0;

	sys.mkdir_p(RUN_DIR);
	write_json(`${RUN_DIR}/caps.json`, { ...cache, nftset: has });

	return has;
}

export function capabilities() {
	let tc = sys.exists('/sbin/tc') || sys.exists('/usr/sbin/tc');
	let tcstate = read_json(`${RUN_DIR}/tc.state`, {});

	return {
		tc: tc && module_available('sch_htb'),
		ifb: tcstate.ifb_failed ? false : (module_available('ifb') || tcstate.ifb_ok == true),
		sqm: sys.exists('/etc/init.d/sqm'),
		cake: module_available('sch_cake'),
		nftset: dnsmasq_has_nftset(),
		hostapd: length(hostapd_objects()) > 0,
		ip: ip_binary() != null
	};
};

export function offload_state() {
	let c = cursor();
	let sw = false, hw = false;

	c.load('firewall');
	c.foreach('firewall', 'defaults', function(s) {
		sw = sw || s.flow_offloading == '1';
		hw = hw || s.flow_offloading_hw == '1';
	});

	return { software: sw, hardware: hw };
};

export function system_info() {
	let rel = {};

	for (let line in split(sys.readfile('/etc/openwrt_release') ?? '', '\n')) {
		let m = match(line, /^([A-Z_]+)='?([^']*)'?$/);

		if (m)
			rel[m[1]] = m[2];
	}

	let up = split(sys.readfile('/proc/uptime') ?? '0', ' ');
	let c = cursor();

	c.load('system');

	let hostname = null, zone = null;

	c.foreach('system', 'system', function(s) {
		hostname ??= s.hostname;
		zone ??= s.zonename;
	});

	return {
		agent_version: VERSION,
		openwrt_version: rel.DISTRIB_RELEASE ?? 'unknown',
		openwrt_description: rel.DISTRIB_DESCRIPTION ?? '',
		target: rel.DISTRIB_TARGET ?? '',
		model: trim(sys.readfile('/tmp/sysinfo/model') ?? ''),
		hostname: hostname ?? trim(sys.readfile('/proc/sys/kernel/hostname') ?? ''),
		timezone: zone ?? 'UTC',
		uptime: int(+up[0]),
		time: sys.time()
	};
};

// ELF header byte 5 (EI_DATA): 1 = little endian, 2 = big endian
function host_big_endian() {
	let hdr = substr(sys.readfile('/bin/sh') ?? '', 0, 6);

	return length(hdr) == 6 && ord(hdr, 5) == 2;
}

// IP addresses that currently hold a TCP connection to uhttpd. Used to
// recognise "the device making this API call" (self-block protection).
export function api_peers() {
	let c = cursor();

	c.load('uhttpd');

	let entries = [];

	c.foreach('uhttpd', 'uhttpd', function(s) {
		for (let k in [ 'listen_http', 'listen_https' ])
			for (let e in (type(s[k]) == 'array' ? s[k] : (s[k] ? [ s[k] ] : [])))
				push(entries, e);
	});

	let ports = parse_listen_ports(entries);

	if (!length(keys(ports)))
		ports = { '80': true, '443': true };

	let peers = [];
	let be = host_big_endian();

	for (let v in [ [ '/proc/net/tcp', false ], [ '/proc/net/tcp6', true ] ])
		for (let conn in parse_proc_tcp(sys.readfile(v[0]), v[1], be))
			if (ports[conn.local_port] && conn.remote_ip != '127.0.0.1' && conn.remote_ip != '::1')
				push(peers, conn.remote_ip);

	return uniq(peers);
};
