// WrtPilot - per-device rate limiting with tc.
//
// Download: HTB on the LAN bridge egress, classified by destination MAC.
// Upload:   LAN bridge ingress -> ifb device (only frames from limited MACs
//           are redirected), HTB on the ifb, classified by source MAC.
// MAC matching (u32 on the ethernet header) covers IPv4 and IPv6 alike and
// keeps working when nftables marks are not available.
// A group limit is an HTB parent class shared by all its members.

'use strict';

const DEFAULT_MINOR = 'ffff';

function hexid(n) {
	return sprintf('%x', n);
}

export function ifb_name(index) {
	return `wp-ifb${index}`;
};

// Build the class tree for one direction ("dl" or "ul").
function classes(model, dir) {
	let key = dir + '_kbps';
	let groups = {}, devs = [];
	let gi = 0;

	for (let g in model.groups)
		if (g[key] > 0)
			groups[g.id] = { minor: hexid(0x100 + gi++), rate: g[key], members: 0 };

	for (let d in model.devices) {
		let g = groups[d.group];

		if (d[key] > 0 || g) {
			if (g)
				g.members++;

			push(devs, { mac: d.mac, group: g ? d.group : null, limit: d[key] });
		}
	}

	let out = { groups: [], devices: [] };

	for (let id, g in groups) {
		if (!g.members)
			continue;

		push(out.groups, { id: id, minor: g.minor, rate: g.rate });
	}

	let di = 0;

	for (let d in devs) {
		let g = d.group ? groups[d.group] : null;
		let ceil = g ? ((d.limit > 0 && d.limit < g.rate) ? d.limit : g.rate) : d.limit;
		let rate = g ? max(8, min(ceil, g.rate / g.members)) : ceil;

		push(out.devices, {
			mac: d.mac,
			minor: hexid(0x1000 + di++),
			parent: g ? g.minor : null,
			rate: rate,
			ceil: ceil
		});
	}

	return out;
}

function htb_tree(lines, dev, tree, match_dir, leaf) {
	push(lines,
		`qdisc add dev ${dev} root handle 1: htb default ${DEFAULT_MINOR}`,
		`class add dev ${dev} parent 1: classid 1:${DEFAULT_MINOR} htb rate 10gbit quantum 1514`);

	if (leaf)
		push(lines, `qdisc add dev ${dev} parent 1:${DEFAULT_MINOR} ${leaf}`);

	for (let g in tree.groups)
		push(lines, `class add dev ${dev} parent 1: classid 1:${g.minor} htb rate ${g.rate}kbit ceil ${g.rate}kbit quantum 1514`);

	for (let d in tree.devices) {
		push(lines, `class add dev ${dev} parent 1:${d.parent ?? ''} classid 1:${d.minor} htb rate ${d.rate}kbit ceil ${d.ceil}kbit quantum 1514`);

		if (leaf)
			push(lines, `qdisc add dev ${dev} parent 1:${d.minor} ${leaf}`);

		push(lines, `filter add dev ${dev} parent 1: protocol all prio 1 u32 match ether ${match_dir} ${d.mac} flowid 1:${d.minor}`);
	}
}

// model: { lan: [devs], groups: [{id, dl_kbps, ul_kbps}],
//          devices: [{mac, group, dl_kbps, ul_kbps}], ifb: bool,
//          leaf: "fq_codel" | null }
// Returns { active, links: [ifb names], batch, ul_limited: bool }
export function generate(model) {
	let dl = classes(model, 'dl');
	let ul = classes(model, 'ul');
	let lines = [];
	let links = [];
	let use_ifb = model.ifb && length(ul.devices) > 0;

	for (let i, dev in model.lan) {
		if (length(dl.devices))
			htb_tree(lines, dev, dl, 'dst', model.leaf);

		if (use_ifb) {
			let ifb = ifb_name(i);

			push(links, ifb);
			push(lines, `qdisc add dev ${dev} handle ffff: ingress`);

			for (let d in ul.devices)
				push(lines, `filter add dev ${dev} parent ffff: protocol all prio 1 u32 match ether src ${d.mac} action mirred egress redirect dev ${ifb}`);

			htb_tree(lines, ifb, ul, 'src', model.leaf);
		}
	}

	return {
		active: length(lines) > 0,
		links: links,
		batch: length(lines) ? join('\n', lines) + '\n' : '',
		dl_devices: length(dl.devices),
		ul_devices: length(ul.devices),
		ul_shaped: use_ifb
	};
};

// Commands removing everything WrtPilot may have installed.
export function teardown(lan, links) {
	let lines = [];

	for (let dev in lan)
		push(lines, `qdisc del dev ${dev} root`, `qdisc del dev ${dev} ingress`);

	return {
		batch: join('\n', lines) + '\n',
		links: links ?? []
	};
};
