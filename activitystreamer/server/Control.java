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

public class Control extends Thread {
	private static final Logger log = LogManager.getLogger();
	private static ArrayList<Connection> connections;
	private static boolean term=false;
	private static Listener listener;
	private static boolean masterFlag; // check if the server is a master server
        
	protected static Control control = null;
	
	public static Control getInstance() {
            if(control==null){
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
	
	public boolean initiateConnection(){
            // make a connection to another server if remote hostname is supplied
            if (Settings.getRemoteHostname() != null){
				try {
		                    outgoingConnection(new Socket(Settings.getRemoteHostname(),Settings.getRemotePort()));
				} catch (IOException e) {
		                    log.error("failed to make connection to "+Settings.getRemoteHostname()+":"+Settings.getRemotePort()+" :"+e);
		                    System.exit(-1);
				}
				return false;
            } else {
            	return true;
            }
	}
	
	/*
	 * Processing incoming messages from the connection.
	 * Return true if the connection should close.
	 */
	public synchronized boolean process(Connection con, String msg){
            
            //wei
            JSONParser parser = new JSONParser();
            try {
                // parese msg to JSONObject
                JSONObject JSONmsg = (JSONObject) parser.parse(msg);
                // get command
                String command = (String) JSONmsg.get("command");
                log.info("Received COMMAND: " + command);
                switch(command)
                {
                    case "AUTHENTICATE":
                        log.info("Receiving an Authenticate command.");
                        String secret = (String) JSONmsg.get("secret");
                        if (secret == null) {
                        	log.debug("Secret received: " + "NULL");
                        } else {
                        	log.debug("Secret received: " + secret);
                        }
                        if (!Settings.getSecret().equals(secret)) {
                            sendAuthFail(con, secret);
                            return true;
                        } else {
                            return false;
                        }
                    
                    case "AUTHENTICATION_FAIL":
                        log.info((String) JSONmsg.get("info"));
                        log.info("close the server");
                        //listener.getServerSocket().close();
                        return true;
                    case "INVALID_MESSAGE":
                    	log.info((String) JSONmsg.get("info"));
                    default:
                        log.info("DEFAULT:" + (String) JSONmsg.get("info"));
                        //log.info("close the server");
                        //listener.getServerSocket().close();
                        //return true;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            
            return true;
	}
	
	/*
	 * The connection has been closed by the other party.
	 */
	public synchronized void connectionClosed(Connection con){
            //if(!term) 
            connections.remove(con);
	}
	
	/*
	 * A new incoming connection has been established, and a reference is returned to it
	 */
	public synchronized Connection incomingConnection(Socket s) throws IOException{
            log.debug("incomming connection: "+Settings.socketAddress(s));
            
            Connection c = new Connection(s);
            connections.add(c);
            return c;
		
	}
	
	/*
	 * A new outgoing connection has been established, and a reference is returned to it
	 */
	public synchronized Connection outgoingConnection(Socket s) throws IOException{
            log.debug("outgoing connection: "+Settings.socketAddress(s));
            Connection c = new Connection(s);
            connections.add(c);
            // wei
            // sending authenticate to authenticate the server
            sendAuthenticate(c);
            return c;
	}
	
	@Override
	public void run(){
		// Shaoxi
		// Check subserver authentication in order to start listener
			if (!masterFlag) {
				System.out.println("Subserver IN");
            	
            	try {
					Thread.sleep(3000);
					if (connections.size() == 0) {
						term = true;
						log.debug("SUB Server IN -- 1");
						System.exit(1);
					} else {
						term = false;
						try {
			                listener = new Listener();
			            } catch (IOException e1) {
			                log.fatal("failed to startup a listening thread: " + e1);
			                System.exit(-1);
			            }
					}
    			} catch (InterruptedException e) {
    				// TODO Auto-generated catch block
    				e.printStackTrace();
    			}
			}
         
        ///////////////////////
 
        	log.info("using activity interval of "+Settings.getActivityInterval()+" milliseconds");
        	
        	
            while(!term){
            	
                // do something with 5 second intervals in between
				try {
		                    Thread.sleep(Settings.getActivityInterval());
		                } catch (InterruptedException e) {
		                    log.info("received an interrupt, system is shutting down");
		                    break;
				}
		                if(!term){
		                    log.debug("doing activity");
		                    term = doActivity();
				}
			
            }
            log.info("closing "+connections.size()+" connections");
            // clean up
            for(Connection connection : connections){
            	connection.closeCon();
            }
            listener.setTerm(true);
	}
	
	public boolean doActivity(){
            return false;
	}
	
	public final void setTerm(boolean t){
            term=t;
	}
	
	public final ArrayList<Connection> getConnections() {
            return connections;
	}
	
	//wei
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
}
