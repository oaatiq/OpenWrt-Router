// sysinfo parsers and util helpers.
'use strict';

import { suite, eq, ok, done } from 'lib';
import * as si from 'wrtpilot.sysinfo';
import * as u from 'wrtpilot.util';

suite('parsers');

// --- util ---
eq(u.normalize_mac('AA-BB-CC-DD-EE-FF'), 'aa:bb:cc:dd:ee:ff');
eq(u.normalize_mac(' aa:bb:cc:dd:ee:0f '), 'aa:bb:cc:dd:ee:0f');
eq(u.normalize_mac('aa:bb:cc:dd:ee'), null);
eq(u.normalize_mac('00:00:00:00:00:00'), null);
eq(u.normalize_mac('gg:bb:cc:dd:ee:ff'), null);
eq(u.normalize_mac(12), null);
eq(u.mac_section('aa:bb:cc:dd:ee:ff'), 'd_aabbccddeeff');
ok(u.mac_is_random('da:a1:19:00:00:01'), 'locally administered bit');
ok(!u.mac_is_random('00:1a:2b:00:00:01'), 'global MAC');
ok(u.is_ipv4('192.168.1.1') && !u.is_ipv4('192.168.1.256') && !u.is_ipv4('fe80::1'));
ok(u.is_ipv6('fe80::1') && u.is_ipv6('2001:db8::1') && !u.is_ipv6('1.2.3.4'));
ok(u.valid_group_id('kids_2') && !u.valid_group_id('Kids') && !u.valid_group_id('a-b') && !u.valid_group_id(''));
ok(u.valid_domain('tiktok.com') && u.valid_domain('a.b-c.example.org'));
ok(!u.valid_domain('-bad.com') && !u.valid_domain('com') && !u.valid_domain('a..b') && !u.valid_domain('x y.com'));
eq(u.clean_label("  Kid's\x01 phone  "), "Kid's phone");
eq(u.clean_label('هاتف أحمد'), 'هاتف أحمد', 'unicode labels are kept');
eq(u.to_int('42', 0), 42);
eq(u.to_int('4x', 7), 7);
eq(u.to_int('', 3), 3);
eq(u.shellquote("a'b"), "'a'\\''b'");
eq(u.day_key(1729900000), '2024-10-26');
eq(u.local_midnight(1729900000, 1), 1729980000, 'next local midnight on DST day');

// --- leases ---
let leases = si.parse_leases(
	'1729999999 aa:bb:cc:dd:ee:01 192.168.1.10 laptop 01:aa:bb:cc:dd:ee:01\n' +
	'1729999999 AA:BB:CC:DD:EE:02 192.168.1.11 * *\n' +
	'garbage line\n' +
	'1729999999 zz:bb:cc:dd:ee:03 192.168.1.12 bad *\n');

eq(length(leases), 2);
eq(leases[0], { expires: 1729999999, mac: 'aa:bb:cc:dd:ee:01', ip: '192.168.1.10', hostname: 'laptop' });
eq(leases[1].hostname, '', '"*" hostname becomes empty');
eq(leases[1].mac, 'aa:bb:cc:dd:ee:02', 'mac normalised');

// --- /proc/net/arp ---
let arp = si.parse_proc_arp(
	'IP address       HW type     Flags       HW address            Mask     Device\n' +
	'192.168.1.10     0x1         0x2         aa:bb:cc:dd:ee:01     *        br-lan\n' +
	'192.168.1.99     0x1         0x0         00:00:00:00:00:00     *        br-lan\n');

eq(arp, [ { ip: '192.168.1.10', mac: 'aa:bb:cc:dd:ee:01', dev: 'br-lan', state: 'STALE' } ]);

// --- ip neigh ---
let n = si.parse_ip_neigh(
	'192.168.1.10 dev br-lan lladdr aa:bb:cc:dd:ee:01 REACHABLE\n' +
	'fd00::10 dev br-lan lladdr aa:bb:cc:dd:ee:01 router STALE\n' +
	'192.168.1.20 dev br-lan  FAILED\n' +
	'10.0.0.1 dev eth1 lladdr 11:22:33:44:55:66 DELAY\n');

eq(length(n), 3);
eq(n[0], { ip: '192.168.1.10', mac: 'aa:bb:cc:dd:ee:01', dev: 'br-lan', state: 'REACHABLE' });
eq(n[1].state, 'STALE');
eq(n[2].dev, 'eth1');

// --- /proc/net/tcp ---
let tcp = si.parse_proc_tcp(
	'  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n' +
	'   0: 0132A8C0:0050 0B32A8C0:C350 01 00000000:00000000 00:00000000 00000000     0        0 1 1\n' +
	'   1: 0132A8C0:0050 0C32A8C0:C351 06 00000000:00000000 00:00000000 00000000     0        0 1 1\n', false);

eq(tcp, [ { local_ip: '192.168.50.1', local_port: 80, remote_ip: '192.168.50.11', remote_port: 50000 } ], 'only established');

let tcp6 = si.parse_proc_tcp(
	'   0: 0000000000000000FFFF00000132A8C0:01BB 0000000000000000FFFF00000B32A8C0:C350 01 0 0 0 0 0 1\n' +
	'   1: 000050FD000000000000000001000000:01BB 000050FD000000000000000011000000:C350 01 0 0 0 0 0 1\n', true);

eq(tcp6[0].remote_ip, '192.168.50.11', 'v4-mapped address');
eq(tcp6[0].local_port, 443);
eq(tcp6[1].remote_ip, 'fd50::11', 'native IPv6 address');

let tcp_be = si.parse_proc_tcp('   0: C0A83201:0050 C0A8320B:C350 01 0 0 0 0 0 1\n', false, true);

eq(tcp_be[0].remote_ip, '192.168.50.11', 'big endian host');

// --- misc ---
eq(si.duid_to_mac('00030001aabbccddeeff'), 'aa:bb:cc:dd:ee:ff', 'DUID-LL');
eq(si.duid_to_mac('000100012a3b4c5daabbccddeeff'), 'aa:bb:cc:dd:ee:ff', 'DUID-LLT');
eq(si.duid_to_mac('0004aabbccddeeff00112233445566778899'), null, 'DUID-UUID has no MAC');
eq(si.band_from_freq(2437), '2.4G');
eq(si.band_from_freq(5180), '5G');
eq(si.band_from_freq(6115), '6G');
eq(si.band_from_freq(null), 'wifi');
eq(si.parse_listen_ports([ '0.0.0.0:80', '[::]:80', '192.168.1.1:8443', '443' ]), { '80': true, '8443': true, '443': true });

done();
