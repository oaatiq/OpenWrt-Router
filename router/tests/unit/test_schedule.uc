// Schedules: parsing, windows across DST, midnight crossing.
'use strict';

import { suite, eq, ok, done } from 'lib';
import * as s from 'wrtpilot.schedule';

suite('schedule');

eq(s.parse_rule('mon-fri 07:00-08:00'), { days: [ 1, 2, 3, 4, 5 ], start: 420, end: 480 });
eq(s.parse_rule('SAT-SUN 09:00-22:00'), { days: [ 0, 6 ], start: 540, end: 1320 }, 'case insensitive');
eq(s.parse_rule('fri-mon 10:00-11:00').days, [ 0, 1, 5, 6 ], 'wrapping day range');
eq(s.parse_rule('mon,wed,fri 10:00-11:00').days, [ 1, 3, 5 ], 'day list');
eq(s.parse_rule('weekend 10:00-11:00').days, [ 0, 6 ]);
eq(s.parse_rule('daily 00:00-24:00'), { days: [ 0, 1, 2, 3, 4, 5, 6 ], start: 0, end: 1440 });
eq(s.parse_rule('fri 20:00-01:00'), { days: [ 5 ], start: 1200, end: 1500 }, 'crosses midnight');

for (let bad in [ 'mon 25:00-26:00', 'xyz 10:00-11:00', 'mon 10:00', 'mon 10:60-11:00', '', 'mon-fri', 'mon 24:00-01:00', null ])
	eq(s.parse_rule(bad), null, `rejects "${bad}"`);

eq(s.format_rule(s.parse_rule('mon-fri 7:00-8:30')), 'mon,tue,wed,thu,fri 07:00-08:30');
eq(s.format_rule(s.parse_rule('fri 20:00-01:00')), 'fri 20:00-01:00');
eq(s.format_rule(s.parse_rule('sun 23:00-24:00')), 'sun 23:00-24:00');

// 2024-10-26 Saturday 01:46 CEST; DST ends Sunday 2024-10-27 03:00
let from = 1729900000;
let rules = map([ 'sat-sun 09:00-22:00', 'fri 20:00-01:00' ], s.parse_rule);
let w = s.windows(rules, from, 2);

// Friday 20:00 CEST -> Saturday 01:00 CEST
eq(w[0], [ 1729879200, 1729897200 ], 'friday window (previous day included)');
// Saturday 09:00-22:00 CEST
eq(w[1], [ 1729926000, 1729972800 ], 'saturday window');
// Sunday 09:00-22:00 CET (after the DST change, offset +1)
eq(w[2], [ 1730016000, 1730062800 ], 'sunday window after DST end');
eq(w[2][1] - w[2][0], 13 * 3600, 'window keeps wall clock length');

ok(!s.allowed_at(w, from), '01:46 saturday is outside');
ok(s.allowed_at(w, 1729926000 + 60), '09:01 saturday is inside');
eq(s.next_change(w, from), 1729926000, 'next change = saturday 09:00');
eq(s.next_change(w, 1729926000 + 60), 1729972800, 'next change = saturday 22:00');

// overlapping rules are merged
let m = s.windows(map([ 'mon 10:00-12:00', 'mon 11:00-13:00', 'mon 13:00-14:00' ], s.parse_rule), 1730070000, 1);

eq(length(m), 1, 'merged into one window');
eq(m[0][1] - m[0][0], 4 * 3600, 'merged window spans 10:00-14:00');

done();
