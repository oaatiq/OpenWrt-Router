// WrtPilot - traffic statistics (used by wrtpilotd).
//
// Counters come from the dynamic nft sets in table inet wrtpilot_acct
// (per IP, download = dl4/dl6, upload = ul4/ul6). Each sample is turned into
// per-MAC byte deltas and kept as:
//   * a RAM ring buffer of rates (last 10 minutes at the sample interval)
//   * per-minute totals for the last 24 h in /var/run (RAM disk)
//   * per-day totals, persisted to flash at most once per hour
// Long-term history lives in the app, which pulls minute/day data.

'use strict';

import * as fs from 'fs';
import { sys, RUN_DIR, read_json, write_json, day_key, day_key_to_ts, log } from 'wrtpilot.util';

export const MIN_DIR = RUN_DIR + '/min';
const RING_SECONDS = 600;
const MAX_EVENTS = 200;
const IPMAP_TTL = 3600;

// nft -j list table inet wrtpilot_acct -> { dl: {ip: bytes}, ul: {ip: bytes} }
export function parse_acct(text) {
	let res = { dl: {}, ul: {} };
	let data;

	try {
		data = json(text);
	}
	catch (e) {
		return null;
	}

	for (let o in data?.nftables ?? []) {
		let set = o.set;

		if (!set || !set.name)
			continue;

		let dir = (set.name == 'dl4' || set.name == 'dl6') ? 'dl' :
			((set.name == 'ul4' || set.name == 'ul6') ? 'ul' : null);

		if (!dir)
			continue;

		for (let e in set.elem ?? []) {
			if (type(e) != 'object' || !e.elem)
				continue;

			let ip = e.elem.val, bytes = e.elem.counter?.bytes;

			if (type(ip) == 'string' && type(bytes) == 'int')
				res[dir][ip] = bytes;
		}
	}

	return res;
};

export function create(opts) {
	let interval = opts?.interval ?? 2;
	let size = max(10, RING_SECONDS / interval);

	return {
		interval: interval,
		size: size,
		pos: 0,               // next ring slot
		count: 0,             // filled slots
		ring_ts: [],
		ring: {},             // mac -> { rx: [], tx: [] } bits per second
		rates: {},            // mac -> [rx_bps, tx_bps]
		prev: { dl: {}, ul: {} },
		prev_time: null,
		ipmap: {},            // ip -> [mac, last_seen]
		minute: null,
		minute_acc: {},       // mac -> [rx, tx] bytes
		days: {},             // 'YYYY-MM-DD' -> { mac: [rx, tx] }
		devs: {},             // mac -> { first_seen, last_seen, ip, hostname, conn }
		known: {},            // mac -> true (for new device detection)
		seeded: false,
		events: [],
		last_event_id: 0,
		quota_notified: {},   // day -> { mac: true }
		dirty: false
	};
};

// ---------------------------------------------------------------------------
// persistence
// ---------------------------------------------------------------------------

export function prune_days(st, now, history_days) {
	let cutoff = day_key(now - history_days * 86400);

	for (let k in keys(st.days))
		if (k < cutoff)
			delete st.days[k];

	for (let k in keys(st.quota_notified))
		if (k != day_key(now))
			delete st.quota_notified[k];
};

export function load(st, path, history_days) {
	let data = read_json(`${path}/stats.json`, null);

	if (data) {
		st.days = data.days ?? {};
		st.devs = data.devs ?? {};
		st.known = data.known ?? {};
		st.quota_notified = data.quota_notified ?? {};
		st.seeded = true;
	}

	let ev = read_json(`${path}/events.json`, null);

	if (ev) {
		st.events = ev.events ?? [];
		st.last_event_id = ev.last_id ?? 0;
	}

	prune_days(st, sys.time(), history_days);
};

export function save(st, path) {
	sys.mkdir_p(path);

	let ok = write_json(`${path}/stats.json`, {
		version: 1,
		saved: sys.time(),
		days: st.days,
		devs: st.devs,
		known: st.known,
		quota_notified: st.quota_notified
	});

	if (ok)
		st.dirty = false;

	return ok;
};

function save_events(st, path) {
	sys.mkdir_p(path);
	write_json(`${path}/events.json`, { last_id: st.last_event_id, events: st.events });
}

// ---------------------------------------------------------------------------
// events
// ---------------------------------------------------------------------------

export function add_event(st, path, ev_type, mac, data, now) {
	let ev = { id: ++st.last_event_id, ts: now, type: ev_type, mac: mac, data: data ?? {} };

	push(st.events, ev);

	if (length(st.events) > MAX_EVENTS)
		st.events = slice(st.events, length(st.events) - MAX_EVENTS);

	if (path)
		save_events(st, path);

	return ev;
};

export function events_since(st, since_id) {
	// ids restart after a factory reset; let the app resync
	let reset = since_id > st.last_event_id;

	return {
		last_id: st.last_event_id,
		reset: reset,
		events: filter(st.events, e => reset || e.id > since_id)
	};
};

