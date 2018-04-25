package activitystreamer.server;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import activitystreamer.util.Settings;

//wei
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import java.net.InetAddress; // getting ip address to compute server id.
import java.net.UnknownHostException;

import java.util.HashMap;
import java.util.logging.Level;

public class Control extends Thread {
	private static final Logger log = LogManager.getLogger();
	private static ArrayList<Connection> connections;
	private static boolean term = false;
	private static Listener listener;

	private static boolean masterFlag; // Used to check if the server is a master server
	// userInfo should be initiated in order to get its size.
	private static HashMap<String, String> userInfo = new HashMap<String, String>(); // Global user info map <username,
																						// password>
	private static HashMap<String, Integer> lockInfo = new HashMap<String, Integer>();

	private static int serverId;

	protected static Control control = null;

	public static Control getInstance() {
		if (control == null) {
			control = new Control();
		}
		return control;
	}

	public Control() {
		// initialize the connections array
		connections = new ArrayList<Connection>();
		masterFlag = false;

		// Shaoxi
		// start a listener for master server
		// or initiate connection to a master server (if it's a sub server)
		if (masterFlag = initiateConnection()) {
			try {
				listener = new Listener();
			} catch (IOException e1) {
				log.fatal("failed to startup a listening thread: " + e1);
				System.exit(-1);
			}
		}
		start();
	}

	public boolean initiateConnection() {
		// make a connection to another server if remote hostname is supplied
		if (Settings.getRemoteHostname() != null) {
			try {
				outgoingConnection(new Socket(Settings.getRemoteHostname(), Settings.getRemotePort()));
			} catch (IOException e) {
				log.error("failed to make connection to " + Settings.getRemoteHostname() + ":"
						+ Settings.getRemotePort() + " :" + e);
				System.exit(-1);
			}
			return false;
		} else {
			return true;
		}
	}

