// WrtPilot rpcd plugin: the "wrtpilot" ubus object, reachable over HTTP at
// POST /ubus (JSON-RPC) with an rpcd session. See docs/API.md.
//
// rpcd validates argument types strictly against the hints below:
// '' = string, 0 = int32, true = boolean, [] = array.

'use strict';

import * as api from 'wrtpilot.api';

function wrap(fn) {
	return function(req) {
		try {
			return fn(req.args ?? {});
		}
		catch (e) {
			warn(`wrtpilot: ${req.info?.method ?? '?'} failed: ${e.message}\n`);

			return { ok: false, error: 'internal', message: e.message };
		}
	};
}

const methods = {
	status:        { call: wrap(() => api.status()) },
	clients:       { call: wrap(() => api.clients()) },
	live:          { args: { macs: [], samples: 0, devices: true }, call: wrap(api.live) },
	history:       { args: { mac: '', resolution: '', since: 0 }, call: wrap(api.history) },
	events:        { args: { since_id: 0 }, call: wrap(api.events) },

	set_device:    { args: { mac: '', name: '', group: '', daily_quota_mb: 0, quota_action: '' }, call: wrap(api.set_device) },
	forget_device: { args: { mac: '' }, call: wrap(api.forget_device) },
	block:         { args: { mac: '', mode: '', duration_s: 0 }, call: wrap(api.block) },
	unblock:       { args: { mac: '' }, call: wrap(api.unblock) },
	set_limit:     { args: { mac: '', group: '', dl_kbps: 0, ul_kbps: 0 }, call: wrap(api.set_limit) },

	groups:        { call: wrap(() => api.groups()) },
	set_group:     {
		args: {
			id: '', name: '', dns_filter: '', dns_custom: [], safesearch: true,
			blocklist: [], schedule: [], schedule_enabled: true, members: [],
			dl_kbps: 0, ul_kbps: 0
		},
		call: wrap(api.set_group)
	},
	delete_group:  { args: { id: '' }, call: wrap(api.delete_group) },
	pause:         { args: { group: '', mac: '', duration_s: 0, until: '' }, call: wrap(api.pause) },
	resume:        { args: { group: '', mac: '' }, call: wrap(api.resume) },

	qos_get:       { call: wrap(() => api.qos_get()) },
	qos_set:       { args: { enabled: true, dl_kbps: 0, ul_kbps: 0, preset: '' }, call: wrap(api.qos_set) },
	set_offload:   { args: { software: true, hardware: true }, call: wrap(api.set_offload) },

	apply:         { call: wrap(() => api.apply()) },
	version:       { call: wrap(() => api.version()) }
};

return { wrtpilot: methods };
