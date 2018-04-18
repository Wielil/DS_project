package activitystreamer.server;


import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

//wei
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import activitystreamer.util.Settings;


public class Connection extends Thread {
	private static final Logger log = LogManager.getLogger();
	private DataInputStream in;
	private DataOutputStream out;
	private BufferedReader inreader;
	private PrintWriter outwriter;
	private boolean open = false;
	private Socket socket;
	private boolean term = false;
	
	Connection(Socket socket) throws IOException{
            in = new DataInputStream(socket.getInputStream());
	    out = new DataOutputStream(socket.getOutputStream());
	    inreader = new BufferedReader( new InputStreamReader(in));
	    outwriter = new PrintWriter(out, true);
	    this.socket = socket;
	    open = true;
	    start();
	}
	
	/*
	 * returns true if the message was written, otherwise false
	 */
	public boolean writeMsg(String msg) {
            if(open) {
		outwriter.println(msg);
		outwriter.flush();
		return true;	
            }
            return false;
	}
	
	public void closeCon(){
            if(open){
		log.info("closing connection "+Settings.socketAddress(socket));
		try {
                    term = true;
                    inreader.close();
                    out.close();
		} catch (IOException e) {
                    // already closed?
                    log.error("received exception closing the connection "+Settings.socketAddress(socket)+": "+e);
		}
            }
	}
	
	
	public void run(){
            try {
                String data;
                
                //wei
                // debugger
                log.debug("Processing data...");
                
		while(!term && (data = inreader.readLine())!= null){
                    //wei
                    log.info("Processing data: " + data);
                    term = Control.getInstance().process(this,data);
		}
                
		log.debug("connection closed to "+Settings.socketAddress(socket));
		Control.getInstance().connectionClosed(this);
		in.close();
                
                // wei
                socket.close();
                
            } catch (IOException e) {
		log.error("connection "+Settings.socketAddress(socket)+" closed with exception: "+e);
		Control.getInstance().connectionClosed(this);
            }
            open = false;
	}
	
	public Socket getSocket() {
            return socket;
	}
	
	public boolean isOpen() {
            return open;
	}
        
        //wei
        public void sendAuthenticate() {
            // create an authenticate json obj
            JSONObject Authenticate = new JSONObject();
            Authenticate.put("command", "AUTHENTICATE");
            if (Settings.getSecret() != null) {
                Authenticate.put("secret", Settings.getSecret());
            }
            this.writeMsg(Authenticate.toJSONString());
            log.info("AUTHENTICATE SENT");
        }
        
        public void sendAuthenticate_Fail(String secret) {
            JSONObject Authenticate_Fail = new JSONObject();
            Authenticate_Fail.put("command", "AUTHENTICATION_FAIL");
            Authenticate_Fail.put("info", "the supplied secret is incorrect: " + secret);
            this.writeMsg(Authenticate_Fail.toJSONString());
        }
        
        public void sendInvalidMsg() {
            JSONObject invalid_Msg = new JSONObject();
            invalid_Msg.put("command", "INVALID_MESSAGE");
            invalid_Msg.put("info", "invalid command");
            this.writeMsg(invalid_Msg.toJSONString());
        }
}
