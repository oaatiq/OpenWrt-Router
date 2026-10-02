// WrtPilot - "Wi-Fi deny" blocking.
//
// Two layers, so the client is dropped immediately AND stays out after a
// Wi-Fi restart or reboot:
//  * persistent: the MAC is added to the hostapd ACL in /etc/config/wireless
//    (macfilter/maclist). The change is committed but Wi-Fi is NOT reloaded,
//    other clients are not disturbed; it takes effect on the next restart.
//  * runtime: hostapd's ubus del_client deauthenticates the station and bans
//    it (ban_time) so it cannot re-associate.
// Every change made to /etc/config/wireless is recorded in the "wifi" state
// section of /etc/config/wrtpilot so unblocking restores the exact previous
// state, even with a user-maintained allow list.

'use strict';

import { ubus_call, normalize_mac, MAX_BAN_MS } from 'wrtpilot.util';
import { cursor, CONFIG } from 'wrtpilot.config';
import { hostapd_objects } from 'wrtpilot.sysinfo';

const DEAUTH_REASON = 5; // "disassociated because AP is unable to handle all associated stations"

function list(v) {
	return v == null ? [] : (type(v) == 'array' ? v : split(v, /\s+/));
}

function ap_sections(c) {
	let res = [];

	c.foreach('wireless', 'wifi-iface', function(s) {
		if ((s.mode ?? 'ap') == 'ap')
			push(res, s['.name']);
	});

	return res;
}

// desired: { mac: until }  (until 0 = indefinite)
// Returns { changed_wireless, banned: [macs], unbanned: [macs] }
export function sync(desired, now) {
	let c = cursor();

	c.load('wireless');
	c.load(CONFIG);

	if (c.get(CONFIG, 'wifi') != 'state')
		c.set(CONFIG, 'wifi', 'state');

	let entries = list(c.get(CONFIG, 'wifi', 'managed'));
	let mf_set = list(c.get(CONFIG, 'wifi', 'macfilter_set'));
	let aps = ap_sections(c);
	let keep = [], reverted = {};
	let changed = false;

	let maclist = (sid) => map(list(c.get('wireless', sid, 'maclist')), m => normalize_mac(m) ?? m);
	let set_maclist = function(sid, l) {
		if (length(l))
			c.set('wireless', sid, 'maclist', l);
		else
			c.delete('wireless', sid, 'maclist');

		changed = true;
	};

	// 1. undo entries that are no longer wanted
	for (let e in entries) {
		let p = split(e, '|');
		let sid = p[0], mac = p[1], op = p[2];

		if (desired[mac] != null && index(aps, sid) >= 0) {
			push(keep, e);
			continue;
		}

		reverted[mac] = true;

		if (index(aps, sid) < 0)
			continue;

		let l = maclist(sid);

		if (op == '+')
			set_maclist(sid, filter(l, m => m != mac));
		else if (op == '-' && c.get('wireless', sid, 'macfilter') == 'allow' && index(l, mac) < 0)
			set_maclist(sid, [ ...l, mac ]);
	}

	// 2. add wanted entries
	for (let sid in aps) {
		for (let mac, until in desired) {
			let done = false;

			for (let e in keep)
				if (substr(e, 0, length(sid) + length(mac) + 2) == `${sid}|${mac}|`)
					done = true;

			if (done)
				continue;

			let mf = c.get('wireless', sid, 'macfilter');
			let l = maclist(sid);

			if (mf == 'allow') {
				if (index(l, mac) >= 0) {
					set_maclist(sid, filter(l, m => m != mac));
					push(keep, `${sid}|${mac}|-`);
				}
			}
			else {
				if (mf != 'deny') {
					c.set('wireless', sid, 'macfilter', 'deny');
					push(mf_set, sid);
					changed = true;
				}

				if (index(l, mac) < 0) {
					set_maclist(sid, [ ...l, mac ]);
					push(keep, `${sid}|${mac}|+`);
				}
			}
		}
	}

	// 3. drop the deny filter we enabled once its list is empty again
	let mf_keep = [];

	for (let sid in uniq(mf_set)) {
		if (index(aps, sid) < 0)
			continue;

		if (c.get('wireless', sid, 'macfilter') == 'deny' && !length(maclist(sid))) {
			c.delete('wireless', sid, 'macfilter');
			changed = true;
		}
		else {
			push(mf_keep, sid);
		}
	}

	if (changed)
		c.commit('wireless');

	let set_or_delete = function(opt, v) {
		if (length(v))
			c.set(CONFIG, 'wifi', opt, v);
		else
			c.delete(CONFIG, 'wifi', opt);
	};

	set_or_delete('managed', keep);
	set_or_delete('macfilter_set', mf_keep);
	c.commit(CONFIG);

	// runtime: kick + ban wanted, unban reverted
	let objs = hostapd_objects();

	for (let mac, until in desired) {
		let ms = (until > 0) ? min(MAX_BAN_MS, (until - now) * 1000) : MAX_BAN_MS;

		for (let o in objs)
			ubus_call(o, 'del_client', { addr: mac, reason: DEAUTH_REASON, deauth: true, ban_time: max(1000, ms) });
	}

	for (let mac in keys(reverted)) {
		if (desired[mac] != null)
			continue;

		for (let o in objs)
			ubus_call(o, 'del_client', { addr: mac, reason: DEAUTH_REASON, deauth: false, ban_time: 0 });
	}

	return {
		changed_wireless: changed,
		banned: keys(desired),
		unbanned: filter(keys(reverted), m => desired[m] == null)
	};
};
