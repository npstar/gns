package edu.umass.cs.gns.nsdesign.gnsReconfigurable;

import edu.umass.cs.gns.database.ColumnField;
import edu.umass.cs.gns.database.MongoRecords;
import edu.umass.cs.gns.exceptions.FailedDBOperationException;
import edu.umass.cs.gns.exceptions.FieldNotFoundException;
import edu.umass.cs.gns.exceptions.RecordExistsException;
import edu.umass.cs.gns.exceptions.RecordNotFoundException;
import edu.umass.cs.gns.main.GNS;
import edu.umass.cs.gns.nio.InterfaceJSONNIOTransport;
import edu.umass.cs.gns.nsdesign.Config;
import edu.umass.cs.gns.nsdesign.nodeconfig.GNSNodeConfig;
import edu.umass.cs.gns.nsdesign.clientsupport.LNSQueryHandler;
import edu.umass.cs.gns.nsdesign.clientsupport.LNSUpdateHandler;
import edu.umass.cs.gns.nsdesign.packet.*;
import edu.umass.cs.gns.nsdesign.recordmap.BasicRecordMap;
import edu.umass.cs.gns.nsdesign.recordmap.MongoRecordMap;
import edu.umass.cs.gns.nsdesign.recordmap.NameRecord;
import edu.umass.cs.gns.ping.PingManager;
import edu.umass.cs.gns.util.ValuesMap;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayList;

/**
 * Implements GNS module which stores the name records for all names that are replicated at this name server.
 * It contains code that is locally executed by an active replica of a name; any code which involves coordination
 * among multiple replicas of a name is included in the coordinator module.
 *
 * Created by abhigyan on 2/26/14.
 */
public class GnsReconfigurable implements GnsReconfigurableInterface {

  /**
   * ID of this node
   */
  private final int nodeID;

  /**
   * nio server
   */
  private final InterfaceJSONNIOTransport nioServer;

  /**
   * Object provides interface to the database table storing name records
   */
  private final BasicRecordMap nameRecordDB;

  /**
   * Configuration for all nodes in GNS *
   */
  private final GNSNodeConfig gnsNodeConfig;

  private PingManager pingManager;

  /**
   * Construct the GnsReconfigurable object.
   *
   * @param nodeID
   * @param gnsNodeConfig
   * @param nioServer
   * @param mongoRecords
   */
  public GnsReconfigurable(int nodeID, GNSNodeConfig gnsNodeConfig, InterfaceJSONNIOTransport nioServer,
          MongoRecords mongoRecords) {
    this.nodeID = nodeID;

    this.gnsNodeConfig = gnsNodeConfig;

    this.nioServer = nioServer;

    if (!Config.emulatePingLatencies) {
      // when emulating ping latencies we do not measure ping latencies but instead emulate ping latencies given
      // in config file.
      // Abhigyan: Move pingmanager object in NameServer.java?
      this.pingManager = new PingManager(nodeID, gnsNodeConfig);
      this.pingManager.startPinging();
    }
    this.nameRecordDB = new MongoRecordMap(mongoRecords, MongoRecords.DBNAMERECORD);
  }

  @Override
  public int getNodeID() {
    return nodeID;
  }

  @Override
  public BasicRecordMap getDB() {
    return nameRecordDB;
  }

  @Override
  public GNSNodeConfig getGNSNodeConfig() {
    return gnsNodeConfig;
  }

  @Override
  public InterfaceJSONNIOTransport getNioServer() {
    return nioServer;
  }

