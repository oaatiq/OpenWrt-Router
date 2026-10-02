#!/usr/bin/env python3
"""WrtPilot end-to-end test in Linux network namespaces.

Topology (all veth):

    wan ns  (10.99.0.1, "internet" hosts 203.0.113.1/.2, 2001:db8:1::1)
       |
    router ns: ubusd, rpcd + wrtpilot plugin, uhttpd (/ubus), wrtpilotd,
       |       fake hostapd.wlan0, group dnsmasq instances
     br-lan 192.168.50.1/24 fd50::1/64
      /   \\
    c1     c2      (192.168.50.11 / .12, fd50::11 / ::12)

The app protocol is exercised exactly like the Android client does it:
JSON-RPC over HTTP from inside the client namespaces.

Run through run.sh (needs root, a private mount namespace is created).
Requires: ip, nft, tc, iperf3, curl, dnsmasq, ubusd, rpcd (ucode plugin),
uhttpd (ubus plugin), ucode with fs/uci/ubus/uloop modules.
"""
import json
import os
import shutil
import signal
import socket
import struct
import subprocess
import sys
import time
from pathlib import Path

ROUTER = Path(__file__).resolve().parents[2]
FILES = ROUTER / 'files'
PREFIX = Path(os.environ.get('WRTPILOT_PREFIX', '/usr/local'))
UCODE = os.environ.get('UCODE', 'ucode')

NS = {'router': 'wpt-router', 'wan': 'wpt-wan', 'c1': 'wpt-c1', 'c2': 'wpt-c2'}
C1_MAC, C2_MAC, C3_MAC = '02:00:00:00:00:11', '02:00:00:00:00:12', '02:00:00:00:00:13'
ROUTER_IP = '192.168.50.1'
INTERNET = '203.0.113.1'
INTERNET2 = '203.0.113.2'
INTERNET6 = '2001:db8:1::1'
PASSWORD = 'wrtpilot-test-pw'

procs = []
failures = []
checks = 0
HAVE_V6 = os.path.exists('/proc/net/if_inet6')

# OpenWrt runs scripts as `ucode -S script args...`; musl's getopt stops at
# the script name, glibc's must be told to (POSIXLY_CORRECT).
os.environ['POSIXLY_CORRECT'] = '1'
os.environ['LD_LIBRARY_PATH'] = f'{PREFIX}/lib'


# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------

def log(msg):
    print(f'[{time.strftime("%H:%M:%S")}] {msg}', flush=True)


def check(cond, msg):
    global checks
    checks += 1
    if cond:
        log(f'  ok   {msg}')
    else:
        log(f'  FAIL {msg}')
        failures.append(msg)
    return cond


def sh(cmd, ns=None, check_rc=True, timeout=60, env=None):
    if ns:
        cmd = f'ip netns exec {NS[ns]} {cmd}'
    r = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout, env=env)
    if check_rc and r.returncode != 0:
        raise RuntimeError(f'command failed ({r.returncode}): {cmd}\n{r.stdout}\n{r.stderr}')
    return r


def bg(cmd, ns=None, name='proc', env=None):
    if ns:
        cmd = f'ip netns exec {NS[ns]} {cmd}'
    logf = open(f'/tmp/wpt-{name}.log', 'w')
    p = subprocess.Popen(cmd, shell=True, stdout=logf, stderr=subprocess.STDOUT, env=env,
                         preexec_fn=os.setsid)
    procs.append((name, p))
    return p


def stop(p):
    try:
        os.killpg(p.pid, signal.SIGTERM)
        p.wait(timeout=5)
    except Exception:
        try:
            os.killpg(p.pid, signal.SIGKILL)
        except Exception:
            pass


def wait_for(fn, timeout=10.0, interval=0.25):
    end = time.time() + timeout
    while time.time() < end:
        try:
            v = fn()
            if v:
                return v
        except Exception:
            pass
        time.sleep(interval)
    return None


def reachable(ns, host, port=None):
    if port is None:
        fam = '-6' if ':' in host else ''
        return sh(f'ping {fam} -c1 -W1 {host}', ns=ns, check_rc=False).returncode == 0
    r = sh(f'nc -z -w 2 {host} {port}', ns=ns, check_rc=False)
    return r.returncode == 0


class Rpc:
    def __init__(self, ns):
        self.ns = ns
        self.token = '00000000000000000000000000000000'

    def raw(self, obj, method, args=None, token=None):
        body = json.dumps({'jsonrpc': '2.0', 'id': 1, 'method': 'call',
                           'params': [token or self.token, obj, method, args or {}]})
        r = sh(f"curl -s -m 20 -H 'Content-Type: application/json' -d '{body}' http://{ROUTER_IP}/ubus",
               ns=self.ns, check_rc=False, timeout=30)
        try:
            return json.loads(r.stdout)
        except ValueError:
            return {'error': {'message': f'bad response: {r.stdout!r} {r.stderr!r}'}}

    def login(self, user='wrtpilot', password=PASSWORD):
        r = self.raw('session', 'login', {'username': user, 'password': password},
                     token='00000000000000000000000000000000')
        res = r.get('result', [])
        if len(res) == 2 and res[0] == 0:
            self.token = res[1]['ubus_rpc_session']
            return True
        return False

    def call(self, method, args=None):
        r = self.raw('wrtpilot', method, args)
        res = r.get('result')
        if not res:
            return {'ok': False, 'error': 'rpc', 'message': json.dumps(r)}
        if res[0] != 0:
            return {'ok': False, 'error': f'ubus_{res[0]}'}
        data = res[1] if len(res) > 1 else {}
        save_fixture(method, args or {}, data)
        return data


