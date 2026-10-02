// Statistics: counter parsing, rates, resets, minute/day aggregation, events.
'use strict';

import { suite, eq, ok, done } from 'lib';
import * as fs from 'fs';
import * as stats from 'wrtpilot.stats';

suite('stats');

let acct_json = sprintf('%J', { nftables: [
	{ metainfo: { version: '1.0.9' } },
	{ set: { family: 'inet', name: 'dl4', table: 'wrtpilot_acct', elem: [
		{ elem: { val: '192.168.1.10', expires: 3599, counter: { packets: 10, bytes: 1000 } } },
		{ elem: { val: '192.168.1.11', counter: { packets: 1, bytes: 50 } } },
		'192.168.1.99'
	] } },
	{ set: { family: 'inet', name: 'dl6', elem: [ { elem: { val: 'fd00::10', counter: { packets: 1, bytes: 500 } } } ] } },
	{ set: { family: 'inet', name: 'ul4', elem: [ { elem: { val: '192.168.1.10', counter: { packets: 1, bytes: 100 } } } ] } },
	{ set: { family: 'inet', name: 'other', elem: [ { elem: { val: '1.1.1.1', counter: { packets: 1, bytes: 9 } } } ] } }
] });

let a = stats.parse_acct(acct_json);

eq(a.dl, { '192.168.1.10': 1000, '192.168.1.11': 50, 'fd00::10': 500 });
eq(a.ul, { '192.168.1.10': 100 });
eq(stats.parse_acct('not json'), null);

// --- sampling ---
let st = stats.create({ interval: 2 });

eq(st.size, 300, '10 minutes of 2 s samples');

stats.learn_ips(st, [ [ '192.168.1.10', 'aa:00:00:00:00:10' ], [ 'fd00::10', 'aa:00:00:00:00:10' ], [ '192.168.1.11', 'aa:00:00:00:00:11' ] ], 1000);

let t0 = 1729900000 - 1729900000 % 60 + 10;  // 10 s into a minute

stats.sample(st, { dl: { '192.168.1.10': 1000, 'fd00::10': 500 }, ul: { '192.168.1.10': 100 } }, t0, 100.0);
eq(st.rates, {}, 'first sample only establishes the baseline');

let r = stats.sample(st, { dl: { '192.168.1.10': 6000, 'fd00::10': 1500, '192.168.1.77': 400 }, ul: { '192.168.1.10': 600 } }, t0 + 2, 102.0);

// dl delta = 5000 + 1000 bytes in 2 s => 24000 bit/s ; ul = 500 bytes => 2000 bit/s
eq(st.rates['aa:00:00:00:00:10'], [ 24000, 2000 ], 'IPv4 + IPv6 aggregated per MAC, bits per second');
eq(r.unmapped, [], 'new IP has no previous value yet');

r = stats.sample(st, { dl: { '192.168.1.10': 200, 'fd00::10': 1500, '192.168.1.77': 900 }, ul: { '192.168.1.10': 600 } }, t0 + 4, 104.0);

eq(st.rates['aa:00:00:00:00:10'], [ 800, 0 ], 'counter reset (element expired) counts the new value');
eq(r.unmapped, [ '192.168.1.77' ], 'unknown IP reported for neighbour refresh');

let today = stats.today_usage(st, 'aa:00:00:00:00:10', t0 + 4);

eq(today, 5000 + 1000 + 500 + 200, 'daily total');

let live = stats.live(st, [ 'aa:00:00:00:00:10', 'aa:00:00:00:00:99' ], 2);

eq(live.ts, [ t0 + 2, t0 + 4 ]);
eq(live.devices['aa:00:00:00:00:10'].rx, [ 24000, 800 ]);
eq(live.devices['aa:00:00:00:00:99'].rx, [ 0, 0 ], 'unknown requested device gets zeros');
eq(live.total.tx, [ 2000, 0 ]);

let snap = stats.snapshot(st, t0 + 4);

eq(snap.devices['aa:00:00:00:00:10'].today_rx, 6200);
eq(snap.devices['aa:00:00:00:00:10'].rx_bps, 800);

// --- ring wrap ---
let st2 = stats.create({ interval: 60 });

eq(st2.size, 10, 'minimum ring size');
stats.learn_ips(st2, [ [ '10.0.0.1', 'bb:00:00:00:00:01' ] ], 0);

