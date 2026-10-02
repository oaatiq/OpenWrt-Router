// WrtPilot - per-group DNS (filtering resolver, SafeSearch, blocklists).
//
// Every group that needs DNS policy gets its own small dnsmasq instance
// (started by /etc/init.d/wrtpilotd, see procd "file" param) listening on a
// dedicated port; nftables redirects the group's port 53 traffic there.
// The instance forwards to the chosen resolver, fills the group's nft
// blocklist sets (nftset=) and rewrites search engines to SafeSearch.

'use strict';

import { sys, is_ipv4, is_ipv6, read_json, write_json, RUN_DIR } from 'wrtpilot.util';
import { DNS_FILTERS, cursor } from 'wrtpilot.config';
import { TABLE, group_set } from 'wrtpilot.nft';

export const BASE_PORT = 5301;

// SafeSearch endpoints; addresses are re-resolved at apply time and the
// values below are only used when that fails.
export const SAFESEARCH = [
	{
		target: 'forcesafesearch.google.com',
		v4: '216.239.38.120', v6: '2001:4860:4802:32::78',
		domains: map([
			'com', 'fr', 'be', 'ch', 'ca', 'lu', 'co.ma', 'dz', 'tn', 'sn', 'ci', 'cm', 'ml', 'bf',
			'ne', 'td', 'mg', 'mu', 'ga', 'cg', 'cd', 'rw', 'bj', 'tg', 'ht', 'com.sa', 'ae',
			'com.eg', 'com.qa', 'com.kw', 'com.lb', 'jo', 'iq', 'com.om', 'com.bh', 'com.ly',
			'co.uk', 'de', 'es', 'it', 'nl', 'pt', 'com.tr', 'at', 'pl', 'se', 'ie', 'com.br',
			'com.mx', 'co.in', 'com.au', 'co.jp'
		], tld => `www.google.${tld}`)
	},
	{
		target: 'restrict.youtube.com',
		v4: '216.239.38.120', v6: '2001:4860:4802:32::78',
		domains: [ 'www.youtube.com', 'm.youtube.com', 'youtubei.googleapis.com',
			'youtube.googleapis.com', 'www.youtube-nocookie.com' ]
	},
	{
		target: 'strict.bing.com',
		v4: '204.79.197.220', v6: null,
		domains: [ 'www.bing.com' ]
	},
	{
		target: 'safe.duckduckgo.com',
		v4: null, v6: null,
		domains: [ 'duckduckgo.com', 'www.duckduckgo.com' ]
	}
];

// Hostnames of public DoH resolvers; their resolved addresses go into the
// dohdyn sets so filtered devices cannot reach them on port 443.
export const DOH_HOSTS = [
	'dns.google', 'dns64.dns.google', 'cloudflare-dns.com', 'one.one.one.one',
	'dns.quad9.net', 'doh.opendns.com', 'doh.familyshield.opendns.com',
	'dns.adguard.com', 'dns.adguard-dns.com', 'dns.nextdns.io', 'doh.cleanbrowsing.org',
	'dns.controld.com', 'freedns.controld.com', 'doh.mullvad.net', 'dns.mullvad.net',
	'doh.dns.sb', 'dns.alidns.com', 'doh.pub', 'dns.twnic.tw', 'dns0.eu',
	'doh.libredns.gr', 'dns.switch.ch', 'doh.ffmuc.net'
];

// Names that make browsers / OSes turn off their own encrypted DNS.
export const DISABLE_ENCRYPTED_DNS = [
	'use-application-dns.net',  // Firefox canary domain
	'mask.icloud.com',          // iCloud Private Relay
	'mask-h2.icloud.com'
];