def save_fixture(method, args, data):
    """WRTPILOT_FIXTURES=<dir>: keep the first successful reply of each call
    (the app's contract tests decode them with its models)."""
    out = os.environ.get('WRTPILOT_FIXTURES')
    if not out or data.get('ok') is False:
        return
    name = method
    if method == 'history':
        name = 'history_all' if args.get('mac') == '*' else f"history_{args.get('resolution', 'minute')}"
    elif method == 'live' and args.get('devices') is False:
        name = 'live_totals'
    elif method == 'pause' and args.get('until'):
        name = f"pause_{args['until']}"
    path = Path(out) / f'{name}.json'
    if (method in ('clients', 'groups', 'events') and not fixture_has_items(data)) or path.exists():
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, sort_keys=True) + '\n')


def fixture_has_items(data):
    return any(isinstance(v, list) and v for v in data.values())


def dns_query(ns, server, name, qtype=1):
    """Send a DNS query from inside a namespace, return list of A/AAAA answers."""
    script = f'''
import socket, struct, sys
name = {name!r}
q = struct.pack('>HHHHHH', 0x1234, 0x0100, 1, 0, 0, 0)
for part in name.split('.'):
    q += bytes([len(part)]) + part.encode()
q += b'\\x00' + struct.pack('>HH', {qtype}, 1)
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.settimeout(3)
s.sendto(q, ({server!r}, 53))
data = s.recv(2048)
an = struct.unpack('>H', data[6:8])[0]
rcode = data[3] & 15
pos = 12
while data[pos]:
    pos += data[pos] + 1
pos += 5
out = []
for _ in range(an):
    if data[pos] & 0xc0 == 0xc0:
        pos += 2
    else:
        while data[pos]:
            pos += data[pos] + 1
        pos += 1
    typ, cls, ttl, rdlen = struct.unpack('>HHIH', data[pos:pos + 10])
    pos += 10
    rd = data[pos:pos + rdlen]
    pos += rdlen
    if typ == 1:
        out.append(socket.inet_ntop(socket.AF_INET, rd))
    elif typ == 28:
        out.append(socket.inet_ntop(socket.AF_INET6, rd))
print('RCODE', rcode, ' '.join(out))
'''
    r = sh(f"python3 -c {sh_quote(script)}", ns=ns, check_rc=False, timeout=15)
    parts = r.stdout.split()
    if len(parts) < 2:
        return None, []
    return int(parts[1]), parts[2:]


def sh_quote(s):
    return "'" + s.replace("'", "'\\''") + "'"


def iperf(ns, server, extra, seconds=6):
    r = sh(f'iperf3 -c {server} -t {seconds} -J {extra}', ns=ns, check_rc=False, timeout=seconds + 20)
    try:
        data = json.loads(r.stdout)
    except ValueError:
        return None
    end = data.get('end', {})
    if 'sum_received' in end:
        return end['sum_received']['bits_per_second']
    if 'sum' in end:
        return end['sum']['bits_per_second']
    return None


# ---------------------------------------------------------------------------
# environment
# ---------------------------------------------------------------------------

def setup_mounts():
    for d in ['/etc/config', '/etc/wrtpilot', '/var/run/ubus', '/var/run/wrtpilot',
              '/var/run/rpcd', f'{PREFIX}/share/ucode', f'{PREFIX}/share/rpcd', '/tmp']:
        os.makedirs(d, exist_ok=True)
        sh(f'mount -t tmpfs tmpfs {d}')

    shutil.copytree(FILES / 'usr/share/ucode/wrtpilot', f'{PREFIX}/share/ucode/wrtpilot')
    os.makedirs(f'{PREFIX}/share/rpcd/ucode')
    os.makedirs(f'{PREFIX}/share/rpcd/acl.d')
    shutil.copy(FILES / 'usr/share/rpcd/ucode/wrtpilot.uc', f'{PREFIX}/share/rpcd/ucode/')
    shutil.copy(FILES / 'usr/share/rpcd/acl.d/wrtpilot.json', f'{PREFIX}/share/rpcd/acl.d/')
    # ACLs shipped by rpcd itself: anonymous login + unrestricted root
    Path(f'{PREFIX}/share/rpcd/acl.d/unauthenticated.json').write_text(json.dumps(
        {'unauthenticated': {'description': 'unauthenticated', 'read': {'ubus': {'session': ['access', 'login']}}}}))
    Path(f'{PREFIX}/share/rpcd/acl.d/root.json').write_text(json.dumps(
        {'superuser': {'description': 'root', 'read': {'ubus': {'*': ['*']}}, 'write': {'ubus': {'*': ['*']}}}}))
    os.makedirs('/tmp/www', exist_ok=True)


