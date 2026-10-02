#!/usr/bin/env python3
"""Installs WrtPilot on a router exactly like the app's "Install WrtPilot"
button (android/core/network/.../AgentInstaller.kt), with nothing but the
root login over the JSON-RPC API and the permissions LuCI gives it: a
one-time job in root's crontab (System > Scheduled Tasks) runs the installer,
the system log (Status > System Log) shows its progress, then the package's
"wrtpilot-setup" ACL lets root read the login of the "wrtpilot" user.

Usage: app_install.py <ubus-url> <root-password> [installer-url]
Prints the wrtpilot password on the last line.
"""
import json
import secrets
import sys
import time
import urllib.request

URL, ROOT_PW = sys.argv[1], sys.argv[2]
INSTALLER = sys.argv[3] if len(sys.argv) > 3 else \
    'https://github.com/oaatiq/OpenWrt-Router/releases/download/router-latest/install.sh'
ANON = '0' * 32
CRONTAB, MARKER, TAG = '/etc/crontabs/root', 'wrtpilot-app-install', 'wrtpilot-install: '
RUN_ID = secrets.token_hex(4)
LOG_COMMANDS = [('/usr/libexec/syslog-wrapper', []), ('/sbin/logread', ['-e', '^']), ('/usr/sbin/logread', ['-e', '^'])]


def rpc(sid, obj, method, args):
    body = json.dumps({'jsonrpc': '2.0', 'id': 1, 'method': 'call', 'params': [sid, obj, method, args]}).encode()
    req = urllib.request.Request(URL, body, {'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=30) as r:
        resp = json.load(r)
    if 'error' in resp:
        return resp['error'].get('code'), None
    res = resp['result']
    return res[0], (res[1] if len(res) > 1 else None)


def login(user='root', pw=ROOT_PW):
    code, data = rpc(ANON, 'session', 'login', {'username': user, 'password': pw})
    if code != 0:
        sys.exit(f'{user} login failed ({code})')
    return data['ubus_rpc_session']


def read(sid, path):
    code, data = rpc(sid, 'file', 'read', {'path': path})
    return (data or {}).get('data', '') if code == 0 else None


def run(sid, cmd, params):
    return rpc(sid, 'file', 'exec', {'command': cmd, 'params': params})


def syslog(sid):
    for cmd, params in LOG_COMMANDS:
        code, data = run(sid, cmd, params)
        if code == 0:
            return data.get('stdout', '')
    return None


sid = login()
code, _ = run(sid, '/bin/sh', ['-c', 'true'])
print(f'a shell through the API: status {code} (no shell for root, as on any stock OpenWrt)', flush=True)

before = read(sid, CRONTAB)
job = f"* * * * * sed -i /{MARKER}/d {CRONTAB};wget -qO /tmp/wrtpilot-install.sh '{INSTALLER}'&&sh /tmp/wrtpilot-install.sh --app {RUN_ID}"
lines = [l for l in (before or '').splitlines() if l.strip() and MARKER not in l]
code, _ = rpc(sid, 'file', 'write', {'path': CRONTAB, 'data': '\n'.join(lines + [job]) + '\n'})
print(f'crontab write -> {code}', flush=True)
if code != 0:
    sys.exit('LuCI does not let root edit the crontab')
code, res = run(sid, '/etc/init.d/cron', ['reload'])
print(f'cron reload -> {code} {res}', flush=True)
if code != 0:
    sys.exit('LuCI does not let root reload cron')

deadline = time.time() + 480
rc, seen = None, 0
while time.time() < deadline and rc is None:
    time.sleep(3)
    try:
        log = syslog(sid)
        if log is None:
            sid = login()          # rpcd restarted by the installer: sessions are gone
            continue
    except Exception as e:         # uhttpd restarting
        print(f'  (router busy: {e})', flush=True)
        continue
    msgs = [l.split(TAG, 1)[1] for l in log.splitlines() if TAG in l]
    if f'started {RUN_ID}' not in msgs:
        continue
    mine = msgs[len(msgs) - 1 - msgs[::-1].index(f'started {RUN_ID}') + 1:]
    for m in mine[seen:]:
        print(f'  | {m}', flush=True)
        if m.startswith(f'finished {RUN_ID} rc='):
            rc = int(m.split('rc=')[1])
    seen = len(mine)

if rc != 0:
    sys.exit(f'installer failed or timed out (rc={rc!r})')
after = read(sid, CRONTAB)
print(f'crontab after the job: {after!r} (before: {before!r})', flush=True)
if after is not None and MARKER in after:
    sys.exit('the job did not remove itself from the crontab')

sid = login()                      # ACLs are granted at login: see the package's
pw = (read(sid, '/etc/wrtpilot/initial_password') or '').strip()
if not pw:
    pw = 'App' + secrets.token_hex(8)
    code, res = run(sid, '/usr/sbin/wrtpilot', ['passwd', pw])
    print(f'no initial password: wrtpilot passwd -> {code} {res}', flush=True)
    if code != 0 or res.get('code') != 0:
        sys.exit('could not set the wrtpilot password')
if after == '' and before is None:
    rpc(sid, 'file', 'remove', {'path': CRONTAB})
    run(sid, '/etc/init.d/cron', ['reload'])

wsid = login('wrtpilot', pw)
code, st = rpc(wsid, 'wrtpilot', 'status', {})
if code != 0 or not st.get('ok'):
    sys.exit(f'wrtpilot.status failed ({code})')
print(f"installed through the API: agent {st['agent_version']} on OpenWrt {st['openwrt_version']}")
print(pw)
