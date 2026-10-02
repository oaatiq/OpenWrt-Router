// nftables / tc / dnsmasq generators. When `nft` is usable the generated
// ruleset is also validated with `nft -c` (dry run against the kernel).
'use strict';

import { suite, eq, ok, contains, not_contains, done } from 'lib';
import * as fs from 'fs';
import * as nft from 'wrtpilot.nft';
import * as tc from 'wrtpilot.tc';
import * as dns from 'wrtpilot.dns';

suite('generators');

let now = 1790000000;
let model = {
	lan: [ 'br-lan' ],
	blocked: [ { mac: 'aa:00:00:00:00:01', until: 0 }, { mac: 'aa:00:00:00:00:02', until: now + 600 } ],
	paused: [ { macs: [ 'aa:00:00:00:00:03' ], until: now + 900 }, { macs: [ 'aa:00:00:00:00:04' ], until: -1 } ],
	quota: [ { mac: 'aa:00:00:00:00:05', until: now + 3600 } ],
	groups: [
		{ id: 'kids', macs: [ 'aa:00:00:00:00:07', 'aa:00:00:00:00:08' ], windows: [ [ now, now + 3600 ] ], blocklist: true, dns_port: 5301, filtered: true },
		{ id: 'nobody', macs: [], windows: [ [ now, now + 60 ] ], blocklist: true, dns_port: 5302, filtered: true }
	],
	coarse: []
};

let text = nft.generate(model);

contains(text, 'table inet wrtpilot_acct {');
contains(text, 'update @dl4 { ip daddr }');
contains(text, 'update @ul6 { ip6 saddr }');
contains(text, 'oifname { "br-lan" } iifname != { "br-lan" }', 'download direction = towards LAN');
contains(text, 'ether saddr @blocked_mac goto wp_reject', 'permanent block via set');
contains(text, `ether saddr aa:00:00:00:00:02 meta time < ${now + 600} goto wp_reject`, 'timed block expires by itself');
contains(text, `ether saddr { aa:00:00:00:00:03 } meta time < ${now + 900} goto wp_reject`, 'timed pause');
contains(text, 'ether saddr { aa:00:00:00:00:04 } goto wp_reject', 'indefinite pause');
contains(text, `ether saddr aa:00:00:00:00:05 meta time < ${now + 3600} goto wp_reject`, 'quota block');
contains(text, 'meta time != @g_kids_win goto wp_reject', 'schedule');
contains(text, `add element inet wrtpilot g_kids_win { ${now}-${now + 3599} }`, 'window end exclusive');
contains(text, 'ip daddr @g_kids_bl4 goto wp_reject', 'blocklist');
contains(text, 'th dport 53 redirect to :5301', 'dns redirect');
contains(text, 'th dport 853 goto wp_reject', 'DoT blocked for filtered groups');
contains(text, 'add element inet wrtpilot filtered_mac { aa:00:00:00:00:07, aa:00:00:00:00:08 }');
not_contains(text, 'g_nobody', 'groups without members produce nothing');
not_contains(text, 'flush set inet wrtpilot g_kids_bl4', 'dnsmasq-filled sets are never flushed');
not_contains(text, 'flush set inet wrtpilot_acct', 'counters are never flushed');

eq(nft.desired_sets({ groups: [ { id: 'a', windows: [], blocklist: false } ] }),
	[ 'blocked_mac', 'filtered_mac', 'doh4', 'doh6', 'dohdyn4', 'dohdyn6', 'g_a_mac', 'g_a_win' ]);
eq(nft.stale_cleanup([ 'blocked_mac', 'g_old_mac', 'g_kids_mac', 'g_kids_win', 'g_kids_bl4', 'g_kids_bl6' ], model),
	[ 'delete set inet wrtpilot g_old_mac' ]);
eq(nft.teardown([ 'wrtpilot' ]), [ 'delete table inet wrtpilot' ]);

let coarse = nft.generate({ lan: [ 'br-lan' ], groups: [], coarse: [ { mac: 'aa:00:00:00:00:09', ips4: [ '192.168.1.9' ], ips6: [], dl_kbps: 8000, ul_kbps: 800 } ] });

contains(coarse, 'ether saddr aa:00:00:00:00:09 limit rate over 100 kbytes/second', 'upload policing in kbytes/s');
contains(coarse, 'ip daddr { 192.168.1.9 } limit rate over 1000 kbytes/second', 'download policing');

// empty model is valid too
let empty = nft.generate({ lan: [ 'br-lan', 'br-guest' ], groups: [] });

contains(empty, 'iifname { "br-lan", "br-guest" }');
not_contains(empty, 'add element inet wrtpilot blocked_mac');

if (getenv('WRTPILOT_NFT_CHECK') == '1') {
	for (let t in [ text, coarse, empty ]) {
		fs.writefile('/tmp/wrtpilot-unit.nft', t);

		let p = fs.popen('nft -c -f /tmp/wrtpilot-unit.nft 2>&1', 'r');
		let out = p.read('all');

		eq(p.close(), 0, 'nft -c accepts the ruleset: ' + out);
	}

	fs.unlink('/tmp/wrtpilot-unit.nft');
}

// --- tc ---
let t = tc.generate({
	lan: [ 'br-lan' ], ifb: true, leaf: 'fq_codel',
	groups: [ { id: 'kids', dl_kbps: 10000, ul_kbps: 0 } ],
	devices: [
		{ mac: 'aa:00:00:00:00:01', group: 'kids', dl_kbps: 4000, ul_kbps: 0 },
		{ mac: 'aa:00:00:00:00:02', group: 'kids', dl_kbps: 0, ul_kbps: 0 },
		{ mac: 'aa:00:00:00:00:03', group: '', dl_kbps: 0, ul_kbps: 1000 }
	]
});