	/*
	 * Processing incoming messages from the connection. Return true if the
	 * connection should close.
	 */
	public synchronized boolean process(Connection con, String msg) {

		// wei
		JSONParser parser = new JSONParser();
		try {
			// parese msg to JSONObject
			JSONObject JSONmsg = (JSONObject) parser.parse(msg);
			// get command
			String command = (String) JSONmsg.get("command");
			if (command == null) {
				log.info("No command received. Close connection.");
				return true;
			}
			log.info("Received COMMAND: " + command);
			switch (command) {
			case "AUTHENTICATE":
				log.info("Receiving an Authenticate command.");
				String secret = (String) JSONmsg.get("secret");
				if (secret == null) {
					log.debug("No secret received");
				} else {
					log.debug("Secret received: " + secret);
				}
				if (!Settings.getSecret().equals(secret)) {
					sendAuthFail(con, secret);
					return true;
				} else {
					con.setServer();
					return false;
				}

			case "AUTHENTICATION_FAIL":
				log.info((String) JSONmsg.get("info"));
				log.info("Close connection to the master server");
				return true;
			case "SERVER_ANNOUNCE":
				return processSerAnn(con, JSONmsg);
			case "REGISTER":
				return processReg(con, JSONmsg);
			case "LOCK_REQUEST":
				return processLockReq(con, JSONmsg);
			case "LOCK_DENIED":
				return processLockDenied(con, JSONmsg);
			case "LOCK_ALLOWED":
				return processLockAllowed(con, JSONmsg);
			case "ACITIVITY_MESSAGE":
				return processActivityMessage(con, JSONmsg);
			case "ACITIVITY_BROADCAST":
				return processActivityBroadcast(con, JSONmsg);
			case "INVALID_MESSAGE":
				log.info((String) JSONmsg.get("info"));
			default:
				log.info("DEFAULT:" + (String) JSONmsg.get("info"));
				// log.info("close the server");
				// listener.getServerSocket().close();
				return true;
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

		return true;
	}

	/*
	 * The connection has been closed by the other party.
	 */
	public synchronized void connectionClosed(Connection con) {
		// if(!term)
		connections.remove(con);
	}

	/*
	 * A new incoming connection has been established, and a reference is returned
	 * to it
	 */
	public synchronized Connection incomingConnection(Socket s) throws IOException {
		log.debug("incomming connection: " + Settings.socketAddress(s));
		Connection c = new Connection(s);
		connections.add(c);
		return c;

	}

	/*
	 * A new outgoing connection has been established, and a reference is returned
	 * to it
	 */
	public synchronized Connection outgoingConnection(Socket s) throws IOException {
		log.debug("outgoing connection: " + Settings.socketAddress(s));
		Connection c = new Connection(s);
		c.setServer(); // all outgoing connections are server connections
		connections.add(c);
		// wei
		// sending authenticate to authenticate the server
		sendAuthenticate(c);
		return c;
	}

	@Override
	public void run() {
		// Shaoxi
		// Check sub server authentication in order to start a listener
		if (!masterFlag) {
			log.info("Sub server IN");

			try {
				Thread.sleep(3000);
				if (connections.size() == 0) {
					term = true;
					log.info("Sub Server Shutdown");
					System.exit(-1);
				} else {
					// trem is false at the beginning.
					// term = false;
					try {
						listener = new Listener();
					} catch (IOException e1) {
						log.fatal("failed to startup a listening thread: " + e1);
						System.exit(-1);
					}
				}
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}

		///////////////////////

		log.info("using activity interval of " + Settings.getActivityInterval() + " milliseconds");

		// wei
		// calculat the id using ip address and port num
		// getting server ip
		try {
			String ipAddress = InetAddress.getLocalHost().getHostAddress();
			serverId = ipToInt(ipAddress) + Settings.getLocalPort();
		} catch (UnknownHostException ex) {
			java.util.logging.Logger.getLogger(Control.class.getName()).log(Level.SEVERE, null, ex);
		}
		// calculate server id using local ip address and local port.

		while (!term) {

			// do something with 5 second intervals in between
			try {
				Thread.sleep(Settings.getActivityInterval());
			} catch (InterruptedException e) {
				log.info("received an interrupt, system is shutting down");
				break;
			}
			if (!term) {
				log.debug("doing activity");
				term = doActivity(serverId);
			}

		}
		log.info("closing " + connections.size() + " connections");
		// clean up
		for (Connection connection : connections) {
			connection.closeCon();
		}
		listener.setTerm(true);
	}

	public boolean doActivity(int id) {
		try {
			sendServerAnnounce(id, getConnections());
		} catch (UnknownHostException ex) {
			java.util.logging.Logger.getLogger(Control.class.getName()).log(Level.SEVERE, null, ex);
		}
		return false;
	}

	public final void setTerm(boolean t) {
		term = t;
	}

	public final ArrayList<Connection> getConnections() {
		return connections;
	}

	// wei
	// a function that sends AUTHENTICATE JSON object with this connection
	@SuppressWarnings("unchecked")
	public void sendAuthenticate(Connection con) {
		// create an authenticate json obj
		JSONObject Authenticate = new JSONObject();
		Authenticate.put("command", "AUTHENTICATE");
		if (Settings.getSecret() != null) {
			Authenticate.put("secret", Settings.getSecret());
		}
		con.writeMsg(Authenticate.toJSONString());
		log.info("AUTHENTICATE SENT");
	}

	// a function that sends Authenticate_fail Json obj if secret is incorrect
	@SuppressWarnings("unchecked")
	public void sendAuthFail(Connection con, String secret) {
		JSONObject Authenticate_Fail = new JSONObject();
		Authenticate_Fail.put("command", "AUTHENTICATION_FAIL");
		Authenticate_Fail.put("info", "the supplied secret is incorrect: " + secret);
		con.writeMsg(Authenticate_Fail.toJSONString());
		log.info("AUTHENTICATE_FAIL SENT");
	}

	// a function that sends Invalid message to client/connecting server if
	// invalid message received.
	@SuppressWarnings("unchecked")
	public void sendInvalidMsg(Connection con) {
		JSONObject invalidMsg = new JSONObject();
		invalidMsg.put("command", "INVALID_MESSAGE");
		invalidMsg.put("info", "invalid command");
		con.writeMsg(invalidMsg.toJSONString());
		log.info("INVALID_MESSAGE SENT");
	}

	// a function that sends server_announce to every server to every other
	// server.
	@SuppressWarnings("unchecked")
	public void sendServerAnnounce(int id, ArrayList<Connection> connections) throws UnknownHostException {
		JSONObject serverAnnounce = new JSONObject();

		serverAnnounce.put("command", "SERVER_ANNOUNCE");
		serverAnnounce.put("id", serverId);
		// load is the number of clients connecting to this server
		int load = 0;
		for (Connection con : connections) {
			if (con.isClient()) {
				load++;
			}
		}
		serverAnnounce.put("load", load);
		serverAnnounce.put("hostname", Settings.getLocalHostname());
		serverAnnounce.put("port", Settings.getLocalPort());

		// // include the information of client to ensure the process
		// // of login, however, it is not mentioned in the spec.
		// serverAnnounce.put("userInfo", userInfo);

		// send serverAnnounce to every server in the system
		for (Connection con : connections) {
			con.writeMsg(serverAnnounce.toJSONString());
		}

	}

	// a function that converts an ipAddress to a Long number.
	// reference from
	// github.com/Albaniusz/java_mkyong/blob/master/src/main/java/com/mkyong/core/JavaBitwiseExample.java
	public int ipToInt(String ipAddress) {
		// ipAddressInArray[0] = 192
		String[] ipAddressInArray = ipAddress.split("\\.");

		int result = 0;
		for (int i = 0; i < ipAddressInArray.length; i++) {
			int power = 3 - i;
			int ip = Integer.parseInt(ipAddressInArray[i]);

			// 1. 192 * 256^3
			// 2. 168 * 256^2
			// 3. 1 * 256^1
			// 4. 2 * 256^0
			result += ip * Math.pow(256, power);
		}
		return result;
	}

	// Shaoxi
	// Check if an user name has been selected
	private boolean isUserOnRecord(String user) {
		for (String key : userInfo.keySet()) {
			if (key.equals(user)) {
				return true;
			}
		}
		for (String key : lockInfo.keySet()) {
			if (key.equals(user)) {
				return true;
			}
		}
		return false;
	}

	// Shaoxi
	// Process incoming REGISTER
	@SuppressWarnings("unchecked")
	private boolean processReg(Connection con, JSONObject msg) {
		JSONObject resMsg = new JSONObject();
		if (con.isClient()) {
			resMsg.put("command", "INVALID_MESSAGE");
			resMsg.put("info", "Client has already logged in. No registeration allowed.");
			con.writeMsg(resMsg.toJSONString());
			return true;
		}
		String userReg = (String) msg.get("username");
		String secretReg = (String) msg.get("secret");
		if (userReg == null || secretReg == null) {
			resMsg.put("command", "INVALID_MESSAGE");
			resMsg.put("info", "username or secret is null");
			con.writeMsg(resMsg.toJSONString());
			return true;
		}

		// 1. Check local userInfo / lockInfo
		if (isUserOnRecord(userReg)) {
			sendRegFailed(con, userReg);
			return true;
		}
		// 2. Send LOCK_REQUEST
		userInfo.put(userReg, secretReg);
		lockInfo.put(userReg, 0);
		int sentCount = sendLockReq(con, userReg, secretReg);

		// 3. Receive LOCK_ALLOWED / LOCK_DENIED to confirm
		while (true) {
			if (lockInfo.get(userReg) == null) {
				sendRegFailed(con, userReg);
				sendLockDenied(con, userReg, secretReg);
				userInfo.remove(userReg);
				return true;
			} else if (lockInfo.get(userReg) == sentCount) {
				sendRegSuccess(con, userReg);
				lockInfo.remove(userReg);
				return false;
			}
		}
	}

	@SuppressWarnings("unchecked")
	private void sendRegSuccess(Connection con, String user) {
		JSONObject resMsg = new JSONObject();
		resMsg.put("command", "REGISTER_SUCCESS");
		resMsg.put("info", "register success for " + user);
		con.writeMsg(resMsg.toJSONString());
	}

	@SuppressWarnings("unchecked")
	private void sendRegFailed(Connection con, String user) {
		JSONObject resMsg = new JSONObject();
		resMsg.put("command", "REGISTER_FAILED");
		resMsg.put("info", user + " is already registered with the system");
		con.writeMsg(resMsg.toJSONString());
	}

	@SuppressWarnings("unchecked")
	private int sendLockReq(Connection con, String user, String secret) {
		JSONObject lockRequest = new JSONObject();
		lockRequest.put("command", "LOCK_REQUEST");
		lockRequest.put("username", user);
		lockRequest.put("secret", secret);
		int sentCount = 0;

		for (Connection c : connections) {
			if (c.equals(con)) { // skip sending to the request server
				continue;
			}
			if (c.isServer()) {
				c.writeMsg(lockRequest.toJSONString());
				sentCount++;
				log.info("LOCK_REQUEST SENT -> " + c.getFullAddr());
			}
		}
		return sentCount;
	}

	// Shaoxi
	// Send LOCK_DENIED to all other connected servers
	@SuppressWarnings("unchecked")
	private void sendLockDenied(Connection con, String user, String secret) {
		JSONObject lockDenied = new JSONObject();
		lockDenied.put("command", "LOCK_DENIED");
		lockDenied.put("username", user);
		lockDenied.put("secret", secret);

		for (Connection c : connections) {
			if (c.equals(con)) {
				continue;
			}
			if (c.isServer()) {
				c.writeMsg(lockDenied.toJSONString());
				log.info("LOCK_DENIED sent -> " + c.getFullAddr());
			}
		}
	}

	@SuppressWarnings("unchecked")
	private void sendLockAllowed(String user, String secret) {
		JSONObject lockAllowed = new JSONObject();
		lockAllowed.put("command", "LOCK_ALLOWED");
		lockAllowed.put("username", user);
		lockAllowed.put("secret", secret);
		// con.writeMsg(lockDenied.toJSONString());
		for (Connection c : connections) {
			if (c.isServer()) {
				c.writeMsg(lockAllowed.toJSONString());
				log.info("LOCK_ALLOWED sent -> " + c.getFullAddr());
			}
		}
	}

	// Shaoxi
	// Process incoming LOCK_REQUEST
	// return: disconnect (true) / keep connection (false)
	@SuppressWarnings("unchecked")
	private boolean processLockReq(Connection con, JSONObject msg) {
		// Check valid server connection
		if (!con.isServer()) {
			return true;
		}
		String userLock = (String) msg.get("username");
		String secretLock = (String) msg.get("secret");
		if (userLock == null || secretLock == null) {
			JSONObject invMsg = new JSONObject();
			invMsg.put("command", "INVALID_MESSAGE");
			invMsg.put("info", "username or secret is null");
			con.writeMsg(invMsg.toJSONString());
			return false;
		}
		if (isUserOnRecord(userLock)) {
			sendLockDenied(con, userLock, secretLock);
			return false;
		}
		userInfo.put(userLock, secretLock);
		lockInfo.put(userLock, 0);
		int sentCount = sendLockReq(con, userLock, secretLock);

		while (true) {
			if (lockInfo.get(userLock) == null) {
				userInfo.remove(userLock);
				// To the request source only
				JSONObject lockDenied = new JSONObject();
				lockDenied.put("command", "LOCK_DENIED");
				lockDenied.put("username", userLock);
				lockDenied.put("secret", secretLock);
				con.writeMsg(lockDenied.toJSONString());
				// To the other connected servers
				sendLockDenied(con, userLock, secretLock);
				return false;
			} else if (lockInfo.get(userLock) == sentCount) {
				sendLockAllowed(userLock, secretLock);
				return false;
			}
		}
	}

	// Shaoxi
	// Process incoming LOCK_ALLOWED
	// return: disconnect (true) / keep connection (false)
	@SuppressWarnings("unchecked")
	private boolean processLockAllowed(Connection con, JSONObject msg) {
		// Check valid server connection
		if (!con.isServer()) {
			return true;
		}
		String userLock = (String) msg.get("username");
		String secretLock = (String) msg.get("secret");
		if (userLock == null || secretLock == null) {
			JSONObject invMsg = new JSONObject();
			invMsg.put("command", "INVALID_MESSAGE");
			invMsg.put("info", "username or secret is null");
			con.writeMsg(invMsg.toJSONString());
			return false;
		}
		if (lockInfo.get(userLock) != null) {
			lockInfo.put(userLock, lockInfo.get(userLock) + 1);
		}
		return false;
	}

	// Shaoxi
	// Process incoming LOCK_DENIED
	// return: disconnect (true) / keep connection (false)
	@SuppressWarnings("unchecked")
	private boolean processLockDenied(Connection con, JSONObject msg) {
		// Check valid server connection
		if (!con.isServer()) {
			return true;
		}
		String userLock = (String) msg.get("username");
		String secretLock = (String) msg.get("secret");
		if (userLock == null || secretLock == null) {
			JSONObject invMsg = new JSONObject();
			invMsg.put("command", "INVALID_MESSAGE");
			invMsg.put("info", "username or secret is null");
			con.writeMsg(invMsg.toJSONString());
			return false;
		}
		if (lockInfo.get(userLock) != null) {
			userInfo.remove(userLock);
			lockInfo.remove(userLock);
		}
		sendLockDenied(con, userLock, secretLock);

		return false;
	}

	// Wei
	// Process incoming SERVER_ANNOUNCE
	// return: disconnect (true) / keep connection (false)
	private boolean processSerAnn(Connection con, JSONObject msg) {
		// test if the announcement format is correct, if not,
		// disconnect the connection
		if (msg.get("id") == null || msg.get("load") == null || msg.get("hostname") == null
				|| msg.get("port") == null) {
			return true;
		}
		// test if the server is already been authenticated
		// if yes, return false. if not, return true.
		return !con.isServer();
	}
}
