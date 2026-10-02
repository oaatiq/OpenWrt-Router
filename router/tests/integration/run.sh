#!/bin/sh
# End-to-end test in network namespaces (needs root). See test_netns.py.
exec unshare -m --propagation private python3 "$(dirname "$0")/test_netns.py" "$@"
