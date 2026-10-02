#!/usr/bin/env python3
"""WrtPilot API smoke test against a real OpenWrt system (QEMU in CI).

Talks to uhttpd's /ubus JSON-RPC endpoint exactly like the Android app:
session login as the dedicated "wrtpilot" user, then wrtpilot.* calls.

Usage: smoke.py <ubus-url> <password>
"""
import json
import sys
import time
import urllib.request

URL, PASSWORD = sys.argv[1], sys.argv[2]
ANON = '0' * 32
FAKE_MAC = '02:00:00:00:00:99'

failures = []


def check(cond, msg):
    print(('  ok   ' if cond else '  FAIL ') + msg, flush=True)
    if not cond:
        failures.append(msg)
    return cond


def rpc(sid, obj, method, args=None):
    body = json.dumps({'jsonrpc': '2.0', 'id': 1, 'method': 'call',
                       'params': [sid, obj, method, args or {}]}).encode()
    req = urllib.request.Request(URL, body, {'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=60) as r:
        resp = json.load(r)
    if 'error' in resp:
        return resp['error'].get('code'), None
    res = resp['result']
    return res[0], (res[1] if len(res) > 1 else None)


def call(sid, method, args=None):
    code, data = rpc(sid, 'wrtpilot', method, args)
    if code != 0:
        raise RuntimeError(f'wrtpilot.{method} failed: {code}')
    return data or {}


def main():
    print('== login')
    code, _ = rpc(ANON, 'session', 'login', {'username': 'wrtpilot', 'password': 'wrong-password'})
    check(code == 6, f'wrong password rejected ({code})')
    code, data = rpc(ANON, 'session', 'login', {'username': 'wrtpilot', 'password': PASSWORD})
    check(code == 0, 'login as wrtpilot')
    sid = data['ubus_rpc_session']

    print('== least privilege')
    code, _ = rpc(sid, 'file', 'exec', {'command': '/bin/ls'})
    check(code not in (0, None), f'file.exec denied ({code})')
    code, _ = rpc(sid, 'uci', 'set', {'config': 'network', 'section': 'lan', 'values': {'proto': 'dhcp'}})
    check(code not in (0, None), f'uci.set denied ({code})')

    print('== status')
    st = call(sid, 'status')
    check(st.get('agent_version', '') != '', f"agent {st.get('agent_version')} on OpenWrt {st.get('openwrt_version')}")
    check(st.get('enabled') is True, 'agent enabled')
    for _ in range(30):
        if st.get('collector', {}).get('running'):
            break
        time.sleep(1)
        st = call(sid, 'status')
    check(st.get('collector', {}).get('running') is True, 'statistics collector running')
    caps = st.get('capabilities', {})
    print(f'     capabilities: {caps}')
    check(caps.get('nftset') is not None, 'capabilities reported')

    print('== clients / live')
    cl = call(sid, 'clients')
    check(isinstance(cl.get('clients'), list), f"clients listed ({len(cl.get('clients', []))})")
    live = call(sid, 'live', {'samples': 3, 'devices': False})
    check('total' in live and 'ts' in live, 'live totals')

    print('== groups')
    g = call(sid, 'set_group', {'name': 'Kids', 'schedule': ['mon-fri 07:00-21:00', 'sat,sun 08:00-22:00'],
                                'schedule_enabled': True, 'dns_filter': 'cloudflare_family',
                                'safesearch': True, 'blocklist': ['example.com']})
    gid = g.get('group', {}).get('id')
    check(bool(gid), f'group created ({gid})')
    gl = call(sid, 'groups')
    check(any(x.get('id') == gid for x in gl.get('groups', [])), 'group listed')
    p = call(sid, 'pause', {'group': gid, 'duration_s': 600})
    check(p.get('paused_until', 0) > time.time(), 'group paused for 10 minutes')
    call(sid, 'resume', {'group': gid})
    gl = call(sid, 'groups')
    me = next((x for x in gl.get('groups', []) if x.get('id') == gid), {})
    check(not me.get('paused'), 'group resumed')

    print('== devices')
    b = call(sid, 'block', {'mac': FAKE_MAC, 'mode': 'internet', 'duration_s': 600})
    check(b.get('ok') is not False, 'block a device for 10 minutes')
    r = call(sid, 'set_limit', {'mac': FAKE_MAC, 'dl_kbps': 2000, 'ul_kbps': 500})
    check(r.get('ok') is not False, f"speed limit (coarse={r.get('coarse')})")
    call(sid, 'unblock', {'mac': FAKE_MAC})
    call(sid, 'set_limit', {'mac': FAKE_MAC, 'dl_kbps': 0, 'ul_kbps': 0})
    call(sid, 'delete_group', {'id': gid})

    print('== qos / events')
    q = call(sid, 'qos_get')
    check('available' in q, f"qos_get (sqm available={q.get('available')})")
    ev = call(sid, 'events', {'since_id': 0})
    check(isinstance(ev.get('events'), list), f"events ({len(ev.get('events', []))})")

    print(f'\n{len(failures)} failed')
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
