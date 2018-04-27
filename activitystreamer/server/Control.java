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

	private static HashMap<String, String> userInfo = new HashMap<>(); // <username, secret>
	// password>
	private static HashMap<String, Integer> lockInfo = new HashMap<>();

	private static HashMap<String, JSONObject> loadInfo = new HashMap<>(); // <serverID, Server_Announce>

	private static String serverId;

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

	//
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
	// public synchronized boolean process(Connection con, String msg) {
	public boolean process(Connection con, String msg) {
		// wei
		JSONParser parser = new JSONParser();
		try {
			// parse msg to JSONObject
			JSONObject JSONmsg = (JSONObject) parser.parse(msg);
			// get command
			String command;
			if (isString(JSONmsg.get("command"))) {
				command = (String) JSONmsg.get("command");
			} else {
				log.info("Invalid command. Close connection.");
				return true;
			}

			log.info("Received COMMAND: " + command);
			switch (command) {
				/*******************
				 * server case
				 */
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
				case "LOCK_REQUEST":
					return processLockReq(con, JSONmsg);
				case "LOCK_DENIED":
					return processLockDenied(con, JSONmsg);
				case "LOCK_ALLOWED":
					return processLockAllowed(con, JSONmsg);
				case "ACTIVITY_MESSAGE":
					return processActivityMessage(con, JSONmsg);
				case "ACTIVITY_BROADCAST":
					return processActivityBroadcast(con, JSONmsg);
				/***********
				 * client case
				 */
				case "REGISTER":
					return processReg(con, JSONmsg);
				case "LOGIN":
					return processLogin(con, JSONmsg);
				default:
					log.info("DEFAULT:" + (String) JSONmsg.get("info"));
					sendInvalidMsg(con, "Unknown command");
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
	// public synchronized void connectionClosed(Connection con) {
	public void connectionClosed(Connection con) {
		if (con.isClient()) Settings.decLoad();
		connections.remove(con);
	}

	/*
	 * A new incoming connection has been established, and a reference is returned
	 * to it
	 */
	// public synchronized Connection incomingConnection(Socket s) throws
	// IOException {
	public Connection incomingConnection(Socket s) throws IOException {
		log.debug("incomming connection: " + Settings.socketAddress(s));
		Connection c = new Connection(s);
		connections.add(c);
		return c;

	}

	/*
	 * A new outgoing connection has been established, and a reference is returned
	 * to it
	 */
	// public synchronized Connection outgoingConnection(Socket s) throws
	// IOException {
	public Connection outgoingConnection(Socket s) throws IOException {
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
				if (connections.isEmpty()) {
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
			int serverIntId = ipToInt(ipAddress) + Settings.getLocalPort();
			serverId = Integer.toString(serverIntId);
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

	public boolean doActivity(String id) {
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
	public void sendInvalidMsg(Connection con, String msg) {
		JSONObject invalidMsg = new JSONObject();
		invalidMsg.put("command", "INVALID_MESSAGE");
		invalidMsg.put("info", msg);
		con.writeMsg(invalidMsg.toJSONString());
		log.info("INVALID_MESSAGE SENT");
	}

	// a function that sends server_announce to every server to every other
	// server.
	@SuppressWarnings("unchecked")
	public void sendServerAnnounce(String id, ArrayList<Connection> connections) throws UnknownHostException {
		JSONObject serverAnnounce = new JSONObject();

		serverAnnounce.put("command", "SERVER_ANNOUNCE");
		serverAnnounce.put("id", serverId);
		serverAnnounce.put("load", Settings.getLoad());
		serverAnnounce.put("hostname", Settings.getLocalHostname());
		serverAnnounce.put("port", Settings.getLocalPort());

		// send serverAnnounce to every server in the system
		for (Connection con : connections) {
			if (con.isServer()) {
				con.writeMsg(serverAnnounce.toJSONString());
			}
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
		sendLockReq(con, userReg, secretReg);
		int serverCount = loadInfo.size();

		// 3. Receive LOCK_ALLOWED / LOCK_DENIED to confirm
		while (true) {
			if (lockInfo.get(userReg) == null) {
				sendRegFailed(con, userReg);
				userInfo.remove(userReg);
				return true;
			} else if (lockInfo.get(userReg) == serverCount) {
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
	private void sendLockReq(Connection con, String user, String secret) {
		JSONObject lockRequest = new JSONObject();
		lockRequest.put("command", "LOCK_REQUEST");
		lockRequest.put("username", user);
		lockRequest.put("secret", secret);

		for (Connection c : connections) {
			if (c.equals(con)) { // skip sending to the request server
				continue;
			}
			if (c.isServer()) {
				c.writeMsg(lockRequest.toJSONString());
				log.info("LOCK_REQUEST SENT -> " + c.getFullAddr());
			}
		}
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
	private void sendLockAllowed(Connection con, String user, String secret) {
		JSONObject lockAllowed = new JSONObject();
		lockAllowed.put("command", "LOCK_ALLOWED");
		lockAllowed.put("username", user);
		lockAllowed.put("secret", secret);
		// con.writeMsg(lockDenied.toJSONString());
		for (Connection c : connections) {
			if (c.equals(con)) {
				continue;
			}
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
		if (!isString(userLock) || !isString(secretLock)) {
			// if (userLock == null || secretLock == null) {
			JSONObject invMsg = new JSONObject();
			invMsg.put("command", "INVALID_MESSAGE");
			invMsg.put("info", "username or secret is null (or in invalid format)");
			con.writeMsg(invMsg.toJSONString());
			return false;
		}
		if (isUserOnRecord(userLock)) {
			JSONObject lockDenied = new JSONObject();
			lockDenied.put("command", "LOCK_DENIED");
			lockDenied.put("username", userLock);
			lockDenied.put("secret", secretLock);
			con.writeMsg(lockDenied.toJSONString());
			sendLockDenied(con, userLock, secretLock);
			return false;
		}
		// Record the user on local storage
		userInfo.put(userLock, secretLock);
		// Broadcast LOCK_REQUEST to other servers
		sendLockReq(con, userLock, secretLock);

		// Send LOCK_ALLOWED to the source server
		JSONObject lockAllowed = new JSONObject();
		lockAllowed.put("command", "LOCK_ALLOWED");
		lockAllowed.put("username", userLock);
		lockAllowed.put("secret", secretLock);
		con.writeMsg(lockAllowed.toJSONString());
		log.info("LOCK_ALLOWED sent -> " + con.getFullAddr());

		// Broadcast LOCK_ALLOWED to the other server
		sendLockAllowed(con, userLock, secretLock);

		return false;
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
		if (!isString(userLock) || !isString(secretLock)) {
			// if (userLock == null || secretLock == null) {
			JSONObject invMsg = new JSONObject();
			invMsg.put("command", "INVALID_MESSAGE");
			invMsg.put("info", "username or secret is null (or in invalid format)");
			con.writeMsg(invMsg.toJSONString());
			return false;
		}
		if (lockInfo.get(userLock) != null) {
			lockInfo.put(userLock, lockInfo.get(userLock) + 1);
		}
		sendLockAllowed(con,userLock,secretLock);
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
		if (!isString(userLock) || !isString(secretLock)) {
			// if (userLock == null || secretLock == null) {
			JSONObject invMsg = new JSONObject();
			invMsg.put("command", "INVALID_MESSAGE");
			invMsg.put("info", "username or secret is null (or in invalid format)");
			con.writeMsg(invMsg.toJSONString());
			return false;
		}

		// Check if that user name is on record
		// if yes, remove it from local storage
		if (userInfo.get(userLock) != null) {
			userInfo.remove(userLock);
		}
		sendLockDenied(con, userLock, secretLock);

		return false;
	}

	// Shaoxi
	// Check if an object is an instance of String / Number / JSONObject
	private boolean isString(Object obj) {
		return obj instanceof String ? true : false;
	}

	private boolean isNumber(Object obj) {
		return obj instanceof Number ? true : false;
	}

	private boolean isJSON(Object obj) {
		return obj instanceof JSONObject ? true : false;
	}

	// Wei
	// Process incoming SERVER_ANNOUNCE
	// return: disconnect (true) / keep connection (false)
	private boolean processSerAnn(Connection con, JSONObject msg) throws UnknownHostException {
		// test if the announcement format is correct, if not,
		// disconnect the connection
		if (msg.get("id") == null || msg.get("load") == null || msg.get("hostname") == null
				|| msg.get("port") == null) {
			sendInvalidMsg(con, "Missing fileds in the server announce");
			return true;
		}
		//////////////////////////////////////////
		// redirect server_announce
		for (Connection c : connections) {
			if (c.isServer() && !c.equals(con)) {
				c.writeMsg(msg.toJSONString());
			}
		}
		//////////////////////////////////////////
		if (con.isServer()) {
			loadInfo.put((String) msg.get("id"), msg);
		}
		// test if the server is already been authenticated
		// if yes, return false. if not, return true.
		return !con.isServer();
	}

	// Process incoming LOGIN command from client
	// return: close connection (true) / keep connection (false)
	@SuppressWarnings("unchecked")
	private boolean processLogin(Connection con, JSONObject msg) {

		String username = (String) msg.get("username");
		String secret = (String) msg.get("secret");

		// check if client wants to log in as anonymous
		if (username == null || username.equals("anonymous")) {
			sendLoginSuccess(con, username);
			// if has other servers' load less than this server's load
			// then redirect this con to this server and close connection
			for (String key : loadInfo.keySet()) {
				int load = Integer.parseInt(loadInfo.get(key).get("load").toString());
				if (load + 2 <= Settings.getLoad()) {
					JSONObject serverAnn = loadInfo.get(key);
					String hostname = (String) serverAnn.get("hostname");
					int pornum = Integer.parseInt(serverAnn.get("port").toString());
					sendRedirect(con, hostname, pornum);
					return true;
				}
			}
			con.setClient();
			return false;
		}

		if (msg.get("secret") == null) {
			sendInvalidMsg(con, "Missing filed of secret");
			return true;
		}

		// check if the secret is correct
		if (userInfo.get(username) == null || !((String) userInfo.get(username)).equals(secret)) {
			sendLoginFailed(con, username);
			return true;
		} else {
			sendLoginSuccess(con, username);
			con.setClient();
			return false;
		}
	}

	@SuppressWarnings("unchecked")
	private void sendLoginSuccess(Connection con, String msg) {
		JSONObject logMsg = new JSONObject();
		logMsg.put("command", "LOGIN_SUCCESS");
		logMsg.put("info", "logged in as user " + msg);
		con.writeMsg(logMsg.toJSONString());
	}

	@SuppressWarnings("unchecked")
	private void sendLoginFailed(Connection con, String msg) {
		JSONObject logMsg = new JSONObject();
		logMsg.put("command", "LOGIN_FAILED");
		logMsg.put("info", msg + " attempt to login with wrong secret");
		con.writeMsg(logMsg.toJSONString());
	}

	@SuppressWarnings("unchecked")
	private void sendRedirect(Connection con, String host, int port) {
		JSONObject msg = new JSONObject();
		msg.put("command", "REDIRECT");
		msg.put("hostname", host);
		msg.put("port", port);
		con.writeMsg(msg.toJSONString());
	}

	/******************* Code by Leo *********************/
	// Server received activity message from client

	// First, Check user's validation
	// Then, if valid, broadcast to all other server and all clients
	// If invalid, send authentication failed message to user

	// disconnect return true; keep connection return false
	public boolean processActivityMessage(Connection connect, JSONObject msg) {
		String userName = (String) msg.get("username");
		String userSecret = (String) msg.get("secret");
		JSONObject content = (JSONObject) msg.get("activity");

		// Generate New Json Message First
		JSONObject newMsg = new JSONObject();
        if(!connect.isClient()){
            return true;
        }
		// *****************If user login as anonymous user*******************
		// *****************activity is allowed to be sent******************
		if (userName.equals("anonymous") || userName.equals("")) {

			// **********Insert authenticate user field**********
			content.put("authenticated_user", "anonymous");

			// **********Put Information to new JSON message**********
			newMsg.put("command", "ACTIVITY_BROADCAST");
			newMsg.put("activity", content);

			activityToClient(getConnections(), newMsg);
			activityToServer(getConnections(), newMsg);
			return false;
		}
		// ***************If User Login As Normal User***********************
		// ***************Do User's Validation Checking***************
		else if (isSecretCorrect(userName, userSecret)) {
			// **********Insert authenticate user field**********
			content.put("authenticated_user", userName);

			// **********Put Information to new JSON message**********
			newMsg.put("command", "ACTIVITY_BROADCAST");
			newMsg.put("activity", content);

			activityToClient(getConnections(), newMsg);
			activityToServer(getConnections(), newMsg);
			return false;
		} else if (!isSecretCorrect(userName, userSecret)) {
			sendAuthFail(connect, userSecret);
			return true;
		} else {
			sendInvalidMsg(connect, "unrecognized format of activity message");
			return true;
		}
	}

	/***************** Code By Leo ********************/
	// Process Activity Broadcast after Received

	// Check The server is authenticate or not
	// If the server is not authenticate, disconnect and send Invalid Message
	// Otherwise, broadcast the JSON message it have received to all other server

	// disconnect return true; keep connection return false
	public boolean processActivityBroadcast(Connection connect, JSONObject msg) {

		// Check server is authenticate
		if (!connect.isServer()) {
			sendInvalidMsg(connect, "Unauthenticate Server Connection");
			return true;
		}

		for (Connection c : getConnections()) {
			if (c.equals(connect)) {
				continue;
			} else if (c.isServer()) {
				c.writeMsg(msg.toJSONString());
				log.info("ACTIVITY_BROADCAST SENT ->" + c.getFullAddr());
			}
		}
		return false;
	}

	/******************* Code by Leo *********************/
	// server broadcast activity to all clients connect to current server

	// Find all client connections
	// Then send message to all client
	public void activityToClient(ArrayList<Connection> connections, JSONObject msg) {
		for (Connection con : connections) {
			if (con.isClient()) {
				con.writeMsg(msg.toJSONString());
			}
		}
	}

	/******************* Code by Leo *********************/
	// server broadcast activity to all other server

	// Find all server connections
	// Then send message to all other server
	public void activityToServer(ArrayList<Connection> connections, JSONObject msg) {
		for (Connection con : connections) {
			if (con.isServer()) {
				con.writeMsg(msg.toJSONString());
			}
		}
	}

	// Code by yuri
	// Function to check username and secret matches or not
	private boolean isSecretCorrect(String user, String secret) {
		if (isUserOnRecord(user)) {
			String value = userInfo.get(user);
			if (value.equals(secret)) {
				return true;
			}
		}
		return false;
	}
}