function is_ip_server(s) {
	let m = match(s, /^(.+)#([0-9]{1,5})$/);

	return m ? (is_ipv4(m[1]) || is_ipv6(m[1])) && +m[2] < 65536 : (is_ipv4(s) || is_ipv6(s));
}

function upstream_servers(g, settings) {
	let f = DNS_FILTERS[g.dns_filter] ?? {};

	if (f.local)
		return [ `127.0.0.1#${settings.adguard_port}` ];

	if (f.custom) {
		let s = filter(g.dns_custom, ip => is_ip_server(ip));

		return length(s) ? s : [ '127.0.0.1#53' ];
	}

	if (f.v4)
		return [ ...f.v4, ...(f.v6 ?? []) ];

	// "off": the main dnsmasq keeps doing the resolution
	return [ '127.0.0.1#53' ];
}

export function needs_instance(g) {
	return g.dns_filter != 'off' || g.safesearch || length(g.blocklist) > 0;
};

// ctx: { port, lan: [devs], settings, nftset: bool, safesearch_ips: {target: {v4, v6}},
//        local_domain, user }
export function generate(g, ctx) {
	let lines = [
		`# WrtPilot DNS for group "${g.id}" - generated, do not edit`,
		`port=${ctx.port}`,
		'bind-dynamic',
		...map(ctx.lan, d => `interface=${d}`),
		...map(ctx.lan, d => `no-dhcp-interface=${d}`),
		'no-resolv',
		'no-hosts',
		'no-poll',
		'domain-needed',
		'cache-size=1000',
		`pid-file=${RUN_DIR}/dnsmasq-${g.id}.pid`
	];

	if (ctx.user)
		push(lines, `user=${ctx.user}`, `group=${ctx.user}`);

	// local names and reverse lookups stay with the main resolver
	for (let zone in [ ctx.local_domain ?? 'lan', 'in-addr.arpa', 'ip6.arpa' ])
		push(lines, `server=/${zone}/127.0.0.1#53`);

	for (let s in upstream_servers(g, ctx.settings))
		push(lines, `server=${s}`);

	if (length(g.blocklist)) {
		if (ctx.nftset) {
			let sets = `4#inet#${TABLE}#${group_set(g.id, 'bl4')},6#inet#${TABLE}#${group_set(g.id, 'bl6')}`;

			for (let i = 0; i < length(g.blocklist); i += 32)
				push(lines, `nftset=/${join('/', slice(g.blocklist, i, i + 32))}/${sets}`);
		}
		else {
			// dnsmasq without nftset support: refuse to resolve instead
			for (let d in g.blocklist)
				push(lines, `address=/${d}/`);
		}
	}

	if (ctx.nftset && ctx.filtered)
		push(lines, `nftset=/${join('/', DOH_HOSTS)}/4#inet#${TABLE}#dohdyn4,6#inet#${TABLE}#dohdyn6`);

	if (ctx.filtered)
		for (let d in DISABLE_ENCRYPTED_DNS)
			push(lines, `address=/${d}/`);

	if (g.safesearch) {
		for (let ss in SAFESEARCH) {
			let ips = ctx.safesearch_ips?.[ss.target] ?? {};
			let v4 = ips.v4 ?? ss.v4, v6 = ips.v6 ?? ss.v6;

			for (let d in ss.domains) {
				if (v4)
					push(lines, `address=/${d}/${v4}`);

				if (v6)
					push(lines, `address=/${d}/${v6}`);
				else if (v4)
					push(lines, `address=/${d}/::`);
			}
		}
	}

	return join('\n', lines) + '\n';
};

function resolve(host) {
	let r = sys.exec(`nslookup ${host} 127.0.0.1`);
	let res = { v4: null, v6: null };

	if (r.code != 0)
		return res;

	let past_server = false;

	for (let line in split(r.stdout, '\n')) {
		if (match(line, /^Name:/))
			past_server = true;

		if (!past_server)
			continue;

		for (let tok in split(line, /[\s,]+/)) {
			if (!res.v4 && is_ipv4(tok))
				res.v4 = tok;
			else if (!res.v6 && is_ipv6(tok) && !match(tok, /^fe80:/))
				res.v6 = tok;
		}
	}

	return res;
}

// Resolve SafeSearch targets, cached for a day.
export function safesearch_ips(now) {
	let path = `${RUN_DIR}/safesearch.json`;
	let cache = read_json(path, {});

	if (cache.ts && now - cache.ts < 86400)
		return cache.ips;

	let ips = {};

	for (let ss in SAFESEARCH) {
		let r = resolve(ss.target);

		if (r.v4 || r.v6)
			ips[ss.target] = r;
	}

	if (length(keys(ips)))
		write_json(path, { ts: now, ips: ips });

	return ips;
};

export function dnsmasq_user() {
	let passwd = sys.readfile('/etc/passwd') ?? '';

	return match(passwd, /(^|\n)dnsmasq:/) ? 'dnsmasq' : null;
};

export function local_domain() {
	let c = cursor();
	let domain = null;

	c.load('dhcp');
	c.foreach('dhcp', 'dnsmasq', function(s) {
		domain ??= s.domain;
	});

	return (domain && match(domain, /^[a-z0-9.-]+$/)) ? domain : 'lan';
};

// Write instance configs; returns true when anything changed.
export function write_confs(confs) {
	let changed = false;
	let want = {};

	for (let gid, text in confs) {
		let path = `${RUN_DIR}/dnsmasq-${gid}.conf`;

		want[path] = true;

		if (sys.readfile(path) != text) {
			sys.writefile_atomic(path, text);
			changed = true;
		}
	}

	for (let path in sys.glob(`${RUN_DIR}/dnsmasq-*.conf`)) {
		if (!want[path]) {
			sys.unlink(path);
			changed = true;
		}
	}

	return changed;
};
