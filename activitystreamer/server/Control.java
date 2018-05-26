package activitystreamer.server;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.text.SimpleDateFormat;
import java.util.Date;

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
import java.util.concurrent.locks.*;

public class Control extends Thread {
    private static final Logger log = LogManager.getLogger();
    private static ArrayList<Connection> connections;
    private static boolean term = false;
    private static Listener listener;

    private static boolean masterFlag; // Used to check if the server is a master server

    private static HashMap<String, String> userInfo = new HashMap<>(); // <username, secret>
    // password>
    private static HashMap<String, Integer> lockInfo = new HashMap<>(); // <username, lockAllowedCount>
    private static HashMap<String, JSONObject> loadInfo = new HashMap<>(); // <serverID, Server_Announce>
    private static HashMap<Connection, Date> clientLogTime = new HashMap<>(); // <connection, connection_setuptime>
    private static String serverId;

    // locking mechanism for handling multiple threads accessing the shared storage
    private static final ReadWriteLock regCountLock = new ReentrantReadWriteLock();
    private static final Lock regRLock = regCountLock.readLock();
    private static final Lock regWLock = regCountLock.writeLock();

    protected static Control control = null;

    public static Control getInstance() {
        if (control == null) {
            control = new Control();
        }
        return control;
    }
    
    public boolean isMaster() {
        return masterFlag;
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
                log.info("1: " + isMaster());
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
     * runing incoming messages from the connection. Return true if the
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
                sendInvalidMsg(con, "Invalid command");
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
                    sendInvalidMsg(con, "No secret provided");
                    return true;
                } else {
                    log.debug("Secret received: " + secret);
                }
                if (!Settings.getSecret().equals(secret)) {
                    sendAuthFail(con, secret);
                    return true;
                } else {
                    sendAuthSuccess(con);
                    for (Connection c : connections) {
                        if (c.isServer()) {
                            sendBackUpSer(con, c.getRemoteHost(), c.getRemotePort());
                            break;
                    }
}
                    con.setServer(true);
                    return false;
                }
            case "AUTHENTICATION_FAIL":
                log.info((String) JSONmsg.get("info"));
                log.info("Close connection to the master server");
                return true;
            case "AUTHENTICATION_SUCCESS":
                return processAuthSuccess(con, JSONmsg);
            case "INVALID_MESSAGE":
                return processInvalidMsg(JSONmsg);
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
            case "BACK_UP_SER":
                return processBackUpSer(con, JSONmsg);
            /***********
             * client case
             */
            case "REGISTER":
            // dont allow user to register before authenticating
                if(Settings.getAuthenticate()) {
                    return processReg(con, JSONmsg);
                } else {
                    sendInvalidMsg(con, "Server not authenticated yet");
                }
            case "LOGIN":
                return processLogin(con, JSONmsg);
            case "LOGOUT":
                return true;
            default:
                log.info("RECEIVE Unknown command" + command);
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
        if (con.isClient())
            Settings.decLoad();
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
        // if no connection here, select the first server comes in as backup server
        if (connections.isEmpty()) {
            Settings.setRemoteHostname(c.getRemoteHost());
            Settings.setRemotePort(c.getRemotePort());
        }
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
        c.setServer(true); // all outgoing connections are server connections
        // if connecting to backup server, update a new backup server
        if (!connections.isEmpty() && connections.get(0).isClosed()) {
            connections.set(0, c);
        } else {
            connections.add(c);
        }
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
            serverId = ipAddress + ":" + Integer.toString(Settings.getLocalPort());
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
        // set authenticate to false before authentication success
        Settings.setAuthenticate(false);
        // create an authenticate json obj
        JSONObject Authenticate = new JSONObject();
        Authenticate.put("command", "AUTHENTICATE");
        if (Settings.getSecret() != null) {
            Authenticate.put("secret", Settings.getSecret());
        }
        con.writeMsg(Authenticate.toJSONString());
        log.info("AUTHENTICATE SENT -> " + con.getFullAddr());
    }
    
    // if connect to server, this server will send backup 
    // server to you after you authenticate success.
    @SuppressWarnings("unchecked")
    public void sendBackUpSer(Connection con, String hostname, int port) {
	JSONObject backServer = new JSONObject();
	backServer.put("command", "BACK_UP_SER");
	backServer.put("hostname", hostname);
	backServer.put("port", port);
	con.writeMsg(backServer.toJSONString());
	log.info("backServer info SENT ->" + con.getFullAddr());
    }
    
    @SuppressWarnings("unchecked")
    public void sendAuthSuccess(Connection con) {
        JSONObject Authenticate_Success = new JSONObject();
        Authenticate_Success.put("command", "AUTHENTICATION_SUCCESS");
        Authenticate_Success.put("userInfo", userInfo);
        Authenticate_Success.put("flag", true);
        con.writeMsg(Authenticate_Success.toJSONString());
        log.info("AUTHENTICATE_SUCCESS SENT -> " + con.getFullAddr());
    }

    // a function that sends Authenticate_fail Json obj if secret is incorrect
    @SuppressWarnings("unchecked")
    public void sendAuthFail(Connection con, String msg) {
        JSONObject Authenticate_Fail = new JSONObject();
        if (msg.equals("Unauthenticated user")) {
            Authenticate_Fail.put("info", msg);
        } else {
            Authenticate_Fail.put("info", "the supplied secret is incorrect: " + msg);
        }
        Authenticate_Fail.put("command", "AUTHENTICATION_FAIL");
        con.writeMsg(Authenticate_Fail.toJSONString());
        log.info("AUTHENTICATE_FAIL SENT -> " + con.getFullAddr());
    }

    // a function that sends Invalid message to client/connecting server if
    // invalid message received.
    @SuppressWarnings("unchecked")
    public void sendInvalidMsg(Connection con, String msg) {
        JSONObject invalidMsg = new JSONObject();
        invalidMsg.put("command", "INVALID_MESSAGE");
        invalidMsg.put("info", msg);
        con.writeMsg(invalidMsg.toJSONString());
        log.info("INVALID_MESSAGE SENT -> " + con.getFullAddr());
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
    
    /******************* Code by Leo *********************/
    /****************Project 2 Modification***************/
    public void removeClientLoginTime(Connection con){
        if(con.isClient()){
            log.debug("Removing disconnect Client from loginTime hash map");
            clientLogTime.remove(con);
        }
    }
    // Shaoxi
    // Check if an user name has been selected
    private boolean isUserOnRecord(String user) {
        for (String key : userInfo.keySet()) {
            if (key.equals(user)) {
                return true;
            }
        }
        regRLock.lock();
        try {
            for (String key : lockInfo.keySet()) {
                if (key.equals(user)) {
                    return true;
                }
            }
        } finally {
            regRLock.unlock();
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
        if (con.isServer()) {
            return false;
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
            regWLock.lock();
            try {
                if (lockInfo.get(userReg) == null) {
                	for (Connection c : connections) {
                		// REG interrupted due to a server crashed
                		if (c.isClosed()) {
                			resMsg.put("command", "INVALID_MESSAGE");
                			resMsg.put("info", "Registeration was interrupted. Please try again later.");
                			con.writeMsg(resMsg.toJSONString());
                			
                			sendLockDenied(con, userReg, secretReg);
                            userInfo.remove(userReg);
                            return true;
                		}
                	}
                	
                	// Normal REG_FAILED
                	sendRegFailed(con, userReg);
                    sendLockDenied(con, userReg, secretReg);
                    userInfo.remove(userReg);
                    return true;
                } else if (lockInfo.get(userReg) == sentCount) {
                    sendRegSuccess(con, userReg);
                    lockInfo.remove(userReg);
                    return false;
                }
            } finally {
                regWLock.unlock();
            }

        }
    }

    @SuppressWarnings("unchecked")
    private void sendRegSuccess(Connection con, String user) {
        JSONObject resMsg = new JSONObject();
        resMsg.put("command", "REGISTER_SUCCESS");
        resMsg.put("info", "register success for " + user);
        con.writeMsg(resMsg.toJSONString());
        log.info("REGISTER_SUCCESS sent -> " + con.getFullAddr());
    }

    @SuppressWarnings("unchecked")
    private void sendRegFailed(Connection con, String user) {
        JSONObject resMsg = new JSONObject();
        resMsg.put("command", "REGISTER_FAILED");
        resMsg.put("info", user + " is already registered with the system");
        con.writeMsg(resMsg.toJSONString());
        log.info("REGISTER_FAILED sent -> " + con.getFullAddr());
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
                log.info("LOCK_REQUEST sent -> " + c.getFullAddr());
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
    private void sendLockAllowed(Connection con, String user, String secret) {
        JSONObject lockAllowed = new JSONObject();
        lockAllowed.put("command", "LOCK_ALLOWED");
        lockAllowed.put("username", user);
        lockAllowed.put("secret", secret);

        con.writeMsg(lockAllowed.toJSONString());
        log.info("LOCK_ALLOWED sent -> " + con.getFullAddr());
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
            JSONObject invMsg = new JSONObject();
            invMsg.put("command", "INVALID_MESSAGE");
            invMsg.put("info", "username or secret is null (or in invalid format)");
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
            regWLock.lock();
            try {
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
                    lockInfo.remove(userLock);
                    sendLockAllowed(con, userLock, secretLock);
                    return false;
                }
            } finally {
                regWLock.unlock();
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
        if (!isString(userLock) || !isString(secretLock)) {
            JSONObject invMsg = new JSONObject();
            invMsg.put("command", "INVALID_MESSAGE");
            invMsg.put("info", "username or secret is null (or in invalid format)");
            con.writeMsg(invMsg.toJSONString());
            return false;
        }

        regWLock.lock();
        try {
            if (lockInfo.get(userLock) != null) {
                lockInfo.put(userLock, lockInfo.get(userLock) + 1);
            }
        } finally {
            regWLock.unlock();
        }

        // sendLockAllowed(con, userLock, secretLock);
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
            JSONObject invMsg = new JSONObject();
            invMsg.put("command", "INVALID_MESSAGE");
            invMsg.put("info", "username or secret is null (or in invalid format)");
            con.writeMsg(invMsg.toJSONString());
            return false;
        }

        regWLock.lock();
        try {
            // Check if that user name is on record
            // if yes, remove it from local storage
            if (userInfo.get(userLock) != null) {
                userInfo.remove(userLock);
            }
        } finally {
            regWLock.unlock();
        }

        sendLockDenied(con, userLock, secretLock);

        return false;
    }
    
    // Shaoxi
    // Remove all lock info for registeration when any server connection is lost
    void stopRegLock() {
    	regWLock.lock();
        try {
            if (lockInfo.size() > 0) {
                lockInfo.clear();
            }
        } finally {
            regWLock.unlock();
        }
    }

    // Shaoxi
    // Check if an object is an instance of String / Number / JSONObject
    private boolean isString(Object obj) {
        return obj instanceof String;
    }

    private boolean isNumber(Object obj) {
        return obj instanceof Number;
    }

    private boolean isJSON(Object obj) {
        return obj instanceof JSONObject;
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
    
    private boolean processAuthSuccess(Connection con, JSONObject msg) {
        userInfo = (HashMap<String, String>) msg.get("userInfo");
        Settings.setAuthenticate((boolean) msg.get("flag"));
        return false;
    }
    
    private boolean processBackUpSer(Connection con, JSONObject msg) {
        String hostname = ((String) msg.get("hostname")).replace("\\", "");
        hostname.replace("/", "");
	con.setBackupHost((String) msg.get("hostname"));
	con.setBackupPort(Integer.parseInt(msg.get("port").toString()));
	for (Connection c : connections) {
		if (c.isServer() && !c.equals(con)) {
			sendBackUpSer(c, con.getRemoteHost(), con.getRemotePort());
		}
	}
	return false;
}

    // Process incoming LOGIN command from client
    // return: close connection (true) / keep connection (false)
    @SuppressWarnings("unchecked")
    private boolean processLogin(Connection con, JSONObject msg) {

        String username = (String) msg.get("username");
        String secret = (String) msg.get("secret");

        if (con.isClient()) {
            sendInvalidMsg(con, "already Login");
        }

        // check if client wants to log in as anonymous
        if (username == null || username.equals("anonymous")) {
            log.info("SENDING LOGIN SUCCESS");
            sendLoginSuccess(con, username);
            // if has other servers' load less than this server's load
            // then redirect this con to this server and close connection
            int smallestLoad = Settings.getLoad();
            int counter1 = 0;
            int flag = 0;
            for (String key : loadInfo.keySet()) {
                counter1++;
                int load = Integer.parseInt(loadInfo.get(key).get("load").toString());
                if (load < smallestLoad) {
                    flag = counter1; 
                    smallestLoad = load;
                }
            }
            int counter2 = 0;
            for (String key : loadInfo.keySet()) {
                counter2++;
                if (counter2 == flag) {
                    JSONObject serverAnn = loadInfo.get(key);
                    String hostname = (String) serverAnn.get("hostname");
                    int pornum = Integer.parseInt(serverAnn.get("port").toString());
                    log.info("SENDING REDIRECT");
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
            log.info("SENDING LOGIN Fail");
            sendLoginFailed(con, username);
            return true;
        } else {
            log.info("SENDING LOGIN SUCCESS");
            sendLoginSuccess(con, username);
            for (String key : loadInfo.keySet()) {
                int load = Integer.parseInt(loadInfo.get(key).get("load").toString());
                if (load + 2 <= Settings.getLoad()) {
                    JSONObject serverAnn = loadInfo.get(key);
                    String hostname = (String) serverAnn.get("hostname");
                    int pornum = Integer.parseInt(serverAnn.get("port").toString());
                    log.info("SENDING REDIRECT");
                    sendRedirect(con, hostname, pornum);
                    return true;
                }
            }
            con.setClient();
            return false;
        }
    }
                               
    /****************Code by Leo*****************/
    /***********Project 2 Modification***********/
    /********recond the time a client login******/
    private void setClientLoginTime(Connection con){
        Date curTime = new Date();
        clientLogTime.put(con,curTime);
    }
                              
    private boolean processInvalidMsg(JSONObject msg) {
        log.error((String) msg.get("info"));
        return true;
    }

    @SuppressWarnings("unchecked")
    private void sendLoginSuccess(Connection con, String msg) {
        JSONObject logMsg = new JSONObject();
        logMsg.put("command", "LOGIN_SUCCESS");
        logMsg.put("info", "logged in as user " + msg);
        con.writeMsg(logMsg.toJSONString());
        setClientLoginTime(con);
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
    private boolean processActivityMessage(Connection connect, JSONObject msg) {

        // Variables for this function
        String userName = (String) msg.get("username");
        String userSecret = (String) msg.get("secret");
        JSONObject content = (JSONObject) msg.get("activity");
        String command = (String) content.get("command");
        //Timestamp sqlTimestamp = new Timestamp(System.currentTimeMillis());
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss");
        Date curTime = new Date();
        String timeString = dateFormat.format(curTime);
        // Generate A New Json Message to Broadcast
        JSONObject newMsg = new JSONObject();

        // First Check whether the connection is valid(is client)
        if (!connect.isClient()) {
            sendAuthFail(connect, "Unauthenticated user");
            log.info("Invalid Client. Close connection.");
            return true;
        }

        if (command != null) {

            // Check Activity Object
            switch (command) {
            case "REGISTER":
                return processReg(connect, content);
            case "LOGIN":
                return processLogin(connect, content);
            case "LOGOUT":
                if (userName.equals("anonymous") || isSecretCorrect(userName, userSecret)) {
                    allowActivityBroadcast(userName, content, timeString, newMsg);
                    activityToClient(getConnections(), curTime, newMsg);
                    activityToServer(getConnections(), newMsg);
                }
                connectionClosed(connect);
                return true;
            }
        }

        // *****************If user login as anonymous user*******************
        // ******************If user name match user secret*******************
        // *****************activity is allowed to be sent******************
        if (userName.equals("anonymous") || isSecretCorrect(userName, userSecret)) {
            allowActivityBroadcast(userName, content,timeString, newMsg);
            activityToClient(getConnections(), curTime, newMsg);
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
    // Be Called when Activity Broadcast is allowed to be sent
    // Create the JSON object for broadcast
    private void allowActivityBroadcast(String userName, JSONObject content,
        String receivedTime, JSONObject newMsg) {
        // **********Insert authenticate user field**********
        content.put("authenticated_user", userName);
        newMsg.put("message_time",receivedTime);
        // **********Put Information to new JSON message**********
        newMsg.put("command", "ACTIVITY_BROADCAST");
        newMsg.put("activity", content);
    }
    
    /***************** Code By Leo ********************/
    /************** Check Connections ****************/
    private boolean checkConnectionClosed(ArrayList<Connection> connections){
        for(Connection con : connections){
            if(con.isClosed()){
                return true;
            }
        }
        return false;
    }
    /***************** Code By Leo ********************/
    /************** Delete Connections ****************/
    private void deleteConnectionClosed(ArrayList<Connection> connections){
        for(Connection con : connections){
            if(con.isClosed()){
                connections.remove(con);
            }
        }
    }
    /***************** Code By Leo ********************/
    /************** Handle disConnections ****************/
    private void handleConnectionClosed(ArrayList<Connection> connections){
        if(checkConnectionClosed(connections)){
            deleteConnectionClosed(connections);
            try{
                Thread.sleep(500);
            }catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    /***************** Code By Leo ********************/
    // Process Activity Broadcast after Received

    // Check The server is authenticate or not
    // If the server is not authenticate, disconnect and send Invalid Message
    // Otherwise, broadcast the JSON message it have received to all other server

    // disconnect return true; keep connection return false
    private boolean processActivityBroadcast(Connection connect, JSONObject msg) throws Exception{

        String timeString = (String) msg.get("message_time");
        SimpleDateFormat timeFormat = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss");
        Date msgTime = timeFormat.parse(timeString);
        // Check server is authenticate
        if (!connect.isServer()) {
            sendInvalidMsg(connect, "Unauthenticate Server Connection");
            return true;
        }
        //
        handleConnectionClosed(getConnections());
        //More Consideration need to be take.
        //First Read the msg and obtain the Msg time
        //Change the String to simple date format.
        for (Connection c : getConnections()) {
            if (c.equals(connect)) {
                continue;
            }
            else if (c.isServer()) {
                c.writeMsg(msg.toJSONString());
                log.info("ACTIVITY_BROADCAST SENT ->" + c.getFullAddr());
            }
            /*****************Project 2 Modification*******************/
            else if (c.isClient() && ! clientLogTime.get(c).after(msgTime)) {
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
    private void activityToClient(ArrayList<Connection> connections, Date curTime, JSONObject msg) {
        handleConnectionClosed(connections);
        for (Connection con : connections) {
            if (con.isClient() && !clientLogTime.get(con).after(curTime)) {
                con.writeMsg(msg.toJSONString());
            }
        }
    }

    /******************* Code by Leo *********************/
    // server broadcast activity to all other server

    // Find all server connections
    // Then send message to all other server
    private void activityToServer(ArrayList<Connection> connections, JSONObject msg) {
        handleConnectionClosed(connections);
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
