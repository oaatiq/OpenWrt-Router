#!/usr/bin/env python3
"""WrtPilot mock router: the app's API without an OpenWrt device.

Serves uhttpd-style JSON-RPC on /ubus with an rpcd-like session login and
the full `wrtpilot` object (see docs/API.md), backed by simulated devices
with live traffic, history, schedules, pauses, quotas and events.

    python3 tools/mock-router/mock_router.py            # http://0.0.0.0:8080
    python3 tools/mock-router/mock_router.py --port 8443 --tls cert.pem key.pem

In the app add a router with the address of this machine (the Android
emulator reaches the host as 10.0.2.2), user "wrtpilot", password
"wrtpilot" (--password to change). Standard library only.
"""
import argparse
import json
import random
import re
import secrets
import ssl
import threading
import time
from collections import deque
from datetime import datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

VERSION = '0.1.0'
INTERVAL = 2                    # seconds between live samples
RING = 300                      # live samples kept (10 minutes, like the agent)
SESSION_TIMEOUT = 300
ANON = '0' * 32

DNS_FILTERS = ['off', 'adguard_local', 'cleanbrowsing_family', 'cloudflare_family',
               'adguard_family', 'opendns_family', 'custom']
QOS_PRESETS = ['default', 'gaming', 'streaming']
DAYS = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun']

# ubus status codes / JSON-RPC errors (as returned by uhttpd-mod-ubus)
UBUS_INVALID_ARGUMENT = 2
UBUS_METHOD_NOT_FOUND = 3
UBUS_PERMISSION_DENIED = 6
RPC_ACCESS_DENIED = -32002

# rpcd checks argument types against the method signature
SIGNATURES = {
    'status': {}, 'clients': {}, 'groups': {}, 'qos_get': {}, 'apply': {}, 'version': {},
    'live': {'macs': list, 'samples': int, 'devices': bool},
    'history': {'mac': str, 'resolution': str, 'since': int},
    'events': {'since_id': int},
    'set_device': {'mac': str, 'name': str, 'group': str, 'daily_quota_mb': int, 'quota_action': str},
    'forget_device': {'mac': str},
    'block': {'mac': str, 'mode': str, 'duration_s': int},
    'unblock': {'mac': str},
    'set_limit': {'mac': str, 'group': str, 'dl_kbps': int, 'ul_kbps': int},
    'set_group': {'id': str, 'name': str, 'dns_filter': str, 'dns_custom': list, 'safesearch': bool,
                  'blocklist': list, 'schedule': list, 'schedule_enabled': bool, 'members': list,
                  'dl_kbps': int, 'ul_kbps': int},
    'delete_group': {'id': str},
    'pause': {'group': str, 'mac': str, 'duration_s': int, 'until': str},
    'resume': {'group': str, 'mac': str},
    'qos_set': {'enabled': bool, 'dl_kbps': int, 'ul_kbps': int, 'preset': str},
    'set_offload': {'software': bool, 'hardware': bool},
}

# name, hostname, mac, ip, conn, profile (average Mbit/s down, up), online
DEMO_DEVICES = [
    ('', 'Pixel-8', '3c:28:6d:11:22:01', '192.168.1.120', '5G', (1.5, 0.3), True),
    ('Living room TV', 'LGwebOSTV', 'a8:23:fe:44:55:02', '192.168.1.130', '5G', (12.0, 0.4), True),
    ('', 'MacBook-Pro', 'f0:18:98:66:77:03', '192.168.1.140', '5G', (6.0, 1.5), True),
    ('Kid tablet', 'Galaxy-Tab-A8', 'c4:5d:83:88:99:04', '192.168.1.150', '2.4G', (3.0, 0.2), True),
    ('', 'PS5', '00:d9:d1:aa:bb:05', '192.168.1.160', 'lan', (20.0, 1.0), True),
    ('', 'iPhone', 'da:a1:19:cc:dd:06', '192.168.1.170', '5G', (0.8, 0.2), True),
    ('', 'HP-Printer', '3c:52:82:ee:ff:07', '192.168.1.180', '2.4G', (0.0, 0.0), True),
    ('', 'echo-dot', '68:54:fd:12:34:08', '192.168.1.190', '2.4G', (0.2, 0.05), True),
    ('Desk PC', 'DESKTOP-4F2K', '04:d9:f5:56:78:09', '192.168.1.110', 'lan', (2.5, 0.8), False),
    ('', '', '7e:11:22:33:44:0a', '192.168.1.200', '5G', (0.5, 0.1), False),
]
NEW_DEVICE = ('', 'Galaxy-S23', '12:9b:6e:00:01:0b', '192.168.1.210', '5G', (1.0, 0.2), True)
SELF_MAC = DEMO_DEVICES[0][2]   # "this phone": protected from blocking