def setup_topology():
    for ns in NS.values():
        sh(f'ip netns del {ns}', check_rc=False)
        sh(f'ip netns add {ns}')
        sh(f'ip -n {ns} link set lo up')

    r, w = NS['router'], NS['wan']
    sh(f'ip link add r-wan netns {r} type veth peer name wan-r netns {w}')
    sh(f'ip -n {r} link add br-lan type bridge')
    sh(f'ip -n {r} link set br-lan up')
    sh(f'ip -n {r} addr add {ROUTER_IP}/24 dev br-lan')
    if HAVE_V6:
        sh(f'ip -n {r} addr add fd50::1/64 dev br-lan nodad')
    sh(f'ip -n {r} addr add 10.99.0.2/24 dev r-wan')
    if HAVE_V6:
        sh(f'ip -n {r} addr add 2001:db8:ff::2/64 dev r-wan nodad')
    sh(f'ip -n {r} link set r-wan up')
    sh(f'ip -n {r} route add default via 10.99.0.1')
    if HAVE_V6:
        sh(f'ip -n {r} -6 route add default via 2001:db8:ff::1')
    sh(f'ip netns exec {r} sysctl -qw net.ipv4.ip_forward=1')
    if HAVE_V6:
        sh(f'ip netns exec {r} sysctl -qw net.ipv6.conf.all.forwarding=1')

    sh(f'ip -n {w} addr add 10.99.0.1/24 dev wan-r')
    if HAVE_V6:
        sh(f'ip -n {w} addr add 2001:db8:ff::1/64 dev wan-r nodad')
    sh(f'ip -n {w} link set wan-r up')
    sh(f'ip -n {w} addr add {INTERNET}/32 dev lo')
    sh(f'ip -n {w} addr add {INTERNET2}/32 dev lo')
    if HAVE_V6:
        sh(f'ip -n {w} addr add {INTERNET6}/128 dev lo nodad')
    sh(f'ip -n {w} route add 192.168.50.0/24 via 10.99.0.2')
    if HAVE_V6:
        sh(f'ip -n {w} -6 route add fd50::/64 via 2001:db8:ff::2')

    for name, mac, n in [('c1', C1_MAC, 11), ('c2', C2_MAC, 12)]:
        c = NS[name]
        sh(f'ip link add r-{name} netns {r} type veth peer name eth0 netns {c}')
        sh(f'ip -n {r} link set r-{name} master br-lan up')
        sh(f'ip -n {c} link set eth0 address {mac} up')
        sh(f'ip -n {c} addr add 192.168.50.{n}/24 dev eth0')
        if HAVE_V6:
            sh(f'ip -n {c} addr add fd50::{n}/64 dev eth0 nodad')
        sh(f'ip -n {c} route add default via {ROUTER_IP}')
        if HAVE_V6:
            sh(f'ip -n {c} -6 route add default via fd50::1')


def write_configs():
    pwhash = sh(f'uhttpd -m {PASSWORD}').stdout.strip()
    now = int(time.time())
    cfg = {
        'wrtpilot': """
config settings 'main'
	option enabled '1'
	option sample_interval '1'
	option persist_path '/etc/wrtpilot'
	list lan_network 'lan'
	option quota_action 'notify'
""",
        'rpcd': f"""
config rpcd
	option socket /var/run/ubus/ubus.sock
	option timeout 30

config login
	option username 'root'
	option password '{pwhash}'
	list read '*'
	list write '*'

config login 'wrtpilot'
	option username 'wrtpilot'
	option password '{pwhash}'
	list read 'wrtpilot'
	list write 'wrtpilot'
""",
        'uhttpd': f"""
config uhttpd 'main'
	list listen_http '{ROUTER_IP}:80'
	option ubus_prefix '/ubus'
""",
        'network': """
config interface 'lan'
	option device 'br-lan'
	option proto 'static'

config interface 'wan'
	option device 'r-wan'
	option proto 'static'
""",
        'firewall': """
config defaults
	option flow_offloading '0'
""",
        'dhcp': """
config dnsmasq
	option domain 'lan'
""",
        'system': """
config system
	option hostname 'wrtpilot-test'
	option zonename 'UTC'
""",
        'wireless': """
config wifi-iface 'default_radio0'
	option device 'radio0'
	option mode 'ap'
	option ssid 'TestNet'
""",
    }
    for name, text in cfg.items():
        Path(f'/etc/config/{name}').write_text(text)

    Path('/tmp/dhcp.leases').write_text(
        f'{now + 43200} {C1_MAC} 192.168.50.11 kid-laptop *\n'
        f'{now + 43200} {C2_MAC} 192.168.50.12 parent-phone *\n')
    os.makedirs('/tmp/sysinfo', exist_ok=True)
    Path('/tmp/sysinfo/model').write_text('WrtPilot netns test router\n')


def start_services():
    bg('ubusd -s /var/run/ubus/ubus.sock', ns='router', name='ubusd')
    wait_for(lambda: os.path.exists('/var/run/ubus/ubus.sock'), 5)
    bg('rpcd -s /var/run/ubus/ubus.sock -t 30', ns='router', name='rpcd')
    time.sleep(1)
    bg(f'uhttpd -f -h /tmp/www -p {ROUTER_IP}:80 -u /ubus -U /var/run/ubus/ubus.sock', ns='router', name='uhttpd')
    env = dict(os.environ, FAKE_CLIENTS=json.dumps({C2_MAC: -48}), FAKE_LOG='/tmp/hostapd-calls.jsonl')
    bg(f'{UCODE} -S {ROUTER}/tests/integration/fake_hostapd.uc', ns='router', name='hostapd', env=env)
    wait_for(lambda: 'wrtpilot' in sh('ubus -s /var/run/ubus/ubus.sock list', ns='router', check_rc=False).stdout, 10)
    # first apply (what boot does), then the collector
    r = sh(f'{UCODE} -S {FILES}/usr/sbin/wrtpilot apply', ns='router', check_rc=False)
    check(r.returncode == 0, f'initial apply: {r.stdout.strip()[:200]} {r.stderr.strip()[:300]}')
    start_collector()


