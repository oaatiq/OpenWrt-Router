// WrtPilot - API implementation shared by the rpcd plugin and the CLI.
//
// Every method takes an args object and returns
//   { ok: true, ... }  or  { ok: false, error: "<code>", message: "..." }.
// Error codes are stable identifiers; the app localises them.

'use strict';

import { sys, ubus_call, normalize_mac, mac_is_random, valid_group_id, valid_domain, is_ip,
         clean_label, local_midnight, read_json, ok, err, VERSION } from 'wrtpilot.util';
import * as config from 'wrtpilot.config';
import * as sysinfo from 'wrtpilot.sysinfo';
import * as schedule from 'wrtpilot.schedule';
import * as sqm from 'wrtpilot.sqm';
import * as applier from 'wrtpilot.apply';

const MAX_KBPS = 10000000;
const MAX_DURATION = 30 * 86400;
const ONLINE_GRACE = 300;

// ---------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------

function collector(method, args) {
	return ubus_call('wrtpilotd', method, args ?? {});
}

function commit_and_apply(c) {
	c.commit(config.CONFIG);

	let r = applier.apply();

	return r.ok ? null : err(r.error ?? 'apply_failed', r.message);
}

// MAC addresses of the devices currently talking to the web server.
function self_macs() {
	let peers = sysinfo.api_peers();

	if (!length(peers))
		return [];

	let ip2mac = {};

	for (let n in sysinfo.neighbors())
		ip2mac[n.ip] = n.mac;

	for (let mac, l in sysinfo.leases())
		if (l.ip)
			ip2mac[l.ip] ??= mac;

	return uniq(filter(map(peers, ip => ip2mac[ip]), m => m != null));
}

function check_self(macs) {
	let mine = self_macs();

	for (let m in macs)
		if (index(mine, m) >= 0)
			return err('self_block', 'Refusing to block the device that is making this request');

	return null;
}

function arg_mac(args) {
	let mac = normalize_mac(args.mac);

	return mac;
}

function int_arg(v, lo, hi) {
	if (type(v) != 'int' || v < lo || v > hi)
		return null;

	return v;
}

// ---------------------------------------------------------------------------
// status / clients
// ---------------------------------------------------------------------------

export function status() {
	let cfg = config.load();
	let info = sysinfo.system_info();
	let wan = sysinfo.wan_status();
	let off = sysinfo.offload_state();
	let caps = sysinfo.capabilities();
	let last = applier.last_result();
	let ping = collector('ping');
	let me = self_macs();

	return ok({
		...info,
		enabled: cfg.settings.enabled,
		wan: wan,
		offload: off,
		offload_warning: off.software || off.hardware,
		capabilities: caps,
		coarse_limiting: index(last?.warnings ?? [], 'coarse_limiting') >= 0,
		collector: {
			running: ping != null,
			interval: ping?.interval ?? cfg.settings.sample_interval
		},
		last_apply: last ?? {},
		self_macs: me
	});
};