def now():
    return int(time.time())


def local_midnight(ts, days_ahead=0):
    d = datetime.fromtimestamp(ts).replace(hour=0, minute=0, second=0, microsecond=0)
    return int((d + timedelta(days=days_ahead)).timestamp())


def norm_mac(s):
    if not isinstance(s, str):
        return None
    s = s.strip().lower().replace('-', ':')
    return s if re.fullmatch(r'([0-9a-f]{2}:){5}[0-9a-f]{2}', s) else None


def ok(**kw):
    return {'ok': True, **kw}


def err(code, message=''):
    return {'ok': False, 'error': code, 'message': message}


# --- schedules ("mon-fri 07:00-21:00", "sat,sun 08:00-22:30") ------------

def parse_rule(rule):
    m = re.fullmatch(r'\s*([a-z,\-]+)\s+(\d{1,2}):(\d{2})-(\d{1,2}):(\d{2})\s*', str(rule).lower())
    if not m:
        return None
    days = set()
    for part in m.group(1).split(','):
        if '-' in part:
            a, b = part.split('-', 1)
            if a not in DAYS or b not in DAYS:
                return None
            i, j = DAYS.index(a), DAYS.index(b)
            days.update(DAYS[(i + k) % 7] for k in range((j - i) % 7 + 1))
        elif part in DAYS:
            days.add(part)
        else:
            return None
    h1, m1, h2, m2 = map(int, m.group(2, 3, 4, 5))
    start, end = h1 * 60 + m1, h2 * 60 + m2
    if h1 > 23 or m1 > 59 or m2 > 59 or end > 1440 or start == end:
        return None
    return {'days': days, 'start': start, 'end': end}


def format_rule(p):
    days = ','.join(d for d in DAYS if d in p['days'])
    return f"{days} {p['start'] // 60:02d}:{p['start'] % 60:02d}-{p['end'] // 60:02d}:{p['end'] % 60:02d}"


def allowed_at(rules, ts):
    t = datetime.fromtimestamp(ts)
    minute = t.hour * 60 + t.minute
    today, yesterday = DAYS[t.weekday()], DAYS[(t.weekday() - 1) % 7]
    for r in rules:
        if r['start'] < r['end']:
            if today in r['days'] and r['start'] <= minute < r['end']:
                return True
        else:   # over midnight
            if (today in r['days'] and minute >= r['start']) or (yesterday in r['days'] and minute < r['end']):
                return True
    return False


def next_change(rules, ts):
    cur = allowed_at(rules, ts)
    t = ts - ts % 60
    for _ in range(7 * 1440):
        t += 60
        if allowed_at(rules, t) != cur:
            return t
    return 0


# --- state -----------------------------------------------------------------