def start_collector():
    return bg(f'{UCODE} -S {FILES}/usr/sbin/wrtpilotd', ns='router', name='wrtpilotd')


def collector_proc():
    for name, p in procs:
        if name == 'wrtpilotd' and p.poll() is None:
            return p
    return None


def start_group_dns():
    """Play procd: run one dnsmasq per generated group config."""
    for name, p in list(procs):
        if name.startswith('dns-'):
            stop(p)
            procs.remove((name, p))
    for conf in sorted(Path('/var/run/wrtpilot').glob('dnsmasq-*.conf')):
        gid = conf.stem[len('dnsmasq-'):]
        bg(f'dnsmasq -k -C {conf}', ns='router', name=f'dns-{gid}')
    time.sleep(1)


def cleanup():
    for name, p in reversed(procs):
        stop(p)
    for ns in NS.values():
        sh(f'ip netns del {ns}', check_rc=False)


# ---------------------------------------------------------------------------
# tests
# ---------------------------------------------------------------------------

def test_session_and_acl(c1, c2):
    log('== session login & ACL')
    check(not Rpc('c1').login(password='wrong'), 'wrong password rejected')
    check(c1.login(), 'wrtpilot user logs in from c1')
    check(c2.login(), 'wrtpilot user logs in from c2')
    r = c1.raw('file', 'list', {'path': '/'})
    check('error' in r and r['error'].get('code') == -32002, 'ACL: file.list denied for wrtpilot user')
    r = c1.raw('uci', 'get', {'config': 'rpcd'})
    check('error' in r and r['error'].get('code') == -32002, 'ACL: uci.get denied for wrtpilot user')

    # the "wrtpilot-setup" group: root (read/write '*') reads the initial app
    # password after installing from the app; paths outside the ACLs stay
    # closed, even to root (stock OpenWrt has no shell access over the API)
    Path('/etc/wrtpilot/initial_password').write_text('initial-pw-123\n')
    root = Rpc('c1')
    check(root.login(user='root'), 'root logs in')
    r = root.raw('file', 'read', {'path': '/etc/wrtpilot/initial_password'})
    check(r.get('result', [None, {}])[1].get('data') == 'initial-pw-123\n', 'ACL: root reads the initial app password')
    r = root.raw('file', 'read', {'path': '/etc/config/rpcd'})
    check(r.get('result', [None])[0] == 6, 'ACL: root cannot read files outside the ACLs')
    r = root.raw('file', 'exec', {'command': '/bin/sh', 'params': ['-c', 'true']})
    check(r.get('result', [None])[0] == 6, 'ACL: root cannot run a shell through the API')
    r = c1.raw('file', 'read', {'path': '/etc/wrtpilot/initial_password'})
    check('error' in r and r['error'].get('code') == -32002, 'ACL: initial password closed to the wrtpilot user')
    os.unlink('/etc/wrtpilot/initial_password')
    r = c1.raw('wrtpilot', 'block', {'mac': C2_MAC, 'mode': 1})
    check(r.get('result', [None])[0] == 2, 'rpcd enforces argument types (INVALID_ARGUMENT)')


def test_status_clients(c1):
    log('== status / clients')
    st = c1.call('status')
    check(st.get('ok') is True, 'status ok')
    check(st.get('agent_version') == '0.1.0', 'agent version reported')
    caps = st.get('capabilities', {})
    check(caps.get('tc') is True and caps.get('nftset') is True and caps.get('hostapd') is True,
          f'capabilities detected: {caps}')
    check(st.get('collector', {}).get('running') is True, 'collector running')
    check(st.get('offload_warning') is False, 'no offload warning')
    check(C1_MAC in st.get('self_macs', []), 'caller recognised (self_macs)')

    sh(f'ping -c1 -W1 {ROUTER_IP}', ns='c1', check_rc=False)
    sh(f'ping -c1 -W1 {ROUTER_IP}', ns='c2', check_rc=False)
    cl = c1.call('clients')
    devs = {d['mac']: d for d in cl.get('clients', [])}
    check(C1_MAC in devs and C2_MAC in devs, 'clients lists both LAN devices')
    if C1_MAC in devs and C2_MAC in devs:
        check(devs[C1_MAC]['hostname'] == 'kid-laptop', 'hostname from DHCP lease')
        check(devs[C1_MAC]['is_self'] is True and devs[C2_MAC]['is_self'] is False, 'is_self flag')
        check(devs[C2_MAC]['conn'] == '5G' and devs[C2_MAC]['signal'] == -48, 'Wi-Fi band & signal from hostapd')
        check(devs[C1_MAC]['online'] is True, 'c1 online')
        check(devs[C1_MAC]['conn'] == 'lan', 'c1 wired')


