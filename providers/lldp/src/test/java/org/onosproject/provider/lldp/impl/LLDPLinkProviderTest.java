/*
 * Copyright 2014-2015 Open Networking Laboratory
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
package org.onosproject.provider.lldp.impl;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.onlab.packet.ChassisId;
import org.onlab.packet.Ethernet;
import org.onlab.packet.ONOSLLDP;
import org.onosproject.cfg.ComponentConfigAdapter;
import org.onosproject.cluster.NodeId;
import org.onosproject.cluster.RoleInfo;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.core.DefaultApplicationId;
import org.onosproject.mastership.MastershipListener;
import org.onosproject.mastership.MastershipService;
import org.onosproject.net.Annotations;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DefaultAnnotations;
import org.onosproject.net.DefaultDevice;
import org.onosproject.net.DefaultPort;
import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.MastershipRole;
import org.onosproject.net.Port;
import org.onosproject.net.PortNumber;
import org.onosproject.net.device.DeviceEvent;
import org.onosproject.net.device.DeviceListener;
import org.onosproject.net.device.DeviceServiceAdapter;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.link.LinkDescription;
import org.onosproject.net.link.LinkProvider;
import org.onosproject.net.link.LinkProviderRegistry;
import org.onosproject.net.link.LinkProviderService;
import org.onosproject.net.link.LinkServiceAdapter;
import org.onosproject.net.packet.DefaultInboundPacket;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.OutboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketServiceAdapter;
import org.onosproject.net.provider.AbstractProviderService;
import org.onosproject.net.provider.ProviderId;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.easymock.EasyMock.*;
import static org.junit.Assert.*;

public class LLDPLinkProviderTest {

    private static final DeviceId DID1 = DeviceId.deviceId("of:0000000000000001");
    private static final DeviceId DID2 = DeviceId.deviceId("of:0000000000000002");
    private static final DeviceId DID3 = DeviceId.deviceId("of:0000000000000003");

    private static Port pd1;
    private static Port pd2;
    private static Port pd3;
    private static Port pd4;

    private final LLDPLinkProvider provider = new LLDPLinkProvider();
    private final TestLinkRegistry linkRegistry = new TestLinkRegistry();
    private final TestLinkService linkService = new TestLinkService();
    private final TestPacketService packetService = new TestPacketService();
    private final TestDeviceService deviceService = new TestDeviceService();
    private final TestMasterShipService masterService = new TestMasterShipService();

    private CoreService coreService;
    private TestLinkProviderService providerService;

    private PacketProcessor testProcessor;
    private DeviceListener deviceListener;

    private ApplicationId appId =
            new DefaultApplicationId(100, "org.onosproject.provider.lldp");

    @Before
    public void setUp() {
        coreService = createMock(CoreService.class);
        expect(coreService.registerApplication(appId.name()))
            .andReturn(appId).anyTimes();
        replay(coreService);

        provider.cfgService = new ComponentConfigAdapter();
        provider.coreService = coreService;

        provider.deviceService = deviceService;
        provider.linkService = linkService;
        provider.packetService = packetService;
        provider.providerRegistry = linkRegistry;
        provider.masterService = masterService;

        provider.activate(null);
    }

    @Test
    public void basics() {
        assertNotNull("registration expected", providerService);
        assertEquals("incorrect provider", provider, providerService.provider());
    }

    @Test
    public void switchAdd() {
        DeviceEvent de = deviceEvent(DeviceEvent.Type.DEVICE_ADDED, DID1);
        deviceListener.event(de);

        assertFalse("Device not added", provider.discoverers.isEmpty());
    }

    @Test
    public void switchRemove() {
        deviceListener.event(deviceEvent(DeviceEvent.Type.DEVICE_ADDED, DID1));
        deviceListener.event(deviceEvent(DeviceEvent.Type.DEVICE_REMOVED, DID1));

        final LinkDiscovery linkDiscovery = provider.discoverers.get(DID1);
        if (linkDiscovery != null) {
            // If LinkDiscovery helper is there after DEVICE_REMOVED,
            // it should be stopped
            assertTrue("Discoverer is not stopped", linkDiscovery.isStopped());
        }
        assertTrue("Device is not gone.", vanishedDpid(DID1));
    }

    /**
     * Checks that links on a reconfigured switch are properly removed.
     */
    @Test
    public void switchSuppressed() {
        // add device to stub DeviceService
        deviceService.putDevice(device(DID3));
        deviceListener.event(deviceEvent(DeviceEvent.Type.DEVICE_ADDED, DID3));

        assertFalse("Device not added", provider.discoverers.isEmpty());

        // update device in stub DeviceService with suppression config
        deviceService.putDevice(device(DID3, DefaultAnnotations.builder()
                                              .set(LLDPLinkProvider.NO_LLDP, "true")
                                              .build()));
        deviceListener.event(deviceEvent(DeviceEvent.Type.DEVICE_UPDATED, DID3));

        assertTrue("Links on suppressed Device was expected to vanish.", vanishedDpid(DID3));
    }

    @Test
    public void portUp() {
        deviceListener.event(deviceEvent(DeviceEvent.Type.DEVICE_ADDED, DID1));
        deviceListener.event(portEvent(DeviceEvent.Type.PORT_ADDED, DID1, port(DID1, 3, true)));

        assertTrue("Port not added to discoverer",
                   provider.discoverers.get(DID1).containsPort(3L));
    }

    @Test
    public void portDown() {

        deviceListener.event(deviceEvent(DeviceEvent.Type.DEVICE_ADDED, DID1));
        deviceListener.event(portEvent(DeviceEvent.Type.PORT_ADDED, DID1, port(DID1, 1, false)));

        assertFalse("Port added to discoverer",
                    provider.discoverers.get(DID1).containsPort(1L));
        assertTrue("Port is not gone.", vanishedPort(1L));
    }

    @Test
    public void portRemoved() {
        deviceListener.event(deviceEvent(DeviceEvent.Type.DEVICE_ADDED, DID1));
        deviceListener.event(portEvent(DeviceEvent.Type.PORT_ADDED, DID1, port(DID1, 3, true)));
        deviceListener.event(portEvent(DeviceEvent.Type.PORT_REMOVED, DID1, port(DID1, 3, true)));

        assertTrue("Port is not gone.", vanishedPort(3L));
        assertFalse("Port was not removed from discoverer",
                   provider.discoverers.get(DID1).containsPort(3L));
    }

    /**
     * Checks that discovery on reconfigured switch are properly restarted.
     */
    @Test
    public void portSuppressedByDeviceConfig() {

        /// When Device is configured with suppression:ON, Port also is same

        // add device in stub DeviceService with suppression configured
        deviceService.putDevice(device(DID3, DefaultAnnotations.builder()
                                              .set(LLDPLinkProvider.NO_LLDP, "true")
                                              .build()));
        deviceListener.event(deviceEvent(DeviceEvent.Type.DEVICE_ADDED, DID3));

        // non-suppressed port added to suppressed device
        final long portno3 = 3L;
        deviceService.putPorts(DID3, port(DID3, portno3, true));
        deviceListener.event(portEvent(DeviceEvent.Type.PORT_ADDED, DID3, port(DID3, portno3, true)));

        // discovery on device is expected to be stopped
        LinkDiscovery linkDiscovery = provider.discoverers.get(DID3);
        if (linkDiscovery != null) {
            assertTrue("Discovery expected to be stopped", linkDiscovery.isStopped());
        }

        /// When Device is reconfigured without suppression:OFF,
        /// Port should be included for discovery

        // update device in stub DeviceService without suppression configured
        deviceService.putDevice(device(DID3));
        // update the Port in stub DeviceService. (Port has reference to Device)
        deviceService.putPorts(DID3, port(DID3, portno3, true));
        deviceListener.event(deviceEvent(DeviceEvent.Type.DEVICE_UPDATED, DID3));

        // discovery should come back on
        assertFalse("Discoverer is expected to start", provider.discoverers.get(DID3).isStopped());
        assertTrue("Discoverer should contain the port there", provider.discoverers.get(DID3).containsPort(portno3));
    }

    /**
     * Checks that discovery on reconfigured port are properly restarted.
     */
    @Test
    public void portSuppressedByPortConfig() {
        // add device in stub DeviceService without suppression configured
        deviceService.putDevice(device(DID3));
        deviceListener.event(deviceEvent(DeviceEvent.Type.DEVICE_ADDED, DID3));

        // suppressed port added to non-suppressed device
        final long portno3 = 3L;
        final Port port3 = port(DID3, portno3, true,
                                          DefaultAnnotations.builder()
                                          .set(LLDPLinkProvider.NO_LLDP, "true")
                                          .build());
        deviceService.putPorts(DID3, port3);
        deviceListener.event(portEvent(DeviceEvent.Type.PORT_ADDED, DID3, port3));

        // discovery helper should be there turned on
        assertFalse("Discoverer is expected to start", provider.discoverers.get(DID3).isStopped());
        assertFalse("Discoverer should not contain the port there",
                    provider.discoverers.get(DID3).containsPort(portno3));
    }

    @Test
    public void portUnknown() {
        deviceListener.event(deviceEvent(DeviceEvent.Type.DEVICE_ADDED, DID1));
        // Note: DID3 hasn't been added to TestDeviceService, but only port is added
        deviceListener.event(portEvent(DeviceEvent.Type.PORT_ADDED, DID3, port(DID3, 1, false)));


        assertNull("DeviceId exists",
                   provider.discoverers.get(DID3));
    }

    @Test
    public void unknownPktCtx() {

        // Note: DID3 hasn't been added to TestDeviceService
        PacketContext pktCtx = new TestPacketContext(device(DID3));

        testProcessor.process(pktCtx);
        assertFalse("Context should still be free", pktCtx.isHandled());
    }

    @Test
    public void knownPktCtx() {
        deviceListener.event(deviceEvent(DeviceEvent.Type.DEVICE_ADDED, DID1));
        deviceListener.event(deviceEvent(DeviceEvent.Type.DEVICE_ADDED, DID2));
        PacketContext pktCtx = new TestPacketContext(deviceService.getDevice(DID2));


        testProcessor.process(pktCtx);

        assertTrue("Link not detected", detectedLink(DID1, DID2));

    }


    @After
    public void tearDown() {
        provider.deactivate();
        provider.coreService = null;
        provider.providerRegistry = null;
        provider.deviceService = null;
        provider.packetService = null;
    }

    private DeviceEvent deviceEvent(DeviceEvent.Type type, DeviceId did) {
        return new DeviceEvent(type, deviceService.getDevice(did));

    }

    private DefaultDevice device(DeviceId did) {
        return new DefaultDevice(ProviderId.NONE, did, Device.Type.SWITCH,
                             "TESTMF", "TESTHW", "TESTSW", "TESTSN", new ChassisId());
    }

    private DefaultDevice device(DeviceId did, Annotations annotations) {
        return new DefaultDevice(ProviderId.NONE, did, Device.Type.SWITCH,
                             "TESTMF", "TESTHW", "TESTSW", "TESTSN", new ChassisId(), annotations);
    }

    @SuppressWarnings(value = { "unused" })
    private DeviceEvent portEvent(DeviceEvent.Type type, DeviceId did, PortNumber port) {
        return new  DeviceEvent(type, deviceService.getDevice(did),
                                deviceService.getPort(did, port));
    }

    private DeviceEvent portEvent(DeviceEvent.Type type, DeviceId did, Port port) {
        return new  DeviceEvent(type, deviceService.getDevice(did), port);
    }

    private Port port(DeviceId did, long port, boolean enabled) {
        return new DefaultPort(deviceService.getDevice(did),
                               PortNumber.portNumber(port), enabled);
    }

    private Port port(DeviceId did, long port, boolean enabled, Annotations annotations) {
        return new DefaultPort(deviceService.getDevice(did),
                               PortNumber.portNumber(port), enabled, annotations);
    }

    private boolean vanishedDpid(DeviceId... dids) {
        for (int i = 0; i < dids.length; i++) {
            if (!providerService.vanishedDpid.contains(dids[i])) {
                return false;
            }
        }
        return true;
    }

    private boolean vanishedPort(Long... ports) {
        for (int i = 0; i < ports.length; i++) {
            if (!providerService.vanishedPort.contains(ports[i])) {
                return false;
            }
        }
        return true;
    }

    private boolean detectedLink(DeviceId src, DeviceId dst) {
        for (DeviceId key : providerService.discoveredLinks.keySet()) {
            if (key.equals(src)) {
                return providerService.discoveredLinks.get(src).equals(dst);
            }
        }
        return false;
    }


    private class TestLinkRegistry implements LinkProviderRegistry {

        @Override
        public LinkProviderService register(LinkProvider provider) {
            providerService = new TestLinkProviderService(provider);
            return providerService;
        }

        @Override
        public void unregister(LinkProvider provider) {
        }

        @Override
        public Set<ProviderId> getProviders() {
            return null;
        }

    }

    private class TestLinkProviderService
            extends AbstractProviderService<LinkProvider>
            implements LinkProviderService {

        List<DeviceId> vanishedDpid = Lists.newLinkedList();
        List<Long> vanishedPort = Lists.newLinkedList();
        Map<DeviceId, DeviceId> discoveredLinks = Maps.newHashMap();

        protected TestLinkProviderService(LinkProvider provider) {
            super(provider);
        }

        @Override
        public void linkDetected(LinkDescription linkDescription) {
            DeviceId sDid = linkDescription.src().deviceId();
            DeviceId dDid = linkDescription.dst().deviceId();
            discoveredLinks.put(sDid, dDid);
        }

        @Override
        public void linkVanished(LinkDescription linkDescription) {
        }

        @Override
        public void linksVanished(ConnectPoint connectPoint) {
            vanishedPort.add(connectPoint.port().toLong());

        }

        @Override
        public void linksVanished(DeviceId deviceId) {
            vanishedDpid.add(deviceId);
        }


    }



    private class TestPacketContext implements PacketContext {

        protected Device device;
        protected boolean blocked = false;

        public TestPacketContext(Device dev) {
            device = dev;
        }

        @Override
        public long time() {
            return 0;
        }

        @Override
        public InboundPacket inPacket() {
            ONOSLLDP lldp = new ONOSLLDP();
            lldp.setChassisId(device.chassisId());
            lldp.setPortId((int) pd1.number().toLong());
            lldp.setDevice(deviceService.getDevice(DID1).id().toString());


            Ethernet ethPacket = new Ethernet();
            ethPacket.setEtherType(Ethernet.TYPE_LLDP);
            ethPacket.setDestinationMACAddress(ONOSLLDP.LLDP_NICIRA);
            ethPacket.setPayload(lldp);
            ethPacket.setPad(true);



            ethPacket.setSourceMACAddress("DE:AD:BE:EF:BA:11");

            ConnectPoint cp = new ConnectPoint(device.id(), pd3.number());

            return new DefaultInboundPacket(cp, ethPacket,
                                            ByteBuffer.wrap(ethPacket.serialize()));

        }

        @Override
        public OutboundPacket outPacket() {
            return null;
        }

        @Override
        public TrafficTreatment.Builder treatmentBuilder() {
            return null;
        }

        @Override
        public void send() {

        }

        @Override
        public boolean block() {
            blocked = true;
            return blocked;
        }

        @Override
        public boolean isHandled() {
            return blocked;
        }

    }

    private class TestPacketService extends PacketServiceAdapter {
        @Override
        public void addProcessor(PacketProcessor processor, int priority) {
            testProcessor = processor;
        }
    }

    private class TestDeviceService extends DeviceServiceAdapter {

        private final Map<DeviceId, Device> devices = new HashMap<>();
        private final ArrayListMultimap<DeviceId, Port> ports =
                ArrayListMultimap.create();
        public TestDeviceService() {
            Device d1 = new DefaultDevice(ProviderId.NONE, DID1, Device.Type.SWITCH,
                                          "TESTMF", "TESTHW", "TESTSW", "TESTSN", new ChassisId());
            Device d2 = new DefaultDevice(ProviderId.NONE, DID2, Device.Type.SWITCH,
                                          "TESTMF", "TESTHW", "TESTSW", "TESTSN", new ChassisId());
            devices.put(DID1, d1);
            devices.put(DID2, d2);
            pd1 = new DefaultPort(d1, PortNumber.portNumber(1), true);
            pd2 = new DefaultPort(d1, PortNumber.portNumber(2), true);
            pd3 = new DefaultPort(d2, PortNumber.portNumber(1), true);
            pd4 = new DefaultPort(d2, PortNumber.portNumber(2), true);

            ports.putAll(DID1, Lists.newArrayList(pd1, pd2));
            ports.putAll(DID2, Lists.newArrayList(pd3, pd4));
        }

        private void putDevice(Device device) {
            DeviceId deviceId = device.id();
            devices.put(deviceId, device);
        }

        private void putPorts(DeviceId did, Port...ports) {
            this.ports.putAll(did, Lists.newArrayList(ports));
        }

        @Override
        public int getDeviceCount() {
            return devices.values().size();
        }

        @Override
        public Iterable<Device> getDevices() {
            return ImmutableList.copyOf(devices.values());
        }

        @Override
        public Device getDevice(DeviceId deviceId) {
            return devices.get(deviceId);
        }

        @Override
        public MastershipRole getRole(DeviceId deviceId) {
            return MastershipRole.MASTER;
        }

        @Override
        public List<Port> getPorts(DeviceId deviceId) {
            return ports.get(deviceId);
        }

        @Override
        public Port getPort(DeviceId deviceId, PortNumber portNumber) {
            for (Port p : ports.get(deviceId)) {
                if (p.number().equals(portNumber)) {
                    return p;
                }
            }
            return null;
        }

        @Override
        public boolean isAvailable(DeviceId deviceId) {
            return true;
        }

        @Override
        public void addListener(DeviceListener listener) {
            deviceListener = listener;

        }

        @Override
        public void removeListener(DeviceListener listener) {

        }
    }

    private final class TestMasterShipService implements MastershipService {

        @Override
        public MastershipRole getLocalRole(DeviceId deviceId) {
            return MastershipRole.MASTER;
        }

        @Override
        public CompletableFuture<MastershipRole> requestRoleFor(DeviceId deviceId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> relinquishMastership(DeviceId deviceId) {
            return null;
        }

        @Override
        public NodeId getMasterFor(DeviceId deviceId) {
            return null;
        }

        @Override
        public Set<DeviceId> getDevicesOf(NodeId nodeId) {
            return null;
        }

        @Override
        public void addListener(MastershipListener listener) {

        }

        @Override
        public void removeListener(MastershipListener listener) {

        }

        @Override
        public RoleInfo getNodesFor(DeviceId deviceId) {
            return new RoleInfo(new NodeId("foo"), Collections.<NodeId>emptyList());
        }
    }


    private class TestLinkService extends LinkServiceAdapter {
    }
}