// ---------------------------------------------------------------------------
// presence
// ---------------------------------------------------------------------------

// seen: { mac: { ip, hostname, conn, active } }  (active = definitely online now)
// Returns list of newly discovered MACs.
export function update_presence(st, seen, now) {
	let fresh = [];

	for (let mac, info in seen) {
		let d = st.devs[mac];

		if (!d) {
			d = st.devs[mac] = { first_seen: now, last_seen: 0 };
			st.dirty = true;
		}

		if (info.active)
			d.last_seen = now;

		if (info.ip)
			d.ip = info.ip;

		if (info.hostname)
			d.hostname = info.hostname;

		if (info.conn)
			d.conn = info.conn;

		if (!st.known[mac]) {
			st.known[mac] = true;
			st.dirty = true;

			if (st.seeded)
				push(fresh, mac);
		}
	}

	st.seeded = true;

	return fresh;
};

export function learn_ips(st, pairs, now) {
	for (let p in pairs)
		st.ipmap[p[0]] = [ p[1], now ];

	for (let ip, e in st.ipmap)
		if (now - e[1] > IPMAP_TTL)
			delete st.ipmap[ip];
};

// ---------------------------------------------------------------------------
// sampling
// ---------------------------------------------------------------------------

function ring_entry(st, mac) {
	let r = st.ring[mac];

	if (!r) {
		r = st.ring[mac] = { rx: [], tx: [] };

		for (let i = 0; i < st.size; i++) {
			r.rx[i] = 0;
			r.tx[i] = 0;
		}
	}

	return r;
}

// Write the finished minute to the hourly file; returns the totals written.
export function flush_minute(st, new_minute) {
	if (st.minute == null || st.minute == new_minute)
		return null;

	let m = st.minute;
	let lines = [];

	for (let mac, a in st.minute_acc)
		if (a[0] || a[1])
			push(lines, `${m}\t${mac}\t${a[0]}\t${a[1]}\n`);

	if (length(lines)) {
		sys.mkdir_p(MIN_DIR);

		let fd = fs.open(`${MIN_DIR}/${m - m % 3600}.tsv`, 'a');

		if (fd) {
			fd.write(join('', lines));
			fd.close();
		}
	}

	let written = st.minute_acc;

	st.minute_acc = {};
	st.minute = new_minute;

	return written;
};

// acct: parse_acct() result; now: epoch seconds; mono: monotonic seconds (float)
// Returns { unmapped: [ips] } so the caller can refresh its neighbour cache.
export function sample(st, acct, now, mono) {
	let deltas = {};
	let unmapped = [];

	for (let dir in [ 'dl', 'ul' ]) {
		let cur = acct[dir], prev = st.prev[dir];

		for (let ip, bytes in cur) {
			let p = prev[ip];
			let delta = (p == null) ? 0 : ((bytes >= p) ? bytes - p : bytes);

			if (delta <= 0)
				continue;

			let m = st.ipmap[ip];

			if (!m) {
				push(unmapped, ip);
				continue;
			}

			let d = deltas[m[0]] ??= [ 0, 0 ];

			d[dir == 'dl' ? 0 : 1] += delta;
		}
	}

	let dt = (st.prev_time != null) ? (mono - st.prev_time) : st.interval;

	st.prev = acct;
	st.prev_time = mono;

	if (dt <= 0)
		dt = st.interval;

	// rates
	let slot = st.pos;

	st.ring_ts[slot] = now;
	st.rates = {};

	for (let mac, r in st.ring) {
		r.rx[slot] = 0;
		r.tx[slot] = 0;
	}

	let minute = now - now % 60;
	let minute_changed = false;

	if (st.minute == null)
		st.minute = minute;
	else if (minute != st.minute) {
		flush_minute(st, minute);
		minute_changed = true;
	}

	let today = day_key(now);
	let day = st.days[today] ??= {};

	for (let mac, d in deltas) {
		let rx = int(d[0] * 8 / dt), tx = int(d[1] * 8 / dt);
		let r = ring_entry(st, mac);

		r.rx[slot] = rx;
		r.tx[slot] = tx;
		st.rates[mac] = [ rx, tx ];

		let a = st.minute_acc[mac] ??= [ 0, 0 ];

		a[0] += d[0];
		a[1] += d[1];

		let t = day[mac] ??= [ 0, 0 ];

		t[0] += d[0];
		t[1] += d[1];

		let dev = st.devs[mac] ??= { first_seen: now, last_seen: now };

		dev.last_seen = now;
		st.dirty = true;
	}

	st.pos = (st.pos + 1) % st.size;
	st.count = min(st.count + 1, st.size);

	return { unmapped: uniq(unmapped), minute: minute, minute_changed: minute_changed };
};

// Drop ring buffers of devices idle for a whole ring period.
export function gc_rings(st) {
	for (let mac, r in st.ring) {
		let busy = false;

		for (let i = 0; i < st.size && !busy; i++)
			if (r.rx[i] || r.tx[i])
				busy = true;

		if (!busy)
			delete st.ring[mac];
	}
};

