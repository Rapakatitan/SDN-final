/*
 * Copyright 2020-present Open Networking Foundation
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

import org.onosproject.core.ApplicationId;
import org.onosproject.net.config.Config;
import org.onlab.packet.Ip4Address;
import java.util.List;
import java.util.function.Function;;

public class vRouterConfig extends Config<ApplicationId> {

  public static final String quagga_cp = "quagga";
  public static final String quagga_mac = "quagga-mac";
  public static final String vGatewayIP = "virtual-ip";
  public static final String vGatewayMAC = "virtual-mac";
  public static final String bgp_peers = "peers";


  @Override
  public boolean isValid() {
    return hasFields(quagga_cp, quagga_mac, vGatewayIP, vGatewayMAC, bgp_peers);
  }

  public String quaggaLocation() {
    return get(quagga_cp, null);
  }

  public String quaggaMAC() {
    return get(quagga_mac, null);
  }

  public String vGatewayIP() {
    return get(vGatewayIP, null);
  }

  public String vGatewayMAC() {
    return get(vGatewayMAC, null);
  }

  public List<Ip4Address> bgpPeers() {
    return getList(bgp_peers, (Function<String, Ip4Address>) Ip4Address::valueOf, null);
  }
}
