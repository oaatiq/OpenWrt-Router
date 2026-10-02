// WrtPilot - apply: UCI -> nftables / tc / dnsmasq / hostapd.
//
// plan() is pure (config + environment + time -> desired state) and unit
// tested. apply() gathers the environment, computes the plan and pushes it
// to the system, touching each subsystem only when its state changed.
// It is idempotent and runs on boot, on firewall reload, on LAN ifup, from
// the API after every change and from wrtpilotd for timed transitions.

'use strict';

import * as fs from 'fs';
import { sys, RUN_DIR, NFT_INCLUDE, log, read_json, write_json, shellquote } from 'wrtpilot.util';
import * as config from 'wrtpilot.config';
import * as sysinfo from 'wrtpilot.sysinfo';
import * as schedule from 'wrtpilot.schedule';
import * as nftgen from 'wrtpilot.nft';
import * as tcgen from 'wrtpilot.tc';
import * as dns from 'wrtpilot.dns';
import * as wifi from 'wrtpilot.wifi';

export const QUOTA_STATE = RUN_DIR + '/quota.json';
const TC_STATE = RUN_DIR + '/tc.state';

// ---------------------------------------------------------------------------
// Planning
// ---------------------------------------------------------------------------

function add_pause(map, mac, until) {
	let cur = map[mac];

	if (cur == null || until == -1 || (cur != -1 && until > cur))
		map[mac] = until;
}

// cfg: config.load() result
// env: { lan, caps, leaf, quota, safesearch_ips, local_domain, dnsmasq_user, addrs }
export function plan(cfg, env, now) {
	let s = cfg.settings;
	let p = {
		enabled: s.enabled,
		nft: { lan: env.lan, blocked: [], paused: [], quota: [], groups: [], coarse: [] },
		tc: { lan: env.lan, groups: [], devices: [], ifb: env.caps.ifb, leaf: env.leaf },
		tc_wanted: false,
		dns: {},
		wifi: {},
		warnings: []
	};

	let paused = {};

	for (let mac in sort(keys(cfg.devices))) {
		let d = cfg.devices[mac];
		let st = config.block_state(d, now);

		if (st == 'internet')
			push(p.nft.blocked, { mac: mac, until: d.blocked_until });
		else if (st == 'wifi')
			p.wifi[mac] = d.blocked_until;

		if (config.active_until(d.paused_until, now))
			add_pause(paused, mac, d.paused_until);
	}

	let port = dns.BASE_PORT;
	let limited_groups = {};

	for (let gid in sort(keys(cfg.groups))) {
		let g = cfg.groups[gid];
		let macs = config.group_members(cfg, gid);

		if (g.dl_limit_kbps > 0 || g.ul_limit_kbps > 0)
			limited_groups[gid] = g;

		if (!length(macs))
			continue;

		if (config.active_until(g.paused_until, now))
			for (let mac in macs)
				add_pause(paused, mac, g.paused_until);

		let windows = (g.schedule_enabled && length(g.schedule_rules))
			? schedule.windows(g.schedule_rules, now, s.sched_horizon_days) : null;

		let need_dns = dns.needs_instance(g);
		let gp = need_dns ? port++ : null;

		if (length(g.blocklist) && !env.caps.nftset)
			push(p.warnings, 'blocklist_dns_only');

		push(p.nft.groups, {
			id: gid,
			macs: macs,
			windows: windows,
			blocklist: length(g.blocklist) > 0 && env.caps.nftset,
			dns_port: gp,
			filtered: need_dns
		});

		if (need_dns)
			p.dns[gid] = dns.generate(g, {
				port: gp,
				lan: env.lan,
				settings: s,
				nftset: env.caps.nftset,
				filtered: true,
				safesearch_ips: env.safesearch_ips,
				local_domain: env.local_domain,
				user: env.dnsmasq_user
			});
	}

	// group pauses by expiry so one rule covers all devices sharing it
	let by_until = {};

	for (let mac, until in paused)
		push(by_until[until] ??= [], mac);

	for (let until in sort(keys(by_until), (a, b) => +a - +b))
		push(p.nft.paused, { macs: sort(by_until[until]), until: +until });

	for (let mac in sort(keys(env.quota ?? {})))
		if (env.quota[mac] > now)
			push(p.nft.quota, { mac: mac, until: env.quota[mac] });

	// --- rate limits ---
	for (let gid, g in limited_groups)
		push(p.tc.groups, { id: gid, dl_kbps: g.dl_limit_kbps, ul_kbps: g.ul_limit_kbps });

	for (let mac in sort(keys(cfg.devices))) {
		let d = cfg.devices[mac];

		if (d.dl_limit_kbps > 0 || d.ul_limit_kbps > 0 || limited_groups[d.group])
			push(p.tc.devices, {
				mac: mac,
				group: limited_groups[d.group] ? d.group : '',
				dl_kbps: d.dl_limit_kbps,
				ul_kbps: d.ul_limit_kbps
			});
	}

	p.tc_wanted = length(p.tc.devices) > 0;

	let shaping = env.caps.tc && s.tc_enabled;

	if (p.tc_wanted && (!shaping || !env.caps.ifb)) {
		// coarse policing per device; a group limit applies to each member
		for (let td in p.tc.devices) {
			let g = limited_groups[td.group];
			let dl = td.dl_kbps || g?.dl_limit_kbps || 0;
			let ul = td.ul_kbps || g?.ul_limit_kbps || 0;
			let a = env.addrs?.[td.mac] ?? { ips4: [], ips6: [] };

			if (shaping)
				dl = 0;  // download is still shaped by tc, only upload falls back

			if (dl > 0 || ul > 0)
				push(p.nft.coarse, { mac: td.mac, ips4: a.ips4, ips6: a.ips6, dl_kbps: dl, ul_kbps: ul });
		}

		push(p.warnings, 'coarse_limiting');

		if (!shaping) {
			p.tc.groups = [];
			p.tc.devices = [];
		}
	}

	return p;
};

