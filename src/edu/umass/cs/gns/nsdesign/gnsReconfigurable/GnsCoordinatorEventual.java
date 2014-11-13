package edu.umass.cs.gns.nsdesign.gnsReconfigurable;

import edu.umass.cs.gns.main.GNS;
import edu.umass.cs.gns.nio.InterfaceJSONNIOTransport;
import edu.umass.cs.gns.nio.InterfaceNodeConfig;
import edu.umass.cs.gns.nsdesign.Config;
import edu.umass.cs.gns.nsdesign.PacketTypeStamper;
import edu.umass.cs.gns.nsdesign.Replicable;
import edu.umass.cs.gns.nsdesign.packet.*;
import edu.umass.cs.gns.paxos.AbstractPaxosManager;
import edu.umass.cs.gns.paxos.PaxosConfig;
import edu.umass.cs.gns.paxos.PaxosManager;
import edu.umass.cs.gns.replicaCoordination.ActiveReplicaCoordinator;
import edu.umass.cs.gns.util.ConsistentHashing;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Set;

/**
 * Module for coordinating among active replicas of a name using paxos protocol.
 * This is the entry point for all messages into gnsReconfigurable module. Its main task
 * is to decide whether coordination is needed for a request. If yes, it proposes requests
 * to paxos for coordination. Otherwise, requests are forwarded to GNS for execution.
 *
 *
 * Created by abhigyan on 3/28/14.
 * @param <NodeIDType>
 */
public class GnsCoordinatorEventual<NodeIDType> extends ActiveReplicaCoordinator{

  private NodeIDType nodeID;
  // this is the app object
  private Replicable paxosInterface;

  private AbstractPaxosManager paxosManager;

  // if true, reads are coordinated as well.
  private boolean readCoordination = false;

  private InterfaceJSONNIOTransport nioTransport;

  public GnsCoordinatorEventual(NodeIDType nodeID, InterfaceJSONNIOTransport nioServer, InterfaceNodeConfig nodeConfig,
                                Replicable paxosInterface, PaxosConfig paxosConfig, boolean readCoordination) {
    this.nodeID = nodeID;
    this.paxosInterface = paxosInterface;
    this.readCoordination = readCoordination;
    this.nioTransport = nioServer;
    this.paxosManager = new PaxosManager(nodeID, nodeConfig,
            new PacketTypeStamper(nioServer, Packet.PacketType.ACTIVE_COORDINATION), paxosInterface, paxosConfig);
  }

  /**
   * Handles coordination among replicas for a request. Returns -1 in case of error, 0 otherwise.
   * Error could happen if replicable app is not initialized, or paxos instance for this name does not exist.
   */
  @Override
  public int coordinateRequest(JSONObject request) {
    if (this.paxosInterface == null) return -1; // replicable app not set
    JSONObject callHandleDecision = null;
    boolean noCoordinatorState = false;
    try {
      Packet.PacketType type = Packet.getPacketType(request);
      String paxosID;
      switch (type) {
        // coordination packets internal to paxos
        case ACTIVE_COORDINATION:
          paxosManager.handleIncomingPacket(request);
          break;

        // call propose
        case UPDATE: // updates need coordination

          UpdatePacket update = new UpdatePacket(request);
          Set<NodeIDType> nodeIDs = paxosManager.getPaxosNodeIDs(update.getName());
          if (update.getNameServerID().equals(nodeID) && nodeIDs!= null) {
            for (NodeIDType x: nodeIDs) {
              if (!x.equals(nodeID))
                nioTransport.sendToID(x,update.toJSONObject());
            }
          }
          paxosInterface.handleDecision(update.getName(), update.toJSONObject().toString(), false);
          break;

        // call proposeStop
        case ACTIVE_REMOVE: // stop request for removing a name record
          OldActiveSetStopPacket stopPacket1 = new OldActiveSetStopPacket(request);
          paxosID = paxosManager.proposeStop(stopPacket1.getName(), stopPacket1.toString(), stopPacket1.getVersion());
          if (paxosID == null) {
            callHandleDecision = stopPacket1.toJSONObject();
            noCoordinatorState = true;
          }
          break;
        case OLD_ACTIVE_STOP: // (sent by active replica) stop request on a group change
          OldActiveSetStopPacket stopPacket2 = new OldActiveSetStopPacket(request);
          paxosID = paxosManager.proposeStop(stopPacket2.getName(), stopPacket2.toString(), stopPacket2.getVersion());
          if (paxosID == null) {
            callHandleDecision = stopPacket2.toJSONObject();
            noCoordinatorState = true;
          }
          break;
        // call createPaxosInstance
        case ACTIVE_ADD:  // createPaxosInstance when name is added for the first time
          // calling handle decision before creating paxos instance to insert state for name in database.
          paxosInterface.handleDecision(null, request.toString(), false);
          AddRecordPacket recordPacket = new AddRecordPacket(request);
          paxosManager.createPaxosInstance(recordPacket.getName(), (short) Config.FIRST_VERSION, ConsistentHashing.getReplicaControllerSet(recordPacket.getName()), paxosInterface);
          if (Config.debuggingEnabled) GNS.getLogger().fine("Added paxos instance:" + recordPacket.getName());
          break;
        case NEW_ACTIVE_START_PREV_VALUE_RESPONSE: // (sent by active replica) createPaxosInstance after a group change
          // active replica has already put initial state for the name in DB. we only need to create paxos instance.
          NewActiveSetStartupPacket newActivePacket = new NewActiveSetStartupPacket(request);
          paxosManager.createPaxosInstance(newActivePacket.getName(), (short) newActivePacket.getNewActiveVersion(),
                  newActivePacket.getNewActiveNameServers(), paxosInterface);
          break;

        // no coordination needed for these requests
        case DNS:
          DNSPacket dnsPacket = new DNSPacket(request);
          String name = dnsPacket.getGuid();

          Set<NodeIDType> nodeIds = paxosManager.getPaxosNodeIDs(name);
          if (nodeIds != null) {
            RequestActivesPacket requestActives = new RequestActivesPacket(name, dnsPacket.getLnsAddress(), 0, nodeID);
            requestActives.setActiveNameServers(nodeIds);
            nioTransport.sendToAddress(dnsPacket.getLnsAddress(), requestActives.toJSONObject());
          }
          if (readCoordination) {

            if (dnsPacket.isQuery()) {
              dnsPacket.setResponder(nodeID);
              paxosID = paxosManager.propose(dnsPacket.getGuid(), dnsPacket.toString());
              if (paxosID == null) {
                callHandleDecision = dnsPacket.toJSONObjectQuestion();
                noCoordinatorState = true;
              }
              break;
            }

          }
        case NAME_SERVER_LOAD:
        case SELECT_REQUEST:
        case SELECT_RESPONSE:
        case CONFIRM_UPDATE:
        case CONFIRM_ADD:
        case CONFIRM_REMOVE:
          // Packets sent from replica controller
          callHandleDecision = request;

          break;
        default:
          GNS.getLogger().severe("Packet type not found in coordination: " + type);
          break;
      }
      if (callHandleDecision != null) {
        if (noCoordinatorState) {
          callHandleDecision.put(Config.NO_COORDINATOR_STATE_MARKER, 0);
        }
        paxosInterface.handleDecision(null, callHandleDecision.toString(), false);
      }
    } catch (JSONException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    }
    return 0;
  }

  @Override
  public void reset() {
    paxosManager.resetAll();
  }

  @Override
  public void shutdown() {

  }
}


