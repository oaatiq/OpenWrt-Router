// Stand-in for hostapd's ubus object (hostapd.wlan0) used by the
// integration test: reports configured stations and records del_client.
'use strict';

import * as uloop from 'uloop';
import * as ubus from 'ubus';
import * as fs from 'fs';

let clients = json(getenv('FAKE_CLIENTS') ?? '{}');
let logfile = getenv('FAKE_LOG') ?? '/tmp/hostapd-calls.jsonl';

uloop.init();

let conn = ubus.connect();

conn.publish('hostapd.wlan0', {
	get_status: {
		call: () => ({ status: 'ENABLED', ssid: 'TestNet', freq: 5180 })
	},
	get_clients: {
		call: function() {
			let res = {};

			for (let mac, sig in clients)
				res[mac] = { auth: true, assoc: true, authorized: true, signal: sig };

			return { freq: 5180, clients: res };
		}
	},
	del_client: {
		args: { addr: '', reason: 0, deauth: true, ban_time: 0 },
		call: function(req) {
			let fd = fs.open(logfile, 'a');

			fd.write(sprintf('%J\n', req.args));
			fd.close();

			if (req.args.deauth)
				delete clients[req.args.addr];

			return {};
		}
	}
});

uloop.run();
