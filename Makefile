All:
	onos-app localhost activate org.onosproject.fpm
	onos-app localhost install! target/bridge-1.0-SNAPSHOT.oar
	onos-app localhost install! target/proxyarp-1.0-SNAPSHOT.oar
	onos-app localhost install! target/unicastdhcp-1.0-SNAPSHOT.oar
	onos-app localhost install! target/vrouter-1.0-SNAPSHOT.oar
	onos-netcfg localhost config.json
install:
        # use this first, so that other apps won't be deleted by clean install	
	mvn clean install -DskipTests
clean:
	sudo ./clean_topo.sh
	onos-app localhost deactivate org.onosproject.fpm
	onos-app localhost deactivate nycu.sdnfv.bridge
	onos-app localhost uninstall nycu.sdnfv.bridge
	onos-app localhost deactivate nycu.sdnfv.unicastdhcp
	onos-app localhost uninstall nycu.sdnfv.unicastdhcp
	onos-app localhost deactivate nycu.sdnfv.proxyarp
	onos-app localhost uninstall nycu.sdnfv.proxyarp
	onos-app localhost deactivate nycu.sdnfv.router
	onos-app localhost uninstall nycu.sdnfv.router
	sudo killall dhcpd