// ---------------------------------------------------------------------------
// Environment
// ---------------------------------------------------------------------------

function device_addresses() {
	let res = {};

	let add = function(mac, ip) {
		let e = res[mac] ??= { ips4: [], ips6: [] };
		let l = (index(ip, ':') >= 0) ? e.ips6 : e.ips4;

		if (index(l, ip) < 0 && !match(ip, /^fe80:/))
			push(l, ip);
	};

	for (let n in sysinfo.neighbors())
		if (n.state != 'FAILED' && n.state != 'INCOMPLETE')
			add(n.mac, n.ip);

	for (let mac, l in sysinfo.leases()) {
		if (l.ip)
			add(mac, l.ip);

		for (let ip6 in l.ipv6)
			add(mac, ip6);
	}

	return res;
}

function fq_codel_available() {
	if (index(sys.readfile('/proc/sys/net/core/default_qdisc') ?? '', 'fq_codel') >= 0)
		return true;

	return sys.exists('/sys/module/sch_fq_codel') || length(sys.glob('/lib/modules/*/sch_fq_codel.ko')) > 0;
}

export function gather(cfg, now) {
	let s = cfg.settings;
	let caps = sysinfo.capabilities();
	let want_safesearch = false, want_limits = false;

	for (let gid, g in cfg.groups)
		if (g.safesearch && length(config.group_members(cfg, gid)))
			want_safesearch = true;

	for (let mac, d in cfg.devices)
		if (d.dl_limit_kbps > 0 || d.ul_limit_kbps > 0 || cfg.groups[d.group]?.dl_limit_kbps > 0 || cfg.groups[d.group]?.ul_limit_kbps > 0)
			want_limits = true;

	return {
		lan: sysinfo.lan_devices(s),
		caps: caps,
		leaf: fq_codel_available() ? 'fq_codel' : null,
		quota: read_json(QUOTA_STATE, {}),
		safesearch_ips: want_safesearch ? dns.safesearch_ips(now) : {},
		local_domain: dns.local_domain(),
		dnsmasq_user: dns.dnsmasq_user(),
		addrs: (want_limits && !(caps.tc && s.tc_enabled && caps.ifb)) ? device_addresses() : {}
	};
};

// ---------------------------------------------------------------------------
// System side
// ---------------------------------------------------------------------------