for (let i = 0; i < 25; i++)
	stats.sample(st2, { dl: { '10.0.0.1': i * 60 }, ul: {} }, 1000000 + i * 60, i * 60.0);

let l2 = stats.live(st2, null, 50);

eq(length(l2.ts), 10, 'ring keeps the last N samples');
eq(l2.ts[9], 1000000 + 24 * 60, 'newest sample last');
eq(l2.devices['bb:00:00:00:00:01'].rx[9], 8, '60 bytes / 60 s = 8 bit/s');

// --- minute files + history ---
system(`rm -rf ${stats.MIN_DIR}`);

let st3 = stats.create({ interval: 2 });
let m0 = 1729900020 - 1729900020 % 60;

stats.learn_ips(st3, [ [ '10.0.0.2', 'cc:00:00:00:00:02' ], [ '10.0.0.3', 'cc:00:00:00:00:03' ] ], 0);
stats.sample(st3, { dl: { '10.0.0.2': 0, '10.0.0.3': 0 }, ul: {} }, m0 + 1, 1.0);
stats.sample(st3, { dl: { '10.0.0.2': 1000, '10.0.0.3': 10 }, ul: {} }, m0 + 30, 30.0);

r = stats.sample(st3, { dl: { '10.0.0.2': 3000, '10.0.0.3': 10 }, ul: {} }, m0 + 61, 61.0);

ok(r.minute_changed, 'minute boundary detected');
ok(fs.access(`${stats.MIN_DIR}/${m0 - m0 % 3600}.tsv`), 'hourly minute file written');

let h = stats.history(st3, 'cc:00:00:00:00:02', 'minute', m0 - 120, m0 + 61);

eq(h.series, [ [ m0, 1000, 0 ], [ m0 + 60, 2000, 0 ] ], 'finished minute from file + minute in progress');

let ha = stats.history(st3, '', 'minute', 0, m0 + 61);

eq(ha.series[0], [ m0, 1010, 0 ], 'all devices summed');

let hh = stats.history(st3, '', 'hour', 0, m0 + 61);

eq(hh.series, [ [ m0 - m0 % 3600, 3010, 0 ] ], 'hourly aggregation');

let hd = stats.history(st3, 'cc:00:00:00:00:02', 'day', 0, m0 + 61);

eq(length(hd.series), 1);
eq(hd.series[0][1], 3000, 'daily total');

system(`rm -rf ${stats.MIN_DIR}`);

// --- presence & events ---
let st4 = stats.create({ interval: 2 });
let fresh = stats.update_presence(st4, { 'dd:00:00:00:00:01': { ip: '10.0.0.5', active: true } }, 500);

eq(fresh, [], 'first scan seeds known devices silently');

fresh = stats.update_presence(st4, { 'dd:00:00:00:00:01': { active: true }, 'dd:00:00:00:00:02': { hostname: 'tablet' } }, 600);

eq(fresh, [ 'dd:00:00:00:00:02' ], 'new device detected');
eq(st4.devs['dd:00:00:00:00:02'].hostname, 'tablet');
eq(st4.devs['dd:00:00:00:00:01'].last_seen, 600);
eq(st4.devs['dd:00:00:00:00:02'].last_seen, 0, 'lease only => not seen active');

let e1 = stats.add_event(st4, null, 'new_device', 'dd:00:00:00:00:02', { hostname: 'tablet' }, 600);
let e2 = stats.add_event(st4, null, 'quota_exceeded', 'dd:00:00:00:00:01', { used_mb: 10 }, 700);

eq([ e1.id, e2.id ], [ 1, 2 ]);
eq(map(stats.events_since(st4, 1).events, e => e.id), [ 2 ]);
eq(stats.events_since(st4, 2).events, []);

let rs = stats.events_since(st4, 50);

ok(rs.reset, 'client ahead of router (router reset) => resync');
eq(length(rs.events), 2);

for (let i = 0; i < 250; i++)
	stats.add_event(st4, null, 'x', null, null, 800);

eq(length(st4.events), 200, 'event log capped');
eq(st4.last_event_id, 252);

// --- persistence ---
let pdir = '/tmp/wrtpilot-unit-persist';

system(`rm -rf ${pdir}`);
stats.save(st, pdir);

let st5 = stats.create({ interval: 2 });

stats.load(st5, pdir, 35);
eq(st5.devs['aa:00:00:00:00:10'] != null, true, 'devices restored');
ok(st5.seeded, 'restored state counts as seeded');
system(`rm -rf ${pdir}`);

done();