class Router:
    def __init__(self, args):
        self.lock = threading.RLock()
        self.args = args
        self.started = now()
        self.devices = {}
        self.groups = {}
        self.events = []
        self.last_event_id = 0
        self.ring_ts = deque(maxlen=RING)
        self.minutes = {}       # minute ts -> {mac: [rx, tx]}
        self.days = {}          # midnight ts -> {mac: [rx, tx]}
        self.qos = {'enabled': False, 'dl_kbps': 0, 'ul_kbps': 0, 'preset': 'default'}
        self.offload = {'software': args.offload, 'hardware': False}
        self.sessions = {}
        for d in DEMO_DEVICES:
            self.add_device(d, seed=True)
        self.groups['kids'] = {
            'id': 'kids', 'name': 'Kids', 'dns_filter': 'cloudflare_family', 'dns_custom': [],
            'safesearch': True, 'blocklist': ['tiktok.com'], 'schedule_enabled': True,
            'schedule': ['mon,tue,wed,thu,sun 07:00-20:30', 'fri,sat 08:00-22:00'],
            'dl_limit_kbps': 0, 'ul_limit_kbps': 0, 'paused_until': 0,
        }
        self.devices['c4:5d:83:88:99:04']['group'] = 'kids'
        self.seed_history()

    def add_device(self, spec, seed=False):
        name, host, mac, ip, conn, profile, online = spec
        t = now()
        self.devices[mac] = {
            'mac': mac, 'custom_name': name, 'hostname': host, 'ip': ip, 'conn': conn,
            'profile': profile, 'online': online, 'first_seen': t - (random.randint(5, 90) * 86400 if seed else 0),
            'last_seen': t if online else t - random.randint(2, 30) * 3600,
            'signal': random.randint(-75, -45) if conn != 'lan' else 0,
            'group': '', 'blocked': '', 'blocked_until': 0, 'paused_until': 0,
            'dl_limit_kbps': 0, 'ul_limit_kbps': 0, 'daily_quota_mb': 0, 'quota_action': '',
            'quota_blocked_until': 0, 'quota_notified': 0,
            'rx': deque([0] * len(self.ring_ts), maxlen=RING), 'tx': deque([0] * len(self.ring_ts), maxlen=RING),
            'today_rx': 0, 'today_tx': 0,
        }

    def seed_history(self):
        t = now()
        midnight = local_midnight(t)
        for back in range(1, 31):
            day = local_midnight(midnight - back * 86400 + 3600)
            self.days[day] = {}
            for mac, d in self.devices.items():
                dl, ul = d['profile']
                hours = random.uniform(1, 6)
                self.days[day][mac] = [int(dl * 125000 * 3600 * hours), int(ul * 125000 * 3600 * hours)]
        # today so far, per minute
        m = midnight
        while m < t - t % 60:
            hour = datetime.fromtimestamp(m).hour
            busy = 0.2 if hour < 7 else (1.0 if hour >= 18 else 0.5)
            bucket = {}
            for mac, d in self.devices.items():
                if d['online'] and random.random() < busy:
                    dl, ul = d['profile']
                    rx, tx = int(dl * 125000 * 60 * random.uniform(0.2, 1.2)), int(ul * 125000 * 60 * random.uniform(0.2, 1.2))
                    bucket[mac] = [rx, tx]
                    d['today_rx'] += rx
                    d['today_tx'] += tx
            self.minutes[m] = bucket
            m += 60

    # --- derived state ----------------------------------------------------

    def group_paused(self, gid, t):
        g = self.groups.get(gid)
        if not g:
            return 0
        p = g['paused_until']
        return p if p == -1 or p > t else 0

    def schedule_blocked(self, gid, t):
        g = self.groups.get(gid)
        if not g or not g['schedule_enabled'] or not g['schedule']:
            return False
        return not allowed_at([parse_rule(r) for r in g['schedule']], t)

    def restricted(self, d, t):
        if d['blocked'] and (d['blocked_until'] == 0 or d['blocked_until'] > t):
            return True
        if d['paused_until'] == -1 or d['paused_until'] > t:
            return True
        if d['group'] and (self.group_paused(d['group'], t) or self.schedule_blocked(d['group'], t)):
            return True
        return d['quota_blocked_until'] > t

    def event(self, ev_type, mac, data):
        self.last_event_id += 1
        self.events.append({'id': self.last_event_id, 'ts': now(), 'type': ev_type, 'mac': mac, 'data': data})
        self.events = self.events[-200:]

    # --- simulation tick --------------------------------------------------

    def tick(self):
        with self.lock:
            t = now()
            if t - self.started > self.args.new_device_after and NEW_DEVICE[2] not in self.devices:
                self.add_device(NEW_DEVICE)
                self.event('new_device', NEW_DEVICE[2], {'hostname': NEW_DEVICE[1], 'ip': NEW_DEVICE[3]})
            midnight = local_midnight(t)
            if midnight not in self.days:
                self.days[midnight] = {}
                for d in self.devices.values():
                    d['today_rx'] = d['today_tx'] = 0
                    d['quota_notified'] = 0
            minute = t - t % 60
            bucket = self.minutes.setdefault(minute, {})
            self.ring_ts.append(t)
            hour = datetime.fromtimestamp(t).hour
            busy = 0.3 if hour < 7 else (1.0 if hour >= 18 else 0.6)
            for mac, d in self.devices.items():
                if d['online'] and random.random() < 0.002:
                    d['online'] = False
                elif not d['online'] and d['profile'][0] > 0 and random.random() < 0.004:
                    d['online'] = True
                rx = tx = 0
                if d['online'] and not self.restricted(d, t) and random.random() < busy:
                    dl, ul = d['profile']
                    rx = int(dl * 1e6 * random.uniform(0.1, 1.8))
                    tx = int(ul * 1e6 * random.uniform(0.1, 1.8))
                    for g in (d, self.groups.get(d['group'], {})):
                        if g.get('dl_limit_kbps'):
                            rx = min(rx, g['dl_limit_kbps'] * 1000)
                        if g.get('ul_limit_kbps'):
                            tx = min(tx, g['ul_limit_kbps'] * 1000)
                if d['online']:
                    d['last_seen'] = t
                d['rx'].append(rx)
                d['tx'].append(tx)
                brx, btx = rx * INTERVAL // 8, tx * INTERVAL // 8
                d['today_rx'] += brx
                d['today_tx'] += btx
                acc = bucket.setdefault(mac, [0, 0])
                acc[0] += brx
                acc[1] += btx
                day = self.days[midnight].setdefault(mac, [0, 0])
                day[0] += brx
                day[1] += btx
                self.check_quota(mac, d, t)
            for m in [m for m in self.minutes if m < t - 86400]:
                del self.minutes[m]

    def check_quota(self, mac, d, t):
        q = d['daily_quota_mb']
        if q <= 0 or d['quota_notified'] or d['today_rx'] + d['today_tx'] < q * 1_000_000:
            return
        action = d['quota_action'] or 'notify'
        d['quota_notified'] = 1
        if action == 'block':
            d['quota_blocked_until'] = local_midnight(t, 1)
        self.event('quota_exceeded', mac, {'used_mb': (d['today_rx'] + d['today_tx']) // 1_000_000,
                                           'quota_mb': q, 'action': action})

    # --- views ------------------------------------------------------------

    def client_view(self, mac, d, t):
        dev_paused = d['paused_until'] if (d['paused_until'] == -1 or d['paused_until'] > t) else 0
        grp_paused = self.group_paused(d['group'], t) if d['group'] else 0
        paused_until = -1 if -1 in (dev_paused, grp_paused) else max(dev_paused, grp_paused)
        blocked = d['blocked'] if d['blocked'] and (d['blocked_until'] == 0 or d['blocked_until'] > t) else ''
        rx = d['rx'][-1] if d['rx'] and d['online'] else 0
        tx = d['tx'][-1] if d['tx'] and d['online'] else 0
        return {
            'mac': mac, 'name': d['custom_name'] or d['hostname'], 'custom_name': d['custom_name'],
            'hostname': d['hostname'], 'ip': d['ip'],
            'ipv6': [f'fd00::{mac[-5:].replace(":", "")}'] if d['online'] else [],
            'conn': d['conn'], 'ssid': 'Home' if d['conn'] != 'lan' else '', 'signal': d['signal'],
            'online': d['online'], 'first_seen': d['first_seen'], 'last_seen': d['last_seen'],
            'rx_bps': rx, 'tx_bps': tx, 'today_rx': d['today_rx'], 'today_tx': d['today_tx'],
            'group': d['group'], 'blocked': blocked, 'blocked_until': d['blocked_until'] if blocked else 0,
            'paused': paused_until != 0, 'paused_until': paused_until,
            'paused_by': 'device' if dev_paused else ('group' if grp_paused else ''),
            'schedule_blocked': self.schedule_blocked(d['group'], t) if d['group'] else False,
            'dl_limit_kbps': d['dl_limit_kbps'], 'ul_limit_kbps': d['ul_limit_kbps'],
            'daily_quota_mb': d['daily_quota_mb'], 'quota_action': d['quota_action'] or 'notify',
            'quota_exceeded': d['quota_blocked_until'] > t or (
                d['daily_quota_mb'] > 0 and d['today_rx'] + d['today_tx'] >= d['daily_quota_mb'] * 1_000_000),
            'is_self': mac == SELF_MAC,
            'random_mac': int(mac[1], 16) & 2 == 2,
        }

    def group_view(self, g, t):
        rules = [parse_rule(r) for r in g['schedule']] if g['schedule_enabled'] and g['schedule'] else None
        return {
            'id': g['id'], 'name': g['name'],
            'members': sorted(m for m, d in self.devices.items() if d['group'] == g['id']),
            'dns_filter': g['dns_filter'], 'dns_custom': g['dns_custom'], 'safesearch': g['safesearch'],
            'blocklist': g['blocklist'], 'schedule': g['schedule'], 'schedule_enabled': g['schedule_enabled'],
            'dl_limit_kbps': g['dl_limit_kbps'], 'ul_limit_kbps': g['ul_limit_kbps'],
            'paused_until': self.group_paused(g['id'], t),
            'allowed_now': allowed_at(rules, t) if rules else True,
            'next_change': next_change(rules, t) if rules else 0,
        }

    def device_view(self, mac):
        d = self.devices[mac]
        return {k: d[k] for k in ('mac', 'custom_name', 'group', 'blocked', 'blocked_until', 'paused_until',
                                  'dl_limit_kbps', 'ul_limit_kbps', 'daily_quota_mb', 'quota_action')}

    def ensure_device(self, mac):
        if mac not in self.devices:
            self.add_device(('', '', mac, '', 'unknown', (0, 0), False))
        return self.devices[mac]

    # --- API --------------------------------------------------------------

    def status(self, a):
        t = now()
        return ok(agent_version=VERSION, openwrt_version='23.05.6', openwrt_description='OpenWrt 23.05.6 r24106-10cc5fcd00',
                  target='mediatek/filogic', model='Mock Router AX3000', hostname='OpenWrt', timezone='UTC',
                  uptime=t - self.started + 86400 * 3, time=t, enabled=True,
                  wan={'up': True, 'interface': 'wan', 'proto': 'dhcp', 'device': 'wan', 'uptime': t - self.started + 86400,
                       'ipv4': '203.0.113.45', 'dns': ['1.1.1.1']},
                  offload=self.offload, offload_warning=self.offload['software'] or self.offload['hardware'],
                  capabilities={'tc': not self.args.coarse, 'ifb': not self.args.coarse, 'sqm': not self.args.no_sqm,
                                'cake': True, 'nftset': True, 'hostapd': True, 'ip': True},
                  coarse_limiting=self.args.coarse, collector={'running': True, 'interval': INTERVAL},
                  last_apply={'ts': self.started, 'ok': True, 'warnings': []}, self_macs=[SELF_MAC])

    def clients(self, a):
        t = now()
        out = [self.client_view(m, d, t) for m, d in self.devices.items()]
        out.sort(key=lambda c: (not c['online'], (c['name'] or c['mac']).lower()))
        return ok(time=t, clients=out)

    def live(self, a):
        n = min(max(a.get('samples', 150), 1), len(self.ring_ts))
        want = [m for m in map(norm_mac, a.get('macs', [])) if m]
        devices = {}
        total_rx, total_tx = [0] * n, [0] * n
        for mac, d in self.devices.items():
            rx, tx = list(d['rx'])[-n:], list(d['tx'])[-n:]
            rx, tx = [0] * (n - len(rx)) + rx, [0] * (n - len(tx)) + tx
            for i in range(n):
                total_rx[i] += rx[i]
                total_tx[i] += tx[i]
            if a.get('devices', True) is not False and (not want or mac in want):
                devices[mac] = {'rx': rx, 'tx': tx}
        return ok(interval=INTERVAL, ts=list(self.ring_ts)[-n:] if n else [], total={'rx': total_rx, 'tx': total_tx},
                  devices=devices)

    def history(self, a):
        res = a.get('resolution', 'minute')
        if res not in ('minute', 'hour', 'day'):
            return err('invalid_argument', 'resolution must be minute, hour or day')
        mac = a.get('mac', '')
        per_device = mac == '*'
        if mac not in ('', 'all', '*'):
            mac = norm_mac(mac)
            if not mac:
                return err('invalid_mac')
        elif mac == 'all':
            mac = ''
        since = a.get('since', 0)
        t = now()
        buckets = {}
        if res == 'day':
            source, step = self.days, 86400
            since_eff = since - 86400
        else:
            source, step = self.minutes, (3600 if res == 'hour' else 60)
            since_eff = max(since, t - 86400) - step
        for ts, per in source.items():
            if ts <= since_eff:
                continue
            key = ts - (ts % step if res != 'day' else 0)
            for m, (rx, tx) in per.items():
                if mac and not per_device and m != mac:
                    continue
                tbl = buckets.setdefault(m, {}) if per_device else buckets
                b = tbl.setdefault(key, [0, 0])
                b[0] += rx
                b[1] += tx

        def series(tbl):
            return [[k, v[0], v[1]] for k, v in sorted(tbl.items())]
        if per_device:
            return ok(resolution=res, mac='*', devices={m: series(tbl) for m, tbl in buckets.items()})
        return ok(resolution=res, mac=mac, series=series(buckets))

    def events_(self, a):
        since = a.get('since_id', 0)
        reset = since > self.last_event_id
        return ok(last_id=self.last_event_id, reset=reset,
                  events=[e for e in self.events if reset or e['id'] > since])

    def set_device(self, a):
        mac = norm_mac(a.get('mac'))
        if not mac:
            return err('invalid_mac', 'A valid MAC address is required')
        d = self.ensure_device(mac)
        if 'group' in a and a['group'] != '' and a['group'] not in self.groups:
            return err('unknown_group', 'No such group')
        if 'daily_quota_mb' in a and not 0 <= a['daily_quota_mb'] <= 10_000_000:
            return err('invalid_argument', 'daily_quota_mb out of range')
        if 'quota_action' in a and a['quota_action'] not in ('', 'notify', 'block'):
            return err('invalid_argument', 'quota_action must be notify or block')
        if 'name' in a:
            d['custom_name'] = a['name'].strip()[:64]
        for k in ('group', 'daily_quota_mb', 'quota_action'):
            if k in a:
                d[k] = a[k]
        if 'daily_quota_mb' in a:
            d['quota_notified'] = 0
            d['quota_blocked_until'] = 0
        return ok(device=self.device_view(mac))

    def forget_device(self, a):
        mac = norm_mac(a.get('mac'))
        if not mac:
            return err('invalid_mac', 'A valid MAC address is required')
        self.devices.pop(mac, None)
        return ok()

    def block(self, a):
        mac = norm_mac(a.get('mac'))
        if not mac:
            return err('invalid_mac', 'A valid MAC address is required')
        if a.get('mode') not in ('internet', 'wifi'):
            return err('invalid_argument', 'mode must be "internet" or "wifi"')
        dur = a.get('duration_s', 0)
        if not 0 <= dur <= 30 * 86400:
            return err('invalid_argument', 'duration_s out of range')
        if mac == SELF_MAC:
            return err('self_block', 'Refusing to block the device that is making this request')
        d = self.ensure_device(mac)
        d['blocked'] = a['mode']
        d['blocked_until'] = now() + dur if dur else 0
        return ok(mac=mac, mode=a['mode'], blocked_until=d['blocked_until'])

    def unblock(self, a):
        mac = norm_mac(a.get('mac'))
        if not mac:
            return err('invalid_mac', 'A valid MAC address is required')
        d = self.ensure_device(mac)
        d['blocked'], d['blocked_until'], d['quota_blocked_until'] = '', 0, 0
        return ok(mac=mac)

    def set_limit(self, a):
        dl, ul = a.get('dl_kbps', 0), a.get('ul_kbps', 0)
        if not (0 <= dl <= 10_000_000 and 0 <= ul <= 10_000_000):
            return err('invalid_argument', 'dl_kbps / ul_kbps out of range')
        if a.get('group'):
            if a['group'] not in self.groups:
                return err('unknown_group', 'No such group')
            target = self.groups[a['group']]
        else:
            mac = norm_mac(a.get('mac'))
            if not mac:
                return err('invalid_mac', 'A valid MAC address or group is required')
            target = self.ensure_device(mac)
        target['dl_limit_kbps'], target['ul_limit_kbps'] = dl, ul
        return ok(coarse=self.args.coarse and (dl > 0 or ul > 0))

    def groups_(self, a):
        t = now()
        return ok(groups=[self.group_view(self.groups[g], t) for g in sorted(self.groups)], dns_filters=DNS_FILTERS)

    def set_group(self, a):
        gid = a.get('id', '')
        existing = None
        if gid:
            if not re.fullmatch(r'[a-z][a-z0-9_]{0,31}', gid):
                return err('invalid_group_id', 'Group ids use a-z, 0-9 and _ (max 32)')
            existing = self.groups.get(gid)
        else:
            name = a.get('name', '').strip()
            if not name:
                return err('invalid_argument', 'A name is required for a new group')
            base = re.sub(r'[^a-z0-9]+', '_', name.lower()).strip('_')[:24] or 'group'
            if not base[0].isalpha():
                base = 'g_' + base
            gid, i = base, 2
            while gid in self.groups:
                gid, i = f'{base}_{i}', i + 1
        g = dict(existing or {'id': gid, 'name': gid, 'dns_filter': 'off', 'dns_custom': [], 'safesearch': False,
                              'blocklist': [], 'schedule': [], 'schedule_enabled': True,
                              'dl_limit_kbps': 0, 'ul_limit_kbps': 0, 'paused_until': 0})
        if 'name' in a:
            if not a['name'].strip():
                return err('invalid_argument', 'Name must not be empty')
            g['name'] = a['name'].strip()[:64]
        if 'dns_filter' in a:
            if a['dns_filter'] not in DNS_FILTERS:
                return err('invalid_argument', 'Unknown dns_filter')
            g['dns_filter'] = a['dns_filter']
        if 'dns_custom' in a:
            if len(a['dns_custom']) > 4:
                return err('invalid_argument', 'At most 4 custom resolvers')
            g['dns_custom'] = [str(s) for s in a['dns_custom']]
        if g['dns_filter'] == 'custom' and not g['dns_custom']:
            return err('invalid_argument', 'A custom resolver address is required')
        if 'safesearch' in a:
            g['safesearch'] = a['safesearch']
        if 'blocklist' in a:
            out = []
            for dom in a['blocklist']:
                dom = re.sub(r'^(https?://)?(www\.)?', '', str(dom).strip().lower()).split('/')[0]
                if not re.fullmatch(r'([a-z0-9-]{1,63}\.)+[a-z]{2,63}', dom):
                    return err('invalid_domain', f'Invalid domain: {dom}')
                if dom not in out:
                    out.append(dom)
            g['blocklist'] = out
        if 'schedule' in a:
            rules = []
            for r in a['schedule']:
                p = parse_rule(r)
                if not p:
                    return err('invalid_schedule', f'Invalid schedule rule: {r}')
                if format_rule(p) not in rules:
                    rules.append(format_rule(p))
            g['schedule'] = rules
        if 'schedule_enabled' in a:
            g['schedule_enabled'] = a['schedule_enabled']
        if 'dl_kbps' in a:
            g['dl_limit_kbps'] = a['dl_kbps']
        if 'ul_kbps' in a:
            g['ul_limit_kbps'] = a['ul_kbps']
        members = None
        if 'members' in a:
            members = [norm_mac(m) for m in a['members']]
            if None in members:
                return err('invalid_mac', 'Invalid MAC address')
        self.groups[gid] = g
        if members is not None:
            for mac, d in self.devices.items():
                if d['group'] == gid and mac not in members:
                    d['group'] = ''
            for mac in members:
                self.ensure_device(mac)['group'] = gid
        return ok(group=self.group_view(g, now()))

    def delete_group(self, a):
        gid = a.get('id', '')
        if gid not in self.groups:
            return err('unknown_group', 'No such group')
        del self.groups[gid]
        for d in self.devices.values():
            if d['group'] == gid:
                d['group'] = ''
        return ok()

    def pause_target(self, a):
        if a.get('group'):
            if a['group'] not in self.groups:
                return None, err('unknown_group', 'No such group')
            return self.groups[a['group']], None
        mac = norm_mac(a.get('mac'))
        if not mac:
            return None, err('invalid_mac', 'A valid MAC address or group is required')
        return self.ensure_device(mac), None

    def pause(self, a):
        t = now()
        until = a.get('until', '')
        if until == 'indefinite':
            p = -1
        elif until == 'tomorrow':
            p = local_midnight(t, 1)
        elif until:
            return err('invalid_argument', 'until must be "tomorrow" or "indefinite"')
        elif a.get('duration_s', 0) == 0:
            p = -1
        elif 60 <= a['duration_s'] <= 30 * 86400:
            p = t + a['duration_s']
        else:
            return err('invalid_argument', 'duration_s out of range')
        target, e = self.pause_target(a)
        if e:
            return e
        macs = [target['mac']] if 'mac' in target else [m for m, d in self.devices.items() if d['group'] == target['id']]
        if SELF_MAC in macs:
            return err('self_block', 'Refusing to block the device that is making this request')
        target['paused_until'] = p
        return ok(paused_until=p)

    def resume(self, a):
        target, e = self.pause_target(a)
        if e:
            return e
        target['paused_until'] = 0
        return ok()

    def qos_get(self, a):
        if self.args.no_sqm:
            return ok(available=False, enabled=False, dl_kbps=0, ul_kbps=0, preset='default', interface='wan',
                      presets=QOS_PRESETS)
        return ok(available=True, interface='wan', qdisc='cake', presets=QOS_PRESETS, **self.qos)

    def qos_set(self, a):
        if self.args.no_sqm:
            return err('sqm_missing', 'sqm-scripts is not installed on the router')
        preset = a.get('preset', 'default') or 'default'
        if preset not in QOS_PRESETS:
            return err('invalid_argument', 'Unknown preset')
        enabled = a.get('enabled', self.qos['enabled'])
        dl, ul = a.get('dl_kbps', 0), a.get('ul_kbps', 0)
        if enabled and not (dl or self.qos['dl_kbps']):
            return err('invalid_argument', 'Download and upload bandwidth are required to enable SQM')
        self.qos = {'enabled': enabled, 'dl_kbps': dl or self.qos['dl_kbps'], 'ul_kbps': ul or self.qos['ul_kbps'],
                    'preset': preset}
        return self.qos_get({})

    def set_offload(self, a):
        for k in ('software', 'hardware'):
            if k in a:
                self.offload[k] = a[k]
        return ok(offload=self.offload)

    def dispatch(self, method, args):
        handler = {
            'status': self.status, 'clients': self.clients, 'live': self.live, 'history': self.history,
            'events': self.events_, 'set_device': self.set_device, 'forget_device': self.forget_device,
            'block': self.block, 'unblock': self.unblock, 'set_limit': self.set_limit, 'groups': self.groups_,
            'set_group': self.set_group, 'delete_group': self.delete_group, 'pause': self.pause,
            'resume': self.resume, 'qos_get': self.qos_get, 'qos_set': self.qos_set,
            'set_offload': self.set_offload, 'apply': lambda a: ok(warnings=[]),
            'version': lambda a: ok(agent_version=VERSION),
        }[method]
        with self.lock:
            return handler(args)


