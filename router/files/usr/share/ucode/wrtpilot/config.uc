// WrtPilot - UCI model (/etc/config/wrtpilot).
//
// load() turns the UCI file into a normalised model; the mutators below
// validate nothing themselves - callers (api.uc) validate input first.

'use strict';

import { cursor as uci_cursor } from 'uci';
import { sys, normalize_mac, mac_section, to_int, valid_group_id } from 'wrtpilot.util';
import * as schedule from 'wrtpilot.schedule';

export const CONFIG = 'wrtpilot';

// Resolver presets for group DNS filtering.
export const DNS_FILTERS = {
	off: { },
	adguard_local: { local: true },
	cleanbrowsing_family: {
		v4: [ '185.228.168.168', '185.228.169.168' ],
		v6: [ '2a0d:2a00:1::', '2a0d:2a00:2::' ]
	},
	cloudflare_family: {
		v4: [ '1.1.1.3', '1.0.0.3' ],
		v6: [ '2606:4700:4700::1113', '2606:4700:4700::1003' ]
	},
	adguard_family: {
		v4: [ '94.140.14.15', '94.140.15.16' ],
		v6: [ '2a10:50c0::bad1:ff', '2a10:50c0::bad2:ff' ]
	},
	opendns_family: {
		v4: [ '208.67.222.123', '208.67.220.123' ]
	},
	custom: { custom: true }
};

export const QOS_PRESETS = [ 'default', 'gaming', 'streaming' ];

export function cursor() {
	return sys.uci_confdir ? uci_cursor(sys.uci_confdir, sys.uci_savedir) : uci_cursor();
};

function list(v) {
	if (v == null)
		return [];

	return type(v) == 'array' ? v : [ v ];
}

function parse_until(v) {
	if (v == null || v == '' || v == '0')
		return 0;

	if (v == 'indefinite')
		return -1;

	return to_int(v, 0);
}

function format_until(v) {
	if (v == -1)
		return 'indefinite';

	return v > 0 ? `${v}` : null;
}

function load_settings(c) {
	let s = c.get_all(CONFIG, 'main') ?? {};

	return {
		enabled: s.enabled != '0',
		sample_interval: max(1, to_int(s.sample_interval, 2)),
		persist_path: s.persist_path ?? '/etc/wrtpilot',
		lan_networks: length(list(s.lan_network)) ? list(s.lan_network) : [ 'lan' ],
		history_days: max(7, to_int(s.history_days, 35)),
		quota_action: s.quota_action == 'block' ? 'block' : 'notify',
		sched_horizon_days: min(28, max(2, to_int(s.sched_horizon_days, 14))),
		adguard_port: to_int(s.adguard_port, 5353),
		tc_enabled: s.tc_enabled != '0'
	};
}

function load_device(s) {
	let mac = normalize_mac(s.mac);

	if (!mac)
		return null;

	let blocked = (s.blocked == 'internet' || s.blocked == 'wifi') ? s.blocked : '';

	return {
		sid: s['.name'],
		mac: mac,
		name: s.name ?? '',
		group: s.group ?? '',
		blocked: blocked,
		blocked_until: blocked ? max(0, to_int(s.blocked_until, 0)) : 0,
		dl_limit_kbps: max(0, to_int(s.dl_limit_kbps, 0)),
		ul_limit_kbps: max(0, to_int(s.ul_limit_kbps, 0)),
		daily_quota_mb: max(0, to_int(s.daily_quota_mb, 0)),
		quota_action: (s.quota_action == 'block' || s.quota_action == 'notify') ? s.quota_action : '',
		paused_until: parse_until(s.paused_until)
	};
}

function load_group(s) {
	let id = s['.name'];

	if (!valid_group_id(id))
		return null;

	let rules = [];

	for (let r in list(s.schedule)) {
		let p = schedule.parse_rule(r);

		if (p)
			push(rules, p);
	}

	return {
		id: id,
		name: s.name ?? id,
		dns_filter: exists(DNS_FILTERS, s.dns_filter) ? s.dns_filter : 'off',
		dns_custom: list(s.dns_custom),
		safesearch: s.safesearch == '1',
		blocklist: list(s.blocklist),
		schedule: list(s.schedule),
		schedule_rules: rules,
		schedule_enabled: s.schedule_enabled != '0',
		dl_limit_kbps: max(0, to_int(s.dl_limit_kbps, 0)),
		ul_limit_kbps: max(0, to_int(s.ul_limit_kbps, 0)),
		paused_until: parse_until(s.paused_until)
	};
}