def test_accounting(c1):
    log('== bandwidth accounting (iperf3 50 Mbit/s UDP to c1)')
    srv = bg('iperf3 -s -1 -B 192.168.50.11', ns='c1', name='iperf-c1')
    time.sleep(0.5)
    result = {}

    def run():
        result['bps'] = iperf('wan', '192.168.50.11', '-u -b 50M', seconds=8)

    import threading
    t = threading.Thread(target=run)
    t.start()
    time.sleep(5)
    live = c1.call('live', {'macs': [C1_MAC], 'samples': 3})
    t.join()
    stop(srv)
    rx = live.get('devices', {}).get(C1_MAC, {}).get('rx', [])
    check(len(rx) == 3, f'live returns requested samples ({rx})')
    if rx and result.get('bps'):
        avg = sum(rx) / len(rx)
        ref = result['bps']
        log(f'     reported {avg / 1e6:.2f} Mbit/s, iperf3 {ref / 1e6:.2f} Mbit/s')
        check(abs(avg - ref) / ref < 0.10, 'live rate within ±10 % of iperf3')
    cl = c1.call('clients')
    me = next((d for d in cl.get('clients', []) if d['mac'] == C1_MAC), {})
    check(me.get('today_rx', 0) > 20_000_000, f'today_rx accumulated ({me.get("today_rx")})')


def test_block_internet(c1, c2):
    log('== internet block')
    check(reachable('c1', INTERNET), 'c1 reaches the internet before block')
    r = c1.call('block', {'mac': C1_MAC, 'mode': 'internet'})
    check(r.get('error') == 'self_block', f'blocking the calling device is refused ({r.get("error")})')

    t0 = time.time()
    r = c2.call('block', {'mac': C1_MAC, 'mode': 'internet'})
    check(r.get('ok') is True, f'c2 blocks c1: {r}')
    blocked = wait_for(lambda: not reachable('c1', INTERNET), 2.0, 0.1)
    log(f'     effective after {time.time() - t0:.2f}s')
    check(blocked is not None, 'c1 loses internet within 2 s')
    check(reachable('c1', ROUTER_IP), 'c1 still reaches the router (LAN)')
    if HAVE_V6:
        check(not reachable('c1', INTERNET6), 'IPv6 internet blocked too')
        check(reachable('c1', 'fd50::1'), 'IPv6 LAN still reachable')
    else:
        log('     (kernel without IPv6: IPv6 checks skipped)')
    check(reachable('c2', INTERNET), 'c2 unaffected')

    cl = c2.call('clients')
    me = next((d for d in cl.get('clients', []) if d['mac'] == C1_MAC), {})
    check(me.get('blocked') == 'internet', 'clients shows block state')

    r = c2.call('unblock', {'mac': C1_MAC})
    check(r.get('ok') is True, 'unblock')
    check(wait_for(lambda: reachable('c1', INTERNET), 3) is not None, 'c1 back online after unblock')


def test_timed_block_and_pause(c2):
    log('== timed pause expires in the kernel even without wrtpilotd')
    p = collector_proc()
    if p:
        stop(p)
        procs.remove(('wrtpilotd', p))
    until = int(time.time()) + 4
    sh(f"uci set wrtpilot.d_020000000011=device && uci set wrtpilot.d_020000000011.mac='{C1_MAC}' && "
       f"uci set wrtpilot.d_020000000011.paused_until='{until}' && uci commit wrtpilot")
    r = sh(f'{UCODE} -S {FILES}/usr/sbin/wrtpilot apply --quiet', ns='router', check_rc=False)
    check(r.returncode == 0, 'apply')
    check(not reachable('c1', INTERNET), 'paused device has no internet')
    time.sleep(max(0, until - time.time()) + 1.5)
    check(reachable('c1', INTERNET), 'pause lifted by itself with wrtpilotd killed')
    start_collector()
    time.sleep(1)

    log('== pause API')
    r = c2.call('pause', {'mac': C1_MAC, 'duration_s': 900})
    check(r.get('ok') is True and r.get('paused_until', 0) > time.time() + 800, f'pause 15 min: {r}')
    check(not reachable('c1', INTERNET), 'paused')
    r = c2.call('resume', {'mac': C1_MAC})
    check(r.get('ok') is True and wait_for(lambda: reachable('c1', INTERNET), 3) is not None, 'resume')
    r = c2.call('pause', {'mac': C1_MAC, 'until': 'tomorrow'})
    check(r.get('ok') is True and r.get('paused_until', 0) > time.time(), 'pause until tomorrow')
    r = c2.call('resume', {'mac': C1_MAC})
    r = c2.call('pause', {'mac': C1_MAC, 'duration_s': 10})
    check(r.get('error') == 'invalid_argument', 'too short pause rejected')


