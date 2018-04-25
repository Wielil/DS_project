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
	
    // wei	
    public void run(){
        try {
            // create a scanner to get clients input
            // I assume that clients input JSONObj.
            // Scanner sc = new Scanner(System.in);
            String data;
            JSONParser parser = new JSONParser();
            // if a username is provided and no secret is provided,
            // then generate a secret for this user and send register
            // command, lastly print the sercret to client.
            if (Settings.getUsername() != null &&
                    !Settings.getUsername().equals("anonymous") &&
                    Settings.getSecret() == null) {
                Settings.setSecret(Settings.nextSecret());
                log.info("Getting secret: " + Settings.getSecret());
                this.sendRegister();
            } else {
                // send login command to server
                this.sendLogin();
            }
            while(!term && (data = inreader.readLine()) != null) {
                term = this.process(data);
                // getting activity obj from scanner and send it to server.
/******************** This part is used for scanner
//                String msg = sc.nextLine().trim().replaceAll("\r","").replaceAll("\n","").replaceAll("\t", "");
//                try {
//                    JSONObject msgJSON = (JSONObject) parser.parse(msg);
//                    sendActivityObject(msgJSON);                                      
//                } catch (ParseException ex) {
//                    log.error("invalid JSON object entered into input text field, data not sent");
//                } 
*********************/
            }
            
            log.debug("connection closed to " + Settings.socketAddress(socket));
            input.close();
            open =false;
            
        } catch (IOException e) {
            log.error("connection " + Settings.socketAddress((socket)) + " closed with exception: " + e);
        }
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
                log.error("received exception closing the connection " + Settings.socketAddress(socket) +": "+e);
            }
        }
    }
	
    // wei
    /*
     * Processing incoming messages from the connection.
     * Return true if the connection should close.
     */
    public synchronized boolean process(String msg) {
        
        log.info("Receive Message From Server: " + msg);
        //wei
        JSONParser parser = new JSONParser();
        try {
            // parese msg to JSONObject
            JSONObject msgJSON = (JSONObject) parser.parse(msg);
            
            // getting the received JSONobj to GUI
            textFrame.setOutputText(msgJSON);
           
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
                    log.info((String) msgJSON.get("activity").toString());
                    return false;
                    
                case "REDIRECT":
                    remoteHost = (String) msgJSON.get("hostname");
                    remotePort = Integer.parseInt((String) msgJSON.get("port"));
                    log.info("THIS SERVER HANDLE TOO MUCH LOAD, REDIRECT TO SERVER %s:%d",
                            remoteHost, remotePort);
                    // starts the protocol afresh.
                    clientSolution = new ClientSkeleton();
                    return true;
                    
                case "REGISTER_FAILED":
                    log.info((String) msgJSON.get("info"));
                    System.exit(-1);
                    
                case "REGISTER_SUCCESS":
                    log.info((String) msgJSON.get("info"));
                    return false;
                    
                case "AUTHENTICATION_FAIL":
                    log.info((String) msgJSON.get("info"));
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
    
    @SuppressWarnings("unchecked")
    public void sendActivityObject(JSONObject activityObj) {
        // wei
        
        // if aObj contains LOGOUT command, then send logout to server
        if (activityObj.get("command") != null &&
                activityObj.get("command").equals( "LOGOUT")) {
            disconnect();
            return;
	}
        // parse the activityObj to Json String
        String sentJSONString = activityObj.toJSONString();
        
        JSONObject clientMeg = new JSONObject();
        clientMeg.put("command", "ACTIVITY_MESSAGE");
        clientMeg.put("username", Settings.getUsername());
        clientMeg.put("secret", Settings.getSecret());
        clientMeg.put("activity", activityObj);	
        
        log.info("Message: " + sentJSONString + " sent"); 
        writeMsg(clientMeg.toJSONString());
        
    }
    
    // wei
    // a function that sends "login" command to server.
    @SuppressWarnings("unchecked")
    public void sendLogin() {
        // create an authenticate json obj
        JSONObject loginJSON = new JSONObject();
        loginJSON.put("command", "LOGIN");
        loginJSON.put("username", Settings.getUsername());
        loginJSON.put("secret", Settings.getSecret());

        this.writeMsg(loginJSON.toJSONString());
        log.info("LOGIN REQUEST SENT");
    }
    @SuppressWarnings("unchecked")
    public void sendLogout() {
        JSONObject logoutJSON = new JSONObject();
        logoutJSON.put("commamd", "LOGOUT");
        
        this.writeMsg(logoutJSON.toJSONString());
        log.info("LOGOUT REQUEST SENT");
    }
    @SuppressWarnings("unchecked")
    public void sendRegister() {
        JSONObject registerJSON = new JSONObject();
        registerJSON.put("command", "REGISTER");
        registerJSON.put("username", Settings.getUsername());
        registerJSON.put("secret", Settings.getSecret());
        
        this.writeMsg(registerJSON.toJSONString());
        log.info("REGISTER REQUEST SENT");
    }
}
