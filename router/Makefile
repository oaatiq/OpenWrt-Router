#
# WrtPilot router agent - OpenWrt package
#
# Build with the OpenWrt SDK:
#   ln -s /path/to/OpenWrt-Router/router package/wrtpilot
#   make package/wrtpilot/compile V=s
#

include $(TOPDIR)/rules.mk

PKG_NAME:=wrtpilot
PKG_VERSION:=0.1.0
PKG_RELEASE:=1

PKG_MAINTAINER:=WrtPilot contributors
PKG_LICENSE:=MIT

include $(INCLUDE_DIR)/package.mk

define Package/wrtpilot
  SECTION:=net
  CATEGORY:=Network
  TITLE:=WrtPilot - per-device control API for the WrtPilot app
  URL:=https://github.com/oaatiq/OpenWrt-Router
  PKGARCH:=all
  DEPENDS:=+rpcd +rpcd-mod-ucode +uhttpd +uhttpd-mod-ubus \
	+ucode +ucode-mod-fs +ucode-mod-uci +ucode-mod-ubus +ucode-mod-uloop \
	+nftables-json +kmod-nft-core +kmod-nft-nat
endef

define Package/wrtpilot/description
  Router agent for the WrtPilot Android app: client list, per-device
  bandwidth accounting, internet / Wi-Fi blocking, per-device rate limits,
  SQM control and parental control (schedules, pause, DNS filtering,
  domain blocklists), exposed as the "wrtpilot" ubus object over rpcd.

  Optional (recommended): tc-tiny kmod-sched-core kmod-ifb for exact
  rate limiting, sqm-scripts for global QoS.
endef

define Package/wrtpilot/conffiles
/etc/config/wrtpilot
/etc/wrtpilot/
endef

define Build/Compile
endef

define Package/wrtpilot/install
	$(INSTALL_DIR) $(1)/etc/config $(1)/etc/init.d $(1)/etc/uci-defaults
	$(INSTALL_DIR) $(1)/etc/hotplug.d/dhcp $(1)/etc/hotplug.d/iface
	$(INSTALL_DIR) $(1)/lib/upgrade/keep.d
	$(INSTALL_DIR) $(1)/usr/sbin
	$(INSTALL_DIR) $(1)/usr/share/rpcd/ucode $(1)/usr/share/rpcd/acl.d
	$(INSTALL_DIR) $(1)/usr/share/ucode/wrtpilot
	$(INSTALL_DIR) $(1)/usr/share/nftables.d/ruleset-post

	$(INSTALL_CONF) ./files/etc/config/wrtpilot $(1)/etc/config/wrtpilot
	$(INSTALL_BIN) ./files/etc/init.d/wrtpilotd $(1)/etc/init.d/wrtpilotd
	$(INSTALL_BIN) ./files/etc/uci-defaults/90-wrtpilot $(1)/etc/uci-defaults/90-wrtpilot
	$(INSTALL_DATA) ./files/etc/hotplug.d/dhcp/50-wrtpilot $(1)/etc/hotplug.d/dhcp/50-wrtpilot
	$(INSTALL_DATA) ./files/etc/hotplug.d/iface/50-wrtpilot $(1)/etc/hotplug.d/iface/50-wrtpilot
	$(INSTALL_DATA) ./files/lib/upgrade/keep.d/wrtpilot $(1)/lib/upgrade/keep.d/wrtpilot
	$(INSTALL_BIN) ./files/usr/sbin/wrtpilot $(1)/usr/sbin/wrtpilot
	$(INSTALL_BIN) ./files/usr/sbin/wrtpilotd $(1)/usr/sbin/wrtpilotd
	$(INSTALL_DATA) ./files/usr/share/rpcd/ucode/wrtpilot.uc $(1)/usr/share/rpcd/ucode/wrtpilot.uc
	$(INSTALL_DATA) ./files/usr/share/rpcd/acl.d/wrtpilot.json $(1)/usr/share/rpcd/acl.d/wrtpilot.json
	$(INSTALL_DATA) ./files/usr/share/ucode/wrtpilot/*.uc $(1)/usr/share/ucode/wrtpilot/
	$(LN) /var/run/wrtpilot/fw4.nft $(1)/usr/share/nftables.d/ruleset-post/30-wrtpilot.nft
endef

define Package/wrtpilot/postinst
#!/bin/sh
[ -n "$${IPKG_INSTROOT}" ] && exit 0
/etc/init.d/rpcd reload
/etc/init.d/uhttpd reload
/etc/init.d/wrtpilotd enable
/usr/sbin/wrtpilot apply --quiet
/etc/init.d/wrtpilotd restart
exit 0
endef

define Package/wrtpilot/prerm
#!/bin/sh
[ -n "$${IPKG_INSTROOT}" ] && exit 0
/etc/init.d/wrtpilotd stop
/etc/init.d/wrtpilotd disable
/usr/sbin/wrtpilot reset >/dev/null 2>&1
exit 0
endef

define Package/wrtpilot/postrm
#!/bin/sh
[ -n "$${IPKG_INSTROOT}" ] && exit 0
/etc/init.d/rpcd reload
exit 0
endef

$(eval $(call BuildPackage,wrtpilot))