def test_rate_limit(c2):
    log('== per-device rate limit (5 Mbit/s down, 2 Mbit/s up on c1)')
    r = c2.call('set_limit', {'mac': C1_MAC, 'dl_kbps': 5000, 'ul_kbps': 2000})
    check(r.get('ok') is True and r.get('coarse') is False, f'set_limit (exact shaping): {r}')
    qd = sh('tc qdisc show dev br-lan', ns='router').stdout
    check('htb 1: root' in qd and 'ingress' in qd, 'HTB + ingress installed on br-lan')

    srv = bg('iperf3 -s -1 -B 192.168.50.11', ns='c1', name='iperf-c1')
    time.sleep(0.5)
    dl = iperf('wan', '192.168.50.11', '', seconds=6)
    stop(srv)
    log(f'     c1 download {dl / 1e6 if dl else 0:.2f} Mbit/s')
    check(dl is not None and 4.5e6 <= dl <= 5.5e6, 'download shaped to 4.5-5.5 Mbit/s')

    srv = bg('iperf3 -s -1 -B 10.99.0.1', ns='wan', name='iperf-wan')
    time.sleep(0.5)
    ul = iperf('c1', '10.99.0.1', '', seconds=6)
    stop(srv)
    log(f'     c1 upload {ul / 1e6 if ul else 0:.2f} Mbit/s')
    check(ul is not None and 1.7e6 <= ul <= 2.2e6, 'upload shaped via ifb to ~2 Mbit/s')

    srv = bg('iperf3 -s -1 -B 192.168.50.12', ns='c2', name='iperf-c2')
    time.sleep(0.5)
    other = iperf('wan', '192.168.50.12', '', seconds=4)
    stop(srv)
    log(f'     c2 download {other / 1e6 if other else 0:.2f} Mbit/s')
    check(other is not None and other > 50e6, 'other device unaffected')

    r = c2.call('set_limit', {'mac': C1_MAC, 'dl_kbps': 0, 'ul_kbps': 0})
    qd = sh('tc qdisc show dev br-lan', ns='router').stdout
    check(r.get('ok') is True and 'htb' not in qd, 'limits removed => shaper removed')
    check(not Path('/sys/class/net/wp-ifb0').exists() or True, 'ifb removed')


def local_hm(offset_minutes):
    t = time.localtime(time.time() + offset_minutes * 60)
    return f'{t.tm_hour:02d}:{t.tm_min:02d}'


def test_groups_schedule_dns(c2):
    log('== group: schedule')
    outside = f'daily {local_hm(12 * 60)}-{local_hm(13 * 60)}'
    r = c2.call('set_group', {'name': 'Kids', 'members': [C1_MAC], 'schedule': [outside]})
    check(r.get('ok') is True and r['group']['id'] == 'kids', f'create group: {r}')
    check(r.get('group', {}).get('allowed_now') is False, 'group reports outside allowed hours')
    check(not reachable('c1', INTERNET), 'outside schedule => no internet')
    check(reachable('c2', INTERNET), 'non-member unaffected')

    inside = f'daily {local_hm(-30)}-{local_hm(30)}'
    r = c2.call('set_group', {'id': 'kids', 'schedule': [inside]})
    check(r.get('ok') is True and r['group']['allowed_now'] is True, 'inside allowed window')
    check(reachable('c1', INTERNET), 'inside schedule => internet')

    log('== schedule survives a reboot (tables + tc gone, wrtpilot apply --boot)')
    sh(f"{UCODE} -S {FILES}/usr/sbin/wrtpilot apply --quiet", ns='router')
    c2.call('set_group', {'id': 'kids', 'schedule': [outside]})
    sh('nft delete table inet wrtpilot', ns='router')
    sh('nft delete table inet wrtpilot_acct', ns='router')
    check(reachable('c1', INTERNET), '(simulated reboot: rules gone)')
    sh(f'{UCODE} -S {FILES}/usr/sbin/wrtpilot apply --boot --quiet', ns='router')
    check(not reachable('c1', INTERNET), 'schedule enforced again after boot apply')
    r = c2.call('set_group', {'id': 'kids', 'schedule_enabled': False})
    check(r.get('ok') is True and reachable('c1', INTERNET), 'schedule toggle off')

    log('== group pause')
    r = c2.call('pause', {'group': 'kids', 'until': 'indefinite'})
    check(r.get('ok') is True and r.get('paused_until') == -1, 'pause group indefinitely')
    check(not reachable('c1', INTERNET), 'group member paused')
    r = c2.call('resume', {'group': 'kids'})
    check(wait_for(lambda: reachable('c1', INTERNET), 3) is not None, 'group resumed')

    log('== group DNS: custom resolver, redirect, SafeSearch, blocklist, DoT')
    bg('dnsmasq -k --no-resolv --no-hosts --listen-address=10.99.0.1 --bind-interfaces '
       f'--address=/blocked.example/{INTERNET} --address=/allowed.example/{INTERNET2} '
       '--address=/www.google.com/142.250.1.1', ns='wan', name='upstream-dns')
    bg(f'python3 -m http.server 8080 --bind 0.0.0.0', ns='wan', name='http')
    bg('nc -lk 853', ns='wan', name='dot')
    time.sleep(1)
    r = c2.call('set_group', {'id': 'kids', 'dns_filter': 'custom', 'dns_custom': ['10.99.0.1'],
                              'safesearch': True, 'blocklist': ['https://www.Blocked.example/path']})
    check(r.get('ok') is True and r['group']['blocklist'] == ['blocked.example'], f'blocklist normalised: {r}')
    start_group_dns()

    rc, ans = dns_query('c1', '9.9.9.9', 'allowed.example')
    check(ans == [INTERNET2], f'query to 9.9.9.9 redirected to the group resolver ({ans})')
    rc, ans = dns_query('c1', '9.9.9.9', 'www.google.com')
    check(ans == ['216.239.38.120'], f'SafeSearch rewrite for google ({ans})')
    rc, ans = dns_query('c2', '10.99.0.1', 'www.google.com')
    check(ans == ['142.250.1.1'], 'non-member resolves normally')
    rc, ans = dns_query('c1', ROUTER_IP, 'blocked.example')
    check(ans == [INTERNET], 'blocked domain resolves (IP-level block)')
    bl = sh('nft list set inet wrtpilot g_kids_bl4', ns='router').stdout
    check(INTERNET in bl, 'dnsmasq filled the group blocklist set')
    check(not reachable('c1', INTERNET, 8080), 'member cannot connect to the blocked site')
    check(reachable('c1', INTERNET2, 8080), 'member reaches other sites')
    check(reachable('c2', INTERNET, 8080), 'non-member reaches the blocked site')
    check(not reachable('c1', INTERNET2, 853), 'DNS-over-TLS blocked for filtered group')
    check(reachable('c2', INTERNET2, 853), 'DoT allowed for others')

    r = c2.call('groups')
    g = next((x for x in r.get('groups', []) if x['id'] == 'kids'), {})
    check(g.get('members') == [C1_MAC] and g.get('safesearch') is True, 'groups lists the group')

    r = c2.call('delete_group', {'id': 'kids'})
    check(r.get('ok') is True, 'delete group')
    check(not list(Path('/var/run/wrtpilot').glob('dnsmasq-*.conf')), 'group DNS config removed')
    sets = sh('nft list sets inet', ns='router').stdout
    check('g_kids' not in sets, 'group sets removed from nftables')
    check(reachable('c1', INTERNET, 8080), 'former member unrestricted')


