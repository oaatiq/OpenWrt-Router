// WrtPilot - shared helpers.
//
// Everything that touches the system (files, processes, ubus) goes through
// the exported `sys` object so unit tests can replace individual functions.

'use strict';

import * as fs from 'fs';

export const VERSION = '0.1.0';
// overridable for tests only
export const RUN_DIR = getenv('WRTPILOT_RUN_DIR') ?? '/var/run/wrtpilot';
export const NFT_INCLUDE = RUN_DIR + '/fw4.nft';
export const MAX_BAN_MS = 2147483647;

export const sys = {
	readfile: function(path) {
		return fs.readfile(path);
	},

	writefile: function(path, data) {
		return fs.writefile(path, data);
	},

	// Write a file atomically (tmp file + rename) so readers never see a
	// half-written state, and a power loss keeps the previous version.
	writefile_atomic: function(path, data) {
		let tmp = path + '.tmp';

		if (fs.writefile(tmp, data) == null)
			return false;

		return fs.rename(tmp, path) != null;
	},

	exists: function(path) {
		return fs.access(path) == true;
	},

	mkdir_p: function(path) {
		let cur = '';

		for (let part in split(path, '/')) {
			if (part == '')
				continue;

			cur += '/' + part;

			if (!fs.access(cur))
				fs.mkdir(cur, 0o755);
		}
	},

	unlink: function(path) {
		return fs.unlink(path);
	},

	glob: function(pattern) {
		return fs.glob(pattern) ?? [];
	},

	// Run a shell command and capture stdout (stderr merged when asked).
	exec: function(cmd, merge_stderr) {
		let p = fs.popen(cmd + (merge_stderr ? ' 2>&1' : ' 2>/dev/null'), 'r');

		if (!p)
			return { code: -1, stdout: '' };

		let out = p.read('all') ?? '';
		let code = p.close();

		return { code: code ?? -1, stdout: out };
	},

	// Run a command given as argv array, returns exit code.
	run: function(argv) {
		return system(argv);
	},

	time: function() {
		return time();
	},

	ubus: null
};

let ubus_conn = null;

// Lazily connect to ubus; reconnects when the previous connection broke.
export function ubus_call(object, method, args) {
	if (sys.ubus)
		return sys.ubus.call(object, method, args);

	for (let attempt = 0; attempt < 2; attempt++) {
		if (!ubus_conn) {
			let ubus = require('ubus');
			ubus_conn = ubus.connect(null, 3);
		}

		if (!ubus_conn)
			return null;

		let rv = ubus_conn.call(object, method, args ?? {});

		if (rv != null)
			return rv;

		// 4 = UBUS_STATUS_NOT_FOUND, anything else may be a dead socket
		if (ubus_conn.error(true) == 4)
			return null;

		ubus_conn = null;
	}

	return null;
};

export function ubus_list(pattern) {
	if (sys.ubus)
		return sys.ubus.list(pattern);

	if (!ubus_conn) {
		let ubus = require('ubus');
		ubus_conn = ubus.connect(null, 3);
	}

	return ubus_conn?.list(pattern) ?? [];
};

export function shellquote(s) {
	return "'" + replace(`${s}`, "'", "'\\''") + "'";
};

export function log(msg) {
	warn(`wrtpilot: ${msg}\n`);
};

// ---------------------------------------------------------------------------
// Validation / normalisation
// ---------------------------------------------------------------------------

export function normalize_mac(s) {
	if (type(s) != 'string')
		return null;

	let m = lc(replace(trim(s), '-', ':'));

	if (!match(m, /^[0-9a-f]{2}(:[0-9a-f]{2}){5}$/))
		return null;

	if (m == '00:00:00:00:00:00' || m == 'ff:ff:ff:ff:ff:ff')
		return null;

	return m;
};

export function mac_section(mac) {
	return 'd_' + replace(mac, ':', '');
};

// Locally administered bit set => randomised / private MAC address.
export function mac_is_random(mac) {
	return (hex(substr(mac, 0, 2)) & 2) != 0;
};

export function is_ipv4(s) {
	return type(s) == 'string' && length(iptoarr(s) ?? []) == 4 && index(s, ':') < 0;
};

export function is_ipv6(s) {
	return type(s) == 'string' && length(iptoarr(s) ?? []) == 16;
};

export function is_ip(s) {
	return is_ipv4(s) || is_ipv6(s);
};

export function valid_group_id(s) {
	return type(s) == 'string' && match(s, /^[a-z0-9_]{1,32}$/) != null;
};

export function valid_domain(s) {
	return type(s) == 'string' && length(s) <= 253 &&
		match(s, /^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9][a-z0-9-]{0,61}[a-z0-9]$/) != null;
};

// Strip control characters and limit the length of user supplied labels.
const CTRL_CHARS = regexp('[\x01-\x1f\x7f]', 'g');

export function clean_label(s, maxlen) {
	if (type(s) != 'string')
		return '';

	s = trim(replace(s, CTRL_CHARS, ''));

	return length(s) > (maxlen ?? 64) ? substr(s, 0, maxlen ?? 64) : s;
};

export function to_int(v, dflt) {
	if (v == null || v == '')
		return dflt;

	let n = int(v);

	return (type(n) == 'int' && `${n}` == trim(`${v}`)) ? n : dflt;
};

// ---------------------------------------------------------------------------
// Time helpers (local time = router time zone)
// ---------------------------------------------------------------------------

export function local_midnight(ts, day_offset) {
	let t = localtime(ts);

	return timelocal({
		year: t.year, mon: t.mon, mday: t.mday + (day_offset ?? 0),
		hour: 0, min: 0, sec: 0, isdst: -1
	});
};

export function day_key(ts) {
	let t = localtime(ts);

	return sprintf('%04d-%02d-%02d', t.year, t.mon, t.mday);
};

export function day_key_to_ts(key) {
	let m = match(key, /^(\d{4})-(\d{2})-(\d{2})$/);

	if (!m)
		return null;

	return timelocal({ year: +m[1], mon: +m[2], mday: +m[3], hour: 0, min: 0, sec: 0, isdst: -1 });
};

// ---------------------------------------------------------------------------
// JSON state files
// ---------------------------------------------------------------------------

export function read_json(path, dflt) {
	let data = sys.readfile(path);

	if (data == null || data == '')
		return dflt;

	try {
		let v = json(data);

		return v ?? dflt;
	}
	catch (e) {
		log(`ignoring corrupt state file ${path}: ${e.message}`);

		return dflt;
	}
};

export function write_json(path, value) {
	return sys.writefile_atomic(path, sprintf('%J', value));
};

export function ok(extra) {
	return { ok: true, ...(extra ?? {}) };
};

export function err(code, message) {
	return { ok: false, error: code, message: message ?? code };
};
