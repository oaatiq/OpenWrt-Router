// WrtPilot - weekly schedules ("allowed internet hours").
//
// A schedule rule looks like "mon-fri 17:00-21:00". Day tokens: mon..sun,
// ranges ("fri-mon" wraps), comma lists, or daily/weekdays/weekend.
// A window whose end is not after its start runs past midnight
// ("fri 20:00-01:00" allows Friday 20:00 until Saturday 01:00).
//
// Rules are turned into absolute [start, end) epoch windows for a rolling
// horizon. Absolute times keep the nftables match independent of the
// kernel time zone setting and handle DST transitions exactly.

'use strict';

const DAYS = { sun: 0, mon: 1, tue: 2, wed: 3, thu: 4, fri: 5, sat: 6 };
const DAY_NAMES = [ 'sun', 'mon', 'tue', 'wed', 'thu', 'fri', 'sat' ];

function parse_time(s) {
	let m = match(s, /^([0-9]{1,2}):([0-9]{2})$/);

	if (!m)
		return null;

	let h = +m[1], min = +m[2];

	if (min > 59 || h > 24 || (h == 24 && min != 0))
		return null;

	return h * 60 + min;
}

function parse_days(s) {
	switch (s) {
	case 'daily':
	case 'everyday':
	case 'all':
		return [ 0, 1, 2, 3, 4, 5, 6 ];

	case 'weekdays':
		return [ 1, 2, 3, 4, 5 ];

	case 'weekend':
		return [ 0, 6 ];
	}

	let days = [];

	for (let part in split(s, ',')) {
		let r = split(part, '-');

		if (length(r) == 1) {
			if (!exists(DAYS, r[0]))
				return null;

			push(days, DAYS[r[0]]);
		}
		else if (length(r) == 2) {
			if (!exists(DAYS, r[0]) || !exists(DAYS, r[1]))
				return null;

			for (let d = DAYS[r[0]]; ; d = (d + 1) % 7) {
				push(days, d);

				if (d == DAYS[r[1]])
					break;
			}
		}
		else {
			return null;
		}
	}

	return sort(uniq(days), (a, b) => a - b);
}

// Parse one rule string, returns { days: [...], start: min, end: min } or null.
// `end` may exceed 1440 when the window crosses midnight.
export function parse_rule(s) {
	if (type(s) != 'string')
		return null;

	let parts = split(trim(lc(s)), /\s+/);

	if (length(parts) != 2)
		return null;

	let days = parse_days(parts[0]);
	let times = split(parts[1], '-');

	if (!days || length(days) == 0 || length(times) != 2)
		return null;

	let start = parse_time(times[0]), end = parse_time(times[1]);

	if (start == null || end == null || start >= 1440)
		return null;

	if (end <= start)
		end += 1440;

	return { days: days, start: start, end: end };
};

// Canonical string form of a parsed rule (used to normalise user input).
export function format_rule(r) {
	let days = join(',', map(r.days, d => DAY_NAMES[d]));
	let end = r.end > 1440 ? r.end - 1440 : r.end;

	return sprintf('%s %02d:%02d-%02d:%02d', days,
		r.start / 60, r.start % 60, end / 60, end % 60);
};

// Compute merged absolute windows covering [from - 1 day, from + horizon days].
export function windows(rules, from, horizon_days) {
	let out = [];
	let t0 = localtime(from);

	for (let d = -1; d <= horizon_days; d++) {
		let day = localtime(timelocal({
			year: t0.year, mon: t0.mon, mday: t0.mday + d,
			hour: 12, min: 0, sec: 0, isdst: -1
		}));

		// ucode reports ISO weekdays (1..7, Sunday = 7)
		let wday = day.wday % 7;

		for (let r in rules) {
			if (index(r.days, wday) < 0)
				continue;

			let base = { year: day.year, mon: day.mon, mday: day.mday, hour: 0, sec: 0, isdst: -1 };

			push(out, [
				timelocal({ ...base, min: r.start }),
				timelocal({ ...base, min: r.end })
			]);
		}
	}

	out = sort(out, (a, b) => a[0] - b[0]);

	let merged = [];

	for (let w in out) {
		let last = merged[-1];

		if (last && w[0] <= last[1]) {
			if (w[1] > last[1])
				last[1] = w[1];
		}
		else {
			push(merged, [ w[0], w[1] ]);
		}
	}

	return merged;
};

export function allowed_at(wins, ts) {
	for (let w in wins)
		if (ts >= w[0] && ts < w[1])
			return true;

	return false;
};

// Next transition time (allowed <-> blocked) after ts, or null.
export function next_change(wins, ts) {
	for (let w in wins) {
		if (ts < w[0])
			return w[0];

		if (ts < w[1])
			return w[1];
	}

	return null;
};