ok(t.active);
eq(t.links, [ 'wp-ifb0' ]);
eq(t.dl_devices, 2, 'two download-limited devices (both kids)');
eq(t.ul_devices, 1);
contains(t.batch, 'class add dev br-lan parent 1: classid 1:100 htb rate 10000kbit ceil 10000kbit', 'group class');
contains(t.batch, 'class add dev br-lan parent 1:100 classid 1:1000 htb rate 4000kbit ceil 4000kbit', 'device under group, own lower cap');
contains(t.batch, 'class add dev br-lan parent 1:100 classid 1:1001 htb rate 5000kbit ceil 10000kbit', 'member without own cap shares group');
contains(t.batch, 'u32 match ether dst aa:00:00:00:00:01 flowid 1:1000');
contains(t.batch, 'u32 match ether src aa:00:00:00:00:03 action mirred egress redirect dev wp-ifb0');
contains(t.batch, 'class add dev wp-ifb0 parent 1: classid 1:1000 htb rate 1000kbit ceil 1000kbit');
contains(t.batch, 'qdisc add dev br-lan parent 1:1000 fq_codel');

let t2 = tc.generate({ lan: [ 'br-lan' ], ifb: false, groups: [], devices: [ { mac: 'aa:00:00:00:00:03', group: '', dl_kbps: 0, ul_kbps: 1000 } ] });

ok(!t2.active, 'upload-only limit without ifb installs nothing');
eq(t2.ul_shaped, false);

let t3 = tc.generate({ lan: [ 'br-lan' ], ifb: true, groups: [], devices: [] });

ok(!t3.active && t3.batch == '', 'no limits => no shaping at all');

// --- dnsmasq ---
let g = { id: 'kids', dns_filter: 'cleanbrowsing_family', dns_custom: [], safesearch: true, blocklist: [ 'tiktok.com', 'roblox.com' ] };
let conf = dns.generate(g, { port: 5301, lan: [ 'br-lan' ], settings: { adguard_port: 5353 }, nftset: true, filtered: true, local_domain: 'lan', user: { user: 'dnsmasq', group: 'dnsmasq' }, safesearch_ips: {} });

contains(conf, 'port=5301');
contains(conf, 'interface=br-lan');
contains(conf, 'server=185.228.168.168');
contains(conf, 'server=2a0d:2a00:1::');
contains(conf, 'server=/lan/127.0.0.1#53', 'local names stay local');
contains(conf, 'nftset=/tiktok.com/roblox.com/4#inet#wrtpilot#g_kids_bl4,6#inet#wrtpilot#g_kids_bl6');
contains(conf, 'address=/www.google.com/216.239.38.120', 'SafeSearch google');
contains(conf, 'address=/www.google.fr/216.239.38.120', 'SafeSearch google.fr');
contains(conf, 'address=/www.youtube.com/216.239.38.120', 'YouTube restricted');
contains(conf, 'address=/www.bing.com/204.79.197.220');
contains(conf, 'address=/www.bing.com/::', 'no IPv6 bypass of SafeSearch');
contains(conf, 'address=/use-application-dns.net/', 'Firefox DoH canary');
contains(conf, 'nftset=/dns.google/');
contains(conf, 'user=dnsmasq');
contains(conf, 'group=dnsmasq');

let conf2 = dns.generate({ id: 'teens', dns_filter: 'off', dns_custom: [], safesearch: false, blocklist: [ 'example.com' ] },
	{ port: 5302, lan: [ 'br-lan' ], settings: { adguard_port: 5353 }, nftset: false, filtered: true, safesearch_ips: {} });

contains(conf2, 'server=127.0.0.1#53', 'filter off forwards to main dnsmasq');
contains(conf2, 'address=/example.com/', 'no nftset support => DNS level block');
not_contains(conf2, 'nftset=');
not_contains(conf2, 'user=');

let conf3 = dns.generate({ id: 'x', dns_filter: 'adguard_local', dns_custom: [], safesearch: false, blocklist: [] },
	{ port: 5303, lan: [ 'br-lan' ], settings: { adguard_port: 3053 }, nftset: true, filtered: true, safesearch_ips: { 'forcesafesearch.google.com': { v4: '1.2.3.4' } } });

contains(conf3, 'server=127.0.0.1#3053', 'AdGuard Home local port');

let conf4 = dns.generate({ id: 'y', dns_filter: 'custom', dns_custom: [ '9.9.9.9', '192.168.1.2#5353', 'not-an-ip' ], safesearch: true, blocklist: [] },
	{ port: 5304, lan: [ 'br-lan' ], settings: { adguard_port: 3053 }, nftset: true, filtered: true, safesearch_ips: { 'forcesafesearch.google.com': { v4: '1.2.3.4', v6: null } } });

contains(conf4, 'server=9.9.9.9');
contains(conf4, 'server=192.168.1.2#5353');
not_contains(conf4, 'not-an-ip');
contains(conf4, 'address=/www.google.com/1.2.3.4', 'resolved SafeSearch address wins');

ok(dns.needs_instance({ dns_filter: 'off', safesearch: false, blocklist: [ 'a.com' ] }));
ok(!dns.needs_instance({ dns_filter: 'off', safesearch: false, blocklist: [] }));

done();