  /**
   * ActiveReplicaCoordinator calls this method to locally execute a decision.
   * Depending on request type, this method will call a private method to execute request.
   *
   * @param name
   * @param value
   * @param recovery
   * @return
   */
  @Override
  public boolean handleDecision(String name, String value, boolean recovery) {
    //
    boolean executed = false;
    try {
      JSONObject json = new JSONObject(value);
      boolean noCoordinationState = json.has(Config.NO_COORDINATOR_STATE_MARKER);
      Packet.PacketType packetType = Packet.getPacketType(json);
      switch (packetType) {
        case DNS:
          // the only dns response we should see are coming in response to LNSQueryHandler requests
          DNSPacket dnsPacket = new DNSPacket(json);
          if (!dnsPacket.isQuery()) {
            LNSQueryHandler.handleDNSResponsePacket(dnsPacket, this);
          } else {
            // otherwise it's a query
            GnsReconLookup.executeLookupLocal(dnsPacket, this, noCoordinationState, recovery);
          }
          break;
        case UPDATE:
          GnsReconUpdate.executeUpdateLocal(new UpdatePacket(json), this, noCoordinationState, recovery);
          break;
        case SELECT_REQUEST:
          Select.handleSelectRequest(json, this);
          break;
        case SELECT_RESPONSE:
          Select.handleSelectResponse(json, this);
          break;
        /**
         * Packets sent from replica controller *
         */
        case ACTIVE_ADD: // sent when new name is added to GNS
          AddRecordPacket addRecordPacket = new AddRecordPacket(json);
          Add.handleActiveAdd(addRecordPacket, this);
          break;
        case ACTIVE_REMOVE: // sent when a name is to be removed from GNS
          Remove.executeActiveRemove(new OldActiveSetStopPacket(json), this, noCoordinationState, recovery);
          break;
        // NEW CODE TO HANDLE CONFIRMATIONS COMING BACK FROM AN LNS
        case CONFIRM_UPDATE:
        case CONFIRM_ADD:
        case CONFIRM_REMOVE:
          LNSUpdateHandler.handleConfirmUpdatePacket(new ConfirmUpdatePacket(json), this);
          break;
        default:
          GNS.getLogger().severe(" Packet type not found: " + json);
          break;
      }
      executed = true;
    } catch (JSONException e) {
      e.printStackTrace();
    } catch (NoSuchAlgorithmException e) {
      e.printStackTrace();
    } catch (SignatureException e) {
      e.printStackTrace();
    } catch (InvalidKeySpecException e) {
      e.printStackTrace();
    } catch (InvalidKeyException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    } catch (FailedDBOperationException e) {
      // all database operations throw this exception, therefore we keep throwing this exception upwards and catch this
      // here.
      // A database operation error would imply that the application hasn't been able to successfully execute
      // the request. therefore, this method returns 'false', hoping that whoever calls handleDecision would retry
      // the request.
      e.printStackTrace();
    }
    return executed;
  }

  private static ArrayList<ColumnField> activeStopFields = new ArrayList<ColumnField>();

  static {
    activeStopFields.add(NameRecord.ACTIVE_VERSION);
    activeStopFields.add(NameRecord.VALUES_MAP);
  }

  public boolean stopVersion(String name, short version) {
    boolean executed = false;
    if (Config.debuggingEnabled) {
      GNS.getLogger().fine("executing stop version: " + name + "\t" + version);
    }
    NameRecord nameRecord;
    try {
      // we copy the active version field to old active version field,
      // and values map field to old values map field
      nameRecord = NameRecord.getNameRecordMultiField(nameRecordDB, name, activeStopFields);
      int activeVersion = nameRecord.getActiveVersion();
      nameRecord.handleCurrentActiveStop();
      executed = true;
      // also inform
//      activeReplica.stopProcessed(name, activeVersion, true);
    } catch (FailedDBOperationException e) {
      GNS.getLogger().warning("Field update exception. Message = " + e.getMessage());
    } catch (RecordNotFoundException e) {
      GNS.getLogger().warning("Record not found exception. Message = " + e.getMessage());
    } catch (FieldNotFoundException e) {
      GNS.getLogger().warning("FieldNotFoundException. " + e.getMessage());
      e.printStackTrace();
    }
    return executed;
  }

  private static ArrayList<ColumnField> prevValueRequestFields = new ArrayList<ColumnField>();