export function clients() {
	let now = sys.time();
	let cfg = config.load();
	let lan = sysinfo.lan_devices(cfg.settings);
	let leases = sysinfo.leases();
	let statics = sysinfo.static_hosts();
	let wifi = sysinfo.wifi_clients();
	let snap = collector('snapshot')?.devices ?? {};
	let me = self_macs();
	let neigh = {};

	for (let n in sysinfo.neighbors()) {
		if (index(lan, n.dev) < 0 || n.state == 'FAILED' || n.state == 'INCOMPLETE')
			continue;

		let e = neigh[n.mac] ??= { ip: null, ipv6: [], reachable: false };

		if (index(n.ip, ':') < 0)
			e.ip ??= n.ip;
		else if (!match(n.ip, /^fe80:/))
			push(e.ipv6, n.ip);

		if (n.state == 'REACHABLE' || n.state == 'DELAY' || n.state == 'PROBE' || n.state == 'PERMANENT')
			e.reachable = true;
	}

	let macs = {};

	for (let src in [ cfg.devices, leases, neigh, wifi, snap ])
		for (let mac in keys(src))
			macs[mac] = true;

	let group_paused = {}, group_sched = {};

	for (let gid, g in cfg.groups) {
		group_paused[gid] = config.active_until(g.paused_until, now) ? g.paused_until : 0;

		if (g.schedule_enabled && length(g.schedule_rules))
			group_sched[gid] = !schedule.allowed_at(schedule.windows(g.schedule_rules, now, 1), now);
	}

	let quota_blocked = read_json(applier.QUOTA_STATE, {});
	let out = [];

	for (let mac in keys(macs)) {
		let d = cfg.devices[mac] ?? {};
		let l = leases[mac] ?? {};
		let n = neigh[mac] ?? {};
		let w = wifi[mac];
		let s = snap[mac] ?? {};
		let last_seen = max(s.last_seen ?? 0, (w || n.reachable) ? now : 0);
		let online = w != null || n.reachable == true || (last_seen > 0 && now - last_seen < ONLINE_GRACE);
		let blocked = config.block_state(d, now);
		let dev_paused = config.active_until(d.paused_until ?? 0, now) ? d.paused_until : 0;
		let grp_paused = d.group ? group_paused[d.group] : 0;
		let paused_until = (dev_paused == -1 || grp_paused == -1) ? -1 : max(dev_paused, grp_paused);
		let ipv6 = uniq([ ...(l.ipv6 ?? []), ...(n.ipv6 ?? []) ]);
		let hostname = l.hostname || s.hostname || '';

		push(out, {
			mac: mac,
			name: d.name || statics[mac] || hostname || '',
			custom_name: d.name ?? '',
			hostname: hostname,
			ip: l.ip ?? n.ip ?? s.ip ?? '',
			ipv6: ipv6,
			conn: w ? w.band : ((online && (n.ip || l.ip)) ? 'lan' : (s.conn ?? 'unknown')),
			ssid: w?.ssid ?? '',
			signal: w?.signal ?? 0,
			online: online,
			first_seen: s.first_seen ?? 0,
			last_seen: last_seen,
			rx_bps: s.rx_bps ?? 0,
			tx_bps: s.tx_bps ?? 0,
			today_rx: s.today_rx ?? 0,
			today_tx: s.today_tx ?? 0,
			group: d.group ?? '',
			blocked: blocked,
			blocked_until: blocked ? (d.blocked_until ?? 0) : 0,
			paused: paused_until != 0,
			paused_until: paused_until,
			paused_by: dev_paused ? 'device' : (grp_paused ? 'group' : ''),
			schedule_blocked: d.group ? (group_sched[d.group] ?? false) : false,
			dl_limit_kbps: d.dl_limit_kbps ?? 0,
			ul_limit_kbps: d.ul_limit_kbps ?? 0,
			daily_quota_mb: d.daily_quota_mb ?? 0,
			quota_action: d.quota_action || cfg.settings.quota_action,
			quota_exceeded: (quota_blocked[mac] ?? 0) > now || (d.daily_quota_mb > 0 && (s.today_rx ?? 0) + (s.today_tx ?? 0) >= d.daily_quota_mb * 1048576),
			is_self: index(me, mac) >= 0,
			random_mac: mac_is_random(mac)
		});
	}

	out = sort(out, function(a, b) {
		if (a.online != b.online)
			return a.online ? -1 : 1;

		let an = lc(a.name || a.mac), bn = lc(b.name || b.mac);

		return (an < bn) ? -1 : ((an > bn) ? 1 : 0);
	});

	return ok({ time: now, clients: out });
};

// ---------------------------------------------------------------------------
// statistics (served by wrtpilotd)
// ---------------------------------------------------------------------------

export function live(args) {
	let macs = filter(map(args.macs ?? [], normalize_mac), m => m != null);
	let r = collector('live', { macs: macs, samples: int_arg(args.samples, 1, 3600) ?? 150 });

	return r ? ok(r) : err('collector_unavailable', 'The statistics collector (wrtpilotd) is not running');
};

