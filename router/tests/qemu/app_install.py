#!/usr/bin/env python3
"""Installs WrtPilot on a router exactly like the app's "Install WrtPilot"
button (android/core/network/.../AgentInstaller.kt): root session over the
JSON-RPC API, file.exec of the published installer in the background, polling
its log with file.read, then reading the login of the "wrtpilot" user.

Usage: app_install.py <ubus-url> <root-password>
Prints the wrtpilot password on the last line.
"""
import json
import sys
import time
import urllib.request

URL, ROOT_PW = sys.argv[1], sys.argv[2]
ANON = '0' * 32
INSTALLER = 'https://github.com/oaatiq/OpenWrt-Router/releases/download/router-latest/install.sh'
LOG, RC, SCRIPT = '/tmp/wrtpilot-install.log', '/tmp/wrtpilot-install.rc', '/tmp/wrtpilot-install.sh'


def rpc(sid, obj, method, args):
    body = json.dumps({'jsonrpc': '2.0', 'id': 1, 'method': 'call', 'params': [sid, obj, method, args]}).encode()
    req = urllib.request.Request(URL, body, {'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=30) as r:
        resp = json.load(r)
    if 'error' in resp:
        return resp['error'].get('code'), None
    res = resp['result']
    return res[0], (res[1] if len(res) > 1 else None)


def login():
    code, data = rpc(ANON, 'session', 'login', {'username': 'root', 'password': ROOT_PW})
    if code != 0:
        sys.exit(f'root login failed ({code})')
    return data['ubus_rpc_session']


def read(sid, path):
    code, data = rpc(sid, 'file', 'read', {'path': path})
    return data.get('data') if code == 0 and data else None


sid = login()
script = (f"rm -f {LOG} {RC}\n"
          f"( wget -q -O {SCRIPT} '{INSTALLER}' && sh {SCRIPT}; echo $? > {RC} ) > {LOG} 2>&1 < /dev/null &")
code, _ = rpc(sid, 'file', 'exec', {'command': '/bin/sh', 'params': ['-c', script]})
print(f'file.exec -> {code}', flush=True)
if code != 0:
    sys.exit('could not start the installer')

deadline = time.time() + 360
rc = None
while time.time() < deadline:
    time.sleep(2)
    try:
        rc = read(sid, RC)
        if rc is None and rpc(sid, 'session', 'access', {})[0] != 0:
            sid = login()          # rpcd restarted by the installer: sessions are gone
    except Exception as e:         # uhttpd restarting
        print(f'  (router busy: {e})', flush=True)
        continue
    if rc and rc.strip():
        break

log = read(sid, LOG) or ''
print('--- installer log ---\n' + log.rstrip() + '\n---', flush=True)
if not rc or rc.strip() != '0':
    sys.exit(f'installer failed or timed out (rc={rc!r})')

pw = (read(sid, '/etc/wrtpilot/initial_password') or '').strip()
if not pw:
    sys.exit('no initial password')
code, data = rpc(ANON, 'session', 'login', {'username': 'wrtpilot', 'password': pw})
if code != 0:
    sys.exit(f'wrtpilot login failed ({code})')
code, st = rpc(data['ubus_rpc_session'], 'wrtpilot', 'status', {})
if code != 0 or not st.get('ok'):
    sys.exit(f'wrtpilot.status failed ({code})')
print(f"installed through the API: agent {st['agent_version']} on OpenWrt {st['openwrt_version']}")
print(pw)
