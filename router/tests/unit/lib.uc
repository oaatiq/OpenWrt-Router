// Minimal assertion helpers for the ucode unit tests.
'use strict';

let state = { count: 0, failed: 0, name: '' };

export function suite(name) {
	state.name = name;
};

function fail(msg, a, b) {
	state.failed++;
	warn(sprintf('  FAIL [%s] %s\n    got:      %J\n    expected: %J\n', state.name, msg, a, b));
}

export function eq(a, b, msg) {
	state.count++;

	if (sprintf('%J', a) != sprintf('%J', b))
		fail(msg ?? 'eq', a, b);
};

export function ok(v, msg) {
	state.count++;

	if (!v)
		fail(msg ?? 'ok', v, true);
};

export function contains(hay, needle, msg) {
	state.count++;

	if (index(hay ?? '', needle) < 0)
		fail(msg ?? `contains "${needle}"`, hay, needle);
};

export function not_contains(hay, needle, msg) {
	state.count++;

	if (index(hay ?? '', needle) >= 0)
		fail(msg ?? `does not contain "${needle}"`, hay, needle);
};

export function done() {
	print(sprintf('%-14s %3d checks, %d failed\n', state.name, state.count, state.failed));
	exit(state.failed ? 1 : 0);
};