  static {
    prevValueRequestFields.add(NameRecord.OLD_ACTIVE_VERSION);
    prevValueRequestFields.add(NameRecord.OLD_VALUES_MAP);
    prevValueRequestFields.add(NameRecord.TIME_TO_LIVE);
  }

  @Override
  public String getFinalState(String name, short version) {
    ValuesMap value = null;
    int ttl = -1;
    try {
      NameRecord nameRecord = NameRecord.getNameRecordMultiField(nameRecordDB, name, prevValueRequestFields);

      value = nameRecord.getOldValuesOnVersionMatch(version);
      ttl = nameRecord.getTimeToLive();
    } catch (FieldNotFoundException e) {
      GNS.getLogger().severe("Field not found exception.");
    } catch (RecordNotFoundException e) {
      GNS.getLogger().severe("Record not found exception. name = " + name + " version = " + version);
    } catch (FailedDBOperationException e) {
      GNS.getLogger().severe("Failed DB Operation. Final state not read: name " + name + " version " + version);
      e.printStackTrace();
      return null;
    }
    if (value == null) {
      return null;
    } else {
      return new TransferableNameRecordState(value, ttl).toString();
    }
  }

  @Override
  public void putInitialState(String name, short version, String state) {
    TransferableNameRecordState state1;
    try {
      state1 = new TransferableNameRecordState(state);
    } catch (JSONException e) {
      GNS.getLogger().severe("JSON Exception in transferred state: " + state + "name " + name + " version " + version);
      e.printStackTrace();
      return;
    }
    // Keep retrying until we can store the initial state for a name in DB. Unless this step completes, future operations
    // e.g., lookupMultipleSystemFields, update, cannot succeed anyway.
    while (true) {
      try {
        try {
          NameRecord nameRecord = new NameRecord(nameRecordDB, name, version, state1.valuesMap, state1.ttl);
          NameRecord.addNameRecord(nameRecordDB, nameRecord);
          if (Config.debuggingEnabled) {
            GNS.getLogger().fine(" NAME RECORD ADDED AT ACTIVE NODE: " + "name record = " + name);
          }
        } catch (RecordExistsException e) {
          NameRecord nameRecord;
          try {
            nameRecord = NameRecord.getNameRecord(nameRecordDB, name);
            nameRecord.handleNewActiveStart(version, state1.valuesMap, state1.ttl);

          } catch (FieldNotFoundException e1) {
            GNS.getLogger().severe("Field not found exception: " + e.getMessage());
            e1.printStackTrace();
          } catch (RecordNotFoundException e1) {
            GNS.getLogger().severe("Not possible because record just existed.");
            e1.printStackTrace();
          }
        }
      } catch (FailedDBOperationException e) {
        try {
          Thread.sleep(100);
        } catch (InterruptedException e1) {
          e1.printStackTrace();
        }
        GNS.getLogger().severe("Failed DB exception. Retry: " + e.getMessage());
        e.printStackTrace();
        continue;
      }
      break;
    }
  }

  @Override
  public int deleteFinalState(String name, short version) {
    // todo (test and remove record) should be an atomic operation otherwise this is wrong.
//    int[] versions = getCurrentOldVersions(name);
//    if (versions != null) {
//      int curVersion = versions[0];
//      int oldVersion = versions[1];
//      if (oldVersion == version) {
//        if (curVersion == NameRecord.NULL_VALUE_ACTIVE_VERSION) {
//
//          try {
//            NameRecord.removeNameRecord(nameRecordDB, name);
//          } catch (FailedUpdateException e) {
//            GNS.getLogger().severe("FailedUpdateException: " + name + "\t " + version + "\t " + e.getMessage());
//            e.printStackTrace();
//          }
//        } else {
//          try {
//            NameRecord nameRecord = new NameRecord(nameRecordDB, name);
//            nameRecord.deleteOldState(version);
//          } catch (FailedUpdateException e) {
//            GNS.getLogger().severe("FailedUpdateException: " + name + "\t " + version + "\t " + e.getMessage());
//            e.printStackTrace();
//          } catch (FieldNotFoundException e) {
//            GNS.getLogger().severe("FieldNotFoundException: " + name + "\t " + version + "\t " + e.getMessage());
//            e.printStackTrace();
//          }
//        }
//      }
//    }
    return 0;
  }