function nft_run(script) {
	let path = `${RUN_DIR}/apply.nft`;

	sys.writefile(path, script);

	let r = sys.exec(`nft -f ${path}`, true);

	return { ok: r.code == 0, output: trim(r.stdout) };
}

// Our tables and the sets in the policy table (terse: no elements).
export function nft_objects() {
	let res = { tables: [], sets: [] };
	let r = sys.exec('nft -j list tables');

	if (r.code != 0)
		return res;

	try {
		for (let o in json(r.stdout).nftables ?? [])
			if (o.table?.family == 'inet' && (o.table.name == nftgen.TABLE || o.table.name == nftgen.ACCT_TABLE))
				push(res.tables, o.table.name);
	}
	catch (e) { }

	if (index(res.tables, nftgen.TABLE) < 0)
		return res;

	r = sys.exec(`nft -j -t list table inet ${nftgen.TABLE}`);

	try {
		for (let o in json(r.stdout).nftables ?? [])
			if (o.set?.name)
				push(res.sets, o.set.name);
	}
	catch (e) { }

	return res;
};

function apply_nft(p) {
	let text = nftgen.generate(p.nft);
	let existing = nft_objects();
	let stale = nftgen.stale_cleanup(existing.sets, p.nft);
	let r = nft_run(text + join('\n', stale) + '\n');

	if (!r.ok && length(existing.tables)) {
		// set definitions changed (e.g. after an upgrade): rebuild from scratch,
		// losing only traffic counters and learned blocklist addresses
		log(`nft incremental load failed, recreating tables: ${r.output}`);
		r = nft_run(join('\n', nftgen.teardown(existing.tables)) + '\n' + text);
	}

	if (r.ok) {
		sys.mkdir_p(RUN_DIR);
		sys.writefile_atomic(NFT_INCLUDE, text);
	}

	return r;
}

function tc_cmd() {
	return sys.exists('/sbin/tc') ? '/sbin/tc' : '/usr/sbin/tc';
}

function ip_cmd() {
	for (let p in [ '/sbin/ip', '/usr/sbin/ip', '/bin/ip' ])
		if (sys.exists(p))
			return p;

	return 'ip';
}

function tc_batch(text, force) {
	if (text == '')
		return { ok: true, output: '' };

	let path = `${RUN_DIR}/tc.batch`;

	sys.writefile(path, text);

	let r = sys.exec(`${tc_cmd()} ${force ? '-force ' : ''}-batch ${path}`, true);

	return { ok: r.code == 0, output: trim(r.stdout) };
}

function our_qdisc_present(dev) {
	let r = sys.exec(`${tc_cmd()} qdisc show dev ${shellquote(dev)}`);

	return match(r.stdout, /qdisc htb 1: root.*default 0xffff/) != null;
}

function tc_teardown(state) {
	if (!length(state.lan ?? []))
		return;

	tc_batch(tcgen.teardown(state.lan).batch, true);

	for (let l in state.links ?? [])
		sys.exec(`${ip_cmd()} link del ${shellquote(l)}`);
}

function apply_tc(p, env, settings) {
	let state = read_json(TC_STATE, {});
	let shaping = env.caps.tc && settings.tc_enabled;
	let model = { ...p.tc, ifb: p.tc.ifb && !state.ifb_failed };
	let gen = shaping ? tcgen.generate(model) : { active: false, links: [], batch: '' };
	let sig = gen.batch;

	let present = true;

	for (let dev in env.lan)
		if (gen.active && length(gen.batch) && index(gen.batch, `dev ${dev} root`) >= 0 && !our_qdisc_present(dev))
			present = false;

	if (state.sig == sig && present)
		return { ok: true, changed: false };

	tc_teardown(state);

	let new_state = { sig: sig, lan: [], links: [], ifb_ok: state.ifb_ok, ifb_failed: state.ifb_failed };

	if (!gen.active) {
		write_json(TC_STATE, new_state);

		return { ok: true, changed: true };
	}

	for (let l in gen.links) {
		sys.exec(`${ip_cmd()} link del ${shellquote(l)}`);

		let r = sys.exec(`${ip_cmd()} link add ${shellquote(l)} type ifb && ${ip_cmd()} link set ${shellquote(l)} up`, true);

		if (r.code != 0) {
			log(`cannot create ifb device ${l}: ${trim(r.stdout)}, upload limits fall back to policing`);
			write_json(TC_STATE, { ...new_state, sig: null, ifb_failed: true });

			return { ok: false, retry_without_ifb: true };
		}
	}

	let r = tc_batch(gen.batch, false);

	new_state.lan = env.lan;
	new_state.links = gen.links;

	if (length(gen.links))
		new_state.ifb_ok = true;

	if (!r.ok) {
		log(`tc setup failed: ${r.output}`);
		tc_teardown(new_state);
		new_state = { ...new_state, sig: null, lan: [], links: [] };
	}

	write_json(TC_STATE, new_state);

	return { ok: r.ok, changed: true, output: r.output };
}

