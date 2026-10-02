# WrtPilot package feed keys (public)

| File | Used by | Install on the router as |
| --- | --- | --- |
| `42af730eb294d6fe` | opkg (OpenWrt 23.05, 24.10), usign | `/etc/opkg/keys/42af730eb294d6fe` |
| `wrtpilot-feed.pem` | apk (OpenWrt 25.12+), ECDSA P-256 | `/etc/apk/keys/wrtpilot-feed.pem` |

`install.sh` contains the same keys. The matching private keys are only in the
repository secrets `WRTPILOT_FEED_USIGN_KEY` and `WRTPILOT_FEED_APK_KEY`, used
by CI to sign the feed index.