# --- JSON-RPC over HTTP ------------------------------------------------------

def type_ok(value, expected):
    if expected is int:
        return isinstance(value, int) and not isinstance(value, bool) and -2**31 <= value < 2**31
    return isinstance(value, expected)


class Handler(BaseHTTPRequestHandler):
    router: Router = None
    password = 'wrtpilot'
    delay = 0.0

    def log_message(self, fmt, *args):
        if self.server.verbose:
            super().log_message(fmt, *args)

    def reply(self, obj, code=200):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        self.reply({'name': 'WrtPilot mock router', 'endpoint': '/ubus'})

    def do_POST(self):
        if self.path.rstrip('/') != '/ubus':
            return self.reply({'error': 'not found'}, 404)
        try:
            req = json.loads(self.rfile.read(int(self.headers.get('Content-Length', 0))))
        except (ValueError, TypeError):
            return self.reply({'jsonrpc': '2.0', 'id': None, 'error': {'code': -32700, 'message': 'Parse error'}})
        if self.delay:
            time.sleep(self.delay)
        rid = req.get('id')
        params = req.get('params') or []
        if req.get('method') != 'call' or len(params) != 4:
            return self.reply({'jsonrpc': '2.0', 'id': rid, 'error': {'code': -32600, 'message': 'Invalid request'}})
        sid, obj, method, args = params
        self.reply({'jsonrpc': '2.0', 'id': rid, **self.call(sid, obj, method, args or {})})

    def call(self, sid, obj, method, args):
        r = self.router
        if obj == 'session' and method == 'login':
            if args.get('username') == 'wrtpilot' and args.get('password') == self.password:
                new = secrets.token_hex(16)
                r.sessions[new] = time.time() + SESSION_TIMEOUT
                return {'result': [0, {'ubus_rpc_session': new, 'timeout': SESSION_TIMEOUT, 'expires': SESSION_TIMEOUT,
                                       'acls': {'ubus': {'wrtpilot': ['*']}}, 'data': {'username': 'wrtpilot'}}]}
            return {'result': [UBUS_PERMISSION_DENIED]}
        expiry = r.sessions.get(sid)
        if sid == ANON or not expiry or expiry < time.time():
            r.sessions.pop(sid, None)
            return {'error': {'code': RPC_ACCESS_DENIED, 'message': 'Access denied'}}
        r.sessions[sid] = time.time() + SESSION_TIMEOUT
        if obj != 'wrtpilot':
            return {'error': {'code': RPC_ACCESS_DENIED, 'message': 'Access denied'}}
        sig = SIGNATURES.get(method)
        if sig is None:
            return {'result': [UBUS_METHOD_NOT_FOUND]}
        for k, v in args.items():
            if k not in sig or not type_ok(v, sig[k]):
                return {'result': [UBUS_INVALID_ARGUMENT]}
        return {'result': [0, r.dispatch(method, args)]}