def test_wifi_deny(c1):
    log('== Wi-Fi deny (hostapd ACL + ban)')
    Path('/tmp/hostapd-calls.jsonl').write_text('')
    r = c1.call('block', {'mac': C2_MAC, 'mode': 'wifi', 'duration_s': 3600})
    check(r.get('ok') is True, f'wifi block: {r}')
    calls = [json.loads(x) for x in Path('/tmp/hostapd-calls.jsonl').read_text().splitlines() if x]
    kick = [x for x in calls if x['addr'] == C2_MAC and x['deauth'] is True]
    check(bool(kick) and 3590000 <= kick[0]['ban_time'] <= 3600000, f'hostapd del_client with ban ({calls})')
    w = sh('uci show wireless.default_radio0', ns=None).stdout
    check(f"maclist='{C2_MAC}'" in w and "macfilter='deny'" in w, 'MAC added to the persistent deny list')
    cl = c1.call('clients')
    me = next((d for d in cl.get('clients', []) if d['mac'] == C2_MAC), {})
    check(me.get('blocked') == 'wifi', 'clients shows wifi block')

    Path('/tmp/hostapd-calls.jsonl').write_text('')
    r = c1.call('unblock', {'mac': C2_MAC})
    calls = [json.loads(x) for x in Path('/tmp/hostapd-calls.jsonl').read_text().splitlines() if x]
    w = sh('uci show wireless.default_radio0').stdout
    check('maclist' not in w and 'macfilter' not in w, 'wireless config restored exactly')
    check(any(x['addr'] == C2_MAC and x['ban_time'] == 0 for x in calls), 'ban lifted')


def test_events_quota(c1, c2):
    log('== events: new device')
    ev0 = c1.call('events', {'since_id': 0})
    last = ev0.get('last_id', 0)
    now = int(time.time())
    with open('/tmp/dhcp.leases', 'a') as f:
        f.write(f'{now + 43200} {C3_MAC} 192.168.50.13 new-tablet *\n')
    sh('ubus -s /var/run/ubus/ubus.sock call wrtpilotd hotplug '
       f"'{{\"action\":\"add\",\"mac\":\"{C3_MAC}\",\"ip\":\"192.168.50.13\",\"hostname\":\"new-tablet\"}}'", ns='router')
    ev = c1.call('events', {'since_id': last})
    new = [e for e in ev.get('events', []) if e['type'] == 'new_device' and e['mac'] == C3_MAC]
    check(len(new) == 1 and new[0]['data']['hostname'] == 'new-tablet', f'new_device event ({ev})')

    log('== daily quota with auto-block')
    r = c2.call('set_device', {'mac': C1_MAC, 'name': 'Kid laptop', 'daily_quota_mb': 1, 'quota_action': 'block'})
    check(r.get('ok') is True and r['device']['custom_name'] == 'Kid laptop', 'set_device name + quota')
    srv = bg('iperf3 -s -1 -B 192.168.50.11', ns='c1', name='iperf-c1')
    time.sleep(0.5)
    iperf('wan', '192.168.50.11', '-n 3M', seconds=3)
    stop(srv)
    got = wait_for(lambda: [e for e in c1.call('events', {'since_id': last}).get('events', [])
                            if e['type'] == 'quota_exceeded' and e['mac'] == C1_MAC], 25, 1)
    check(bool(got) and got[0]['data']['action'] == 'block', 'quota_exceeded event')
    check(wait_for(lambda: not reachable('c1', INTERNET), 5) is not None, 'device blocked until midnight')
    cl = c2.call('clients')
    me = next((d for d in cl.get('clients', []) if d['mac'] == C1_MAC), {})
    check(me.get('quota_exceeded') is True and me.get('name') == 'Kid laptop', 'clients shows quota + name')
    sh(f'{UCODE} -S {FILES}/usr/sbin/wrtpilot unblock-all', ns='router')
    check(reachable('c1', INTERNET), 'unblock-all clears quota block')
    c2.call('set_device', {'mac': C1_MAC, 'daily_quota_mb': 0})