export function history(args) {
	let res = args.resolution ?? 'minute';

	if (index([ 'minute', 'hour', 'day' ], res) < 0)
		return err('invalid_argument', 'resolution must be minute, hour or day');

	let mac = (args.mac == null || args.mac == '' || args.mac == 'all') ? '' : normalize_mac(args.mac);

	if (mac == null)
		return err('invalid_mac');

	let r = collector('history', { mac: mac, resolution: res, since: int_arg(args.since, 0, 2147483647) ?? 0 });

	return r ? ok(r) : err('collector_unavailable', 'The statistics collector (wrtpilotd) is not running');
};

export function events(args) {
	let r = collector('events', { since_id: int_arg(args.since_id, 0, 2147483647) ?? 0 });

	return r ? ok(r) : err('collector_unavailable', 'The statistics collector (wrtpilotd) is not running');
};

// ---------------------------------------------------------------------------
// devices
// ---------------------------------------------------------------------------

function device_view(mac) {
	let cfg = config.load();
	let d = cfg.devices[mac] ?? {};

	return {
		mac: mac,
		custom_name: d.name ?? '',
		group: d.group ?? '',
		blocked: d.blocked ?? '',
		blocked_until: d.blocked_until ?? 0,
		paused_until: d.paused_until ?? 0,
		dl_limit_kbps: d.dl_limit_kbps ?? 0,
		ul_limit_kbps: d.ul_limit_kbps ?? 0,
		daily_quota_mb: d.daily_quota_mb ?? 0,
		quota_action: d.quota_action ?? ''
	};
}

export function set_device(args) {
	let mac = arg_mac(args);

	if (!mac)
		return err('invalid_mac', 'A valid MAC address is required');

	let c = config.cursor();

	c.load(config.CONFIG);

	let fields = {};
	let needs_apply = false;

	if (args.name != null)
		fields.name = clean_label(args.name, 64);

	if (args.group != null) {
		if (args.group != '' && c.get(config.CONFIG, args.group) != 'group')
			return err('unknown_group', 'No such group');

		fields.group = args.group;
		needs_apply = true;
	}

	if (args.daily_quota_mb != null) {
		let q = int_arg(args.daily_quota_mb, 0, 10485760);

		if (q == null)
			return err('invalid_argument', 'daily_quota_mb out of range');

		fields.daily_quota_mb = q > 0 ? q : null;
	}

	if (args.quota_action != null) {
		if (index([ '', 'notify', 'block' ], args.quota_action) < 0)
			return err('invalid_argument', 'quota_action must be notify or block');

		fields.quota_action = args.quota_action;
	}

	config.update_device(c, mac, fields);
	c.commit(config.CONFIG);

	if (needs_apply) {
		let r = applier.apply();

		if (!r.ok)
			return err(r.error ?? 'apply_failed', r.message);
	}

	return ok({ device: device_view(mac) });
};

export function forget_device(args) {
	let mac = arg_mac(args);

	if (!mac)
		return err('invalid_mac', 'A valid MAC address is required');

	let c = config.cursor();

	c.load(config.CONFIG);
	config.forget_device(c, mac);
	collector('forget', { mac: mac });

	return commit_and_apply(c) ?? ok();
};

export function block(args) {
	let mac = arg_mac(args);

	if (!mac)
		return err('invalid_mac', 'A valid MAC address is required');

	if (args.mode != 'internet' && args.mode != 'wifi')
		return err('invalid_argument', 'mode must be "internet" or "wifi"');

	let duration = args.duration_s ?? 0;

	if (int_arg(duration, 0, MAX_DURATION) == null)
		return err('invalid_argument', 'duration_s out of range');

	let e = check_self([ mac ]);

	if (e)
		return e;

	let until = duration > 0 ? sys.time() + duration : 0;
	let c = config.cursor();

	c.load(config.CONFIG);
	config.set_device_block(c, mac, args.mode, until);

	return commit_and_apply(c) ?? ok({ mac: mac, mode: args.mode, blocked_until: until });
};

export function unblock(args) {
	let mac = arg_mac(args);

	if (!mac)
		return err('invalid_mac', 'A valid MAC address is required');

	let c = config.cursor();

	c.load(config.CONFIG);
	config.set_device_block(c, mac, null, 0);

	return commit_and_apply(c) ?? ok({ mac: mac });
};

