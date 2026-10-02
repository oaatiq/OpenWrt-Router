// Config model + apply planning (UCI in a temporary directory).
'use strict';

import { suite, eq, ok, done } from 'lib';
import * as fs from 'fs';
import { sys } from 'wrtpilot.util';
import * as config from 'wrtpilot.config';
import * as applier from 'wrtpilot.apply';

suite('plan');

let dir = '/tmp/wrtpilot-unit-uci';

system(`rm -rf ${dir} && mkdir -p ${dir}/.save`);
sys.uci_confdir = dir;
sys.uci_savedir = dir + '/.save';

let now = 1729900000;   // 2024-10-26 01:46 CEST (Saturday)

fs.writefile(`${dir}/wrtpilot`, `
config settings 'main'
	option enabled '1'
	list lan_network 'lan'

config device 'd_aa0000000001'
	option mac 'aa:00:00:00:00:01'
	option name 'Laptop'
	option blocked 'internet'

config device
	option mac 'AA:00:00:00:00:02'
	option blocked 'wifi'
	option blocked_until '${now + 600}'

config device 'd_aa0000000003'
	option mac 'aa:00:00:00:00:03'
	option blocked 'internet'
	option blocked_until '${now - 10}'
	option paused_until '${now + 900}'

config device 'd_aa0000000004'
	option mac 'aa:00:00:00:00:04'
	option group 'kids'
	option dl_limit_kbps '3000'

config device 'd_aa0000000005'
	option mac 'aa:00:00:00:00:05'
	option group 'kids'

config device 'd_aa0000000006'
	option mac 'aa:00:00:00:00:06'
	option group 'ghost'

config group 'kids'
	option name 'Kids'
	option dns_filter 'cleanbrowsing_family'
	option safesearch '1'
	list blocklist 'tiktok.com'
	list schedule 'sat-sun 09:00-22:00'
	list schedule 'bogus rule'
	option paused_until 'indefinite'
	option dl_limit_kbps '10000'

config group 'empty'
	option name 'Empty'
	option dns_filter 'cloudflare_family'
`);

let cfg = config.load();

eq(sort(keys(cfg.devices)), [ 'aa:00:00:00:00:01', 'aa:00:00:00:00:02', 'aa:00:00:00:00:03', 'aa:00:00:00:00:04', 'aa:00:00:00:00:05', 'aa:00:00:00:00:06' ]);
eq(cfg.devices['aa:00:00:00:00:02'].sid != 'd_aa0000000002', true, 'anonymous section is honoured');
eq(cfg.devices['aa:00:00:00:00:06'].group, '', 'reference to unknown group dropped');
eq(length(cfg.groups.kids.schedule_rules), 1, 'invalid schedule rule ignored');
eq(cfg.groups.kids.paused_until, -1);
eq(cfg.settings.sample_interval, 2, 'defaults');
eq(config.block_state(cfg.devices['aa:00:00:00:00:03'], now), '', 'expired block is inactive');
eq(config.group_members(cfg, 'kids'), [ 'aa:00:00:00:00:04', 'aa:00:00:00:00:05' ]);

let env = {
	lan: [ 'br-lan' ],
	caps: { tc: true, ifb: true, nftset: true },
	leaf: 'fq_codel',
	quota: { 'aa:00:00:00:00:01': now + 100, 'aa:00:00:00:00:09': now - 1 },
	safesearch_ips: {},
	local_domain: 'lan',
	dnsmasq_user: null,
	addrs: {}
};

let p = applier.plan(cfg, env, now);

eq(p.nft.blocked, [ { mac: 'aa:00:00:00:00:01', until: 0 } ], 'only active internet blocks');
eq(p.wifi, { 'aa:00:00:00:00:02': now + 600 }, 'wifi deny with expiry');
eq(p.nft.paused, [
	{ macs: [ 'aa:00:00:00:00:04', 'aa:00:00:00:00:05' ], until: -1 },
	{ macs: [ 'aa:00:00:00:00:03' ], until: now + 900 }
], 'pauses grouped by expiry');
eq(p.nft.quota, [ { mac: 'aa:00:00:00:00:01', until: now + 100 } ], 'expired quota ignored');
eq(length(p.nft.groups), 1, 'empty group skipped');