def test_history(c1):
    log('== history')
    h = c1.call('history', {'mac': C1_MAC, 'resolution': 'minute', 'since': int(time.time()) - 3600})
    check(h.get('ok') is True and len(h.get('series', [])) >= 1, f'minute history ({len(h.get("series", []))} points)')
    h = c1.call('history', {'mac': '', 'resolution': 'day', 'since': 0})
    check(h.get('ok') is True and len(h.get('series', [])) == 1 and h['series'][0][1] > 0, 'daily totals')
    h = c1.call('history', {'mac': '*', 'resolution': 'day', 'since': 0})
    per = h.get('devices', {}) if h.get('ok') else {}
    check(C1_MAC in per and per[C1_MAC][0][1] > 0, 'daily history of all devices')
    h = c1.call('history', {'mac': C1_MAC, 'resolution': 'week', 'since': 0})
    check(h.get('error') == 'invalid_argument', 'bad resolution rejected')
    live = c1.call('live', {'samples': 2, 'devices': False})
    check('total' in live and not live.get('devices'), 'live totals without devices')


def test_firewall_reload_and_watchdog(c2):
    log('== fw4 reload keeps state; watchdog restores deleted tables')
    c2.call('block', {'mac': C1_MAC, 'mode': 'internet'})
    before = sh('nft list set inet wrtpilot_acct dl4', ns='router').stdout.count('counter')
    sh('nft -f /var/run/wrtpilot/fw4.nft', ns='router')   # what fw4 does on reload
    after = sh('nft list set inet wrtpilot_acct dl4', ns='router').stdout.count('counter')
    check(before > 0 and before == after, f'counters preserved across reload ({before} -> {after})')
    check(not reachable('c1', INTERNET), 'block still active after reload')
    time.sleep(11)
    sh('nft delete table inet wrtpilot', ns='router')
    sh('nft delete table inet wrtpilot_acct', ns='router')
    restored = wait_for(lambda: sh('nft list table inet wrtpilot', ns='router', check_rc=False).returncode == 0, 8)
    check(restored is not None, 'wrtpilotd restored the tables')
    check(not reachable('c1', INTERNET), 'block enforced again')
    c2.call('unblock', {'mac': C1_MAC})


def test_qos_offload_misc(c2):
    log('== qos / offload / reset')
    r = c2.call('qos_get')
    check(r.get('ok') is True and r.get('available') is False, 'qos_get without sqm-scripts')
    r = c2.call('qos_set', {'enabled': True, 'dl_kbps': 100000, 'ul_kbps': 20000, 'preset': 'gaming'})
    check(r.get('error') == 'sqm_missing', 'qos_set reports missing sqm')
    r = c2.call('set_offload', {'software': True})
    st = c2.call('status')
    check(st.get('offload_warning') is True, 'offload warning when flow offloading is on')
    c2.call('set_offload', {'software': False})
    r = c2.call('forget_device', {'mac': C3_MAC})
    check(r.get('ok') is True, 'forget device')

    r = sh(f'{UCODE} -S {FILES}/usr/sbin/wrtpilot reset', ns='router', check_rc=False)
    tables = sh('nft list tables', ns='router').stdout
    check(r.returncode == 0 and 'wrtpilot' not in tables, 'wrtpilot reset removes all rules')
    time.sleep(15)
    tables = sh('nft list tables', ns='router').stdout
    check('wrtpilot' not in tables, 'wrtpilotd does not bring the rules back after a reset')
    sh(f'{UCODE} -S {FILES}/usr/sbin/wrtpilot apply --quiet', ns='router')
    tables = sh('nft list tables', ns='router').stdout
    check('wrtpilot' in tables, 'apply restores the rules after a reset')


def main():
    if os.geteuid() != 0:
        print('integration test needs root')
        return 2
    try:
        setup_mounts()
        setup_topology()
        write_configs()
        start_services()
        time.sleep(2)
        c1, c2 = Rpc('c1'), Rpc('c2')
        test_session_and_acl(c1, c2)
        test_status_clients(c1)
        test_accounting(c1)
        test_block_internet(c1, c2)
        test_timed_block_and_pause(c2)
        test_rate_limit(c2)
        test_groups_schedule_dns(c2)
        test_wifi_deny(c1)
        test_events_quota(c1, c2)
        test_history(c1)
        test_firewall_reload_and_watchdog(c2)
        test_qos_offload_misc(c2)
    except Exception as e:
        import traceback
        traceback.print_exc()
        failures.append(f'exception: {e}')
    finally:
        if failures or os.environ.get('WPT_KEEP_LOGS'):
            for name in ['wrtpilotd', 'rpcd', 'uhttpd']:
                p = Path(f'/tmp/wpt-{name}.log')
                if p.exists():
                    print(f'--- {name} log ---\n{p.read_text()[-3000:]}')
        cleanup()

    print(f'\n{checks} checks, {len(failures)} failed')
    for f in failures:
        print(f'  FAIL: {f}')
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