export function set_limit(args) {
	let dl = int_arg(args.dl_kbps ?? 0, 0, MAX_KBPS);
	let ul = int_arg(args.ul_kbps ?? 0, 0, MAX_KBPS);

	if (dl == null || ul == null)
		return err('invalid_argument', 'dl_kbps / ul_kbps out of range');

	let c = config.cursor();

	c.load(config.CONFIG);

	if (args.group != null && args.group != '') {
		if (c.get(config.CONFIG, args.group) != 'group')
			return err('unknown_group', 'No such group');

		config.set_limit(c, 'group', args.group, dl, ul);
	}
	else {
		let mac = arg_mac(args);

		if (!mac)
			return err('invalid_mac', 'A valid MAC address or group is required');

		config.set_limit(c, 'device', mac, dl, ul);
	}

	c.commit(config.CONFIG);

	let r = applier.apply();

	if (!r.ok)
		return err(r.error ?? 'apply_failed', r.message);

	return ok({ coarse: index(r.warnings ?? [], 'coarse_limiting') >= 0 });
};

// ---------------------------------------------------------------------------
// pause
// ---------------------------------------------------------------------------

function pause_target(args, c) {
	if (args.group != null && args.group != '') {
		if (c.get(config.CONFIG, args.group) != 'group')
			return { error: err('unknown_group', 'No such group') };

		let macs = [];

		c.foreach(config.CONFIG, 'device', function(s) {
			let m = normalize_mac(s.mac);

			if (m && s.group == args.group)
				push(macs, m);
		});

		return { kind: 'group', id: args.group, macs: macs };
	}

	let mac = arg_mac(args);

	if (!mac)
		return { error: err('invalid_mac', 'A valid MAC address or group is required') };

	return { kind: 'device', id: mac, macs: [ mac ] };
}

export function pause(args) {
	let now = sys.time();
	let until;

	if (args.until == 'indefinite')
		until = -1;
	else if (args.until == 'tomorrow')
		until = local_midnight(now, 1);
	else if (args.until != null && args.until != '')
		return err('invalid_argument', 'until must be "tomorrow" or "indefinite"');
	else if (int_arg(args.duration_s, 60, MAX_DURATION) != null)
		until = now + args.duration_s;
	else if (args.duration_s == null || args.duration_s == 0)
		until = -1;
	else
		return err('invalid_argument', 'duration_s out of range');

	let c = config.cursor();

	c.load(config.CONFIG);

	let t = pause_target(args, c);

	if (t.error)
		return t.error;

	let e = check_self(t.macs);

	if (e)
		return e;

	config.set_pause(c, t.kind, t.id, until);

	return commit_and_apply(c) ?? ok({ paused_until: until });
};

export function resume(args) {
	let c = config.cursor();

	c.load(config.CONFIG);

	let t = pause_target(args, c);

	if (t.error)
		return t.error;

	config.set_pause(c, t.kind, t.id, 0);

	return commit_and_apply(c) ?? ok();
};

// ---------------------------------------------------------------------------
// groups
// ---------------------------------------------------------------------------

function group_view(cfg, g, now) {
	let wins = (g.schedule_enabled && length(g.schedule_rules)) ? schedule.windows(g.schedule_rules, now, 7) : null;

	return {
		id: g.id,
		name: g.name,
		members: config.group_members(cfg, g.id),
		dns_filter: g.dns_filter,
		dns_custom: g.dns_custom,
		safesearch: g.safesearch,
		blocklist: g.blocklist,
		schedule: map(g.schedule_rules, schedule.format_rule),
		schedule_enabled: g.schedule_enabled,
		dl_limit_kbps: g.dl_limit_kbps,
		ul_limit_kbps: g.ul_limit_kbps,
		paused_until: config.active_until(g.paused_until, now) ? g.paused_until : 0,
		allowed_now: wins ? schedule.allowed_at(wins, now) : true,
		next_change: wins ? (schedule.next_change(wins, now) ?? 0) : 0
	};
}