let g = p.nft.groups[0];

eq(g.id, 'kids');
eq(g.dns_port, 5301);
ok(g.blocklist && g.filtered);
ok(length(g.windows) > 0, 'schedule windows computed');
ok(index(p.dns.kids, 'server=185.228.168.168') >= 0, 'dns conf generated');
eq(keys(p.dns), [ 'kids' ]);

eq(p.tc.groups, [ { id: 'kids', dl_kbps: 10000, ul_kbps: 0 } ]);
eq(p.tc.devices, [
	{ mac: 'aa:00:00:00:00:04', group: 'kids', dl_kbps: 3000, ul_kbps: 0 },
	{ mac: 'aa:00:00:00:00:05', group: 'kids', dl_kbps: 0, ul_kbps: 0 }
]);
eq(p.nft.coarse, [], 'exact shaping available');
eq(p.warnings, []);

// no tc => coarse policing, group limit applied per member
env.caps.tc = false;
env.addrs = { 'aa:00:00:00:00:04': { ips4: [ '192.168.1.4' ], ips6: [] } };

let p2 = applier.plan(cfg, env, now);

eq(p2.nft.coarse, [
	{ mac: 'aa:00:00:00:00:04', ips4: [ '192.168.1.4' ], ips6: [], dl_kbps: 3000, ul_kbps: 0 },
	{ mac: 'aa:00:00:00:00:05', ips4: [], ips6: [], dl_kbps: 10000, ul_kbps: 0 }
]);
eq(p2.tc.devices, [], 'tc disabled');
eq(p2.warnings, [ 'coarse_limiting' ]);

// no nftset support => blocklist handled in DNS only
env.caps = { tc: true, ifb: true, nftset: false };

let p3 = applier.plan(cfg, env, now);

ok(!p3.nft.groups[0].blocklist);
ok(index(p3.dns.kids, 'address=/tiktok.com/') >= 0);
eq(p3.warnings, [ 'blocklist_dns_only' ]);

// --- mutators ---
let c = config.cursor();

c.load(config.CONFIG);
config.update_device(c, 'aa:00:00:00:00:07', { name: 'New phone' });
config.set_device_block(c, 'aa:00:00:00:00:02', null, 0);
config.set_pause(c, 'group', 'kids', 0);
config.set_limit(c, 'device', 'aa:00:00:00:00:04', 0, 0);
config.save_group(c, { id: 'teens', name: 'Teens', dns_filter: 'off', dns_custom: [], safesearch: false, blocklist: [], schedule: [ 'mon-fri 18:00-20:00' ], schedule_enabled: false });
c.commit(config.CONFIG);

cfg = config.load();

eq(cfg.devices['aa:00:00:00:00:07'].name, 'New phone', 'new named section');
eq(cfg.devices['aa:00:00:00:00:02'], null, 'empty anonymous section removed by tidy');
eq(cfg.groups.kids.paused_until, 0);
eq(cfg.devices['aa:00:00:00:00:04'].dl_limit_kbps, 0);
eq(cfg.groups.teens.schedule_enabled, false);
eq(cfg.groups.teens.schedule, [ 'mon-fri 18:00-20:00' ]);

c = config.cursor();
c.load(config.CONFIG);
config.delete_group(c, 'kids');
c.commit(config.CONFIG);
cfg = config.load();

eq(cfg.groups.kids, null);
eq(cfg.devices['aa:00:00:00:00:05'], null, 'member without other settings tidied away');
eq(cfg.devices['aa:00:00:00:00:04'], null, 'limit and membership cleared => section removed');
eq(cfg.devices['aa:00:00:00:00:01'].name, 'Laptop', 'unrelated device untouched');

system(`rm -rf ${dir}`);

done();
