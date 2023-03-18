/*
 * Copyright 2022-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nycu.sdnfv.vrouter;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onlab.packet.Ethernet;
import org.onlab.packet.MacAddress;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.IpPrefix;
import org.onlab.packet.IPv4;
import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigRegistry;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;

import org.onosproject.net.intent.Intent;
import org.onosproject.net.intent.IntentService;
import org.onosproject.net.intent.PointToPointIntent;
import org.onosproject.net.intent.MultiPointToSinglePointIntent;
import org.onosproject.net.FilteredConnectPoint;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;

import org.onosproject.net.intf.Interface;
import org.onosproject.net.intf.InterfaceService;
import org.onosproject.routeservice.RouteService;

import org.onosproject.routeservice.RouteTableId;
import org.onosproject.routeservice.RouteInfo;

import org.onosproject.net.Host;
import org.onosproject.net.HostId;
import org.onosproject.net.host.HostService;

import java.util.Optional;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_ADDED;
import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_UPDATED;
import static org.onosproject.net.config.basics.SubjectFactories.APP_SUBJECT_FACTORY;

@Component(immediate = true)
public class AppComponent {

  private final Logger log = LoggerFactory.getLogger(getClass());
  private final vRouterConfigListener cfgListener = new vRouterConfigListener();
  private final ConfigFactory<ApplicationId, vRouterConfig> factory = new ConfigFactory<ApplicationId, vRouterConfig>(
      APP_SUBJECT_FACTORY, vRouterConfig.class, "router") {
    @Override
    public vRouterConfig createConfig() {
      return new vRouterConfig();
    }
  };

  private ApplicationId appId;
  private PacketProcessor processor;

  @Reference(cardinality = ReferenceCardinality.MANDATORY)
  protected CoreService coreService;

  @Reference(cardinality = ReferenceCardinality.MANDATORY)
  protected NetworkConfigRegistry cfgService;

  @Reference(cardinality = ReferenceCardinality.MANDATORY)
  protected PacketService packetService;

  @Reference(cardinality = ReferenceCardinality.MANDATORY)
  protected IntentService intentService;

  @Reference(cardinality = ReferenceCardinality.MANDATORY)
  protected InterfaceService intfService;

  @Reference(cardinality = ReferenceCardinality.MANDATORY)
  protected HostService hostService;

  @Reference(cardinality = ReferenceCardinality.MANDATORY)
  protected RouteService routeService;

  @Activate
  protected void activate() {
    appId = coreService.registerApplication("nycu.sdnfv.vrouter");
    cfgService.addListener(cfgListener);
    cfgService.registerConfigFactory(factory);

    processor = new vRouterPacketProcessor();
    packetService.addProcessor(processor, PacketProcessor.director(6));
    packetService.requestPackets(DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4).build(),
                                PacketPriority.REACTIVE, appId, Optional.empty());
    log.info("Started");
  }

  @Deactivate
  protected void deactivate() {
    cfgService.removeListener(cfgListener);
    cfgService.unregisterConfigFactory(factory);

    packetService.cancelPackets(DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4).build(),
                                PacketPriority.REACTIVE, appId);
    packetService.removeProcessor(processor);
    intentService.getIntents().forEach(intent -> intentService.withdraw(intent));
    log.info("Stopped");
  }

  private class vRouterConfigListener implements NetworkConfigListener {
    @Override
    public void event(NetworkConfigEvent event) {
      if ((event.type() == CONFIG_ADDED || event.type() == CONFIG_UPDATED)
          && event.configClass().equals(vRouterConfig.class)) {
        vRouterConfig config = cfgService.getConfig(appId, vRouterConfig.class);
        if (config != null) {
            /* if this shows, means config file parsed successfully*/
            ConnectPoint QuaggaConnectPoint = ConnectPoint.deviceConnectPoint(config.quaggaLocation());
            log.info("quagga is connected to `{}`, port `{}`", QuaggaConnectPoint.deviceId(),
                      QuaggaConnectPoint.port());

            MacAddress quaggaMac = MacAddress.valueOf(config.quaggaMAC());
            log.info("quaggaMac is `{}`", quaggaMac);

            Ip4Address vGateWayIp = Ip4Address.valueOf(config.vGatewayIP());
            log.info("vGateWayIp is `{}`", vGateWayIp);

            MacAddress vGateWayMac = MacAddress.valueOf(config.vGatewayMAC());
            log.info("vGateWayMac is `{}`", vGateWayMac);
            // get the list of bpg peers
            List<Ip4Address> bgpPeersList = config.bgpPeers();
            for (Ip4Address peerIp:bgpPeersList) {
              log.info("peerIp: `{}`", peerIp);
            }


            /* install flowrules for eBGP peering */
            // get interface of bgp peers
            Interface outIntf1 = intfService.getMatchingInterface(bgpPeersList.get(0));
            Interface outIntf2 = intfService.getMatchingInterface(bgpPeersList.get(1));
            Interface outIntf3 = intfService.getMatchingInterface(bgpPeersList.get(2));

            // define egress and ingress point
            FilteredConnectPoint egressPoint1 = new FilteredConnectPoint(outIntf1.connectPoint());
            FilteredConnectPoint egressPoint2 = new FilteredConnectPoint(outIntf2.connectPoint());
            FilteredConnectPoint egressPoint3 = new FilteredConnectPoint(outIntf3.connectPoint());
            FilteredConnectPoint ingressPoint = new FilteredConnectPoint(QuaggaConnectPoint);

            // creates intents for three external routers
            // public static IpPrefix valueOf(IpAddress address, int prefixLength)
            // ipAddressesList() retrieves a list of IP addresses that are assigned to the interface
            // subnet broadcast address is 192.168.30.255
            buildIntent(ingressPoint, egressPoint1, IpPrefix.valueOf(bgpPeersList.get(0), 24));
            buildIntent(egressPoint1, ingressPoint, outIntf1.ipAddressesList().get(0).subnetAddress());

            buildIntent(ingressPoint, egressPoint2, IpPrefix.valueOf(bgpPeersList.get(1), 24));
            buildIntent(egressPoint2, ingressPoint, outIntf2.ipAddressesList().get(0).subnetAddress());

            buildIntent(ingressPoint, egressPoint3, IpPrefix.valueOf(bgpPeersList.get(2), 24));
            buildIntent(egressPoint3, ingressPoint, outIntf3.ipAddressesList().get(0).subnetAddress());

            /* External to External **/

            // get route from fpm
            // getRouteTables() returns a set of iterable route entries
            Collection<RouteTableId> routeTables = routeService.getRouteTables();
            // get a set of routes from routeTable
            Collection<RouteInfo> routes = routeService.getRoutes(routeTables.iterator().next());
            // MultiPointToSinglePoint intent
            // IngressPoints are all BGP peer connection points
            Set<FilteredConnectPoint> ingressPoints = new HashSet<FilteredConnectPoint>();
            ingressPoints.add(egressPoint1);
            ingressPoints.add(egressPoint2);
            ingressPoints.add(egressPoint3);

            RouteInfo nextHopRouteInfo = routes.iterator().next();
            Iterator<RouteInfo> routeIterate = routes.iterator();

            while (routeIterate.hasNext()) {
              // EgressPoint is next hop interface
              nextHopRouteInfo = routeIterate.next();
              Ip4Address nextHopIp = nextHopRouteInfo.bestRoute().get().nextHop().getIp4Address();
              MacAddress nextHopMac = nextHopRouteInfo.bestRoute().get().nextHopMac();
              // getMatchingInterface returns an interface that has an address
              // that is in the same subnet as the given IP address
              Interface nextHopIntf = intfService.getMatchingInterface(nextHopIp);
              FilteredConnectPoint egressPoint = new FilteredConnectPoint(nextHopIntf.connectPoint());

              // remove egressPoint from ingressPoints, otherwise will have one same ingress/egress point
              ingressPoints.remove(egressPoint);
              // create multipointToSinglePoint intents with modified L2 header
              buildMultipointIntent(ingressPoints, egressPoint, nextHopRouteInfo.prefix(), quaggaMac, nextHopMac);
              // restore the removed egressPoint
              ingressPoints.add(egressPoint);
            }
        }
      }
    }

    private void buildMultipointIntent(Set<FilteredConnectPoint> ingressPoints, FilteredConnectPoint egressPoint,
                                  IpPrefix filterIpPrefix, MacAddress srcMac, MacAddress dstMac) {
            // function to build MultiPointToSinglePointIntent
            MultiPointToSinglePointIntent multiIntents = MultiPointToSinglePointIntent.builder()
                                                                                       .appId(appId)
                                                                                       .selector(DefaultTrafficSelector.builder()
                                                                                                 .matchEthType(Ethernet.TYPE_IPV4)
                                                                                                 .matchIPDst(filterIpPrefix)
                                                                                                 .build())
                                                                                       .treatment(DefaultTrafficTreatment.builder()
                                                                                                 .setOutput(egressPoint.connectPoint().port())
                                                                                                 // srcMac is Quagga's MAC
                                                                                                 // dstMac is next hop router's MAC
                                                                                                 .setEthSrc(srcMac)
                                                                                                 .setEthDst(dstMac)
                                                                                                 .build())
                                                                                       .filteredIngressPoints(ingressPoints)
                                                                                       .filteredEgressPoint(egressPoint)
                                                                                       .priority(Intent.DEFAULT_INTENT_PRIORITY)
                                                                                       .build();
            intentService.submit(multiIntents);
            log.info("Multipoint to single point intents => `{}`, port `{}` are submitted.",
                      egressPoint.connectPoint().deviceId(), egressPoint.connectPoint().port());
    }

    private void buildIntent(FilteredConnectPoint ingressPoint, FilteredConnectPoint egressPoint, IpPrefix filterIpPrefix) {
            // function to build PointToPointIntent
            PointToPointIntent intent = PointToPointIntent.builder()
                                                          .appId(appId)
                                                          .selector(DefaultTrafficSelector.builder()
                                                                    .matchEthType(Ethernet.TYPE_IPV4)
                                                                    .matchIPDst(filterIpPrefix)
                                                                    .build())
                                                          .treatment(DefaultTrafficTreatment.builder()
                                                                    .setOutput(egressPoint.connectPoint().port())
                                                                    .build())
                                                          .priority(Intent.DEFAULT_INTENT_PRIORITY)
                                                          .filteredIngressPoint(ingressPoint)
                                                          .filteredEgressPoint(egressPoint)
                                                          .build();
            intentService.submit(intent);
            log.info("Intent `{}`, port `{}` => `{}`, port `{}` is submitted.",
                      ingressPoint.connectPoint().deviceId(), ingressPoint.connectPoint().port(),
                      egressPoint.connectPoint().deviceId(), egressPoint.connectPoint().port());
    }
  }


  private class vRouterPacketProcessor implements PacketProcessor {

    @Override
    public void process(PacketContext pc) {

        // handle IPv4 packets, arp packets go to proxyArp app
        Short type = pc.inPacket().parsed().getEtherType();
        if (type != Ethernet.TYPE_IPV4) {
            return;
        }

        Ethernet eth = pc.inPacket().parsed();
        IPv4 ip = (IPv4) eth.getPayload();
        Ip4Address srcIp = Ip4Address.valueOf(ip.getSourceAddress());
        Ip4Address dstIp = Ip4Address.valueOf(ip.getDestinationAddress());
        MacAddress srcMac = eth.getSourceMAC();
        MacAddress dstMac = eth.getDestinationMAC();

        /*  check if hosts are both intra-domain */
        // getHosts returns a collection of all hosts in the domain
        // intra-domain is left for learning bridge app to learn

        Iterator<Host> hostIterate = hostService.getHosts().iterator();
        int count = 0;
        while (hostIterate.hasNext()) {
          Host hst = hostIterate.next();
          if (hst.equals(hostService.getHost(HostId.hostId(srcMac))) ||
              hst.equals(hostService.getHost(HostId.hostId(dstMac)))) {
                  count ++;
          }
          if (count == 2) {
              return;
          }
        }

        Collection<RouteTableId> routeTables = routeService.getRouteTables();
        Collection<RouteInfo> routes = routeService.getRoutes(routeTables.iterator().next());
        vRouterConfig config = cfgService.getConfig(appId, vRouterConfig.class);
        MacAddress quaggaMac = MacAddress.valueOf(config.quaggaMAC());
        MacAddress vGateWayMac = MacAddress.valueOf(config.vGatewayMAC());

        if (dstMac.equals(vGateWayMac)) {
            /* SDN to External */
            // host will send arp request to vGateway MAC first
            // Then, Proxy ARP will reply the vGateway MAC
            log.info("SDN to External");

            // find the route for dstIp
            RouteInfo nextHopRouteInfo = routes.iterator().next();
            Iterator<RouteInfo> routeIterate = routes.iterator();
            int gotroute = 0;
            log.info("finding route to {}", dstIp);

            // If vRouter knows route to destination IP, it installs flowrule through intent otherwise Noop
            while (routeIterate.hasNext()) {
              nextHopRouteInfo = routeIterate.next();
              if (nextHopRouteInfo.prefix().equals(IpPrefix.valueOf(dstIp, 24))) {
                  gotroute ++;
                  log.info("nextHopRouteInfo:{}", nextHopRouteInfo);
                  break;
              }
            }

            if (gotroute == 1) {
                Ip4Address nextHopIp = nextHopRouteInfo.bestRoute().get().nextHop().getIp4Address();
                MacAddress nextHopMac = nextHopRouteInfo.bestRoute().get().nextHopMac();
                Interface nextHopIntf = intfService.getMatchingInterface(nextHopIp);
                log.info("vRouter knows destination IP, install flowrules");
                // ingress is packet-in port
                FilteredConnectPoint ingressPoint = new FilteredConnectPoint(pc.inPacket().receivedFrom());
                // egress is nexthop connect point
                FilteredConnectPoint egressPoint = new FilteredConnectPoint(nextHopIntf.connectPoint());
                buildIntent2(ingressPoint, egressPoint, IpPrefix.valueOf(dstIp, 24), quaggaMac, nextHopMac);
                buildIntent2(egressPoint, ingressPoint, IpPrefix.valueOf(srcIp, 24), vGateWayMac, srcMac);
            } else {
                log.info("No route found.");
                return;
            }
        } else if (dstMac.equals(quaggaMac)) {
            /* External to SDN */
            log.info("external to SDN");

            Set<Host> dstHosts = hostService.getHostsByIp(dstIp);
            // If vRouter knows host, it installs flowrule through intent otherwise Noop
            if (!dstHosts.isEmpty()) {
                log.info("vRouter knows destination host, install flowrules");
                // test get(0)
                Host dstHost = dstHosts.iterator().next();
                // ingress is packet-in port
                FilteredConnectPoint ingressPoint = new FilteredConnectPoint(pc.inPacket().receivedFrom());
                // egress is host connection point
                FilteredConnectPoint egressPoint = new FilteredConnectPoint(dstHost.location());
                buildIntent2(ingressPoint, egressPoint, IpPrefix.valueOf(dstIp, 24), vGateWayMac, dstHost.mac());

            }

        } else {
            log.info("No host found");
            return;
        }
        // mark packet as "handled"
        pc.block();
    }

    private void buildIntent2(FilteredConnectPoint ingressPoint, FilteredConnectPoint egressPoint,
                             IpPrefix filterIpPrefix, MacAddress srcMac, MacAddress dstMac) {

          PointToPointIntent intent = PointToPointIntent.builder()
                                                        .appId(appId)
                                                        .selector(DefaultTrafficSelector.builder()
                                                                  .matchEthType(Ethernet.TYPE_IPV4)
                                                                  .matchIPDst(filterIpPrefix)
                                                                  .build())
                                                        .treatment(DefaultTrafficTreatment.builder()
                                                                  .setOutput(egressPoint.connectPoint().port())
                                                                  .setEthSrc(srcMac)
                                                                  .setEthDst(dstMac)
                                                                  .build())
                                                        .priority(Intent.DEFAULT_INTENT_PRIORITY)
                                                        .filteredIngressPoint(ingressPoint)
                                                        .filteredEgressPoint(egressPoint)
                                                        .build();
          intentService.submit(intent);
          log.info("Intent `{}`, port `{}` => `{}`, port `{}` is submitted.",
                    ingressPoint.connectPoint().deviceId(), ingressPoint.connectPoint().port(),
                    egressPoint.connectPoint().deviceId(), egressPoint.connectPoint().port());
    }
  }
}