export function groups() {
	let now = sys.time();
	let cfg = config.load();
	let out = [];

	for (let gid in sort(keys(cfg.groups)))
		push(out, group_view(cfg, cfg.groups[gid], now));

	return ok({ groups: out, dns_filters: keys(config.DNS_FILTERS) });
};

function slug(name, c) {
	let base = replace(lc(name ?? ''), /[^a-z0-9]+/g, '_');

	base = replace(base, /^_+|_+$/g, '');
	base = substr(base, 0, 24);

	if (base == '' || !match(base, /^[a-z]/))
		base = 'g' + (base == '' ? sprintf('%x', sys.time() % 0xffffff) : '_' + base);

	let id = base;

	for (let i = 2; c.get(config.CONFIG, id) != null; i++)
		id = `${base}_${i}`;

	return id;
}

function clean_domain(d) {
	if (type(d) != 'string')
		return null;

	d = lc(trim(d));
	d = replace(d, /^[a-z]+:\/\//, '');
	d = replace(d, /[\/?#].*$/, '');
	d = replace(d, /:[0-9]+$/, '');
	d = replace(d, /^\*\./, '');
	d = replace(d, /^www\./, '');
	d = replace(d, /\.$/, '');

	return valid_domain(d) ? d : null;
}

export function set_group(args) {
	let c = config.cursor();

	c.load(config.CONFIG);

	let cfg = config.load(c);
	let id = args.id;
	let existing = null;

	if (id != null && id != '') {
		if (!valid_group_id(id))
			return err('invalid_group_id', 'Group ids use a-z, 0-9 and _ (max 32)');

		existing = cfg.groups[id];

		if (!existing && c.get(config.CONFIG, id) != null)
			return err('invalid_group_id', 'Id is already used by another section');
	}
	else {
		if (args.name == null || clean_label(args.name, 64) == '')
			return err('invalid_argument', 'A name is required for a new group');

		id = slug(args.name, c);
	}

	let g = {
		id: id,
		name: existing?.name ?? id,
		dns_filter: existing?.dns_filter ?? 'off',
		dns_custom: existing?.dns_custom ?? [],
		safesearch: existing?.safesearch ?? false,
		blocklist: existing?.blocklist ?? [],
		schedule: existing ? map(existing.schedule_rules, schedule.format_rule) : [],
		schedule_enabled: existing?.schedule_enabled ?? true
	};

	if (args.name != null) {
		let n = clean_label(args.name, 64);

		if (n == '')
			return err('invalid_argument', 'Name must not be empty');

		g.name = n;
	}

	if (args.dns_filter != null) {
		if (!exists(config.DNS_FILTERS, args.dns_filter))
			return err('invalid_argument', 'Unknown dns_filter');

		g.dns_filter = args.dns_filter;
	}

	if (args.dns_custom != null) {
		let list = [];

		for (let s in args.dns_custom) {
			let p = split(`${s}`, '#');
			let port = (length(p) == 2) ? +p[1] : 53;

			if (length(p) > 2 || !is_ip(p[0]) || type(port) != 'int' || port < 1 || port > 65535)
				return err('invalid_argument', `Invalid resolver address: ${s}`);

			push(list, `${s}`);
		}

		if (length(list) > 4)
			return err('invalid_argument', 'At most 4 custom resolvers');

		g.dns_custom = list;
	}

	if (g.dns_filter == 'custom' && !length(g.dns_custom))
		return err('invalid_argument', 'A custom resolver address is required');

	if (args.safesearch != null)
		g.safesearch = args.safesearch == true;

	if (args.blocklist != null) {
		let list = [];

		for (let d in args.blocklist) {
			let cd = clean_domain(d);

			if (!cd)
				return err('invalid_domain', `Invalid domain: ${d}`);

			push(list, cd);
		}

		list = uniq(list);

		if (length(list) > 500)
			return err('invalid_argument', 'At most 500 blocked domains per group');

		g.blocklist = list;
	}

	if (args.schedule != null) {
		let list = [];

		for (let r in args.schedule) {
			let p = schedule.parse_rule(r);

			if (!p)
				return err('invalid_schedule', `Invalid schedule rule: ${r}`);

			push(list, schedule.format_rule(p));
		}

		if (length(list) > 100)
			return err('invalid_argument', 'Too many schedule rules');

		g.schedule = uniq(list);
	}

	if (args.schedule_enabled != null)
		g.schedule_enabled = args.schedule_enabled == true;

	config.save_group(c, g);

	if (args.dl_kbps != null || args.ul_kbps != null) {
		let dl = int_arg(args.dl_kbps ?? existing?.dl_limit_kbps ?? 0, 0, MAX_KBPS);
		let ul = int_arg(args.ul_kbps ?? existing?.ul_limit_kbps ?? 0, 0, MAX_KBPS);

		if (dl == null || ul == null)
			return err('invalid_argument', 'dl_kbps / ul_kbps out of range');

		config.set_limit(c, 'group', id, dl, ul);
	}

	if (args.members != null) {
		let want = {};

		for (let m in args.members) {
			let mac = normalize_mac(m);

			if (!mac)
				return err('invalid_mac', `Invalid MAC address: ${m}`);

			want[mac] = true;
		}

		for (let mac in config.group_members(cfg, id))
			if (!want[mac])
				config.update_device(c, mac, { group: null });

		for (let mac in keys(want))
			config.update_device(c, mac, { group: id });
	}

	let e = commit_and_apply(c);

	if (e)
		return e;

	let now = sys.time();
	let ncfg = config.load();

	return ok({ group: group_view(ncfg, ncfg.groups[id], now) });
};

export function delete_group(args) {
	if (!valid_group_id(args.id))
		return err('invalid_group_id');

	let c = config.cursor();

	c.load(config.CONFIG);

	if (c.get(config.CONFIG, args.id) != 'group')
		return err('unknown_group', 'No such group');

	config.delete_group(c, args.id);

	return commit_and_apply(c) ?? ok();
};

// ---------------------------------------------------------------------------
// QoS / offload / maintenance
// ---------------------------------------------------------------------------

export function qos_get() {
	let wan = sysinfo.wan_status();

	return ok({ ...sqm.get(wan.device), presets: config.QOS_PRESETS });
};

export function qos_set(args) {
	if (!sqm.available())
		return err('sqm_missing', 'sqm-scripts is not installed on the router');

	let preset = args.preset ?? 'default';

	if (index(config.QOS_PRESETS, preset) < 0)
		return err('invalid_argument', 'Unknown preset');

	let dl = int_arg(args.dl_kbps ?? 0, 0, MAX_KBPS);
	let ul = int_arg(args.ul_kbps ?? 0, 0, MAX_KBPS);

	if (dl == null || ul == null)
		return err('invalid_argument', 'dl_kbps / ul_kbps out of range');

	let wan = sysinfo.wan_status();

	if (!wan.device)
		return err('no_wan', 'Could not determine the WAN interface');

	let cur = sqm.get(wan.device);
	let enabled = args.enabled ?? cur.enabled;

	if (enabled && (dl || cur.dl_kbps) == 0)
		return err('invalid_argument', 'Download and upload bandwidth are required to enable SQM');

	return ok({ ...sqm.set(wan.device, { enabled: enabled == true, dl_kbps: dl, ul_kbps: ul, preset: preset }), presets: config.QOS_PRESETS });
};

export function set_offload(args) {
	let c = config.cursor();

	c.load('firewall');

	let changed = false;

	c.foreach('firewall', 'defaults', function(s) {
		for (let k in [ [ 'software', 'flow_offloading' ], [ 'hardware', 'flow_offloading_hw' ] ]) {
			if (args[k[0]] == null)
				continue;

			let want = args[k[0]] ? '1' : '0';

			if ((s[k[1]] ?? '0') != want) {
				c.set('firewall', s['.name'], k[1], want);
				changed = true;
			}
		}
	});

	if (changed) {
		c.commit('firewall');
		sys.run([ '/etc/init.d/firewall', 'reload' ]);
	}

	return ok({ offload: sysinfo.offload_state() });
};

export function apply() {
	let r = applier.apply();

	return r.ok ? ok({ warnings: r.warnings }) : err(r.error ?? 'apply_failed', r.message);
};

export function version() {
	return ok({ agent_version: VERSION });
};