def main():
    p = argparse.ArgumentParser(description='WrtPilot mock router (JSON-RPC on /ubus)')
    p.add_argument('--host', default='0.0.0.0')
    p.add_argument('--port', type=int, default=8080)
    p.add_argument('--password', default='wrtpilot', help='password of the "wrtpilot" user')
    p.add_argument('--tls', nargs=2, metavar=('CERT', 'KEY'), help='serve HTTPS with this certificate')
    p.add_argument('--delay', type=float, default=0.0, help='seconds of latency added to every call')
    p.add_argument('--no-sqm', action='store_true', help='pretend sqm-scripts is not installed')
    p.add_argument('--coarse', action='store_true', help='pretend tc/ifb are missing (approximate limits)')
    p.add_argument('--offload', action='store_true', help='pretend flow offloading is enabled')
    p.add_argument('--new-device-after', type=int, default=60,
                   help='seconds until a new device joins (new_device event)')
    p.add_argument('--seed', type=int, help='random seed')
    p.add_argument('-v', '--verbose', action='store_true')
    args = p.parse_args()
    if args.seed is not None:
        random.seed(args.seed)

    router = Router(args)
    for _ in range(30):     # one minute of live samples to start with
        router.tick()
    router.ring_ts = deque([t - (29 - i) * INTERVAL for i, t in enumerate(router.ring_ts)], maxlen=RING)

    def loop():
        while True:
            time.sleep(INTERVAL)
            router.tick()
    threading.Thread(target=loop, daemon=True).start()

    Handler.router, Handler.password, Handler.delay = router, args.password, args.delay
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    server.verbose = args.verbose
    scheme = 'http'
    if args.tls:
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(*args.tls)
        server.socket = ctx.wrap_socket(server.socket, server_side=True)
        scheme = 'https'
    print(f'WrtPilot mock router on {scheme}://{args.host}:{args.port}/ubus  (user wrtpilot, password {args.password})')
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == '__main__':
    main()
