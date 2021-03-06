/*
 * Copyright 2015 Open Networking Laboratory
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

package org.onosproject.bgp.controller;

import org.onosproject.bgpio.protocol.BGPMessage;

/**
 * Abstraction of an BGP controller. Serves as a one stop shop for obtaining BGP devices and (un)register listeners
 * on bgp events
 */
public interface BGPController {

    /**
     * Returns list of bgp peers connected to this BGP controller.
     *
     * @return Iterable of BGPPeer elements
     */
    Iterable<BGPPeer> getPeers();

    /**
     * Returns the actual bgp peer for the given ip address.
     *
     * @param bgpId the id of the bgp peer to fetch
     * @return the interface to this bgp peer
     */
    BGPPeer getPeer(BGPId bgpId);

    /**
     * Send a message to a particular bgp peer.
     *
     * @param bgpId the id of the peer to send message.
     * @param msg the message to send
     */
    void writeMsg(BGPId bgpId, BGPMessage msg);

    /**
     * Process a message and notify the appropriate listeners.
     *
     * @param bgpId id of the peer the message arrived on
     * @param msg the message to process.
     */
    void processBGPPacket(BGPId bgpId, BGPMessage msg);

    /**
     * Close all connected BGP peers.
     *
     */
    void closeConnectedPeers();

    /**
     * Get the BGPConfig class to the caller.
     *
     * @return configuration object
     */
    BGPCfg getConfig();

    /**
     * Get the BGP connected peers to this controller.
     *
     * @return the integer number
     */
    int getBGPConnNumber();
}