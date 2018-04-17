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
            
            // wei
            // connect to master servers if remote hostname is supplied
            this.initiateConnection();
            
            // start a listener
            try {
                listener = new Listener();
            } catch (IOException e1) {
                log.fatal("failed to startup a listening thread: " + e1);
                System.exit(-1);
            }	
	}
	
	public void initiateConnection(){
            // make a connection to another server if remote hostname is supplied
            if(Settings.getRemoteHostname()!=null){
		try {
                    outgoingConnection(new Socket(Settings.getRemoteHostname(),Settings.getRemotePort()));
		} catch (IOException e) {
                    log.error("failed to make connection to "+Settings.getRemoteHostname()+":"+Settings.getRemotePort()+" :"+e);
                    System.exit(-1);
		}
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
                switch(command)
                {
                    case "AUTHENTICATE":
                        log.info("try to get secret");
                        String secret = (String) JSONmsg.get("secret");
                        log.debug("secret got: " + secret);
                        if (!Settings.getSecret().equals(secret)) {
                            con.sendAuthenticate_Fail(secret);
                            return true;
                        } else {
                            return false;
                        }
                    
                    case "AUTHENTICATION_FAIL":
                        log.info((String) JSONmsg.get("info"));
                        log.info("close the server");
                        listener.getServerSocket().close();
                        return true;
                    default:
                        log.info((String) JSONmsg.get("info"));
                        log.info("close the server");
                        listener.getServerSocket().close();
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
	public synchronized void connectionClosed(Connection con){
            if(!term) connections.remove(con);
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
            return c;
		
	}
	
	@Override
	public void run(){
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
}
