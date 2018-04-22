package activitystreamer.client;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import activitystreamer.util.Settings;
import java.net.UnknownHostException;

// wei
import java.util.Scanner;
import java.util.logging.Level;

public class ClientSkeleton extends Thread {
    private static final Logger log = LogManager.getLogger();
    private static ClientSkeleton clientSolution;
    private TextFrame textFrame;
    
    // wei
    // create a socket to connect server
    private Socket socket;
    private String remoteHost = Settings.getRemoteHostname();
    private int remotePort = Settings.getRemotePort();
    private DataInputStream input;
    private DataOutputStream output;
    private BufferedReader inreader;
    private PrintWriter outwriter;
    private boolean term = false;
    private boolean open = false;
    	
    public static ClientSkeleton getInstance(){
        if(clientSolution==null){
            clientSolution = new ClientSkeleton();
        }
        return clientSolution;
    }
	
    public ClientSkeleton(){
        // wei
        // declare the socket to the server and relevant reader and writer.
        try {
            socket = new Socket(remoteHost, remotePort);
            log.info("Connection established to server -> " + remoteHost + ":" + remotePort);
            
            input  = new DataInputStream(socket.getInputStream());
            output = new DataOutputStream(socket.getOutputStream());
            inreader = new BufferedReader(new InputStreamReader(input));
            outwriter = new PrintWriter(output, true);
            open = true;
            
            textFrame = new TextFrame();
            start();
            
        } catch (UnknownHostException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } 

    }
		
    @SuppressWarnings("unchecked")
    public void sendActivityObject(JSONObject activityObj) {
        // wei
        // parse the activityObj to Json String
        String sentJSONString = activityObj.toJSONString();
        
        writeMsg(sentJSONString);
        log.info("Message: ", sentJSONString, " sent"); 
        
    }
    
    // wei
    // a function that sends "login" command to server.
    public void sendLogin() {
        // create an authenticate json obj
        JSONObject loginJSON = new JSONObject();
        loginJSON.put("command", "LOGIN");
        if (Settings.getUsername() != null) {
            loginJSON.put("username", Settings.getUsername());
        }
        if (Settings.getSecret() != null) {
            loginJSON.put("secret", Settings.getSecret());
        }
        this.writeMsg(loginJSON.toJSONString());
        log.info("LOGIN REQUEST SENT");
    }
    
    // wei
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
	
    // wei
    // use it when client wants to logout.
    public void disconnect(){
	if (open) {
            log.info("closing connection to " + Settings.socketAddress(socket));
            try {
                term = true;
                inreader.close();
                output.close();
            } catch (IOException e) {
                // already closed?
                log.error("received exception closing the connection " + Settings.socketAddress(socket) +": "+e);
            }
        }
    }
	
    // wei	
    public void run(){
        try {
            // create a scanner to get clients input
            // I assume that clients input JSONObj.
            Scanner sc = new Scanner(System.in);
            String data;
            JSONParser parser = new JSONParser();
            // send login command to server
            this.sendLogin();
            while(!term && (data = inreader.readLine()) != null) {
                term = this.process(data);
                // getting activity obj from scanner and send it to server.
                try {
                    JSONObject msgJSON = (JSONObject) parser.parse(sc.nextLine());
                    if (((String) msgJSON.get("command")).equals("LOGOUT")) {
                        sendActivityObject(msgJSON);
                        disconnect();
                    } else {
                        // send whatever client types, let server handles it.
                        sendActivityObject(msgJSON);
                    }                                        
                } catch (ParseException ex) {
                    java.util.logging.Logger.getLogger(ClientSkeleton.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
            
            log.debug("connection closed to " + Settings.socketAddress(socket));
            input.close();
            // Do I need to close the socket?
            // socket.close();
            
        } catch (IOException e) {
            log.error("connection " + Settings.socketAddress((socket)) + " closed with exception: " + e);
        }
        
        open = false;
    }
    
    // wei
    /*
     * Processing incoming messages from the connection.
     * Return true if the connection should close.
     */
    public synchronized boolean process(String msg) {
        //wei
        JSONParser parser = new JSONParser();
        try {
            // parese msg to JSONObject
            JSONObject msgJSON = (JSONObject) parser.parse(msg);
            // get command
            String command = (String) msgJSON.get("command");
            switch(command)
            {
                case "LOGIN_SUCCESS":
                    log.info((String) msgJSON.get("info"));
                    return false;
                    
                case "LOGIN_FAIL":
                    log.info((String) msgJSON.get("info"));
                    return true;
                    
                case "ACTIVITY_BROADCAST":
                    log.info((String) msgJSON.get("activity"));
                    return false;
                    
                case "REDIRECT":
                    remoteHost = (String) msgJSON.get("hostname");
                    remotePort = Integer.parseInt((String) msgJSON.get("port"));
                    log.info("THIS SERVER HANDLE TOO MUCH LOAD, REDIRECT TO SERVER %s:%d",
                            remoteHost, remotePort);
                    // starts the protocol afresh.
                    clientSolution = new ClientSkeleton();
                    ClientSkeleton newCon = ClientSkeleton.getInstance();
                    return true;
                
                // default would be the situation that receives INVALID_MESSAGE
                default:
                    log.info((String) msgJSON.get("info"));
                    return true;
                }
        } catch (Exception e) {
            e.printStackTrace();
        }
            
        return true;
    }
}
