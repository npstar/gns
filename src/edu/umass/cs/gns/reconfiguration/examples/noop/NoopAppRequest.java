package edu.umass.cs.gns.reconfiguration.examples.noop;

import org.json.JSONException;
import org.json.JSONObject;

import edu.umass.cs.gns.nio.IntegerPacketType;
import edu.umass.cs.gns.reconfiguration.InterfaceReplicableRequest;
import edu.umass.cs.gns.reconfiguration.InterfaceStopRequest;
import edu.umass.cs.gns.reconfiguration.examples.AppRequest;

/**
@author V. Arun
 */
public class NoopAppRequest extends AppRequest implements InterfaceStopRequest, InterfaceReplicableRequest {
	
	/* Can define IntegerPacketType types here other than
	 * those defined in AppRequest. The reconfiguration 
	 * package is agnostic to the contents of this class
	 * other than that it supports InterfaceRequest. The
	 * super class AppRequest is there only for convenience.
	 */

	public NoopAppRequest(String name, int epoch, int id, String value,
			IntegerPacketType type, boolean stop) {
		super(name, epoch, id, value, type, stop);
	}
	public NoopAppRequest(String name, int id, String value,
			IntegerPacketType type, boolean stop) {
		super(name, 0, id, value, type, stop);
	}

	public NoopAppRequest(JSONObject json) throws JSONException {
		super(json);
	}

	public static void main(String[] args) {
	}
}
