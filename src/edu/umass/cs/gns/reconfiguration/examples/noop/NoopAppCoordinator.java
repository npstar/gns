package edu.umass.cs.gns.reconfiguration.examples.noop;

import java.io.IOException;
import java.util.HashMap;
import java.util.Set;

import org.json.JSONException;

import edu.umass.cs.gns.gigapaxos.PaxosManager;
import edu.umass.cs.gns.nio.IntegerPacketType;
import edu.umass.cs.gns.nio.JSONMessenger;
import edu.umass.cs.gns.reconfiguration.AbstractReplicaCoordinator;
import edu.umass.cs.gns.reconfiguration.InterfaceReconfigurable;
import edu.umass.cs.gns.reconfiguration.InterfaceReplicable;
import edu.umass.cs.gns.reconfiguration.InterfaceRequest;
import edu.umass.cs.gns.reconfiguration.InterfaceReconfigurableRequest;
import edu.umass.cs.gns.reconfiguration.RequestParseException;
import edu.umass.cs.gns.util.Stringifiable;

/**
 * @author V. Arun
 */
public class NoopAppCoordinator extends AbstractReplicaCoordinator<Integer> {

	public static enum CoordType {
		LAZY, PAXOS
	};

	private final CoordType coordType;
	private final PaxosManager<Integer> paxosManager;

	private class CoordData {
		final String name;
		final int epoch;
		final Set<Integer> replicas;

		CoordData(String name, int epoch, Set<Integer> replicas) {
			this.name = name;
			this.epoch = epoch;
			this.replicas = replicas;
		}
	}

	private final HashMap<String, CoordData> groups = new HashMap<String, CoordData>();

	NoopAppCoordinator(InterfaceReplicable app) {
		this(app, CoordType.LAZY, null, null);
	}

	NoopAppCoordinator(InterfaceReplicable app, CoordType coordType,
			Stringifiable<Integer> unstringer, JSONMessenger<Integer> msgr) {
		super(app, msgr);
		this.coordType = coordType;
		this.registerCoordination(NoopAppRequest.PacketType.DEFAULT_APP_REQUEST);
		if(app instanceof NoopApp) ((NoopApp)app).setMessenger(msgr);
		if (this.coordType.equals(CoordType.PAXOS)) {
			this.paxosManager = new PaxosManager<Integer>(this.messenger.getMyID(),
					unstringer, this.messenger, this);
		} else
			this.paxosManager = null;
	}

	@Override
	public boolean coordinateRequest(InterfaceRequest request)
			throws IOException, RequestParseException {
		try {
			// coordinate exactly once, and set self to entry replica
			((NoopAppRequest) request).setNeedsCoordination(false);
			((NoopAppRequest) request).setEntryReplica(this.getMyID());
			// pick lazy or paxos coordinator, the defaults supported 
			if (this.coordType.equals(CoordType.LAZY))
				this.sendAllLazy((NoopAppRequest) request);
			else if (this.coordType.equals(CoordType.PAXOS)) {
				NoopAppRequest noopReq = (NoopAppRequest)request;
				if (noopReq.isStop()) {
					this.paxosManager.proposeStop(request.getServiceName(),
							request.toString(),
							(short)(int)(this.getEpoch(request.getServiceName())));
				}
				else
					this.paxosManager.propose(request.getServiceName(),
							request.toString());
			}
		} catch (JSONException e) {
			e.printStackTrace();
		}
		return true;
	}

	@Override
	public boolean createReplicaGroup(String serviceName, int epoch,
			String state, Set<Integer> nodes) {
		if (this.coordType.equals(CoordType.LAZY)) {
			this.groups.put(serviceName, new CoordData(serviceName, epoch, nodes));
		} else if (this.coordType.equals(CoordType.PAXOS)) {
			this.paxosManager.createPaxosInstance(serviceName, (short) epoch,
					nodes, this);
		}
		/* FIXME: This putInitialState may not happen atomically with paxos
		 * instance creation. However, gigapaxos currently has no way to 
		 * specify any initial state.
		 */
		this.app.putInitialState(serviceName, epoch, state);
		return true;
	}

	//@Override
	public void deleteReplicaGroup(String serviceName, int epoch) {
		if (this.coordType.equals(CoordType.LAZY)) {
			// FIXME: check epoch here
			this.groups.remove(serviceName);
		}
		else if (this.coordType.equals(CoordType.PAXOS)) {
			// FIXME: invoke paxosManager remove here
		}
	}

	@Override
	public Set<Integer> getReplicaGroup(String serviceName) {
		if (this.coordType.equals(CoordType.LAZY)) {
			CoordData data = this.groups.get(serviceName);
			if (data != null)
				return data.replicas;
			else
				return null;
		} else
			assert (this.coordType.equals(CoordType.PAXOS));
		return this.paxosManager.getPaxosNodeIDs(serviceName);
	}

	@Override
	public Set<IntegerPacketType> getRequestTypes() {
		return this.app.getRequestTypes();
	}

	@Override
	public InterfaceReconfigurableRequest getStopRequest(String name, int epoch) {
		if (this.app instanceof InterfaceReconfigurable)
			return ((InterfaceReconfigurable) this.app).getStopRequest(name,
					epoch);
		throw new RuntimeException(
				"Can not get stop request for a non-reconfigurable app");
	}

	// FIXME: unused method
	public boolean existsGroup(String name, int epoch) {
		CoordData data = this.groups.get(name);
		assert (data == null || data.name.equals(name));
		return (data != null && data.epoch == epoch);
	}

	@Override
	public void deleteReplicaGroup(String serviceName) {
		throw new RuntimeException("Method not yet implemented");
	}

	/*
	protected void sendAllLazy(NoopAppRequest request) throws IOException,
			RequestParseException, JSONException {
		if(this.getReplicaGroup(request.getServiceName())==null) return;
		GenericMessagingTask<Integer, JSONObject> mtask = new GenericMessagingTask<Integer, JSONObject>(
				this.getReplicaGroup(request.getServiceName()).toArray(),
				request.toJSONObject());
		if (this.messenger != null)
			this.messenger.send(mtask);
	}
	*/
}