export function prune_minutes(now) {
	for (let path in sys.glob(`${MIN_DIR}/*.tsv`)) {
		let hour = +replace(fs.basename(path), '.tsv', '');

		if (type(hour) == 'int' && hour < now - 25 * 3600)
			sys.unlink(path);
	}
};

// ---------------------------------------------------------------------------
// queries
// ---------------------------------------------------------------------------

export function snapshot(st, now) {
	let today = st.days[day_key(now)] ?? {};
	let out = {};

	for (let mac, d in st.devs) {
		let r = st.rates[mac] ?? [ 0, 0 ];
		let t = today[mac] ?? [ 0, 0 ];

		out[mac] = {
			rx_bps: r[0], tx_bps: r[1],
			today_rx: t[0], today_tx: t[1],
			first_seen: d.first_seen ?? 0,
			last_seen: d.last_seen ?? 0,
			ip: d.ip ?? '',
			hostname: d.hostname ?? '',
			conn: d.conn ?? 'unknown'
		};
	}

	return { time: now, devices: out };
};

// devices == false: totals only (dashboard)
export function live(st, macs, samples, devices) {
	let n = min(samples ?? st.count, st.count);
	let idx = [];

	for (let i = n; i > 0; i--)
		push(idx, (st.pos - i + st.size * 2) % st.size);

	let total_rx = map(idx, i => 0), total_tx = map(idx, i => 0);
	let per_device = {};
	let want = length(macs ?? []) ? macs : null;

	for (let mac, r in st.ring) {
		let rx = map(idx, i => r.rx[i]), tx = map(idx, i => r.tx[i]);

		for (let k = 0; k < length(idx); k++) {
			total_rx[k] += rx[k];
			total_tx[k] += tx[k];
		}

		if (devices !== false && (!want || index(want, mac) >= 0))
			per_device[mac] = { rx: rx, tx: tx };
	}

	if (want && devices !== false)
		for (let mac in want)
			per_device[mac] ??= { rx: map(idx, i => 0), tx: map(idx, i => 0) };

	return {
		interval: st.interval,
		ts: map(idx, i => st.ring_ts[i]),
		total: { rx: total_rx, tx: total_tx },
		devices: per_device
	};
};

// resolution: minute | hour | day
// mac '' = all devices summed, '*' = one series per device ({ devices: {...} })
export function history(st, mac, resolution, since, now) {
	let per_device = (mac == '*');
	let buckets = {};

	let add = function(ts, rx, tx, m) {
		let tbl = per_device ? (buckets[m] ??= {}) : buckets;
		let b = tbl[ts] ??= [ 0, 0 ];

		b[0] += rx;
		b[1] += tx;
	};

	let wanted = (m) => (mac == '' || per_device || m == mac);

	if (resolution == 'day') {
		for (let k, day in st.days) {
			let ts = day_key_to_ts(k);

			if (ts == null || ts + 86400 <= since)
				continue;

			for (let m, v in day)
				if (wanted(m))
					add(ts, v[0], v[1], m);
		}
	}
	else {
		let step = (resolution == 'hour') ? 3600 : 60;
		let from = max(since, now - 86400);

		for (let path in sys.glob(`${MIN_DIR}/*.tsv`)) {
			let hour = +replace(fs.basename(path), '.tsv', '');

			if (type(hour) != 'int' || hour + 3600 <= from)
				continue;

			for (let line in split(sys.readfile(path) ?? '', '\n')) {
				let f = split(line, '\t');

				if (length(f) != 4 || !wanted(f[1]))
					continue;

				let ts = +f[0];

				if (ts < from - (from % step))
					continue;

				add(ts - ts % step, +f[2], +f[3], f[1]);
			}
		}

		// include the minute in progress
		if (st.minute != null)
			for (let m, a in st.minute_acc)
				if (wanted(m))
					add(st.minute - st.minute % step, a[0], a[1], m);
	}

	let to_series = function(tbl) {
		let series = [];

		for (let ts in sort(map(keys(tbl), k => +k), (a, b) => a - b))
			push(series, [ ts, tbl[ts][0], tbl[ts][1] ]);

		return series;
	};

	if (per_device) {
		let devices = {};

		for (let m, tbl in buckets)
			devices[m] = to_series(tbl);

		return { resolution: resolution, mac: mac, devices: devices };
	}

	return { resolution: resolution, mac: mac, series: to_series(buckets) };
};

export function today_usage(st, mac, now) {
	// bytes in the minute in progress are already part of the day totals
	let t = st.days[day_key(now)]?.[mac] ?? [ 0, 0 ];

	return t[0] + t[1];
};

export function forget(st, mac) {
	delete st.devs[mac];
	delete st.ring[mac];
	delete st.rates[mac];

	for (let k, day in st.days)
		delete day[mac];

	st.dirty = true;
};