function reload_service() {
	if (sys.exists('/etc/init.d/wrtpilotd'))
		sys.run([ '/etc/init.d/wrtpilotd', 'reload' ]);
}

function lock() {
	sys.mkdir_p(RUN_DIR);

	let fd = fs.open(`${RUN_DIR}/apply.lock`, 'w');

	fd?.lock('x');

	return fd;
}

function unlock(fd) {
	if (fd) {
		fd.lock('u');
		fd.close();
	}
}

function do_apply(opts) {
	let now = sys.time();
	let cfg = config.load();

	if (!cfg.settings.enabled)
		return do_reset({ keep_wifi_state: false, boot: opts.boot });

	let env = gather(cfg, now);
	let p = plan(cfg, env, now);
	let res = { ok: true, warnings: p.warnings };

	let r = apply_nft(p);

	if (!r.ok) {
		log(`nft load failed: ${r.output}`);
		res.ok = false;
		res.error = 'nft_failed';
		res.message = r.output;
	}

	let t = apply_tc(p, env, cfg.settings);

	if (t.retry_without_ifb) {
		// ifb unavailable: re-plan so uploads are policed by nftables instead
		env.caps.ifb = false;
		env.addrs = device_addresses();
		p = plan(cfg, env, now);
		res.warnings = p.warnings;
		apply_nft(p);
		t = apply_tc(p, env, cfg.settings);
	}

	if (!t.ok) {
		push(res.warnings, 'tc_failed');
	}

	if (dns.write_confs(p.dns) && !opts.boot)
		reload_service();

	wifi.sync(p.wifi, now);

	write_json(`${RUN_DIR}/apply.json`, { ts: now, ok: res.ok, warnings: res.warnings, error: res.error });

	return res;
}

function do_reset(opts) {
	let objs = nft_objects();

	if (length(objs.tables))
		nft_run(join('\n', nftgen.teardown(objs.tables)) + '\n');

	sys.unlink(NFT_INCLUDE);

	let state = read_json(TC_STATE, {});

	tc_teardown(state);

	for (let l in sys.glob('/sys/class/net/wp-ifb*'))
		sys.exec(`${ip_cmd()} link del ${shellquote(fs.basename(l))}`);

	write_json(TC_STATE, { ifb_ok: state.ifb_ok, ifb_failed: state.ifb_failed });

	if (dns.write_confs({}) && !opts.boot)
		reload_service();

	wifi.sync({}, sys.time());

	if (opts.disable) {
		let c = config.cursor();

		c.load(config.CONFIG);
		c.set(config.CONFIG, 'main', 'enabled', '0');
		c.commit(config.CONFIG);
	}

	return { ok: true, warnings: [] };
}

export function apply(opts) {
	opts ??= {};

	let fd = lock();
	let res;

	try {
		res = do_apply(opts);
	}
	catch (e) {
		log(`apply failed: ${e.message}`);
		res = { ok: false, error: 'apply_failed', message: e.message, warnings: [] };
	}

	unlock(fd);

	return res;
};

// Remove every rule, shaper, DNS instance and Wi-Fi ban WrtPilot installed.
export function reset(opts) {
	opts ??= {};

	let fd = lock();
	let res;

	try {
		res = do_reset(opts);
	}
	catch (e) {
		res = { ok: false, error: 'reset_failed', message: e.message };
	}

	unlock(fd);

	return res;
};

// Last apply result (for status reporting).
export function last_result() {
	return read_json(`${RUN_DIR}/apply.json`, null);
};