export function load(c) {
	c ??= cursor();
	c.load(CONFIG);

	let model = {
		settings: load_settings(c),
		devices: {},
		groups: {}
	};

	c.foreach(CONFIG, 'group', function(s) {
		let g = load_group(s);

		if (g)
			model.groups[g.id] = g;
	});

	c.foreach(CONFIG, 'device', function(s) {
		let d = load_device(s);

		if (d) {
			if (d.group != '' && !model.groups[d.group])
				d.group = '';

			model.devices[d.mac] = d;
		}
	});

	return model;
};

// ---------------------------------------------------------------------------
// Effective state helpers
// ---------------------------------------------------------------------------

export function active_until(until, now) {
	return until == -1 || until > now;
};

export function block_state(dev, now) {
	if (!dev?.blocked)
		return '';

	return (dev.blocked_until == 0 || dev.blocked_until > now) ? dev.blocked : '';
};

export function group_members(model, gid) {
	let macs = [];

	for (let mac, d in model.devices)
		if (d.group == gid)
			push(macs, mac);

	return sort(macs);
};

// ---------------------------------------------------------------------------
// Mutators (operate on a loaded cursor, caller commits)
// ---------------------------------------------------------------------------

function set_opt(c, sid, opt, val) {
	if (val == null || val == '' || (type(val) == 'array' && length(val) == 0))
		c.delete(CONFIG, sid, opt);
	else
		c.set(CONFIG, sid, opt, type(val) == 'array' ? val : `${val}`);
}

// Device sections are normally named d_<mac>, but hand-written anonymous
// "config device" sections are honoured too.
export function find_device(c, mac) {
	let sid = mac_section(mac);

	if (c.get(CONFIG, sid) == 'device')
		return sid;

	sid = null;

	c.foreach(CONFIG, 'device', function(s) {
		if (sid == null && normalize_mac(s.mac) == mac)
			sid = s['.name'];
	});

	return sid;
};

export function ensure_device(c, mac) {
	let sid = find_device(c, mac);

	if (!sid) {
		sid = mac_section(mac);
		c.set(CONFIG, sid, 'device');
		c.set(CONFIG, sid, 'mac', mac);
	}

	return sid;
};

// Remove device sections that no longer carry any setting.
export function tidy_device(c, mac) {
	let sid = find_device(c, mac);
	let s = sid ? c.get_all(CONFIG, sid) : null;

	if (!s)
		return;

	for (let k in keys(s))
		if (substr(k, 0, 1) != '.' && k != 'mac')
			return;

	c.delete(CONFIG, sid);
};

export function update_device(c, mac, fields) {
	let sid = ensure_device(c, mac);

	for (let k, v in fields)
		set_opt(c, sid, k, v);

	tidy_device(c, mac);
};

export function set_device_block(c, mac, mode, until) {
	update_device(c, mac, {
		blocked: mode || null,
		blocked_until: (mode && until > 0) ? until : null
	});
};

export function set_pause(c, kind, id, until) {
	let sid = (kind == 'group') ? id : ensure_device(c, id);

	set_opt(c, sid, 'paused_until', format_until(until));

	if (kind != 'group')
		tidy_device(c, id);
};

export function set_limit(c, kind, id, dl, ul) {
	let sid = (kind == 'group') ? id : ensure_device(c, id);

	set_opt(c, sid, 'dl_limit_kbps', dl > 0 ? dl : null);
	set_opt(c, sid, 'ul_limit_kbps', ul > 0 ? ul : null);

	if (kind != 'group')
		tidy_device(c, id);
};

export function save_group(c, g) {
	if (c.get(CONFIG, g.id) != 'group')
		c.set(CONFIG, g.id, 'group');

	set_opt(c, g.id, 'name', g.name);
	set_opt(c, g.id, 'dns_filter', g.dns_filter == 'off' ? null : g.dns_filter);
	set_opt(c, g.id, 'dns_custom', g.dns_custom);
	set_opt(c, g.id, 'safesearch', g.safesearch ? '1' : null);
	set_opt(c, g.id, 'blocklist', g.blocklist);
	set_opt(c, g.id, 'schedule', g.schedule);
	set_opt(c, g.id, 'schedule_enabled', g.schedule_enabled ? null : '0');
};

export function delete_group(c, gid) {
	let macs = [];

	c.foreach(CONFIG, 'device', function(s) {
		if (s.group == gid)
			push(macs, [ s['.name'], normalize_mac(s.mac) ]);
	});

	for (let m in macs) {
		c.delete(CONFIG, m[0], 'group');

		if (m[1])
			tidy_device(c, m[1]);
	}

	c.delete(CONFIG, gid);
};

export function forget_device(c, mac) {
	let sid = find_device(c, mac);

	if (sid)
		c.delete(CONFIG, sid);
};
