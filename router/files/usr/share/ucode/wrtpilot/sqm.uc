// WrtPilot - global QoS through sqm-scripts (/etc/config/sqm, cake).

'use strict';

import { sys } from 'wrtpilot.util';
import { cursor, CONFIG } from 'wrtpilot.config';

// cake options per preset (ingress = download on the WAN ifb, egress = upload)
export const PRESETS = {
	default: {
		script: 'piece_of_cake.qos',
		ingress: 'nat dual-dsthost ingress',
		egress: 'nat dual-srchost'
	},
	gaming: {
		script: 'layer_cake.qos',
		ingress: 'diffserv4 nat dual-dsthost ingress',
		egress: 'diffserv4 nat dual-srchost ack-filter'
	},
	streaming: {
		script: 'layer_cake.qos',
		ingress: 'diffserv3 nat dual-dsthost ingress',
		egress: 'diffserv3 nat dual-srchost'
	}
};

export function available() {
	return sys.exists('/etc/init.d/sqm');
};

// Find the queue section for the WAN device: an existing one on that
// interface, else our own "wrtpilot" section.
function find_section(c, wan_dev) {
	let found = null;

	c.foreach('sqm', 'queue', function(s) {
		if (found == null && wan_dev && s.interface == wan_dev)
			found = s['.name'];
	});

	if (found == null && c.get('sqm', 'wrtpilot') == 'queue')
		found = 'wrtpilot';

	return found;
}

export function get(wan_dev) {
	if (!available())
		return { available: false, enabled: false, dl_kbps: 0, ul_kbps: 0, preset: 'default', interface: wan_dev };

	let c = cursor();

	c.load('sqm');
	c.load(CONFIG);

	let sid = find_section(c, wan_dev);
	let s = sid ? c.get_all('sqm', sid) : {};
	let preset = c.get(CONFIG, 'main', 'qos_preset');

	return {
		available: true,
		enabled: s.enabled == '1',
		dl_kbps: int(s.download ?? 0) || 0,
		ul_kbps: int(s.upload ?? 0) || 0,
		preset: PRESETS[preset] ? preset : 'default',
		interface: s.interface ?? wan_dev,
		qdisc: s.qdisc ?? 'cake'
	};
};

export function set(wan_dev, opts) {
	let c = cursor();

	c.load('sqm');
	c.load(CONFIG);

	let sid = find_section(c, wan_dev);

	if (!sid) {
		sid = 'wrtpilot';
		c.set('sqm', sid, 'queue');
	}

	let p = PRESETS[opts.preset] ?? PRESETS.default;

	c.set('sqm', sid, 'enabled', opts.enabled ? '1' : '0');

	if (wan_dev)
		c.set('sqm', sid, 'interface', wan_dev);

	if (opts.dl_kbps > 0)
		c.set('sqm', sid, 'download', `${opts.dl_kbps}`);

	if (opts.ul_kbps > 0)
		c.set('sqm', sid, 'upload', `${opts.ul_kbps}`);

	c.set('sqm', sid, 'qdisc', 'cake');
	c.set('sqm', sid, 'script', p.script);
	c.set('sqm', sid, 'qdisc_advanced', '1');
	c.set('sqm', sid, 'qdisc_really_really_advanced', '1');
	c.set('sqm', sid, 'iqdisc_opts', p.ingress);
	c.set('sqm', sid, 'eqdisc_opts', p.egress);

	// never run two SQM instances on the same interface
	c.foreach('sqm', 'queue', function(s) {
		if (s['.name'] != sid && s.interface == wan_dev && s.enabled == '1')
			c.set('sqm', s['.name'], 'enabled', '0');
	});

	c.commit('sqm');

	c.set(CONFIG, 'main', 'qos_preset', opts.preset ?? 'default');
	c.commit(CONFIG);

	if (opts.enabled)
		sys.run([ '/etc/init.d/sqm', 'enable' ]);

	sys.run([ '/etc/init.d/sqm', 'restart' ]);

	return get(wan_dev);
};