  //  @Override
  /**
   * Used by deleteFinalState method. Therefore, not deleting this method.
   *
   * @param name
   * @return
   */
  private int[] getCurrentOldVersions(String name) {
    try {
      NameRecord nameRecord = NameRecord.getNameRecordMultiField(nameRecordDB, name, readVersions);
      int[] versions = {nameRecord.getActiveVersion(), nameRecord.getOldActiveVersion()};
      return versions;
    } catch (RecordNotFoundException e) {
      GNS.getLogger().severe("Record not found for name: " + name);
      e.printStackTrace();
    } catch (FieldNotFoundException e) {
      GNS.getLogger().severe("Field not found exception: " + e.getMessage());
      e.printStackTrace();
    } catch (FailedDBOperationException e) {
      e.printStackTrace();
    }
    return null;
  }

  private static ArrayList<ColumnField> curValueRequestFields = new ArrayList<ColumnField>();

  static {
    curValueRequestFields.add(NameRecord.VALUES_MAP);
    curValueRequestFields.add(NameRecord.TIME_TO_LIVE);
  }

  @Override
  public String getState(String name) {
    try {
      NameRecord nameRecord = NameRecord.getNameRecordMultiField(nameRecordDB, name, curValueRequestFields);
      if (Config.debuggingEnabled) {
        GNS.getLogger().fine(nameRecord.toString());
      }
      TransferableNameRecordState state = new TransferableNameRecordState(nameRecord.getValuesMap(), nameRecord.getTimeToLive());
      if (Config.debuggingEnabled) {
        GNS.getLogger().fine("Getting state: " + state.toString());
      }
      return state.toString();
    } catch (RecordNotFoundException e) {
      GNS.getLogger().severe("Record not found for name: " + name);
      e.printStackTrace();
    } catch (FieldNotFoundException e) {
      GNS.getLogger().severe("Field not found exception: " + e.getMessage());
      e.printStackTrace();
    } catch (FailedDBOperationException e) {
      GNS.getLogger().severe("State not read from DB: " + e.getMessage());
      e.printStackTrace();
    }
    return null;
  }

  @Override
  public boolean updateState(String name, String state) {
    if (Config.debuggingEnabled) {
      GNS.getLogger().fine("Updating state: " + state);
    }
    boolean stateUpdated = false;
    try {
      TransferableNameRecordState state1 = new TransferableNameRecordState(state);
      NameRecord nameRecord = new NameRecord(nameRecordDB, name);
      nameRecord.updateState(state1.valuesMap, state1.ttl);
      stateUpdated = true;
      // todo handle the case if record does not exist. for this update state should return record not found exception.
    } catch (JSONException e) {
      e.printStackTrace();
    } catch (FieldNotFoundException e) {
      GNS.getLogger().severe("Field not found exception: " + e.getMessage());
      e.printStackTrace();
    } catch (FailedDBOperationException e) {
      GNS.getLogger().severe("Failed update exception: " + e.getMessage());
      e.printStackTrace();
    }
    return stateUpdated;
  }

  /**
   * Nuclear option for clearing out all state at GNS.
   */
  @Override
  public void reset() throws FailedDBOperationException {
    nameRecordDB.reset();
  }

  private static ArrayList<ColumnField> readVersions = new ArrayList<ColumnField>();

  static {
    readVersions.add(NameRecord.ACTIVE_VERSION);
    readVersions.add(NameRecord.OLD_ACTIVE_VERSION);
  }

  @Override
  public PingManager getPingManager() {
    return pingManager;
  }

  @Override
  public void shutdown() {
    // ping manager created here. so this class calls shutdown.
    pingManager.shutdown();
  }
}
